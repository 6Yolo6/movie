package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gying.movie.client.XunleiClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IXunleiTransferTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XunleiTransferRunnerServiceImplTest {

    @Test
    void isolatesEachTransferInItsOwnFolder() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getXunlei().setSavePath("/影视剧资源分享(先转存后再查看)/GYing Resource Hub");
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(42L);
        task.setMovieId("tmdb:movie/123");

        assertEquals(
                "/影视剧资源分享(先转存后再查看)/GYing Resource Hub/tmdb_movie_123",
                XunleiTransferRunnerServiceImpl.transferPath(properties, task));
    }

    @Test
    void namesTransferFolderWithMovieTitleAndResourceId() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiTransferTask task = new XunleiTransferTask();
        task.setMovieId("tmdb_tv_93088");

        assertEquals(
                "/影视剧资源分享(先转存后再查看)/GYing Resource Hub/问心（tmdb_tv_93088）",
                XunleiTransferRunnerServiceImpl.transferPath(properties, task, "问心"));
    }

    @Test
    void sanitizesMovieTitleButKeepsResourceIdInTransferFolderName() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiTransferTask task = new XunleiTransferTask();
        task.setMovieId("tmdb:tv/93088");

        assertEquals(
                "/影视剧资源分享(先转存后再查看)/GYing Resource Hub/Example_Season（tmdb_tv_93088）",
                XunleiTransferRunnerServiceImpl.transferPath(properties, task, "Example/Season"));
    }

    @Test
    void automaticRunnerSkipsCappedFailuresWithoutStarvingPendingTasks() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask capped = new XunleiTransferTask();
        capped.setId(10L);
        capped.setStatus("FAILED");
        capped.setAttempts(100);
        XunleiTransferTask pending = new XunleiTransferTask();
        pending.setId(11L);
        pending.setMovieId("movie-11");
        pending.setStatus("PENDING");
        pending.setAttempts(0);
        pending.setOriginalUrl("https://pan.xunlei.com/s/pending");
        when(client.isConfigured()).thenReturn(true);
        when(taskService.list(isA(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(java.util.List.of(capped, pending));
        when(client.restore(eq(pending.getOriginalUrl()), any()))
                .thenThrow(new IllegalStateException("pending task reached client"));
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties, client, taskService, mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitPending(1);

        assertEquals(1, result.getFailed());
        assertEquals("pending task reached client", pending.getLastError());
        verify(client).restore(eq(pending.getOriginalUrl()), any());
    }

    @Test
    void sharesStableMovieFolderAfterRestoringFilteredVideos() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(12L);
        task.setMovieId("gying_tv_demo");
        task.setStatus("PENDING");
        task.setAttempts(0);
        task.setOriginalUrl("https://pan.xunlei.com/s/source");
        when(taskService.getById(12L)).thenReturn(task);
        when(client.restore(eq(task.getOriginalUrl()),
                eq("/影视剧资源分享(先转存后再查看)/GYing Resource Hub/gying_tv_demo（gying_tv_demo）")))
                .thenReturn(new XunleiClient.RestoreResult(
                        "restore-task", "{}", "stable-folder-id", null,
                        java.util.List.of("episode-01.mkv", "episode-02.mp4"), 1L));
        when(client.await("restore-task"))
                .thenReturn(new XunleiClient.RestoreStatus(true, "SUCCESS", "{}"));
        when(client.contentSummary("stable-folder-id"))
                .thenReturn(new XunleiClient.ContentSummary(1, 3, 2));
        when(client.awaitContent("stable-folder-id"))
                .thenReturn(new XunleiClient.ContentSummary(1, 3, 2));
        when(client.createShare("stable-folder-id"))
                .thenReturn("https://pan.xunlei.com/s/owned?pwd=code");
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties, client, taskService, mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitOne(12L);

        assertEquals(1, result.getSubmitted());
        assertEquals("stable-folder-id", task.getSavedPath());
        var order = inOrder(client);
        order.verify(client).awaitExpectedContent("stable-folder-id", java.util.List.of("episode-01.mkv", "episode-02.mp4"));
        order.verify(client).awaitContent("stable-folder-id");
        order.verify(client).ensureTransferImage("stable-folder-id");
        order.verify(client).createShare("stable-folder-id");
    }

    @Test
    void movesRestoredTraceIdsIntoStableFolderBeforeSharing() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(13L);
        task.setMovieId("legacy-movie");
        task.setStatus("PENDING");
        task.setOriginalUrl("https://pan.xunlei.com/s/source");
        when(taskService.getById(13L)).thenReturn(task);
        when(client.restore(eq(task.getOriginalUrl()), any()))
                .thenReturn(new XunleiClient.RestoreResult(
                        "restore-task", "restore-response", "stable-folder-id", "root-id",
                        java.util.List.of("movie.mkv"), 1L));
        when(client.await("restore-task"))
                .thenReturn(new XunleiClient.RestoreStatus(true, "SUCCESS", "{}"));
        when(client.extractRestoredFileIds("restore-response"))
                .thenReturn(java.util.List.of("restored-video-id"));
        when(client.directContentSummary("stable-folder-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 3, 3));
        when(client.awaitContent("stable-folder-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.createShare("stable-folder-id"))
                .thenReturn("https://pan.xunlei.com/s/owned?pwd=code");
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties, client, taskService, mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitOne(13L);

        assertEquals(1, result.getSubmitted());
        assertEquals("stable-folder-id", task.getSavedPath());
        verify(client).moveFiles(java.util.List.of("restored-video-id"), "stable-folder-id");
        verify(client).createShare("stable-folder-id");
    }

    @Test
    void retriesMoveAndShareWithoutRestoringAgain() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(14L);
        task.setMovieId("retry-movie");
        task.setStatus("PENDING");
        task.setOriginalUrl("https://pan.xunlei.com/s/source");
        when(taskService.getById(14L)).thenReturn(task);
        when(client.restore(eq(task.getOriginalUrl()), any()))
                .thenReturn(new XunleiClient.RestoreResult(
                        "restore-task", "restore-response", "stable-folder-id", "root-id",
                        java.util.List.of("episode.mp4"), 1L));
        when(client.await("restore-task"))
                .thenReturn(new XunleiClient.RestoreStatus(true, "SUCCESS", "{}"));
        when(client.extractRestoredFileIds("restore-response"))
                .thenReturn(java.util.List.of("restored-video-id"));
        when(client.restoredFileIdsPayload(java.util.List.of("restored-video-id")))
                .thenReturn("recovery-payload");
        when(client.extractRestoredFileIds("recovery-payload"))
                .thenReturn(java.util.List.of("restored-video-id"));
        when(client.contentSummary("stable-folder-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 0, 0));
        doThrow(new IllegalStateException("move failed"))
                .doNothing()
                .when(client).moveFiles(java.util.List.of("restored-video-id"), "stable-folder-id");
        when(client.awaitContent("stable-folder-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.createShare("stable-folder-id"))
                .thenReturn("https://pan.xunlei.com/s/owned?pwd=code");
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties, client, taskService, mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult first = service.submitOne(14L);
        QuarkTransferRunResult second = service.submitOne(14L);

        assertEquals(1, first.getFailed());
        assertEquals(1, second.getSubmitted());
        assertEquals("SUCCEEDED", task.getStatus());
        verify(client, times(1)).restore(eq(task.getOriginalUrl()), any());
        verify(client, times(2)).moveFiles(
                java.util.List.of("restored-video-id"), "stable-folder-id");
    }

    @Test
    void skipsAlreadySucceededTask() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(2L);
        task.setStatus("SUCCEEDED");
        task.setShareUrl("https://pan.xunlei.com/s/share?pwd=code");
        when(taskService.getById(2L)).thenReturn(task);
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties,
                client,
                taskService,
                mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitOne(2L);

        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getFailed());
        verify(taskService).update(any());
        verifyNoInteractions(client);
    }

    @Test
    void clearsPreviousErrorAfterWaitingShareRetrySucceeds() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(3L);
        task.setStatus("WAITING_SHARE");
        task.setAttempts(100);
        task.setSavedPath("saved-file-id");
        task.setLastError("old authorization error");
        when(taskService.getById(3L)).thenReturn(task);
        when(client.contentSummary("saved-file-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.awaitContent("saved-file-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.createShare("saved-file-id"))
                .thenReturn("https://pan.xunlei.com/s/share?pwd=code");
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties,
                client,
                taskService,
                mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitOne(3L);

        assertEquals(1, result.getSubmitted());
        assertEquals("SUCCEEDED", task.getStatus());
        verify(taskService).update(any());
    }

    @Test
    void propagatesPasswordFromFinalShareUrlToDiscoveryAndResourceLink() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IResourceLinkService linkService = mock(IResourceLinkService.class);
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(30L);
        task.setDiscoveryResultId(300L);
        task.setStatus("WAITING_SHARE");
        task.setSavedPath("saved-file-id");
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(300L);
        discovery.setCode("old-code");
        discovery.setResourceLinkId(301L);
        ResourceLink link = new ResourceLink();
        link.setId(301L);
        link.setCode("old-code");
        when(taskService.getById(30L)).thenReturn(task);
        when(discoveryService.getById(300L)).thenReturn(discovery);
        when(linkService.getById(301L)).thenReturn(link);
        when(client.contentSummary("saved-file-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.awaitContent("saved-file-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.createShare("saved-file-id"))
                .thenReturn("https://pan.xunlei.com/s/owned?pwd=new-code");

        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties, client, taskService, discoveryService, linkService);

        QuarkTransferRunResult result = service.submitOne(30L);

        assertEquals(1, result.getSubmitted());
        assertEquals("new-code", discovery.getCode());
        assertEquals("new-code", link.getCode());
        assertEquals("https://pan.xunlei.com/s/owned?pwd=new-code", link.getUrl());
    }

    @Test
    void reportsWaitingShareAsFailureWhenApiReturnsNoUrl() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(4L);
        task.setStatus("WAITING_SHARE");
        task.setSavedPath("saved-file-id");
        when(taskService.getById(4L)).thenReturn(task);
        when(client.contentSummary("saved-file-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.awaitContent("saved-file-id"))
                .thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.createShare("saved-file-id")).thenReturn(null);
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties,
                client,
                taskService,
                mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitOne(4L);

        assertEquals(1, result.getFailed());
        assertEquals(0, result.getSubmitted());
        assertEquals("WAITING_SHARE", task.getStatus());
        assertEquals("Xunlei transfer succeeded but share API did not return a URL", task.getLastError());
        verify(taskService).updateById(task);
    }

    @Test
    void restoresHistoricalTaskWithExtractionCodeFromDiscovery() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(5L);
        task.setDiscoveryResultId(50L);
        task.setStatus("PENDING");
        task.setAttempts(0);
        task.setOriginalUrl("https://pan.xunlei.com/s/share-id#noise");
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(50L);
        discovery.setCode("abcd");
        when(taskService.getById(5L)).thenReturn(task);
        when(discoveryService.getById(50L)).thenReturn(discovery);
        when(client.restore(eq("https://pan.xunlei.com/s/share-id?pwd=abcd"), any()))
                .thenThrow(new IllegalStateException("stop after URL verification"));
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties,
                client,
                taskService,
                discoveryService,
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitOne(5L);

        assertEquals(1, result.getFailed());
        assertEquals("https://pan.xunlei.com/s/share-id?pwd=abcd", task.getOriginalUrl());
        verify(client).restore(eq("https://pan.xunlei.com/s/share-id?pwd=abcd"), any());
    }

    @Test
    void explicitRetryRunsEvenAfterAutomaticAttemptLimit() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(6L);
        task.setStatus("FAILED");
        task.setAttempts(3);
        task.setOriginalUrl("https://pan.xunlei.com/s/share-id?pwd=abcd");
        when(taskService.getById(6L)).thenReturn(task);
        when(client.restore(eq(task.getOriginalUrl()), any()))
                .thenThrow(new IllegalStateException("manual retry reached client"));
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties,
                client,
                taskService,
                mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitOne(6L);

        assertEquals(1, result.getFailed());
        assertEquals(4, task.getAttempts());
        assertEquals("manual retry reached client", task.getLastError());
        verify(client).restore(eq(task.getOriginalUrl()), any());
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesImageCopyBeforeSharingWithoutRestoringVideosAgain(boolean temporary) {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        when(taskService.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        when(client.awaitRestoredFiles(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new XunleiClient.RestoredSelection(java.util.List.of("verified-video"),
                        new XunleiClient.ContentSummary(0, 1, 1)));
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(90L);
        task.setMovieId("movie");
        task.setStatus("PENDING");
        task.setOriginalUrl("https://pan.xunlei.com/s/source");
        String path = temporary ? "/QQ临时转存/movie" : "/影视剧资源分享(先转存后再查看)/GYing Resource Hub/movie（movie）";
        if (temporary) task.setRequestPayload(com.gying.movie.utils.QqTransferMarker.payload(path));
        when(taskService.getById(90L)).thenReturn(task);
        when(client.restore(task.getOriginalUrl(), path)).thenReturn(new XunleiClient.RestoreResult(
                "restore-task", "{}", "destination", null, java.util.List.of("movie.mp4"), 1L));
        when(client.await("restore-task")).thenReturn(new XunleiClient.RestoreStatus(true, "SUCCESS", "{}"));
        when(client.contentSummary("destination")).thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        when(client.awaitContent("destination")).thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        doThrow(new IllegalStateException("image copy failed")).doNothing().when(client).ensureTransferImage("destination");
        when(client.createShare("destination")).thenReturn("https://pan.xunlei.com/s/owned");
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(properties, client,
                taskService, mock(IResourceDiscoveryResultService.class), mock(IResourceLinkService.class));

        QuarkTransferRunResult first = service.submitOne(90L);
        assertEquals(1, first.getFailed());
        assertEquals("WAITING_SHARE", task.getStatus());
        assertEquals("image copy failed", task.getLastError());
        verify(client, never()).createShare(any(String.class));

        QuarkTransferRunResult retry = service.submitOne(90L);
        assertEquals(1, retry.getSubmitted());
        assertEquals("SUCCEEDED", task.getStatus());
        verify(client).restore(task.getOriginalUrl(), path);
        var order = inOrder(client);
        order.verify(client).ensureTransferImage("destination");
        order.verify(client).awaitContent("destination");
        order.verify(client).ensureTransferImage("destination");
        order.verify(client).createShare("destination");
    }
    @Test
    void queueFiltersExhaustedFailuresInSqlBeforeLimitAndRotatesShareFailures() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getXunlei().setShareEnabled(true);
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService tasks = mock(IXunleiTransferTaskService.class);
        when(client.isConfigured()).thenReturn(true);
        when(tasks.list(isA(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            org.junit.jupiter.api.Assertions.assertTrue(sql.contains("attempts <"));
            org.junit.jupiter.api.Assertions.assertTrue(sql.contains("attempts IS NULL"));
            org.junit.jupiter.api.Assertions.assertTrue(sql.contains("ORDER BY updated_at ASC,id ASC"));
            org.junit.jupiter.api.Assertions.assertTrue(sql.endsWith("LIMIT 5"));
            org.junit.jupiter.api.Assertions.assertTrue(query.getParamNameValuePairs().containsValue(3));
            return java.util.List.of();
        });
        new XunleiTransferRunnerServiceImpl(properties, client, tasks,
                mock(IResourceDiscoveryResultService.class), mock(IResourceLinkService.class)).submitPending(5);
        verify(tasks).list(isA(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test void nestedPlacementRetriesExactTargetAfterRestartDespiteExistingVideos() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService tasks = mock(IXunleiTransferTaskService.class);
        XunleiTransferTask task = new XunleiTransferTask(); task.setId(90L); task.setMovieId("fixture");
        task.setStatus("PENDING"); task.setOriginalUrl("https://pan.xunlei.com/s/fixture");
        when(tasks.getById(90L)).thenReturn(task); when(tasks.updateById(any(XunleiTransferTask.class))).thenReturn(true);
        var placement = new XunleiClient.RestorePlacement("job", "season", "root", "response", "root",
                java.util.List.of("episode.mp4"), 1L, false, java.util.List.of("Season 1"));
        when(client.restore(any(), any())).thenReturn(new XunleiClient.RestoreResult("job", "response", "movie", "root",
                java.util.List.of("episode.mp4"), 1L, false, java.util.List.of(placement)));
        when(client.await("job")).thenReturn(new XunleiClient.RestoreStatus(true, "SUCCESS", "{}"));
        when(client.extractRestoredFileIds("response")).thenReturn(java.util.List.of("new-episode"));
        when(client.directContentSummary("season")).thenReturn(new XunleiClient.ContentSummary(0, 1, 1));
        doThrow(new IllegalStateException("move pending")).doNothing().when(client).moveFiles(java.util.List.of("new-episode"), "season");
        when(client.createShare("movie")).thenReturn("https://pan.xunlei.com/s/fixture-own");
        var discovery = mock(IResourceDiscoveryResultService.class); var links = mock(IResourceLinkService.class);
        var first = new XunleiTransferRunnerServiceImpl(properties, client, tasks, discovery, links);
        assertEquals(1, first.submitOne(90L).getFailed());
        assertEquals("WAITING_SHARE", task.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(task.getResponsePayload().contains("season"));
        var restarted = new XunleiTransferRunnerServiceImpl(properties, client, tasks, discovery, links);
        assertEquals(1, restarted.submitOne(90L).getSubmitted());
        verify(client, times(1)).restore(any(), any());
        verify(client, times(2)).moveFiles(java.util.List.of("new-episode"), "season");
        verify(client, never()).moveFiles(any(), eq("movie"));
        verify(client).awaitExpectedContent("season", java.util.List.of("episode.mp4"));
    }

    @Test void legacyRecoveryMovesExactIdsEvenWhenTargetAlreadyHasVideos() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService tasks = mock(IXunleiTransferTaskService.class);
        XunleiTransferTask task = new XunleiTransferTask(); task.setId(91L); task.setStatus("WAITING_SHARE");
        task.setSavedPath("movie"); task.setResponsePayload("legacy");
        when(tasks.getById(91L)).thenReturn(task);
        when(client.extractRestoreRecoveries("legacy")).thenReturn(java.util.List.of(
                new XunleiClient.RestoreRecovery("season", java.util.List.of("new-episode"))));
        when(client.directContentSummary("season")).thenReturn(new XunleiClient.ContentSummary(0, 2, 2));
        when(client.createShare("movie")).thenReturn("https://pan.xunlei.com/s/fixture-own");
        var service = new XunleiTransferRunnerServiceImpl(properties, client, tasks,
                mock(IResourceDiscoveryResultService.class), mock(IResourceLinkService.class));
        assertEquals(1, service.submitOne(91L).getSubmitted());
        verify(client).moveFiles(java.util.List.of("new-episode"), "season"); verify(client, never()).restore(any(), any());
    }
}
