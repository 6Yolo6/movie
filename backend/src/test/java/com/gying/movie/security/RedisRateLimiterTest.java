package com.gying.movie.security;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class RedisRateLimiterTest {
    @Test void atomicScriptReturnDeterminesAdmission() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(0L, 2501L);
        var limiter = new RedisRateLimiter(redis);
        assertEquals(0, limiter.retryAfterMillis("test", "203.0.113.1", 5, Duration.ofMinutes(1)));
        var error = assertThrows(RedisRateLimiter.RateLimitExceededException.class,
                () -> limiter.require("test", "203.0.113.1", 5, Duration.ofMinutes(1)));
        assertEquals(3, error.retryAfterSeconds());
        verify(redis, times(2)).execute(any(RedisScript.class), eq(List.of(RedisRateLimiter.key("test", "203.0.113.1"))),
                eq("5"), eq("60000"), any());
    }
    @Test void errorsAndNullResultsFailClosed() {
        var redis = mock(StringRedisTemplate.class);
        var limiter = new RedisRateLimiter(redis);
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> limiter.retryAfterMillis("test", "ip", 5, Duration.ofMinutes(1))).getStatusCode().value());
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenThrow(new IllegalStateException("fixture"));
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> limiter.retryAfterMillis("test", "ip", 5, Duration.ofMinutes(1))).getStatusCode().value());
    }
    @Test void keysDoNotIncludeRawIdentity() {
        String key = RedisRateLimiter.key("qq-user", "private-member-identity");
        assertFalse(key.contains("private-member-identity"));
        assertEquals("security:rate:qq-user:".length()+64, key.length());
    }
}
