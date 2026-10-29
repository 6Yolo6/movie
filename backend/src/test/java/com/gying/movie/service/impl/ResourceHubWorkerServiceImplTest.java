package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.dto.ResourceHubWorkerResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IGyingMetadataSyncService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class ResourceHubWorkerServiceImplTest {

    @Test
    void enqueuesOneTmdbSourcePerIntervalInRoundRobinOrder() {
        Fixture fixture = new Fixture();
        ResourceHubTask latest = new ResourceHubTask();
        latest.setKeyword("TOP_RATED_MOVIE");
        latest.setCreatedAt(LocalDateTime.now().minusHours(13));
        when(fixture.taskService.count(any(Wrapper.class))).thenReturn(0L);
        when(fixture.taskService.getOne(any(Wrapper.class), eq(false))).thenReturn(latest);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());

        ResourceHubWorkerResult result = fixture.service.runOnce();

        verify(fixture.tmdbService).enqueueAutomatic("TOP_RATED_TV");
        assertEquals(1, result.getMetadataSyncTasksCreated());
    }

    @Test
    void doesNotEnqueueWhileTmdbTaskIsActive() {
        Fixture fixture = new Fixture();
        when(fixture.taskService.count(any(Wrapper.class))).thenReturn(1L);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());

        ResourceHubWorkerResult result = fixture.service.runOnce();

        verify(fixture.tmdbService, never()).enqueueAutomatic(any());
        assertEquals(0, result.getMetadataSyncTasksCreated());
    }

    @Test
    void wrapsToFirstSourceAfterLastConfiguredSource() {
        assertEquals(
                "TRENDING_MOVIE_DAY",
                ResourceHubWorkerServiceImpl.nextAutoSyncSource(
                        List.of("TRENDING_MOVIE_DAY", "TRENDING_TV_DAY"),
                        "TRENDING_TV_DAY"));
    }

    @Test
    void scheduledAndForcedWorkerNeverDrainLegacyCloudQueues() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        properties.getWorker().setEnabled(true);
        IResourceHubTaskService taskService = mock(IResourceHubTaskService.class);
        ITmdbMetadataSyncService tmdbService = mock(ITmdbMetadataSyncService.class);
        IGyingMetadataSyncService gyingService = mock(IGyingMetadataSyncService.class);
        IResourceDiscoveryService discoveryService = mock(IResourceDiscoveryService.class);
        IQuarkTransferRunnerService quarkService = mock(IQuarkTransferRunnerService.class);
        IXunleiTransferRunnerService xunleiService = mock(IXunleiTransferRunnerService.class);
        IResourceHubPublishService publishService = mock(IResourceHubPublishService.class);
        IResourceHubConfigService configService = mock(IResourceHubConfigService.class);
        when(taskService.list(any(Wrapper.class))).thenReturn(List.of());
        when(quarkService.submitPending(any(Integer.class))).thenReturn(new QuarkTransferRunResult());
        when(xunleiService.submitPending(any(Integer.class))).thenReturn(new QuarkTransferRunResult());
        when(publishService.publishPending(any(Integer.class))).thenReturn(new ResourceHubPublishResult());
        ResourceHubWorkerServiceImpl service = new ResourceHubWorkerServiceImpl(
                properties, taskService, tmdbService, gyingService, discoveryService,
                quarkService, xunleiService, publishService, configService);

        service.runOnce();

        service.runOnce(true);
        Mockito.verifyNoInteractions(xunleiService, quarkService, publishService, discoveryService);
        org.mockito.ArgumentCaptor<Wrapper<ResourceHubTask>> queries = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(taskService, Mockito.times(2)).list(queries.capture());
        for (var query : queries.getAllValues()) {
            org.junit.jupiter.api.Assertions.assertTrue(query.getCustomSqlSegment().contains("task_type"));
        }
    }

    @Test
    void gyingUsesAutomaticPlannerAndRetainsIntervalAndActiveTaskGuards() {
        Fixture fixture = new Fixture();
        fixture.properties.getTmdb().setAutoSyncEnabled(false);
        fixture.properties.getGying().setAutoSyncEnabled(true);
        fixture.properties.getGying().setAutoSyncSources("HITS_MOVIE,CSCORE_MOVIE");
        fixture.properties.getGying().setAutoSyncIntervalHours(1);
        ResourceHubTask latest = new ResourceHubTask();
        latest.setKeyword("HITS_MOVIE");
        latest.setCreatedAt(LocalDateTime.now().minusHours(2));
        when(fixture.taskService.getOne(any(Wrapper.class), eq(false))).thenReturn(latest);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());
        fixture.service.runOnce();
        verify(fixture.gyingService).enqueueAutomatic("CSCORE_MOVIE");
        Mockito.clearInvocations(fixture.gyingService);
        latest.setCreatedAt(LocalDateTime.now());
        fixture.service.runOnce();
        verify(fixture.gyingService, never()).enqueueAutomatic(any());
        latest.setCreatedAt(LocalDateTime.now().minusHours(2));
        when(fixture.taskService.count(any(Wrapper.class))).thenReturn(1L);
        fixture.service.runOnce();
        verify(fixture.gyingService, never()).enqueueAutomatic(any());
    }

    @Test void scheduledRetryDoesNotTouchAnyQueue() {
        Fixture fixture = new Fixture();
        var result = fixture.service.retryDiscoveredTransfers(false);
        assertEquals("SKIPPED", result.get("status"));
        Mockito.verifyNoInteractions(fixture.taskService, fixture.discoveryService, fixture.transferService, fixture.configService);
    }


    @Test void sourceRotationSkipsPausedCatalogsAndAllPausedCatalogsCreateNoWork() {
        Fixture fixture = new Fixture();
        fixture.properties.getTmdb().setAutoSyncEnabled(false);
        fixture.properties.getGying().setAutoSyncEnabled(true);
        fixture.properties.getGying().setAutoSyncSources("HITS_MOVIE,HITS_TV,CSCORE_TV");
        fixture.properties.getGying().setAutoSyncPausedSources(java.util.Map.of("HITS_MOVIE", true, "HITS_TV", true));
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());
        fixture.service.runOnce();
        verify(fixture.gyingService).enqueueAutomatic("CSCORE_TV");
        Mockito.clearInvocations(fixture.gyingService);
        fixture.properties.getGying().setAutoSyncPausedSources(java.util.Map.of("HITS_MOVIE", true, "HITS_TV", true, "CSCORE_TV", true));
        fixture.service.runOnce();
        Mockito.verifyNoInteractions(fixture.gyingService);
    }

    @Test void runnableQueryExcludesPausedAutomaticHeadsBeforeApplyingItsLimit() {
        Fixture fixture = new Fixture();
        fixture.properties.getTmdb().setAutoSyncEnabled(false);
        fixture.properties.getGying().setAutoSyncPausedSources(java.util.Map.of("HITS_MOVIE", true));
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());
        fixture.service.runOnce();
        var capture = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(fixture.taskService).list(capture.capture());
        var query = capture.getValue(); String sql = query.getCustomSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("keyword NOT IN"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("JSON_VALID(payload)"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("$.crawl.mode"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.indexOf("keyword NOT IN") < sql.indexOf("LIMIT"));
        org.junit.jupiter.api.Assertions.assertTrue(query.getParamNameValuePairs().containsValue("HITS_MOVIE"));
        org.junit.jupiter.api.Assertions.assertTrue(query.getParamNameValuePairs().containsValue("GYING"));
    }

    @Test void pauseAfterQueryLeavesPendingCheckpointUntouchedWhileManualAndTmdbTasksRun() {
        Fixture fixture = new Fixture();
        fixture.properties.getTmdb().setAutoSyncEnabled(false);
        ResourceHubTask automatic = metadataTask(1L, "GYING", "HITS_MOVIE", "{\"page\":80,\"crawl\":{\"mode\":\"RANGE\",\"offset\":12}}");
        ResourceHubTask manual = metadataTask(2L, "GYING", "HITS_MOVIE", "{\"page\":2}");
        ResourceHubTask tmdb = metadataTask(3L, "TMDB", "POPULAR_MOVIE", "{\"crawl\":{\"mode\":\"RANGE\"}}");
        String payload = automatic.getPayload(); LocalDateTime scheduled = automatic.getScheduledAt();
        when(fixture.taskService.list(any(Wrapper.class))).thenAnswer(call -> {
            fixture.properties.getGying().setAutoSyncPausedSources(java.util.Map.of("HITS_MOVIE", true));
            return List.of(automatic, manual, tmdb);
        });
        var success = new com.gying.movie.dto.TmdbSyncResult(); success.setStatus("SUCCEEDED");
        when(fixture.gyingService.runTask(2L)).thenReturn(success); when(fixture.tmdbService.runTask(3L)).thenReturn(success);
        var result = fixture.service.runOnce();
        verify(fixture.gyingService, never()).runTask(1L); verify(fixture.gyingService).runTask(2L); verify(fixture.tmdbService).runTask(3L);
        assertEquals(2, result.getTasksProcessed()); assertEquals(2, result.getTasksSucceeded());
        assertEquals("PENDING", automatic.getStatus()); assertEquals(payload, automatic.getPayload());
        assertEquals(4, automatic.getAttempts()); assertEquals(scheduled, automatic.getScheduledAt());
        verify(fixture.taskService, never()).updateById(any());
        fixture.properties.getGying().setAutoSyncPausedSources(java.util.Map.of("HITS_MOVIE", false));
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of(automatic));
        when(fixture.gyingService.runTask(1L)).thenReturn(success);
        fixture.service.runOnce(); verify(fixture.gyingService).runTask(1L); assertEquals(payload, automatic.getPayload());
    }

    @Test void pauseAfterAutomaticTaskStartsAllowsItsResultToFinishNormally() {
        Fixture fixture = new Fixture(); fixture.properties.getTmdb().setAutoSyncEnabled(false);
        var automatic = metadataTask(1L, "GYING", "HITS_MOVIE", "{\"crawl\":{\"mode\":\"RANGE\"}}");
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of(automatic));
        when(fixture.gyingService.runTask(1L)).thenAnswer(call -> {
            fixture.properties.getGying().setAutoSyncPausedSources(java.util.Map.of("HITS_MOVIE", true));
            var success = new com.gying.movie.dto.TmdbSyncResult(); success.setStatus("SUCCEEDED"); return success;
        });
        var result = fixture.service.runOnce(); assertEquals(1, result.getTasksSucceeded()); assertEquals(0, result.getTasksFailed());
        verify(fixture.taskService, never()).updateById(any());
    }

    private ResourceHubTask metadataTask(Long id, String provider, String source, String payload) {
        var task = new ResourceHubTask(); task.setId(id); task.setTaskType("METADATA_SYNC");
        task.setSource(provider); task.setKeyword(source); task.setPayload(payload); task.setStatus("PENDING");
        task.setAttempts(4); task.setScheduledAt(LocalDateTime.now().minusMinutes(1)); return task;
    }

    private static class Fixture {
        private final ResourceHubProperties properties = new ResourceHubProperties();
        private final IResourceHubTaskService taskService = mock(IResourceHubTaskService.class);
        private final ITmdbMetadataSyncService tmdbService = mock(ITmdbMetadataSyncService.class);
        private final IGyingMetadataSyncService gyingService = mock(IGyingMetadataSyncService.class);
        private final IResourceDiscoveryService discoveryService = mock(IResourceDiscoveryService.class);
        private final IQuarkTransferRunnerService transferService = mock(IQuarkTransferRunnerService.class);
        private final IResourceHubPublishService publishService = mock(IResourceHubPublishService.class);
        private final IResourceHubConfigService configService = mock(IResourceHubConfigService.class);
        private final ResourceHubWorkerServiceImpl service;

        private Fixture() {
            properties.setEnabled(true);
            properties.getWorker().setEnabled(true);
            properties.getTmdb().setApiKey("configured");
            properties.getTmdb().setAutoSyncEnabled(true);
            properties.getTmdb().setAutoSyncSources("TOP_RATED_MOVIE,TOP_RATED_TV");
            properties.getTmdb().setAutoSyncIntervalHours(12);
            when(transferService.submitPending(any(Integer.class))).thenReturn(new QuarkTransferRunResult());
            when(publishService.publishPending(any(Integer.class))).thenReturn(new ResourceHubPublishResult());
            service = new ResourceHubWorkerServiceImpl(
                    properties,
                    taskService,
                    tmdbService,
                    gyingService,
                    discoveryService,
                    transferService,
                    publishService,
                    configService);
        }
    }
}
