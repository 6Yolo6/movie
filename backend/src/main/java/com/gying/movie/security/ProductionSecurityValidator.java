package com.gying.movie.security;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fail at startup rather than silently running with missing or sample credentials. */
@Component
@Profile("prod")
public class ProductionSecurityValidator {
    private final Environment env;
    public ProductionSecurityValidator(Environment env) { this.env = env; }
    @PostConstruct public void validate() {
        String dbUser = env.getProperty("spring.datasource.username", "");
        if (dbUser.isBlank() || "root".equalsIgnoreCase(dbUser.trim())) invalid("DB_USER (must be non-root)");
        require("spring.datasource.password", 16);
        require("spring.data.redis.password", 16);
        require("minio.access-key", 3);
        require("minio.secret-key", 16);
        require("jwt.secret", 32);
        require("app.internal-token", 32);
        require("gying-source.token", 32);
        require("social-publisher.token", 32);
        if (env.getProperty("qq-bot.enabled", Boolean.class, false)) require("qq-bot.webhook-token", 32);
        if (env.getProperty("qq-bot.rate-limit-per-minute", Integer.class, 5) < 1) invalid("qq-bot.rate-limit-per-minute");
        String cors = env.getProperty("app.cors.allowed-origin", "");
        if (cors.isBlank() || cors.contains("*") || cors.contains("localhost")) invalid("app.cors.allowed-origin");
    }
    private void require(String key, int length) {
        String value = env.getProperty(key, "");
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.getBytes(StandardCharsets.UTF_8).length < length || lower.contains("change-me")
                || lower.contains("changeme") || lower.contains("replace-me") || lower.contains("your-secret")
                || lower.contains("example") || lower.contains("dev-secret")) invalid(key);
    }
    private static void invalid(String key) {
        throw new IllegalStateException("Unsafe production configuration: " + key + "; see docs/security/secret-management.md");
    }
}
