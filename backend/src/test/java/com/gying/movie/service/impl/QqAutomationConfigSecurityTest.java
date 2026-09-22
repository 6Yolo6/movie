package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.ISysConfigService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QqAutomationConfigSecurityTest {

    @Test
    void runtimeConfigCannotDisablePerUserQqRateLimit() {
        QqBotProperties properties = new QqBotProperties();
        properties.setRateLimitPerMinute(5);
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.count(any(QueryWrapper.class))).thenReturn(1L);
        when(configs.getConfigValue(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String fallback = invocation.getArgument(1);
            return "qq.bot.rate_limit_per_minute".equals(key) ? "0" : fallback;
        });

        QqAutomationConfigServiceImpl service = new QqAutomationConfigServiceImpl(
                properties, configs, "guild", "movie", "tv");

        service.reload();

        assertEquals(1, properties.getRateLimitPerMinute());
        assertEquals(1, service.getConfig().get("botRateLimitPerMinute"));
    }

    @Test
    void updateRequestClampsZeroRateLimitToOne() {
        QqBotProperties properties = new QqBotProperties();
        properties.setRateLimitPerMinute(5);
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.count(any(QueryWrapper.class))).thenReturn(1L);
        SysConfig existing = new SysConfig();
        existing.setConfigKey("qq.bot.rate_limit_per_minute");
        existing.setConfigValue("5");
        when(configs.getOne(any(QueryWrapper.class), eq(false))).thenReturn(existing);
        when(configs.getConfigValue(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String fallback = invocation.getArgument(1);
            return "qq.bot.rate_limit_per_minute".equals(key) ? existing.getConfigValue() : fallback;
        });

        QqAutomationConfigServiceImpl service = new QqAutomationConfigServiceImpl(
                properties, configs, "guild", "movie", "tv");

        service.updateConfig(Map.of("botRateLimitPerMinute", "0"));

        assertEquals("1", existing.getConfigValue());
        assertEquals(1, properties.getRateLimitPerMinute());
    }
}
