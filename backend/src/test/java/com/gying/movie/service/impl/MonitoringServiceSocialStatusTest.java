package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class MonitoringServiceSocialStatusTest {
    @Test
    void socialFailureSummaryIncludesPreparationFailuresButDoesNotLabelUnknownAsFailed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of());
        when(jdbc.queryForMap(argThat((String sql) -> sql.contains("FROM social_post_log"))))
                .thenReturn(Map.of("socialPostedToday", 5L, "socialFailedToday", 3L));

        var result = new MonitoringService(jdbc, redis).overview();

        assertEquals(5L, result.get("socialPostedToday"));
        assertEquals(3L, result.get("socialFailedToday"));
        verify(jdbc).queryForMap(argThat((String sql) -> sql.contains("FROM social_post_log")
                && sql.contains("SUM(status IN ('FAILED','PREPARE_FAILED')) socialFailedToday")
                && !sql.contains("UNKNOWN")));
    }
}
