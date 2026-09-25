package com.gying.movie.service.impl;

import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.ISysUserService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationServiceTest {
    private final ISysConfigService config = mock(ISysConfigService.class);
    private final ISysUserService users = mock(ISysUserService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RegistrationService service = new RegistrationService(config, users, mock(EmailVerificationService.class), jdbc);
    private Map<String, Object> invitation;

    @BeforeEach void setup() {
        when(config.getConfigValue(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        invitation = new HashMap<>(Map.of("id", 1L, "creator_user_id", 2L, "max_uses", 1,
                "used_count", 0, "status", "ACTIVE", "expires_at", LocalDateTime.now().plusDays(1)));
        when(jdbc.queryForList(anyString(), eq(RegistrationService.sha256("fixture")))).thenReturn(List.of(invitation));
    }

    @Test void acceptsConnectorJLocalDateTime() { assertEquals(true, service.policy("fixture").get("registrationAllowed")); }
    @Test void acceptsLegacyTimestamp() {
        invitation.put("expires_at", Timestamp.valueOf(LocalDateTime.now().plusDays(1)));
        assertEquals(true, service.policy("fixture").get("inviteValid"));
    }
    @Test void rejectsExpiredExhaustedAndMissingExpiryWithout500() {
        invitation.put("expires_at", LocalDateTime.now().minusMinutes(1));
        assertEquals(false, service.policy("fixture").get("inviteValid"));
        invitation.put("expires_at", null);
        assertEquals(false, service.policy("fixture").get("inviteValid"));
        invitation.put("expires_at", LocalDateTime.now().plusDays(1));
        invitation.put("used_count", 1);
        assertEquals(false, service.policy("fixture").get("inviteValid"));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.register("fixture", "fixture-password", "test@example.com", null, "fixture")).getStatusCode().value());
        verify(users, never()).register(anyString(), anyString(), anyString(), any());
    }
    @Test void rejectsPublicRegistrationWhenUserLimitReached() {
        when(config.getConfigValue(eq("auth.register.max_users"), anyString())).thenReturn("2");
        when(config.getConfigValue(eq("auth.register.enabled"), anyString())).thenReturn("true");
        doReturn(2L).when(users).count();
        assertEquals(true, service.policy(null).get("registrationLimitReached"));
        assertEquals(false, service.policy(null).get("registrationAllowed"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.register("fixture", "fixture-password", "test@example.com", null, null));
        assertEquals(403, error.getStatusCode().value());
        assertEquals("Registration limit reached", error.getReason());
        verify(users, never()).register(anyString(), anyString(), anyString(), any());
    }
    @Test void inviteRegistrationBypassesUserLimit() {
        when(config.getConfigValue(eq("auth.register.max_users"), anyString())).thenReturn("2");
        doReturn(2L).when(users).count();
        assertEquals(true, service.policy("fixture").get("registrationLimitReached"));
        assertEquals(true, service.policy("fixture").get("inviteValid"));
        assertEquals(true, service.policy("fixture").get("registrationAllowed"));
        SysUser user = new SysUser(); user.setId(4L);
        when(users.register("fixture", "fixture-password", "test@example.com", 2L)).thenReturn(user);
        assertSame(user, service.register("fixture", "fixture-password", "test@example.com", null, "fixture"));
        verify(users).register("fixture", "fixture-password", "test@example.com", 2L);
    }
    @Test void validInviteRegistersAndConsumesOneUse() {
        SysUser user = new SysUser(); user.setId(3L);
        when(users.register("fixture", "fixture-password", "test@example.com", 2L)).thenReturn(user);
        assertSame(user, service.register("fixture", "fixture-password", "TEST@example.com", null, "fixture"));
        verify(jdbc).update(contains("INSERT INTO user_invitation_use"), eq(1L), eq(2L), eq(3L));
        verify(jdbc).update(contains("UPDATE user_invitation_code"), eq(1), eq("EXHAUSTED"), eq(1L));
        verify(jdbc).queryForList(contains("FOR UPDATE"), eq(RegistrationService.sha256("fixture")));
    }
}
