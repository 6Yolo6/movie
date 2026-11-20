package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.utils.BaiduTemporaryScope;

public final class BaiduScopeTestAssertions {
    public static final String TEMP_SCOPE = "QQ_BOT:11111111-1111-1111-1111-111111111111";

    private BaiduScopeTestAssertions() {}

    public static void permanentTaskQuery(Wrapper<?> wrapper) {
        QueryWrapper<?> query = (QueryWrapper<?>) wrapper;
        assertTrue(query.getSqlSegment().contains("scope_key ="));
        assertTrue(query.getParamNameValuePairs().containsValue(BaiduTemporaryScope.PERMANENT));
    }

    public static void permanentDiscoveryQuery(Wrapper<?> wrapper) {
        QueryWrapper<?> query = (QueryWrapper<?>) wrapper;
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("source_ref IS NULL"));
        assertTrue(sql.contains("OR source_ref NOT LIKE"));
        assertTrue(query.getParamNameValuePairs().containsValue(BaiduTemporaryScope.DISCOVERY_PREFIX + "%"));
    }

    public static void permanentResourceQuery(Wrapper<?> wrapper) {
        QueryWrapper<?> query = (QueryWrapper<?>) wrapper;
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("source IS NULL"));
        assertTrue(sql.contains("OR source <>"));
        assertTrue(query.getParamNameValuePairs().containsValue(BaiduTemporaryScope.RESOURCE_SOURCE));
    }
}
