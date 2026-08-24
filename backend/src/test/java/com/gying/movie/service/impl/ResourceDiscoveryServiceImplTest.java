package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.PanSouClient;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.DiscoveredResource;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IXunleiTransferTaskService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResourceDiscoveryServiceImplTest {

    private PanSouClient panSouClient;
    private GyingSourceWorkflowService gyingWorkflow;
    private IMovieMetadataService movieService;
    private IResourceHubTaskService taskService;
    private IResourceDiscoveryResultService discoveryService;
    private IQuarkTransferTaskService quarkTransferTaskService;
    private IResourceLinkService resourceLinkService;
    private IXunleiTransferTaskService xunleiTransferTaskService;
    private ResourceDiscoveryServiceImpl service;
    private MovieMetadata movie;
    private ResourceHubTask task;

    @BeforeEach
    void setUp() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        properties.getGying().setDiscoveryEnabled(true);
        panSouClient = mock(PanSouClient.class);
        gyingWorkflow = mock(GyingSourceWorkflowService.class);
        movieService = mock(IMovieMetadataService.class);
        taskService = mock(IResourceHubTaskService.class);
        discoveryService = mock(IResourceDiscoveryResultService.class);
        quarkTransferTaskService = mock(IQuarkTransferTaskService.class);
        resourceLinkService = mock(IResourceLinkService.class);
        xunleiTransferTaskService = mock(IXunleiTransferTaskService.class);
        service = new ResourceDiscoveryServiceImpl(
                properties,
                panSouClient,
                gyingWorkflow,
                movieService,
                taskService,
                discoveryService,
                quarkTransferTaskService,
                resourceLinkService,
                xunleiTransferTaskService,
                new ObjectMapper());

        movie = new MovieMetadata();
        movie.setId("tmdb_movie_278");
        movie.setTitleCn("肖申克的救赎");
        movie.setCategory("mv");
        movie.setStatus("ACTIVE");
        task = new ResourceHubTask();
        task.setId(1L);
        task.setTaskType("RESOURCE_DISCOVERY");
        task.setMovieId(movie.getId());
        task.setSource("AUTO");
        task.setKeyword("肖申克的救赎 1994");
        when(taskService.getById(1L)).thenReturn(task);
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(panSouClient.checkLink(any(DiscoveredResource.class)))
                .thenReturn(new LinkCheckResult("https://pan.quark.cn/s/test", false, false, "unchecked"));
    }

    @Test
    void usesGyingWithoutCallingPanSouWhenStrictResourcesExist() {
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of(resource(
                "肖申克的救赎 1994 4K", "https://pan.quark.cn/s/gying", "GYING")));

        ResourceDiscoveryRunResult result = service.runTask(1L);

        assertEquals(1, result.getDiscovered());
        verify(panSouClient, never()).searchQuark(anyString(), anyInt());
        ArgumentCaptor<ResourceDiscoveryResult> saved = ArgumentCaptor.forClass(ResourceDiscoveryResult.class);
        verify(discoveryService).save(saved.capture());
        assertEquals("GYING", saved.getValue().getSource());
    }

    @Test
    void supplementsGyingQuarkResultsWithXunleiCandidates() {
        DiscoveredResource gying = resource(
                "鑲栫敵鍏嬬殑鏁戣祹 4K", "https://pan.quark.cn/s/gying", "GYING");
        DiscoveredResource xunlei = resource(
                "鑲栫敵鍏嬬殑鏁戣祹 4K 杩呴浄", "https://pan.xunlei.com/s/xunlei", "PANSOU");
        xunlei.setProvider("XUNLEI");
        movie.setTitleCn("Movie");
        movie.setTitleEn("Movie");
        task.setKeyword("Movie");
        gying.setTitle("Movie 4K");
        xunlei.setTitle("Movie 4K XUNLEI");
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of(gying));
        when(panSouClient.searchClouds(anyString(), any(), anyInt())).thenReturn(List.of(xunlei));

        ResourceDiscoveryRunResult result = service.runTask(1L);

        assertEquals(2, result.getDiscovered());
        verify(panSouClient).searchClouds(anyString(), any(), anyInt());
        verify(xunleiTransferTaskService).save(any(XunleiTransferTask.class));
    }

    @Test
    void supplementsGyingXunleiResultsWithQuarkCandidates() {
        DiscoveredResource gying = resource(
                "Movie 4K 迅雷", "https://pan.xunlei.com/s/gying", "GYING");
        gying.setProvider("XUNLEI");
        DiscoveredResource quark = resource(
                "Movie 4K 夸克", "https://pan.quark.cn/s/quark", "PANSOU");
        quark.setProvider("QUARK");
        movie.setTitleCn("Movie");
        movie.setTitleEn("Movie");
        task.setKeyword("Movie");
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of(gying));
        when(panSouClient.searchQuark(anyString(), anyInt())).thenReturn(List.of(quark));

        ResourceDiscoveryRunResult result = service.runTask(1L);

        assertEquals(2, result.getDiscovered());
        verify(panSouClient).searchQuark(anyString(), anyInt());
        verify(quarkTransferTaskService).save(any(QuarkTransferTask.class));
    }

    @Test
    void fallsBackToPanSouAndCountsUnrelatedResultsWithoutPersistingThem() {
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of());
        when(panSouClient.searchQuark(anyString(), anyInt())).thenReturn(List.of(resource(
                "完美世界 4K 合集 最新", "https://pan.quark.cn/s/noise", "PANSOU")));

        ResourceDiscoveryRunResult result = service.runTask(1L);

        assertEquals(0, result.getDiscovered());
        assertEquals(1, result.getRejected());
        verify(discoveryService, never()).save(any());
    }

    @Test
    void keepsQuarkAndXunleiCandidatesWhenQuarkHasMoreResults() {
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of());
        DiscoveredResource quark = resource(
                "肖申克的救赎 4K", "https://pan.quark.cn/s/quark", "PANSOU");
        DiscoveredResource xunlei = resource(
                "肖申克的救赎 4K 迅雷", "https://pan.xunlei.com/s/xunlei", "PANSOU");
        xunlei.setProvider("XUNLEI");
        when(panSouClient.searchQuark(anyString(), anyInt())).thenReturn(List.of(quark));
        when(panSouClient.searchClouds(anyString(), any(), anyInt())).thenReturn(List.of(xunlei));

        ResourceDiscoveryRunResult result = service.runTask(1L);

        assertEquals(2, result.getDiscovered());
        verify(discoveryService, org.mockito.Mockito.times(2)).save(any(ResourceDiscoveryResult.class));
        verify(panSouClient).checkLink(quark);
        verify(panSouClient).checkLink(xunlei);
    }

    @Test
    void letsXunleiTransferApiValidateCandidateWhenPanSouReportsBad() {
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of());
        DiscoveredResource xunlei = resource(
                "肖申克的救赎 4K 迅雷", "https://pan.xunlei.com/s/xunlei", "PANSOU");
        xunlei.setProvider("XUNLEI");
        when(panSouClient.searchQuark(anyString(), anyInt())).thenReturn(List.of());
        when(panSouClient.searchClouds(anyString(), any(), anyInt())).thenReturn(List.of(xunlei));
        when(panSouClient.checkLink(xunlei))
                .thenReturn(new LinkCheckResult(xunlei.getUrl(), true, false, "bad"));

        ResourceDiscoveryRunResult result = service.runTask(1L);

        assertEquals(1, result.getDiscovered());
        ArgumentCaptor<ResourceDiscoveryResult> discovery =
                ArgumentCaptor.forClass(ResourceDiscoveryResult.class);
        verify(discoveryService).save(discovery.capture());
        assertEquals("DISCOVERED", discovery.getValue().getStatus());
        verify(xunleiTransferTaskService).save(any(XunleiTransferTask.class));
    }

    @Test
    void deferredDiscoverySavesCandidatesWithoutCreatingTransferTasks() {
        task.setPayload("""
                {
                  "movieId": "tmdb_movie_278",
                  "keyword": "肖申克的救赎 1994",
                  "source": "AUTO",
                  "maxResults": 10,
                  "deferTransfer": true
                }
                """);
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of(resource(
                "肖申克的救赎 4K REMUX",
                "https://pan.quark.cn/s/deferred",
                "GYING")));

        ResourceDiscoveryRunResult result = service.runTask(1L);

        assertEquals(1, result.getDiscovered());
        assertEquals(0, result.getTransferTasksCreated());
        verify(discoveryService).save(any(ResourceDiscoveryResult.class));
        verify(quarkTransferTaskService, never()).save(any());
        verify(xunleiTransferTaskService, never()).save(any());
    }

    @Test
    void ensureTransferTaskCreatesOnlySelectedQuarkTask() {
        ResourceDiscoveryResult discovery = discovery(
                101L,
                "QUARK",
                "https://pan.quark.cn/s/selected");
        when(discoveryService.getById(discovery.getId())).thenReturn(discovery);
        when(quarkTransferTaskService.save(any(QuarkTransferTask.class))).thenReturn(true);

        assertTrue(service.ensureTransferTask(discovery.getId()));

        ArgumentCaptor<QuarkTransferTask> saved = ArgumentCaptor.forClass(QuarkTransferTask.class);
        verify(quarkTransferTaskService).save(saved.capture());
        assertEquals(discovery.getId(), saved.getValue().getDiscoveryResultId());
        assertEquals(discovery.getOriginalUrl(), saved.getValue().getOriginalUrl());
        assertEquals("PENDING", saved.getValue().getStatus());
        verify(xunleiTransferTaskService, never()).save(any());
    }

    @Test
    void ensureTransferTaskCreatesOnlySelectedXunleiTask() {
        ResourceDiscoveryResult discovery = discovery(
                102L,
                "XUNLEI",
                "https://pan.xunlei.com/s/selected");
        when(discoveryService.getById(discovery.getId())).thenReturn(discovery);
        when(xunleiTransferTaskService.save(any(XunleiTransferTask.class))).thenReturn(true);

        assertTrue(service.ensureTransferTask(discovery.getId()));

        ArgumentCaptor<XunleiTransferTask> saved = ArgumentCaptor.forClass(XunleiTransferTask.class);
        verify(xunleiTransferTaskService).save(saved.capture());
        assertEquals(discovery.getId(), saved.getValue().getDiscoveryResultId());
        assertEquals(discovery.getOriginalUrl(), saved.getValue().getOriginalUrl());
        assertEquals("PENDING", saved.getValue().getStatus());
        verify(quarkTransferTaskService, never()).save(any());
    }

    @Test
    void ensureTransferTaskReusesExistingTaskWithoutSavingDuplicate() {
        ResourceDiscoveryResult discovery = discovery(
                103L,
                "QUARK",
                "https://pan.quark.cn/s/existing");
        QuarkTransferTask existing = new QuarkTransferTask();
        existing.setId(900L);
        when(discoveryService.getById(discovery.getId())).thenReturn(discovery);
        when(quarkTransferTaskService.getOne(any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(existing);

        assertTrue(service.ensureTransferTask(discovery.getId()));

        verify(quarkTransferTaskService, never()).save(any());
        verify(xunleiTransferTaskService, never()).save(any());
    }

    private ResourceDiscoveryResult discovery(long id, String provider, String url) {
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(id);
        discovery.setMovieId(movie.getId());
        discovery.setProvider(provider);
        discovery.setOriginalUrl(url);
        discovery.setOriginalUrlHash("hash-" + id);
        return discovery;
    }

    private DiscoveredResource resource(String title, String url, String source) {
        DiscoveredResource resource = new DiscoveredResource();
        resource.setTitle(title);
        resource.setUrl(url);
        resource.setProvider("QUARK");
        resource.setSource(source);
        return resource;
    }
}
