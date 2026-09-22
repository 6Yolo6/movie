package com.gying.movie.security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="SECURITY_TEST_REDIS_HOST", matches=".+")
class RedisRateLimiterIntegrationTest {
    @Test void realRedisAtomicConcurrencyExpiryAndAcl() throws Exception {
        var config = new RedisStandaloneConfiguration(System.getenv("SECURITY_TEST_REDIS_HOST"), 6379);
        config.setPassword(System.getenv("SECURITY_TEST_REDIS_PASSWORD"));
        var factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet();
        var pool = Executors.newFixedThreadPool(12);
        try {
            var redis = new StringRedisTemplate(factory);
            var limiter = new RedisRateLimiter(redis);
            String identity = UUID.randomUUID().toString();
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i=0;i<24;i++) tasks.add(() -> limiter.retryAfterMillis("atomic-test", identity, 5, Duration.ofSeconds(30)) == 0);
            int admitted=0; for (var result : pool.invokeAll(tasks)) if (result.get()) admitted++;
            assertEquals(5, admitted);
            Long ttl = redis.getExpire(RedisRateLimiter.key("atomic-test", identity));
            assertNotNull(ttl); assertTrue(ttl > 0 && ttl <= 30);
            assertEquals(0, limiter.retryAfterMillis("expiry-test", identity, 1, Duration.ofMillis(200)));
            assertTrue(limiter.retryAfterMillis("expiry-test", identity, 1, Duration.ofMillis(200)) > 0);
            Thread.sleep(350);
            assertEquals(0, limiter.retryAfterMillis("expiry-test", identity, 1, Duration.ofMillis(200)));
        } finally { pool.shutdownNow(); factory.destroy(); }
    }
}
