package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.jdbc.core.JdbcTemplate;

class MonitoringServiceHotKeywordsTest {
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private MonitoringService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        service = new MonitoringService(jdbc, redis);
    }

    private static Set<TypedTuple<String>> tuples(Object... valueScorePairs) {
        Set<TypedTuple<String>> set = new LinkedHashSet<>();
        for (int index = 0; index < valueScorePairs.length; index += 2) {
            set.add(new DefaultTypedTuple<>(
                    String.valueOf(valueScorePairs[index]),
                    ((Number) valueScorePairs[index + 1]).doubleValue()));
        }
        return set;
    }

    @Test
    void mergesRecentDaysAndRanksByScore() {
        when(zset.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(tuples("热词甲", 2, "热词乙", 1))
                .thenReturn(tuples("热词乙", 2))
                .thenReturn(new LinkedHashSet<>());

        List<Map<String, Object>> result = service.hotKeywords(3, 10);

        assertEquals(2, result.size());
        assertEquals("热词乙", result.get(0).get("keyword"));
        assertEquals(3L, result.get(0).get("count"));
        assertEquals("热词甲", result.get(1).get("keyword"));
        assertEquals(2L, result.get(1).get("count"));
    }

    @Test
    void respectsLimitAndIgnoresBlankKeywords() {
        when(zset.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(tuples("热词甲", 5, "  ", 9, "热词乙", 4));

        List<Map<String, Object>> result = service.hotKeywords(1, 1);

        assertEquals(1, result.size());
        assertEquals("热词甲", result.get(0).get("keyword"));
    }

    @Test
    void fallsBackToDatabaseWhenRedisIsEmpty() {
        when(zset.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>());
        when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
                .thenReturn(List.of(Map.of("keyword", "冷门热词", "hits", 7L)));

        List<Map<String, Object>> result = service.hotKeywords(7, 10);

        assertEquals(1, result.size());
        assertEquals("冷门热词", result.get(0).get("keyword"));
        assertEquals(7L, result.get(0).get("count"));
    }

    @Test
    void returnsEmptyListWhenBothSourcesFail() {
        when(zset.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("redis down"));
        when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
                .thenThrow(new IllegalStateException("mysql down"));

        List<Map<String, Object>> result = service.hotKeywords(7, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void clampsRequestedBounds() {
        when(zset.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(tuples("热词甲", 1));

        List<Map<String, Object>> result = service.hotKeywords(0, 500);

        assertEquals(1, result.size());
        assertEquals("热词甲", result.get(0).get("keyword"));
        org.mockito.Mockito.verify(zset, org.mockito.Mockito.times(1))
                .reverseRangeWithScores(eq("hot:search:" + java.time.LocalDate.now()), eq(0L), eq(49L));
    }
}
