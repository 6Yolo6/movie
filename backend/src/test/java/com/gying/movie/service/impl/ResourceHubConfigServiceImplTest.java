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
}
