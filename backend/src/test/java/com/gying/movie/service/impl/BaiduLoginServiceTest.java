package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduSessionVault;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class BaiduLoginServiceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final BaiduSessionVault vault = mock(BaiduSessionVault.class);
    final BaiduWebClient client = mock(BaiduWebClient.class);
    ResourceHubProperties properties;
    BaiduLoginService service;
    Path requestPath;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-08T15:00:00Z"), ZoneOffset.UTC);
    final List<BaiduSessionVault.Cookie> cookies = List.of(new BaiduSessionVault.Cookie(
            "BDUSS", "fixture-baidu-session", ".baidu.com", "/", -1, true));

    @BeforeEach void setup() {
        properties = new ResourceHubProperties(); requestPath = directory.resolve("private/login-request.json");
        properties.getBaidu().setLoginRequestPath(requestPath.toString());
        when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(false, null, null, 0));
        service = new BaiduLoginService(properties, vault, client, mapper, clock);
    }
    JsonNode ticket() throws Exception { return mapper.readTree(Files.readString(requestPath)); }
    String nonce() throws Exception { return ticket().get("nonce").asText(); }
    String startAndClaim() throws Exception {
        String id = service.start().ticketId();
        service.helperState(id, nonce(), "WAITING_SCAN", null); return id;
    }

    @Test void startingAgainReusesOnePendingTicketAndPublicStatusNeverContainsNonce() throws Exception {
        var first = service.start(); String secret = nonce(); var second = service.start();
        assertEquals(first.ticketId(), second.ticketId()); assertEquals(secret, nonce());
        assertEquals(600000, first.expiresAt() - clock.millis());
        assertFalse(mapper.writeValueAsString(second).contains(secret));
        assertFalse(mapper.writeValueAsString(second).contains("nonce"));
        verifyNoInteractions(client); verify(vault, never()).save(anyString(), anyString(), anyList(), anyBoolean(), anyString());
    }

    @Test void wrongNonceAndUnclaimedImportDoNotValidateOrOverwriteSession() throws Exception {
        String id = service.start().ticketId();
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.helperState(id, "B".repeat(43), "WAITING_SCAN", null)).getStatusCode().value());
        String secret = nonce();
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.importSession(id, secret, cookies)).getStatusCode().value());
        verifyNoInteractions(client); verify(vault, never()).save(anyString(), anyString(), anyList(), anyBoolean(), anyString());
    }

    @Test void expiredTicketErasesNonceAndCannotBeClaimedOrImported() throws Exception {
        String id = service.start().ticketId(); String secret = nonce();
        BaiduLoginService expired = new BaiduLoginService(properties, vault, client, mapper,
                Clock.offset(clock, java.time.Duration.ofMinutes(10)));
        assertEquals("EXPIRED", expired.status().state()); assertTrue(ticket().get("nonce").isNull());
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> expired.importSession(id, secret, cookies)).getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test void validationFinishingAtTheDeadlineCannotCommitAnExpiredHandoff() throws Exception {
        var currentTime = new AtomicReference<>(clock.instant());
        Clock advancing = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return currentTime.get(); }
        };
        service = new BaiduLoginService(properties, vault, client, mapper, advancing);
        String id = startAndClaim(); String secret = nonce();
        when(client.validateSession(cookies)).thenAnswer(invocation -> {
            currentTime.set(clock.instant().plusSeconds(600));
            return new BaiduWebClient.AccountInfo("12345", "专用账号");
        });
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.importSession(id, secret, cookies)).getStatusCode().value());
        assertEquals("EXPIRED", service.status().state()); assertTrue(ticket().get("nonce").isNull());
        verify(vault, never()).save(anyString(), anyString(), anyList(), anyBoolean(), anyString());
    }

    @Test void successfulImportValidatesIdentityBeforeVaultCommitAndRejectsReplay() throws Exception {
        String id = startAndClaim(); String secret = nonce();
        when(client.validateSession(cookies)).thenReturn(new BaiduWebClient.AccountInfo("12345", "专用账号"));
        AtomicReference<String> consumed = new AtomicReference<>();
        when(vault.hasImportTicketId(any())).thenAnswer(invocation -> invocation.getArgument(0) != null
                && invocation.getArgument(0).equals(consumed.get()));
        doAnswer(invocation -> {
            consumed.set(invocation.getArgument(4));
            when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(true, "12345", "专用账号", clock.instant().getEpochSecond()));
            return null;
        }).when(vault).save(eq("12345"), eq("专用账号"), eq(cookies), eq(false), eq(id));
        var status = service.importSession(id, secret, cookies);
        assertEquals("CONNECTED", status.state()); assertTrue(status.configured()); assertEquals("专***", status.accountName());
        assertEquals(clock.millis(), status.lastValidatedAt()); assertTrue(ticket().get("nonce").isNull());
        var order = inOrder(client, vault); order.verify(client).validateSession(cookies);
        order.verify(vault).metadata(); order.verify(vault).save("12345", "专用账号", cookies, false, id);
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.importSession(id, secret, cookies)).getStatusCode().value());
        verify(client, times(1)).validateSession(cookies);
    }

    @Test void differentDedicatedAccountKeepsExistingVaultAndConsumesFailedTicket() throws Exception {
        when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(true, "12345", "原账号", 100));
        String id = startAndClaim(); String secret = nonce();
        when(client.validateSession(cookies)).thenReturn(new BaiduWebClient.AccountInfo("99999", "不同账号"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.importSession(id, secret, cookies)).getStatusCode().value());
        assertEquals("ACCOUNT_CHANGED", service.status().failureCode()); assertTrue(ticket().get("nonce").isNull());
        verify(vault, never()).save(anyString(), anyString(), anyList(), anyBoolean(), anyString());
    }

    @Test void remoteValidationFailureRetainsOldSessionAndClaimForSafeHelperFailure() throws Exception {
        String id = startAndClaim(); String secret = nonce();
        when(client.validateSession(cookies)).thenThrow(new IllegalStateException("Baidu session validation unavailable"));
        assertThrows(IllegalStateException.class, () -> service.importSession(id, secret, cookies));
        verify(vault, never()).save(anyString(), anyString(), anyList(), anyBoolean(), anyString());
        assertEquals("WAITING_SCAN", service.status().state());
        assertEquals("FAILED", service.helperState(id, secret, "FAILED", "VALIDATION_UNAVAILABLE").state());
    }

    @Test void restartAfterVaultCommitTreatsImportMarkerAsAuthoritativeNonceConsumption() throws Exception {
        String id = startAndClaim(); String secret = nonce();
        when(vault.hasImportTicketId(id)).thenReturn(true);
        when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(true, "12345", "专用账号", clock.instant().getEpochSecond()));
        BaiduLoginService restarted = new BaiduLoginService(properties, vault, client, mapper, clock);
        assertEquals("CONNECTED", restarted.status().state()); assertTrue(ticket().get("nonce").isNull());
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> restarted.importSession(id, secret, cookies)).getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test void malformedLocalTicketFailsClosedAndIsNeverOverwritten() throws Exception {
        Files.createDirectories(requestPath.getParent()); Files.writeString(requestPath, "{\"nonce\":\"fixture-secret\"}");
        String original = Files.readString(requestPath);
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> service.start()).getStatusCode().value());
        assertEquals(original, Files.readString(requestPath));
    }
}
