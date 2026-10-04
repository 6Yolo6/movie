package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ResourceHubConfigRequest;
import com.gying.movie.dto.ResourceHubConfigResponse;
import com.gying.movie.service.ISysConfigService;
import org.junit.jupiter.api.Test;

class ResourceHubConfigServiceImplTest {

    @Test
    void updatesXunleiCredentialsOnlyInRuntimeProperties() {
        ResourceHubProperties properties = new ResourceHubProperties();
        ISysConfigService sysConfigService = mock(ISysConfigService.class);
        ResourceHubConfigServiceImpl service = new ResourceHubConfigServiceImpl(properties, sysConfigService);
        ResourceHubConfigRequest request = new ResourceHubConfigRequest();
        request.setXunleiAuthorization("  Bearer latest-token  ");
        request.setXunleiCaptchaToken("  latest-captcha  ");

        ResourceHubConfigResponse response = service.updateConfig(request);

        assertEquals("Bearer latest-token", properties.getXunlei().getAuthorization());
        assertEquals("latest-captcha", properties.getXunlei().getCaptchaToken());
        assertTrue(response.isXunleiAuthorizationConfigured());
        assertTrue(response.isXunleiCaptchaConfigured());
        verifyNoInteractions(sysConfigService);
    }

    @Test
    void blankCredentialValuesKeepCurrentRuntimeValues() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getXunlei().setAuthorization("Bearer current-token");
        properties.getXunlei().setCaptchaToken("current-captcha");
        ISysConfigService sysConfigService = mock(ISysConfigService.class);
        ResourceHubConfigServiceImpl service = new ResourceHubConfigServiceImpl(properties, sysConfigService);
        ResourceHubConfigRequest request = new ResourceHubConfigRequest();
        request.setXunleiAuthorization("  ");
        request.setXunleiCaptchaToken("");

        service.updateConfig(request);

        assertEquals("Bearer current-token", properties.getXunlei().getAuthorization());
        assertEquals("current-captcha", properties.getXunlei().getCaptchaToken());
        verifyNoInteractions(sysConfigService);
    }

    @Test
    void exposesJwtAuthorizationExpirationWithoutExposingToken() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getXunlei().setAuthorization("Bearer eyJhbGciOiJub25lIn0.eyJleHAiOjQxMDI0NDQ4MDB9.signature");
        ResourceHubConfigServiceImpl service = new ResourceHubConfigServiceImpl(
                properties, mock(ISysConfigService.class));

        ResourceHubConfigResponse response = service.getConfig();

        assertEquals("2100-01-01T00:00:00Z", response.getXunleiAuthorizationExpiresAt());
        assertTrue(!response.isXunleiAuthorizationExpired());
    }
    @Test
    void missingEndPagesPreserveLegacyScope() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getTmdb().setAutoSyncPage(5);
        properties.getGying().setAutoSyncPage(8);
        var service = new ResourceHubConfigServiceImpl(properties, mock(ISysConfigService.class));
        var response = service.getConfig();
        assertEquals(5, response.getTmdbAutoSyncEndPage());
        assertEquals(8, response.getGyingAutoSyncEndPage());
    }

    @Test
    void invalidRangeIsRejectedBeforeAnyOtherSettingIsWritten() {
        var properties = new ResourceHubProperties();
        var storage = mock(ISysConfigService.class);
        var service = new ResourceHubConfigServiceImpl(properties, storage);
        var request = new ResourceHubConfigRequest();
        request.setEnabled(true);
        request.setTmdbAutoSyncPage(9);
        request.setTmdbAutoSyncEndPage(3);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class, () -> service.updateConfig(request));
        org.junit.jupiter.api.Assertions.assertFalse(properties.isEnabled());
        verifyNoInteractions(storage);
    }

    @Test
    void rangesSurviveReloadInAFreshServiceAndOldClientsCanStillSetASinglePage() {
        var values = new java.util.HashMap<String, String>();
        var storage = mock(ISysConfigService.class);
        org.mockito.Mockito.when(storage.getConfigValue(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(call ->
                    values.getOrDefault(call.getArgument(0), call.getArgument(1)));
        org.mockito.Mockito.when(storage.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.gying.movie.entity.SysConfig config = call.getArgument(0);
            values.put(config.getConfigKey(), config.getConfigValue());
            return true;
        });
        var service = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), storage);
        var request = new ResourceHubConfigRequest();
        request.setTmdbAutoSyncPage(2);
        request.setTmdbAutoSyncEndPage(500);
        request.setGyingAutoSyncPage(4);
        service.updateConfig(request);
        var reloaded = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), storage).getConfig();
        assertEquals(2, reloaded.getTmdbAutoSyncPage());
        assertEquals(500, reloaded.getTmdbAutoSyncEndPage());
        assertEquals(4, reloaded.getGyingAutoSyncPage());
        assertEquals(4, reloaded.getGyingAutoSyncEndPage());
    }

    @Test
    void rejectsOutOfBoundsAndReversedGyingRanges() {
        for (int[] range : new int[][] {{0, 2}, {1, 501}, {3, 2}}) {
            var service = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), mock(ISysConfigService.class));
            var request = new ResourceHubConfigRequest();
            request.setGyingAutoSyncPage(range[0]);
            request.setGyingAutoSyncEndPage(range[1]);
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.web.server.ResponseStatusException.class, () -> service.updateConfig(request));
        }
    }
}
