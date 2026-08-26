package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

class XunleiTransferRunnerServiceImplTest {

    @Test
    void isolatesEachTransferInItsOwnFolder() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getXunlei().setSavePath("/GYing Resource Hub");
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(42L);
        task.setMovieId("tmdb:movie/123");

        assertEquals(
                "/GYing Resource Hub",
                XunleiTransferRunnerServiceImpl.transferPath(properties, task));
    }

    @Test
    void skipsAlreadySucceededTask() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
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
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(3L);
        task.setStatus("WAITING_SHARE");
        task.setSavedPath("saved-file-id");
        task.setLastError("old authorization error");
        when(taskService.getById(3L)).thenReturn(task);
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
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(4L);
        task.setStatus("WAITING_SHARE");
        task.setSavedPath("saved-file-id");
        when(taskService.getById(4L)).thenReturn(task);
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
}
