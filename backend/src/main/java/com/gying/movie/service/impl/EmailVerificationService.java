package com.gying.movie.service.impl;

import com.gying.movie.service.ISysConfigService;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import com.gying.movie.security.RedisRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class EmailVerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final ISysConfigService config;
    private final RedisRateLimiter rateLimiter;
    private final RestClient brevoClient = RestClient.builder().baseUrl("https://api.brevo.com/v3").build();
    private final RestClient resendClient = RestClient.builder().baseUrl("https://api.resend.com").build();

    @Value("${mail.provider:${MAIL_PROVIDER:auto}}") private String provider;
    @Value("${mail.brevo.api-key:${BREVO_API_KEY:}}") private String brevoApiKey;
    @Value("${mail.resend.api-key:${RESEND_API_KEY:}}") private String resendApiKey;
    @Value("${mail.from-address:${MAIL_FROM_ADDRESS:}}") private String fromAddress;
    @Value("${mail.from-name:${MAIL_FROM_NAME:影窝}}") private String fromName;

    public EmailVerificationService(StringRedisTemplate redis, ISysConfigService config, RedisRateLimiter rateLimiter) {
        this.redis = redis;
        this.config = config;
        this.rateLimiter = rateLimiter;
    }

    public boolean enabled() {
        return Boolean.parseBoolean(config.getConfigValue("auth.email_verification.enabled", "false"))
                && hasText(fromAddress) && hasText(activeProvider());
    }

    public static final String PURPOSE_REGISTER = "register";
    public static final String PURPOSE_RESET_PASSWORD = "reset-password";
    public static final String PURPOSE_CHANGE_EMAIL = "change-email";

    public void send(String email, String clientKey) {
        send(email, clientKey, PURPOSE_REGISTER);
    }

    public void send(String email, String clientKey, String purpose) {
        if (!enabled()) throw new IllegalStateException("Email verification is not configured");
        String normalized = RegistrationService.normalizeEmail(email);
        String scope = normalizePurpose(purpose);
        rateLimiter.require("email-send", clientKey, 10, Duration.ofHours(1));
        String cooldownKey = cooldownKey(scope, normalized);
        Boolean allowed = redis.opsForValue().setIfAbsent(cooldownKey, "1", 60, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(allowed)) throw new IllegalStateException("Please wait before requesting another code");
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String codeKey = codeKey(scope, normalized);
        String subject = subjectFor(scope);
        String html = "<p>" + introFor(scope) + "</p><p style=\"font-size:24px;font-weight:bold\">" + code + "</p><p>验证码 5 分钟内有效。若非本人操作请忽略。</p>";
        // Store the code before sending so a delivered email always has a verifiable code.
        redis.opsForValue().set(codeKey, code, 5, TimeUnit.MINUTES);
        try {
            if ("resend".equals(activeProvider())) {
                resendClient.post().uri("/emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + resendApiKey)
                        .body(Map.of("from", fromName + " <" + fromAddress + ">", "to", List.of(normalized), "subject", subject, "html", html))
                        .retrieve().toBodilessEntity();
            } else {
                brevoClient.post().uri("/smtp/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("api-key", brevoApiKey)
                        .body(Map.of("sender", Map.of("name", fromName, "email", fromAddress), "to", List.of(Map.of("email", normalized)), "subject", subject, "htmlContent", html))
                        .retrieve().toBodilessEntity();
            }
        } catch (RuntimeException error) {
            redis.delete(codeKey);
            redis.delete(cooldownKey);
            throw new IllegalStateException("Failed to send verification email", error);
        }
    }

    public void verify(String email, String code) {
        verify(email, code, PURPOSE_REGISTER);
    }

    public void verify(String email, String code, String purpose) {
        if (!enabled()) return;
        if (!hasText(code)) throw new IllegalArgumentException("Email verification code is required");
        String normalized = normalize(email);
        String key = codeKey(normalizePurpose(purpose), normalized);
        rateLimiter.require("email-verify", normalized, 5, Duration.ofMinutes(5));
        String stored = redis.opsForValue().get(key);
        if (stored == null) throw new IllegalArgumentException("Email verification code expired");
        if (!stored.equals(code.trim())) throw new IllegalArgumentException("Invalid email verification code");
        redis.delete(key);
    }

    private String normalizePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) return PURPOSE_REGISTER;
        return switch (purpose) {
            case PURPOSE_REGISTER, PURPOSE_RESET_PASSWORD, PURPOSE_CHANGE_EMAIL -> purpose;
            default -> PURPOSE_REGISTER;
        };
    }

    private String codeKey(String purpose, String normalized) {
        return PURPOSE_REGISTER.equals(purpose)
                ? "register:email:code:" + RegistrationService.sha256(normalized)
                : "email-code:" + purpose + ":" + RegistrationService.sha256(normalized);
    }

    private String cooldownKey(String purpose, String normalized) {
        return PURPOSE_REGISTER.equals(purpose)
                ? "register:email:cooldown:" + RegistrationService.sha256(normalized)
                : "email-cooldown:" + purpose + ":" + RegistrationService.sha256(normalized);
    }

    private String subjectFor(String purpose) {
        return switch (purpose) {
            case PURPOSE_RESET_PASSWORD -> "影窝 重置密码验证码";
            case PURPOSE_CHANGE_EMAIL -> "影窝 更换邮箱验证码";
            default -> "影窝 注册验证码";
        };
    }

    private String introFor(String purpose) {
        return switch (purpose) {
            case PURPOSE_RESET_PASSWORD -> "您正在重置影窝账号密码，验证码为：";
            case PURPOSE_CHANGE_EMAIL -> "您正在更换影窝账号邮箱，验证码为：";
            default -> "您的影窝注册验证码为：";
        };
    }

    private String activeProvider() {
        String selected = provider == null ? "auto" : provider.trim().toLowerCase();
        if (("auto".equals(selected) || "resend".equals(selected)) && hasText(resendApiKey)) return "resend";
        if (("auto".equals(selected) || "brevo".equals(selected)) && hasText(brevoApiKey)) return "brevo";
        return "";
    }
    private String normalize(String email) { return RegistrationService.normalizeEmail(email); }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
