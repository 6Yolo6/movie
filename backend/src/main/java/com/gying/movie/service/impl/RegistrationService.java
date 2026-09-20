package com.gying.movie.service.impl;

import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.ISysUserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistrationService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final ISysConfigService config;
    private final ISysUserService users;
    private final EmailVerificationService emailVerification;
    private final JdbcTemplate jdbc;

    public RegistrationService(ISysConfigService config, ISysUserService users, EmailVerificationService emailVerification, JdbcTemplate jdbc) {
        this.config = config;
        this.users = users;
        this.emailVerification = emailVerification;
        this.jdbc = jdbc;
    }

    public Map<String, Object> policy(String inviteCode) {
        boolean publicEnabled = bool("auth.register.enabled", false);
        boolean invitationEnabled = bool("auth.invite.enabled", true);
        boolean inviteValid = invitationEnabled && validateInvitation(inviteCode, false) != null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicRegistrationEnabled", publicEnabled);
        result.put("invitationEnabled", invitationEnabled);
        result.put("inviteValid", inviteValid);
        result.put("registrationAllowed", publicEnabled || inviteValid);
        result.put("emailRequired", true);
        result.put("emailVerificationEnabled", emailVerification.enabled());
        return result;
    }

    @Transactional
    public SysUser register(String username, String password, String email, String emailCode, String inviteCode) {
        String normalizedEmail = normalizeEmail(email);
        boolean publicEnabled = bool("auth.register.enabled", false);
        Map<String, Object> invitation = bool("auth.invite.enabled", true) ? validateInvitation(inviteCode, true) : null;
        if (!publicEnabled && invitation == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration is invite-only");
        }
        try {
            emailVerification.verify(normalizedEmail, emailCode);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        Long inviterId = invitation == null ? null : ((Number) invitation.get("creator_user_id")).longValue();
        SysUser user = users.register(username, password, normalizedEmail, inviterId);
        if (invitation != null) {
            long invitationId = ((Number) invitation.get("id")).longValue();
            int maxUses = ((Number) invitation.get("max_uses")).intValue();
            int used = ((Number) invitation.get("used_count")).intValue() + 1;
            jdbc.update("INSERT INTO user_invitation_use(invitation_id, inviter_user_id, invitee_user_id) VALUES (?,?,?)", invitationId, inviterId, user.getId());
            jdbc.update("UPDATE user_invitation_code SET used_count=?, last_used_at=NOW(), status=? WHERE id=?", used, used >= maxUses ? "EXHAUSTED" : "ACTIVE", invitationId);
        }
        return user;
    }

    private Map<String, Object> validateInvitation(String inviteCode, boolean lock) {
        if (inviteCode == null || inviteCode.isBlank()) return null;
        String suffix = lock ? " FOR UPDATE" : "";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, creator_user_id, max_uses, used_count, status, expires_at FROM user_invitation_code WHERE code_hash=? LIMIT 1" + suffix, sha256(inviteCode.trim()));
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        String status = String.valueOf(row.get("status"));
        LocalDateTime expires = ((java.sql.Timestamp) row.get("expires_at")).toLocalDateTime();
        int max = ((Number) row.get("max_uses")).intValue();
        int used = ((Number) row.get("used_count")).intValue();
        return "ACTIVE".equals(status) && expires.isAfter(LocalDateTime.now()) && used < max ? row : null;
    }

    public static String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase();
        if (value.length() > 200 || !EMAIL.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email address is required");
        }
        return value;
    }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    private boolean bool(String key, boolean fallback) { return Boolean.parseBoolean(config.getConfigValue(key, String.valueOf(fallback))); }
}
