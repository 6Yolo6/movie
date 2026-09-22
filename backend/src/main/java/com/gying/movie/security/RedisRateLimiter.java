package com.gying.movie.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Atomic sliding window, using Redis time. No per-process fallback or raw identities in keys. */
@Component
public class RedisRateLimiter {
    static final String LUA = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then
                local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
                return math.max(1, tonumber(first[2]) + window - now)
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
            """;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(LUA, Long.class);
    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public long retryAfterMillis(String scope, String identity, int limit, Duration window) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate limit configuration must be positive");
        }
        try {
            Long result = redis.execute(SCRIPT, List.of(key(scope, identity)),
                    Integer.toString(limit), Long.toString(window.toMillis()), UUID.randomUUID().toString());
            if (result == null) throw new IllegalStateException("Empty rate limiter result");
            return result;
        } catch (Exception error) {
            // Fail closed: a Redis outage must not remove brute-force protection.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Security service temporarily unavailable");
        }
    }

    public void require(String scope, String identity, int limit, Duration window) {
        long retry = retryAfterMillis(scope, identity, limit, window);
        if (retry > 0) throw new RateLimitExceededException(retry);
    }

    static String key(String scope, String identity) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((identity == null ? "unknown" : identity).getBytes(StandardCharsets.UTF_8));
            return "security:rate:" + scope + ":" + HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static class RateLimitExceededException extends ResponseStatusException {
        private final long retryAfterSeconds;
        public RateLimitExceededException(long retryMillis) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again later");
            retryAfterSeconds = Math.max(1, (retryMillis + 999) / 1000);
        }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }
}
