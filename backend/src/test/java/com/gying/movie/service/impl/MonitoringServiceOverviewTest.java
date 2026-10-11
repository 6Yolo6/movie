package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class MonitoringServiceOverviewTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    final MonitoringService service = new MonitoringService(jdbc, redis);

    @Test void separatesApiRequestsFromPageViewsInOverviewAndTrend() {
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of());
        var overview = service.overview();
        assertEquals(LocalDate.now().toString(), overview.get("date"));
        assertEquals("Asia/Shanghai", overview.get("timeZone"));
        verify(jdbc).queryForMap(argThat((String sql) -> sql.contains("FROM site_access_log")
                && sql.contains("SUM(event_type='API'),0) requestsToday")
                && sql.contains("CASE WHEN event_type='PAGE_VIEW' THEN visitor_hash END")
                && sql.contains("event_type='API' AND status_code>=500")));
        verify(jdbc).queryForList(argThat((String sql) -> sql.contains("SUM(event_type='API'),0) requests")
                && sql.contains("GROUP BY DATE_FORMAT(created_at,'%Y-%m-%d')")));
    }

    @Test void fallsBackToDatabaseForTheRequestedDayWhenCacheFails() {
        when(redis.opsForZSet().reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenThrow(new IllegalStateException("fixture cache outage"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("keyword", "fixture", "count", 4L)));
        var date = LocalDate.of(2026, 10, 11);
        assertEquals(4L, service.hotSearches(date, 10).get(0).get("count"));
        verify(jdbc).queryForList(contains("created_at>=? AND created_at<?"), eq(java.sql.Date.valueOf(date)), eq(java.sql.Date.valueOf(date.plusDays(1))), eq(10));
    }

    @Test void emptyCacheAlsoFallsBackAndClampsLimit() {
        when(redis.opsForZSet().reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
        service.hotSearches(LocalDate.of(2026, 10, 11), 0);
        verify(redis.opsForZSet()).reverseRangeWithScores("hot:search:2026-10-11", 0, 0);
        verify(jdbc).queryForList(anyString(), any(java.sql.Date.class), any(java.sql.Date.class), eq(1));
    }
}
