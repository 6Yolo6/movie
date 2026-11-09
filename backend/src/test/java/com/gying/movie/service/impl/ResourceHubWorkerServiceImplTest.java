package com.gying.movie.service.impl;

import static com.gying.movie.service.impl.BaiduScopeTestAssertions.*;
import static org.junit.jupiter.api.Assertions.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        when(fixture.tmdbService.enqueueAutomatic("TOP_RATED_TV")).thenReturn(new ResourceHubTask());

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


    @Test
    void automaticRotationSkipsCooledSourcesAndStopsAtTheFirstCreatedTask() {
        Fixture fixture = new Fixture();
        fixture.properties.getTmdb().setAutoSyncSources("TOP_RATED_MOVIE,POPULAR_MOVIE,TOP_RATED_TV,POPULAR_TV");
        ResourceHubTask latest = new ResourceHubTask();
        latest.setKeyword("POPULAR_MOVIE");
        latest.setCreatedAt(LocalDateTime.now().minusHours(13));
        when(fixture.taskService.getOne(any(Wrapper.class), eq(false))).thenReturn(latest);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());
        when(fixture.tmdbService.enqueueAutomatic("TOP_RATED_TV")).thenReturn(null);
        when(fixture.tmdbService.enqueueAutomatic("POPULAR_TV")).thenReturn(null);
        when(fixture.tmdbService.enqueueAutomatic("TOP_RATED_MOVIE")).thenReturn(new ResourceHubTask());

        ResourceHubWorkerResult result = fixture.service.runOnce();

        InOrder order = Mockito.inOrder(fixture.tmdbService);
        order.verify(fixture.tmdbService).enqueueAutomatic("TOP_RATED_TV");
        order.verify(fixture.tmdbService).enqueueAutomatic("POPULAR_TV");
        order.verify(fixture.tmdbService).enqueueAutomatic("TOP_RATED_MOVIE");
        order.verifyNoMoreInteractions();
        assertEquals(1, result.getMetadataSyncTasksCreated());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void allCooledSourcesAreCheckedOnceWithoutClaimingThatATaskWasCreated() {
        Fixture fixture = new Fixture();
        fixture.properties.getTmdb().setAutoSyncSources("TOP_RATED_MOVIE,TOP_RATED_TV,POPULAR_MOVIE");
        ResourceHubTask latest = new ResourceHubTask();
        latest.setKeyword("TOP_RATED_TV");
        latest.setCreatedAt(LocalDateTime.now().minusHours(13));
        when(fixture.taskService.getOne(any(Wrapper.class), eq(false))).thenReturn(latest);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());
        when(fixture.tmdbService.enqueueAutomatic(any())).thenReturn(null);

        ResourceHubWorkerResult result = fixture.service.runOnce();

        InOrder order = Mockito.inOrder(fixture.tmdbService);
        order.verify(fixture.tmdbService).enqueueAutomatic("POPULAR_MOVIE");
        order.verify(fixture.tmdbService).enqueueAutomatic("TOP_RATED_MOVIE");
        order.verify(fixture.tmdbService).enqueueAutomatic("TOP_RATED_TV");
        order.verifyNoMoreInteractions();
        assertEquals(0, result.getMetadataSyncTasksCreated());
        assertTrue(result.getErrors().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theGlobalTmdbIntervalBlocksAllSourcesEvenForAForcedRun(boolean force) {
        Fixture fixture = new Fixture();
        // Force can run a disabled worker, but it must retain the source-wide enqueue interval.
        fixture.properties.getWorker().setEnabled(!force);
        ResourceHubTask latest = new ResourceHubTask();
        latest.setKeyword("TOP_RATED_MOVIE");
        latest.setCreatedAt(LocalDateTime.now().minusHours(1));
        when(fixture.taskService.getOne(any(Wrapper.class), eq(false))).thenReturn(latest);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());

        ResourceHubWorkerResult result = fixture.service.runOnce(force);

        Mockito.verifyNoInteractions(fixture.tmdbService);
        verify(fixture.taskService).list(any(Wrapper.class));
        assertFalse(result.isSkipped());
        assertEquals(0, result.getMetadataSyncTasksCreated());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void anInvalidConfiguredIntervalStillRetainsTheMinimumOneHourThrottle(int configuredHours) {
        Fixture fixture = new Fixture();
        fixture.properties.getTmdb().setAutoSyncIntervalHours(configuredHours);
        ResourceHubTask latest = new ResourceHubTask();
        latest.setKeyword("TOP_RATED_MOVIE");
        latest.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        when(fixture.taskService.getOne(any(Wrapper.class), eq(false))).thenReturn(latest);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());

        ResourceHubWorkerResult result = fixture.service.runOnce(true);

        Mockito.verifyNoInteractions(fixture.tmdbService);
        assertEquals(0, result.getMetadataSyncTasksCreated());
    }

    @Test
    void aPlannerFailureIsReportedAndCannotBeMistakenForSourceCooldown() {
        Fixture fixture = new Fixture();
        ResourceHubTask latest = new ResourceHubTask();
        latest.setKeyword("TOP_RATED_MOVIE");
        latest.setCreatedAt(LocalDateTime.now().minusHours(13));
        when(fixture.taskService.getOne(any(Wrapper.class), eq(false))).thenReturn(latest);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());
        when(fixture.tmdbService.enqueueAutomatic("TOP_RATED_TV"))
                .thenThrow(new IllegalStateException("Invalid metadata crawl checkpoint"));

        ResourceHubWorkerResult result = fixture.service.runOnce();

        verify(fixture.tmdbService).enqueueAutomatic("TOP_RATED_TV");
        Mockito.verifyNoMoreInteractions(fixture.tmdbService);
        assertEquals(0, result.getMetadataSyncTasksCreated());
        assertEquals(List.of("tmdb auto sync TOP_RATED_TV: Invalid metadata crawl checkpoint"), result.getErrors());
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
    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "ACTION_REQUIRED", "REVIEW_REQUIRED", "CANCELED"})
    void retryRoutesBaiduSafelyAndNeverResetsReviewStatesOrCallsQuark(String state) {
        var properties = new ResourceHubProperties(); properties.setEnabled(true); properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true);
        properties.getBaidu().setTorrentEnabled(true); properties.getWorker().setDiscoveredRetryDelayMs(0);
        var discoveries = mock(com.gying.movie.service.IResourceDiscoveryResultService.class);
        var baiduTasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        var baiduRunner = mock(com.gying.movie.service.IBaiduTransferRunnerService.class);
        var quark = mock(IQuarkTransferRunnerService.class); var publish = mock(IResourceHubPublishService.class);
        var service = new ResourceHubWorkerServiceImpl(properties, mock(IResourceHubTaskService.class), mock(ITmdbMetadataSyncService.class),
                mock(IGyingMetadataSyncService.class), mock(IResourceDiscoveryService.class), quark, publish, mock(IResourceHubConfigService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "discoveryResultService", discoveries);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", baiduTasks);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferRunnerService", baiduRunner);
        var candidate = new com.gying.movie.entity.ResourceDiscoveryResult(); candidate.setId(1L); candidate.setProvider("BAIDU"); candidate.setMovieId("movie1");
        candidate.setOriginalUrl("https://pan.baidu.com/s/1fixture");
        when(discoveries.list(any(Wrapper.class))).thenReturn(List.of(candidate));
        var task = new com.gying.movie.entity.BaiduTransferTask(); task.setId(2L); task.setStatus(state); task.setAttempts(2);
        when(baiduTasks.getOne(any(Wrapper.class), eq(false))).thenReturn(task);
        var result = service.retryDiscoveredTransfers(true);
        assertEquals(1, result.get("skipped")); assertEquals(state, task.getStatus()); assertEquals(2, task.getAttempts());
        Mockito.verifyNoInteractions(baiduRunner, quark, publish); verify(baiduTasks, never()).updateById(any());
    }
    @Test void baiduOwnShareVerificationFailureNeverPublishesTheOldSucceededUrl() {
        var properties = new ResourceHubProperties(); properties.setEnabled(true); properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true);
        properties.getBaidu().setTorrentEnabled(true); properties.getWorker().setDiscoveredRetryDelayMs(0);
        var discoveries = mock(com.gying.movie.service.IResourceDiscoveryResultService.class);
        var baiduTasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        var baiduRunner = mock(com.gying.movie.service.IBaiduTransferRunnerService.class);
        var quark = mock(IQuarkTransferRunnerService.class); var publish = mock(IResourceHubPublishService.class);
        var service = new ResourceHubWorkerServiceImpl(properties, mock(IResourceHubTaskService.class), mock(ITmdbMetadataSyncService.class),
                mock(IGyingMetadataSyncService.class), mock(IResourceDiscoveryService.class), quark, publish, mock(IResourceHubConfigService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "discoveryResultService", discoveries);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", baiduTasks);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferRunnerService", baiduRunner);
        var candidate = new com.gying.movie.entity.ResourceDiscoveryResult(); candidate.setId(1L); candidate.setProvider("BAIDU"); candidate.setMovieId("movie1");
        candidate.setOriginalUrl("https://pan.baidu.com/s/1fixture"); when(discoveries.list(any(Wrapper.class))).thenReturn(List.of(candidate));
        var task = new com.gying.movie.entity.BaiduTransferTask(); task.setId(2L); task.setStatus("SUCCEEDED"); task.setShareUrl("https://pan.baidu.com/s/1old");
        when(baiduTasks.getOne(any(Wrapper.class), eq(false))).thenReturn(task); when(baiduTasks.getById(2L)).thenReturn(task);
        var checked = new QuarkTransferRunResult(); checked.setFailed(1); when(baiduRunner.submitOne(2L)).thenReturn(checked);
        assertEquals(1, service.retryDiscoveredTransfers(true).get("failed")); verify(baiduRunner).submitOne(2L); Mockito.verifyNoInteractions(quark, publish);
    }


    @Test void discoveredRetryExcludesTemporaryClonesBeforeTransferAndPublication() {
        var f = new BaiduRetryFixture(); var clone = f.candidate(); clone.setSource("QQ_BOT");
        clone.setSourceRef("BAIDU_TEMP:" + TEMP_SCOPE);
        when(f.discoveries.list(any(Wrapper.class))).thenAnswer(call -> { permanentDiscoveryQuery(call.getArgument(0)); return List.of(clone); });
        f.service.retryDiscoveredTransfers(true);
        Mockito.verifyNoInteractions(f.baiduTasks, f.baiduRunner, f.publish);
        assertEquals("BAIDU_TEMP:" + TEMP_SCOPE, clone.getSourceRef());
    }

    @ParameterizedTest @ValueSource(strings = {TEMP_SCOPE, "UNKNOWN_SCOPE"})
    void discoveredRetryCannotConsumeTemporaryTasksThroughEitherLookup(String scope) {
        var f = new BaiduRetryFixture(); var task = new com.gying.movie.entity.BaiduTransferTask();
        task.setId(88L); task.setScopeKey(scope); task.setStatus("SUCCEEDED"); task.setShareUrl("https://pan.baidu.com/s/1temp");
        when(f.discoveries.list(any(Wrapper.class))).thenReturn(List.of(f.candidate()));
        when(f.baiduTasks.getOne(any(), eq(false))).thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return task; });
        assertEquals(1, f.service.retryDiscoveredTransfers(true).get("skipped"));
        verify(f.baiduTasks, Mockito.times(2)).getOne(any(), eq(false));
        Mockito.verifyNoInteractions(f.baiduRunner, f.publish); verify(f.baiduTasks, never()).updateById(any());
    }

    @Test void permanentFallbackStillTransfersAndPublishesItsOwnShare() {
        var f = new BaiduRetryFixture(); var task = new com.gying.movie.entity.BaiduTransferTask(); task.setId(88L);
        task.setStatus("SUCCEEDED"); task.setShareUrl("https://pan.baidu.com/s/1owned");
        when(f.discoveries.list(any(Wrapper.class))).thenAnswer(call -> { permanentDiscoveryQuery(call.getArgument(0)); return List.of(f.candidate()); });
        when(f.baiduTasks.getOne(any(), eq(false))).thenAnswer(call -> {
            Wrapper<?> query = call.getArgument(0); permanentTaskQuery(query);
            return query.getSqlSegment().contains("discovery_result_id") ? null : task;
        });
        when(f.baiduRunner.submitOne(88L)).thenReturn(new QuarkTransferRunResult());
        var published = new ResourceHubPublishResult(); published.setPublished(1);
        when(f.publish.publishDiscovery(99L)).thenReturn(published);
        assertEquals(1, f.service.retryDiscoveredTransfers(true).get("succeeded"));
        verify(f.baiduRunner).submitOne(88L); verify(f.publish).publishDiscovery(99L);
    }

    private static class BaiduRetryFixture {
        final ResourceHubProperties properties = new ResourceHubProperties();
        final com.gying.movie.service.IResourceDiscoveryResultService discoveries = mock(com.gying.movie.service.IResourceDiscoveryResultService.class);
        final com.gying.movie.service.IBaiduTransferTaskService baiduTasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        final com.gying.movie.service.IBaiduTransferRunnerService baiduRunner = mock(com.gying.movie.service.IBaiduTransferRunnerService.class);
        final IResourceHubPublishService publish = mock(IResourceHubPublishService.class);
        final ResourceHubWorkerServiceImpl service;
        BaiduRetryFixture() {
            properties.setEnabled(true); properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true);
            properties.getBaidu().setVideoEnabled(true); properties.getWorker().setDiscoveredRetryDelayMs(0);
            service = new ResourceHubWorkerServiceImpl(properties, mock(IResourceHubTaskService.class), mock(ITmdbMetadataSyncService.class),
                    mock(IGyingMetadataSyncService.class), mock(IResourceDiscoveryService.class), mock(IQuarkTransferRunnerService.class), publish,
                    mock(IResourceHubConfigService.class));
            org.springframework.test.util.ReflectionTestUtils.setField(service, "discoveryResultService", discoveries);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", baiduTasks);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferRunnerService", baiduRunner);
        }
        com.gying.movie.entity.ResourceDiscoveryResult candidate() {
            var row = new com.gying.movie.entity.ResourceDiscoveryResult(); row.setId(99L); row.setMovieId("movie1"); row.setProvider("BAIDU");
            row.setOriginalUrl("https://pan.baidu.com/s/1source"); return row;
        }
    }

}
