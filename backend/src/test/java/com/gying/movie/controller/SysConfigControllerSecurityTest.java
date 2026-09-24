package com.gying.movie.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gying.movie.entity.SysConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.utils.AuthHelper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class SysConfigControllerSecurityTest {

    private AuthHelper authHelper;
    private ISysConfigService configService;
    private SysConfigController controller;

    @BeforeEach
    void setUp() {
        authHelper = mock(AuthHelper.class);
        configService = mock(ISysConfigService.class);
        controller = new SysConfigController(configService, authHelper);
    }

    @Test
    void suppliesEditableWebSearchDefaultWithoutDatabaseWrite() {
        when(configService.list(any(QueryWrapper.class))).thenReturn(List.of());
        var response=controller.getAllConfigs("admin");
        var items=(List<?>)response.getBody();
        assertEquals("resource.search.rate_limit_per_minute",((Map<?,?>)items.get(0)).get("configKey"));
        assertEquals("5",((Map<?,?>)items.get(0)).get("configValue"));
        verify(configService,never()).updateConfig(any(),any());
    }
    @Test
    void validatesWebSearchRateBeforeSaving() {
        for(String value:List.of("0","61","-1","1.5","", "bad"))
            assertEquals(400,controller.updateConfig("resource.search.rate_limit_per_minute",value,"admin").getStatusCode().value());
        verify(configService,never()).updateConfig(any(),any());
        when(configService.updateConfig("resource.search.rate_limit_per_minute","12")).thenReturn(true);
        assertEquals(200,controller.updateConfig("resource.search.rate_limit_per_minute","12","admin").getStatusCode().value());
    }

    @Test
    void listRedactsSensitiveConfigurationValues() {
        SysConfig publicConfig = config("resource.audit.enabled", "true");
        SysConfig secretConfig = config("qq.bot.webhook-token", "fixture-secret");
        when(configService.list(any(QueryWrapper.class))).thenReturn(List.of(publicConfig, secretConfig));

        ResponseEntity<?> response = controller.getAllConfigs("Bearer admin");

        assertEquals(200, response.getStatusCode().value());
        List<?> body = assertInstanceOf(List.class, response.getBody());
        Map<?, ?> publicView = assertInstanceOf(Map.class, body.get(0));
        Map<?, ?> secretView = assertInstanceOf(Map.class, body.get(1));
        assertEquals("true", publicView.get("configValue"));
        assertEquals(SysConfigController.REDACTED, secretView.get("configValue"));
        assertEquals(true, secretView.get("sensitive"));
        verify(authHelper).requireAdmin("Bearer admin");
    }

    @Test
    void directSensitiveReadReturnsRedactedValue() {
        when(configService.getConfigValue("mail.resend.api-key", null)).thenReturn("fixture-secret");

        ResponseEntity<?> response = controller.getConfig("mail.resend.api-key", "Bearer admin");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(SysConfigController.REDACTED, response.getBody());
    }

    @Test
    void sensitiveConfigurationCannotBeUpdatedThroughRuntimeSettings() {
        ResponseEntity<?> response = controller.updateConfig(
                "resource.tmdb.api-key", "fixture-secret", "Bearer admin");

        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody()).contains("protected secret storage"));
        verify(configService, never()).updateConfig(any(), any());
    }

    @Test
    void malformedKeysAndOversizedValuesAreRejectedBeforePersistence() {
        ResponseEntity<?> malformed = controller.updateConfig("../secret", "value", "Bearer admin");
        ResponseEntity<?> oversized = controller.updateConfig("resource.audit.enabled", "x".repeat(501), "Bearer admin");

        assertEquals(400, malformed.getStatusCode().value());
        assertEquals(400, oversized.getStatusCode().value());
        verify(configService, never()).updateConfig(any(), any());
    }

    @Test
    void nonSensitiveRuntimeSettingStillUpdates() {
        when(configService.updateConfig("resource.audit.enabled", "false")).thenReturn(true);

        ResponseEntity<?> response = controller.updateConfig(
                "resource.audit.enabled", "false", "Bearer admin");

        assertEquals(200, response.getStatusCode().value());
        verify(configService).updateConfig("resource.audit.enabled", "false");
    }

    @Test
    void redactsCamelCaseSensitiveKeys() {
        assertTrue(SysConfigController.isSensitiveKey("dbPassword"));
        assertTrue(SysConfigController.isSensitiveKey("refreshToken"));
        assertTrue(SysConfigController.isSensitiveKey("clientSecret"));
        assertTrue(SysConfigController.isSensitiveKey("accessKey"));
    }

    private SysConfig config(String key, String value) {
        SysConfig config = new SysConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }
}
