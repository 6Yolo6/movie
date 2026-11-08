package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaiduSessionVaultTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private ResourceHubProperties properties() {
        ResourceHubProperties p = new ResourceHubProperties();
        p.getBaidu().setSessionStatePath(temporary.resolve("private/session.enc").toString());
        p.getBaidu().setSessionKeyPath(temporary.resolve("private/session.key").toString());
        return p;
    }
    private List<BaiduSessionVault.Cookie> cookies() {
        return List.of(new BaiduSessionVault.Cookie("BDUSS", "fixture-session-cookie", ".baidu.com", "/", Instant.now().getEpochSecond() + 3600, true));
    }
    @Test void absentMetadataReadDoesNotCreateCredentialFiles() {
        var vault = new BaiduSessionVault(properties(), mapper);
        assertFalse(vault.metadata().configured());
        assertNull(vault.read());
        assertFalse(Files.exists(temporary.resolve("private")));
    }
    @Test void encryptedRoundTripUsesSeparateKeyAndNeverPrintsCookies() throws Exception {
        var props = properties(); var vault = new BaiduSessionVault(props, mapper);
        vault.save("1001", "Test account", cookies(), false);
        assertEquals("1001", vault.read().uid());
        assertEquals(cookies().get(0).value(), vault.read().cookies().get(0).value());
        String bytes = Files.readString(Path.of(props.getBaidu().getSessionStatePath()));
        assertFalse(bytes.contains("fixture-session-cookie"));
        assertFalse(bytes.contains("BDUSS"));
        assertTrue(Files.exists(Path.of(props.getBaidu().getSessionKeyPath())));
        assertFalse(vault.read().toString().contains("fixture-session-cookie"));
        assertFalse(cookies().get(0).toString().contains("fixture-session-cookie"));
        assertTrue(vault.metadata().configured());
    }
    @Test void accountReplacementRequiresConfirmationAndPreservesOldBytes() throws Exception {
        var props = properties(); var vault = new BaiduSessionVault(props, mapper);
        vault.save("1001", "First", cookies(), false);
        byte[] before = Files.readAllBytes(Path.of(props.getBaidu().getSessionStatePath()));
        assertThrows(IllegalStateException.class, () -> vault.save("2002", "Second", cookies(), false));
        assertArrayEquals(before, Files.readAllBytes(Path.of(props.getBaidu().getSessionStatePath())));
        vault.save("2002", "Second", cookies(), true);
        assertEquals("2002", vault.read().uid());
    }
    @Test void repeatedImportTicketCannotRewriteCommittedSession() throws Exception {
        var props = properties(); var vault = new BaiduSessionVault(props, mapper);
        String ticket = "fixture_ticket_abcdefghijkl";
        vault.save("1001", "First", cookies(), false, ticket);
        assertTrue(vault.hasImportTicketId(ticket));
        assertEquals(ticket, vault.lastImportTicketId());
        byte[] before = Files.readAllBytes(Path.of(props.getBaidu().getSessionStatePath()));
        assertThrows(IllegalStateException.class, () -> vault.save("1001", "First", cookies(), false, ticket));
        assertArrayEquals(before, Files.readAllBytes(Path.of(props.getBaidu().getSessionStatePath())));
    }
    @Test void corruptCiphertextFailsClosedWithoutPrintingBytes() throws Exception {
        var props = properties(); var vault = new BaiduSessionVault(props, mapper);
        vault.save("1001", "First", cookies(), false);
        Path state = Path.of(props.getBaidu().getSessionStatePath());
        var node = mapper.readTree(Files.readString(state));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("ciphertext", "fixture-corrupted-ciphertext");
        Files.writeString(state, mapper.writeValueAsString(node));
        var error = assertThrows(IllegalStateException.class, vault::read);
        assertEquals("Baidu session storage is unavailable or invalid", error.getMessage());
        assertNull(error.getCause());
    }
    @Test void existingStateWithoutKeyCannotBeSilentlyRekeyed() throws Exception {
        var props = properties(); var vault = new BaiduSessionVault(props, mapper);
        vault.save("1001", "First", cookies(), false);
        Files.delete(Path.of(props.getBaidu().getSessionKeyPath()));
        assertThrows(IllegalStateException.class, () -> vault.save("1001", "First", cookies(), false));
        assertFalse(Files.exists(Path.of(props.getBaidu().getSessionKeyPath())));
    }
    @Test void importRejectsForeignExpiredDuplicateOrHeaderInjectingCookies() {
        assertThrows(IllegalArgumentException.class, () -> BaiduSessionVault.validateCookies(List.of(new BaiduSessionVault.Cookie("BDUSS", "fixture-value", ".evil.example", "/", -1, true))));
        assertThrows(IllegalArgumentException.class, () -> BaiduSessionVault.validateCookies(List.of(new BaiduSessionVault.Cookie("BDUSS", "fixture-value", ".baidu.com", "/", 1, true))));
        assertThrows(IllegalArgumentException.class, () -> BaiduSessionVault.validateCookies(List.of(cookies().get(0), cookies().get(0))));
        assertThrows(IllegalArgumentException.class, () -> BaiduSessionVault.validateCookies(List.of(new BaiduSessionVault.Cookie("BDUSS", "value\r\nInjected: header", ".baidu.com", "/", -1, true))));
    }
    @Test void mismatchedCredentialParentsAreRejectedBeforeFileCreation() {
        var props = properties(); props.getBaidu().setSessionKeyPath(temporary.resolve("different/session.key").toString());
        var vault = new BaiduSessionVault(props, mapper);
        assertThrows(IllegalStateException.class, () -> vault.save("1001", "First", cookies(), false));
        assertFalse(Files.exists(temporary.resolve("private")));
    }
    @Test void symlinkCredentialDestinationIsRejected() throws Exception {
        Path real = temporary.resolve("real"); Files.createDirectories(real);
        Path link = temporary.resolve("private");
        try { Files.createSymbolicLink(link, real); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException e) { org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlink capability unavailable"); }
        var vault = new BaiduSessionVault(properties(), mapper);
        assertThrows(IllegalStateException.class, () -> vault.save("1001", "First", cookies(), false));
        assertFalse(Files.exists(real.resolve("session.enc")));
    }
}
