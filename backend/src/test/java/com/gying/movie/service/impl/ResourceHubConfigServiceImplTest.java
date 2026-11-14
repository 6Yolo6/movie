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
        for (int[] range : new int[][] {{0, 2}, {1, 51}, {1, 501}, {3, 2}}) {
            var service = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), mock(ISysConfigService.class));
            var request = new ResourceHubConfigRequest();
            request.setGyingAutoSyncPage(range[0]);
            request.setGyingAutoSyncEndPage(range[1]);
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.web.server.ResponseStatusException.class, () -> service.updateConfig(request));
        }
    }

    @Test void perSourceRangesSurviveRestartAndPartialEditsLeaveOtherSourcesUntouched() {
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
        var properties = new ResourceHubProperties();
        var service = new ResourceHubConfigServiceImpl(properties, storage);
        var request = new ResourceHubConfigRequest();
        request.setTmdbAutoSyncRanges(java.util.Map.of(
                "POPULAR_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(151, 500),
                "POPULAR_TV", new com.gying.movie.dto.MetadataCrawlRange(121, 400)));
        request.setGyingAutoSyncRanges(java.util.Map.of(
                "HITS_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(20, 40),
                "HITS_TV", new com.gying.movie.dto.MetadataCrawlRange(45, 50)));
        service.updateConfig(request);
        request = new ResourceHubConfigRequest();
        request.setTmdbAutoSyncRanges(java.util.Map.of("POPULAR_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(160, 500)));
        service.updateConfig(request);
        var restarted = new ResourceHubProperties();
        var response = new ResourceHubConfigServiceImpl(restarted, storage).getConfig();
        assertEquals(160, response.getTmdbAutoSyncRanges().get("POPULAR_MOVIE").startPage());
        assertEquals(121, restarted.getTmdb().rangeFor("POPULAR_TV").startPage());
        assertEquals(20, restarted.getGying().rangeFor("HITS_MOVIE").startPage());
        assertEquals(45, restarted.getGying().rangeFor("HITS_TV").startPage());
        assertEquals(1, restarted.getTmdb().rangeFor("TOP_RATED_TV").startPage());
    }

    @Test void invalidSourceRangesAreRejectedBeforeCredentialsOrSwitchesChange() {
        for (var ranges : java.util.List.of(
                java.util.Map.of("POPULAR_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(150, 100)),
                java.util.Map.of("HITS_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(1, 500)),
                java.util.Map.of("POPULAR_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(1, 501)))) {
            var properties = new ResourceHubProperties();
            var storage = mock(ISysConfigService.class);
            var service = new ResourceHubConfigServiceImpl(properties, storage);
            var request = new ResourceHubConfigRequest();
            request.setEnabled(true);
            request.setXunleiAuthorization("replacement");
            request.setTmdbAutoSyncRanges(ranges);
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.web.server.ResponseStatusException.class, () -> service.updateConfig(request));
            org.junit.jupiter.api.Assertions.assertFalse(properties.isEnabled());
            org.junit.jupiter.api.Assertions.assertNull(properties.getXunlei().getAuthorization());
            verifyNoInteractions(storage);
        }
    }

    @Test void progressUsesTheSamePerSourceBoundsAsAutomaticScheduling() {
        var properties = new ResourceHubProperties();
        properties.getTmdb().setAutoSyncSources("POPULAR_MOVIE,POPULAR_TV");
        properties.getTmdb().setAutoSyncRanges(java.util.Map.of(
                "POPULAR_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(151, 500),
                "POPULAR_TV", new com.gying.movie.dto.MetadataCrawlRange(121, 400)));
        var storage = mock(ISysConfigService.class);
        org.mockito.Mockito.when(storage.getConfigValue(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> call.getArgument(1));
        var service = new ResourceHubConfigServiceImpl(properties, storage);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "taskService",
                mock(com.gying.movie.service.IResourceHubTaskService.class));
        var progress = service.getConfig().getMetadataCrawlProgress();
        assertEquals(151, progress.get(0).startPage());
        assertEquals(151, progress.get(0).nextPage());
        assertEquals(121, progress.get(1).startPage());
        assertEquals(121, progress.get(1).nextPage());
    }
    @Test void missingPauseKeysDefaultToFalseWithoutCreatingPauseRows() {
        var values = new java.util.HashMap<String, String>();
        var storage = pauseStorage(values);
        var service = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), storage);
        var pauses = service.getConfig().getGyingAutoSyncPausedSources();
        assertEquals(12, pauses.size());
        assertTrue(pauses.values().stream().noneMatch(Boolean::booleanValue));
        assertTrue(values.keySet().stream().noneMatch(key -> key.startsWith("resource.hub.gying.auto_sync_paused.")));
    }

    @Test void sourcePauseUpdatesOnlyItsOwnKeyAndSurvivesIndependentEditsAndRestart() {
        var values = new java.util.HashMap<String, String>();
        values.put("resource.hub.gying.auto_sync_sources", "HITS_MOVIE,CSCORE_TV");
        values.put("resource.hub.gying.auto_sync_ranges", "{\"HITS_MOVIE\":{\"startPage\":80,\"endPage\":500}}");
        var original = new java.util.HashMap<>(values);
        var storage = pauseStorage(values);
        var service = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), storage);
        service.setGyingSourcePaused("HITS_MOVIE", true);
        service.setGyingSourcePaused("CSCORE_TV", true);
        service.setGyingSourcePaused("HITS_MOVIE", false);
        assertEquals(4, values.size());
        original.forEach((key, value) -> assertEquals(value, values.get(key)));
        var restarted = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), storage).getConfig();
        assertEquals(false, restarted.getGyingAutoSyncPausedSources().get("HITS_MOVIE"));
        assertEquals(true, restarted.getGyingAutoSyncPausedSources().get("CSCORE_TV"));
        assertEquals(50, restarted.getGyingAutoSyncRanges().get("HITS_MOVIE").startPage());
        assertEquals(50, restarted.getGyingAutoSyncRanges().get("HITS_MOVIE").endPage());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> restarted.getGyingAutoSyncPausedSources().put("HITS_TV", true));
    }

    @Test void failedPausePersistenceAndUnknownSourcesDoNotChangeRuntimeState() {
        var properties = new ResourceHubProperties();
        properties.getGying().setAutoSyncPausedSources(java.util.Map.of("CSCORE_TV", true));
        var storage = mock(ISysConfigService.class);
        var service = new ResourceHubConfigServiceImpl(properties, storage);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.setGyingSourcePaused("POPULAR_MOVIE", true));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.setGyingSourcePaused(null, true));
        verifyNoInteractions(storage);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.setGyingSourcePaused("HITS_MOVIE", true));
        assertEquals(java.util.Map.of("CSCORE_TV", true), properties.getGying().getAutoSyncPausedSources());
    }

    @Test void pausedProgressPreservesSavedPageOffsetTaskAndConfiguredRange() {
        var properties = new ResourceHubProperties();
        properties.getTmdb().setAutoSyncSources("");
        properties.getGying().setAutoSyncSources("CSCORE_TV");
        properties.getGying().setAutoSyncEndPage(500);
        var values = new java.util.HashMap<String, String>();
        values.put("resource.hub.gying.auto_sync_paused.CSCORE_TV", "true");
        var tasks = mock(com.gying.movie.service.IResourceHubTaskService.class);
        var checkpoint = new com.gying.movie.entity.ResourceHubTask();
        checkpoint.setId(91L); checkpoint.setStatus("SUCCEEDED");
        checkpoint.setPayload("{\"page\":44,\"crawl\":{\"mode\":\"RANGE\",\"startPage\":1,\"endPage\":500,\"nextPage\":45,\"nextOffset\":12}}");
        org.mockito.Mockito.when(tasks.getOne(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(checkpoint);
        var service = new ResourceHubConfigServiceImpl(properties, pauseStorage(values));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "taskService", tasks);
        String original = checkpoint.getPayload();
        var position = service.getConfig().getMetadataCrawlProgress().get(0);
        assertEquals("PAUSED", position.status()); assertEquals(45, position.nextPage());
        assertEquals(13, position.nextItem()); assertEquals(50, position.endPage()); assertEquals(91L, position.taskId());
        assertEquals(original, checkpoint.getPayload());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test void newCatalogSourcesHaveIndependentRangesAndPausesWithoutChangingOtherSources() {
        var values = new java.util.HashMap<String, String>();
        values.put("resource.hub.gying.auto_sync_paused.CSCORE_MOVIE", "true");
        var service = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), pauseStorage(values));
        var request = new ResourceHubConfigRequest();
        request.setGyingAutoSyncSources("TIME_MOVIE,TIME_TV,TIME_ANIME,SCORE_MOVIE,SCORE_TV,SCORE_ANIME");
        request.setGyingAutoSyncRanges(java.util.Map.of(
                "TIME_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(1, 2),
                "SCORE_TV", new com.gying.movie.dto.MetadataCrawlRange(1, 50)));
        service.updateConfig(request);
        service.setGyingSourcePaused("TIME_TV", true);
        var fresh = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), pauseStorage(values)).getConfig();
        assertEquals(request.getGyingAutoSyncSources(), fresh.getGyingAutoSyncSources());
        assertEquals(2, fresh.getGyingAutoSyncRanges().get("TIME_MOVIE").endPage());
        assertEquals(50, fresh.getGyingAutoSyncRanges().get("SCORE_TV").endPage());
        assertTrue(fresh.getGyingAutoSyncPausedSources().get("TIME_TV"));
        assertTrue(fresh.getGyingAutoSyncPausedSources().get("CSCORE_MOVIE"));
        assertEquals(false, fresh.getGyingAutoSyncPausedSources().get("TIME_MOVIE"));
    }

    @Test void legacyGyingRangeReadsAreBoundedWithoutWritingSavedConfigurationAndTmdbKeeps500Pages() {
        var values = new java.util.HashMap<String, String>();
        values.put("resource.hub.gying.auto_sync_page", "80");
        values.put("resource.hub.gying.auto_sync_end_page", "500");
        values.put("resource.hub.gying.auto_sync_ranges", "{\"CSCORE_MOVIE\":{\"startPage\":80,\"endPage\":500}}");
        values.put("resource.hub.tmdb.auto_sync_end_page", "500");
        var original = new java.util.HashMap<>(values);
        var service = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), pauseStorage(values));
        var config = service.getConfig();
        assertEquals(50, config.getGyingAutoSyncPage());
        assertEquals(50, config.getGyingAutoSyncEndPage());
        assertEquals(50, config.getGyingAutoSyncRanges().get("CSCORE_MOVIE").startPage());
        assertEquals(50, config.getGyingAutoSyncRanges().get("CSCORE_MOVIE").endPage());
        assertEquals(500, config.getTmdbAutoSyncEndPage());
        original.forEach((key, value) -> assertEquals(value, values.get(key)));
    }

    @Test void gyingSourceSpecificPage51IsRejectedBeforeAnyConfigurationOrCredentialWrite() {
        var properties = new ResourceHubProperties();
        var storage = mock(ISysConfigService.class);
        var service = new ResourceHubConfigServiceImpl(properties, storage);
        var request = new ResourceHubConfigRequest();
        request.setEnabled(true);
        request.setXunleiAuthorization("replacement");
        request.setGyingAutoSyncRanges(java.util.Map.of("TIME_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(1, 51)));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.updateConfig(request));
        org.junit.jupiter.api.Assertions.assertFalse(properties.isEnabled());
        org.junit.jupiter.api.Assertions.assertNull(properties.getXunlei().getAuthorization());
        verifyNoInteractions(storage);
    }

    @Test void latestChartDefaultsUseFirstTwoPagesAndExplicitRangesAndOtherProvidersKeepTheirScope() {
        var properties = new ResourceHubProperties();
        properties.getGying().setAutoSyncPage(11);
        properties.getGying().setAutoSyncEndPage(50);
        properties.getTmdb().setAutoSyncPage(151);
        properties.getTmdb().setAutoSyncEndPage(500);
        for (String source : java.util.List.of("TIME_MOVIE", "TIME_TV", "TIME_ANIME")) {
            assertEquals(new com.gying.movie.dto.MetadataCrawlRange(1, 2), properties.getGying().rangeFor(source));
        }
        for (String source : java.util.List.of("HITS_MOVIE", "CSCORE_TV", "SCORE_ANIME")) {
            assertEquals(new com.gying.movie.dto.MetadataCrawlRange(11, 50), properties.getGying().rangeFor(source));
        }
        assertEquals(new com.gying.movie.dto.MetadataCrawlRange(151, 500), properties.getTmdb().rangeFor("POPULAR_TV"));
        properties.getGying().setAutoSyncRanges(java.util.Map.of(
                "TIME_TV", new com.gying.movie.dto.MetadataCrawlRange(3, 8),
                "HITS_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(17, 29)));
        assertEquals(new com.gying.movie.dto.MetadataCrawlRange(3, 8), properties.getGying().rangeFor("TIME_TV"));
        assertEquals(new com.gying.movie.dto.MetadataCrawlRange(17, 29), properties.getGying().rangeFor("HITS_MOVIE"));
        assertEquals(new com.gying.movie.dto.MetadataCrawlRange(1, 2), properties.getGying().rangeFor("TIME_MOVIE"));
        var values = new java.util.HashMap<String, String>();
        values.put("resource.hub.gying.auto_sync_page", "11");
        values.put("resource.hub.gying.auto_sync_end_page", "50");
        values.put("resource.hub.gying.auto_sync_ranges", "{\"TIME_TV\":{\"startPage\":3,\"endPage\":8}}");
        var fresh = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), pauseStorage(values)).getConfig();
        assertEquals(new com.gying.movie.dto.MetadataCrawlRange(1, 2), fresh.getGyingAutoSyncRanges().get("TIME_MOVIE"));
        assertEquals(new com.gying.movie.dto.MetadataCrawlRange(3, 8), fresh.getGyingAutoSyncRanges().get("TIME_TV"));
        assertEquals(new com.gying.movie.dto.MetadataCrawlRange(11, 50), fresh.getGyingAutoSyncRanges().get("CSCORE_TV"));
    }

    private ISysConfigService pauseStorage(java.util.Map<String, String> values) {
        var storage = mock(ISysConfigService.class);
        org.mockito.Mockito.when(storage.count(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query = call.getArgument(0);
            query.getSqlSegment();
            String key = (String) query.getParamNameValuePairs().values().iterator().next();
            return values.containsKey(key) ? 1L : 0L;
        });
        org.mockito.Mockito.when(storage.getConfigValue(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> values.getOrDefault(call.getArgument(0), call.getArgument(1)));
        org.mockito.Mockito.when(storage.getOne(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query = call.getArgument(0);
            query.getSqlSegment();
            String key = (String) query.getParamNameValuePairs().values().iterator().next();
            if (!values.containsKey(key)) return null;
            var config = new com.gying.movie.entity.SysConfig(); config.setId(1L);
            config.setConfigKey(key); config.setConfigValue(values.get(key)); return config;
        });
        org.mockito.stubbing.Answer<Boolean> save = call -> {
            com.gying.movie.entity.SysConfig config = call.getArgument(0);
            values.put(config.getConfigKey(), config.getConfigValue()); return true;
        };
        org.mockito.Mockito.when(storage.save(org.mockito.ArgumentMatchers.any())).thenAnswer(save);
        org.mockito.Mockito.when(storage.updateById(org.mockito.ArgumentMatchers.any())).thenAnswer(save);
        return storage;
    }

}
