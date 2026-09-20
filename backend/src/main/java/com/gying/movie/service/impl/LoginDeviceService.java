package com.gying.movie.service.impl;

import com.gying.movie.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LoginDeviceService {
    private final JdbcTemplate jdbc;
    private final JwtUtils jwtUtils;

    public LoginDeviceService(JdbcTemplate jdbc, JwtUtils jwtUtils) {
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
    }

    public void record(String token, Long userId, String ipAddress, String userAgent) {
        Claims claims = jwtUtils.validateToken(token);
        if (claims == null || claims.getId() == null) return;
        String jti = claims.getId();
        LocalDateTime expiresAt = claims.getExpiration().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        String ua = truncate(userAgent, 512);
        String ip = truncate(ipAddress, 100);
        String deviceName = resolveDeviceName(ua);
        jdbc.update("INSERT INTO login_device (user_id, jti, device_name, ip_address, user_agent, login_at, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)",
                userId, jti, deviceName, ip, ua, expiresAt);
    }

    public boolean isActive(Long userId, String jti) {
        if (userId == null || jti == null || jti.isBlank()) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM login_device WHERE user_id=? AND jti=? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP", Integer.class, userId, jti);
        return count != null && count > 0;
    }

    public void touch(Long userId, String jti) {
        if (userId == null || jti == null || jti.isBlank()) return;
        jdbc.update("UPDATE login_device SET last_seen_at=CURRENT_TIMESTAMP WHERE user_id=? AND jti=? AND revoked_at IS NULL", userId, jti);
    }

    public List<Map<String, Object>> list(Long userId, String currentJti) {
        return jdbc.queryForList("SELECT id, jti, device_name, ip_address, user_agent, login_at, last_seen_at, expires_at, revoked_at FROM login_device WHERE user_id=? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP ORDER BY last_seen_at DESC", userId)
                .stream().map(row -> {
                    Map<String, Object> device = new LinkedHashMap<>();
                    device.put("id", row.get("id"));
                    device.put("deviceName", row.get("device_name"));
                    device.put("ipAddress", row.get("ip_address"));
                    device.put("userAgent", row.get("user_agent"));
                    device.put("loginAt", formatDateTime(row.get("login_at")));
                    device.put("lastSeenAt", formatDateTime(row.get("last_seen_at")));
                    device.put("expiresAt", formatDateTime(row.get("expires_at")));
                    device.put("revokedAt", formatDateTime(row.get("revoked_at")));
                    device.put("current", currentJti != null && currentJti.equals(row.get("jti")));
                    return device;
                }).toList();
    }

    public boolean revoke(Long userId, Long id) {
        return jdbc.update("UPDATE login_device SET revoked_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND revoked_at IS NULL", id, userId) > 0;
    }

    private String formatDateTime(Object value) {
        if (value == null) return null;
        LocalDateTime localDateTime;
        if (value instanceof java.sql.Timestamp timestamp) {
            localDateTime = timestamp.toLocalDateTime();
        } else if (value instanceof LocalDateTime valueDateTime) {
            localDateTime = valueDateTime;
        } else if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } else {
            return String.valueOf(value);
        }
        return localDateTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String resolveDeviceName(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("Chrome/") ? "Chrome" : ua.contains("Firefox/") ? "Firefox" : ua.contains("Safari/") ? "Safari" : "Browser";
        String os = ua.contains("Windows") ? "Windows" : ua.contains("Android") ? "Android" : ua.contains("iPhone") || ua.contains("iPad") ? "iOS" : ua.contains("Mac OS") ? "macOS" : ua.contains("Linux") ? "Linux" : "Device";
        return browser + " / " + os;
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
