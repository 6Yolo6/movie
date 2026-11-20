package com.gying.movie.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitoringServiceEventTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ZSetOperations<String, String> scores = mock(ZSetOperations.class);
    private final MonitoringService service = new MonitoringService(jdbc, redis);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        when(redis.opsForZSet()).thenReturn(scores);
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "9223372036854775807,2147483647"})
    void preservesNormalizationCountBoundsAndCacheTtl(long count, long expected) {
        service.search("  fixture   movie  ", count, request);

        verify(jdbc).update(contains("INSERT INTO site_search_log"), eq("fixture movie"), anyString(), eq(expected));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(scores).incrementScore(key.capture(), eq("fixture movie"), eq(1D));
        assertTrue(key.getValue().startsWith("hot:search:"));
        verify(redis).expire(key.getValue(), 32, TimeUnit.DAYS);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "x"})
    void untrackedKeywordsDoNotTouchEitherStore(String keyword) {
        service.search(keyword, 1, request);
        verifyNoInteractions(jdbc, redis, scores);
    }

    @Test
    void databaseFailureDoesNotPreventTheIndependentCacheWrite() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("fixture database outage"));

        assertDoesNotThrow(() -> service.search("fixture", 4, request));

        verify(scores).incrementScore(anyString(), eq("fixture"), eq(1D));
        verify(redis).expire(anyString(), eq(32L), eq(TimeUnit.DAYS));
    }

    @Test
    void unavailableCacheOperationsDoNotFailSearchRecording() {
        when(redis.opsForZSet()).thenThrow(new RedisConnectionFailureException("fixture cache outage"));

        assertDoesNotThrow(() -> service.search("fixture", 4, request));

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void failedIncrementDoesNotPropagateOrIssueAnotherCacheCall() {
        when(scores.incrementScore(anyString(), anyString(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException("fixture cache outage"));

        assertDoesNotThrow(() -> service.search("fixture", 4, request));

        verify(jdbc).update(anyString(), any(Object[].class));
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void expiryFailureDoesNotTurnASuccessfulSearchIntoAnError() {
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RedisConnectionFailureException("fixture cache outage"));

        assertDoesNotThrow(() -> service.search("fixture", 4, request));

        verify(jdbc).update(anyString(), any(Object[].class));
        verify(scores).incrementScore(anyString(), eq("fixture"), eq(1D));
    }

    @Test
    void simultaneousFailuresAreBestEffortAndLogsDoNotExposeExceptionBodies() {
        String privateMarker = "fixture-private-exception-body";
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException(privateMarker));
        when(scores.incrementScore(anyString(), anyString(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException(privateMarker));
        Logger logger = (Logger) LoggerFactory.getLogger(MonitoringService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> service.search("fixture", 4, request));
            assertEquals(2, appender.list.size());
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(privateMarker)));
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void resourceViewAnalyticsFailureDoesNotPropagate() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("fixture database outage"));

        assertDoesNotThrow(() -> service.resourceOperation("movie", null, "VIEW", null, "SUCCESS", null, request));

        verifyNoInteractions(redis, scores);
    }
}
