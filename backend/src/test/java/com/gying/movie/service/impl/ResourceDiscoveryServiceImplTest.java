package com.gying.movie.service.impl;

import static com.gying.movie.service.impl.BaiduScopeTestAssertions.*;
import static org.junit.jupiter.api.Assertions.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
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

    private ResourceHubProperties properties;
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
        properties = new ResourceHubProperties();
        properties.getXunlei().setEnabled(true);
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

    @Test
    void reconcilesDeferredDiscoveredCandidateWithoutCreatingProviderDuplicates() {
        ResourceDiscoveryResult first = discovery(201L, "XUNLEI", "https://pan.xunlei.com/s/first");
        ResourceDiscoveryResult second = discovery(202L, "XUNLEI", "https://pan.xunlei.com/s/second");
        when(discoveryService.list(isA(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(first, second));
        when(discoveryService.getById(201L)).thenReturn(first);
        when(xunleiTransferTaskService.save(any(XunleiTransferTask.class))).thenReturn(true);

        assertEquals(1, service.reconcileDiscoveredTransferTasks(10));
        verify(xunleiTransferTaskService).save(any(XunleiTransferTask.class));
    }

    @Test
    void reconciliationSkipsCandidateWhenMovieProviderAlreadyHasActiveTask() {
        ResourceDiscoveryResult candidate = discovery(203L, "QUARK", "https://pan.quark.cn/s/existing-task");
        when(discoveryService.list(isA(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(candidate));
        when(quarkTransferTaskService.count(any())).thenReturn(1L);

        assertEquals(0, service.reconcileDiscoveredTransferTasks(10));
        verify(quarkTransferTaskService, never()).save(any(QuarkTransferTask.class));
    }

    @Test
    void keepsGyingResultsWhenPanSouSupplementFails() {
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of(resource(
                "肖申克的救赎 1994 4K", "https://pan.quark.cn/s/gying", "GYING")));
        when(panSouClient.searchClouds(anyString(), any(), anyInt())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(1, service.runTask(1L).getDiscovered());
        verify(panSouClient, never()).searchQuark(anyString(), anyInt());
    }

    @Test
    void gyingAndOneProviderFailureStillReturnsOtherProvider() {
        when(gyingWorkflow.discoverResources(movie, 10)).thenThrow(new IllegalStateException("unavailable"));
        when(panSouClient.searchQuark(anyString(), anyInt())).thenThrow(new IllegalStateException("unavailable"));
        DiscoveredResource xunlei = resource("肖申克的救赎 1994", "https://pan.xunlei.com/s/fixture", "PANSOU");
        xunlei.setProvider("XUNLEI");
        when(panSouClient.searchClouds(anyString(), any(), anyInt())).thenReturn(List.of(xunlei));
        assertEquals(1, service.runTask(1L).getDiscovered());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"QUARK", "XUNLEI"})
    void disabledProviderDiscoveryNeverSearchesChecksOrPersistsThatProvider(String provider) {
        if ("QUARK".equals(provider)) properties.getQuark().setDiscoveryEnabled(false);
        else properties.getXunlei().setDiscoveryEnabled(false);
        var disabled = resource("肖申克的救赎 1994", "https://example.invalid/disabled", "GYING");
        disabled.setProvider(provider);
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of(disabled));
        var result = service.runTask(1L);
        assertEquals(0, result.getDiscovered());
        if ("QUARK".equals(provider)) verify(panSouClient, never()).searchQuark(anyString(), anyInt());
        else verify(panSouClient, never()).searchClouds(anyString(), any(), anyInt());
        verify(panSouClient, never()).checkLink(disabled);
        verify(discoveryService, never()).save(any());
        verify(quarkTransferTaskService, never()).save(any());
        verify(xunleiTransferTaskService, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"QUARK", "XUNLEI"})
    void disabledTransfersRetainDiscoveryButNeverCreateEvenAnExplicitTask(String provider) {
        if ("QUARK".equals(provider)) properties.getQuark().setTransferEnabled(false);
        else properties.getXunlei().setEnabled(false);
        var candidate = resource("肖申克的救赎 1994", "https://example.invalid/candidate", "GYING");
        candidate.setProvider(provider);
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of(candidate));
        var result = service.runTask(1L);
        assertEquals(1, result.getDiscovered()); assertEquals(0, result.getTransferTasksCreated());
        verify(discoveryService).save(any(ResourceDiscoveryResult.class));
        var discovered = discovery(999L, provider, candidate.getUrl());
        when(discoveryService.getById(999L)).thenReturn(discovered);
        org.junit.jupiter.api.Assertions.assertFalse(service.ensureTransferTask(999L));
        verify(quarkTransferTaskService, never()).save(any());
        verify(xunleiTransferTaskService, never()).save(any());
    }

    @Test void plannedBaiduCandidateCannotCreateDiscoveryOrFallThroughToQuarkTransfer() {
        var baidu = resource("肖申克的救赎 1994", "https://pan.baidu.com/s/candidate", "GYING");
        baidu.setProvider("BAIDU");
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of(baidu));
        assertEquals(0, service.runTask(1L).getDiscovered());
        when(discoveryService.getById(999L)).thenReturn(discovery(999L, "BAIDU", baidu.getUrl()));
        org.junit.jupiter.api.Assertions.assertFalse(service.ensureTransferTask(999L));
        verify(discoveryService, never()).save(any());
        verify(quarkTransferTaskService, never()).save(any());
        verify(xunleiTransferTaskService, never()).save(any());
    }

    @Test void enabledBaiduCreatesOnlyDedicatedTaskAndValidatesItsSourceHost() {
        properties.getBaidu().setDiscoveryEnabled(true); properties.getBaidu().setEnabled(true);
        properties.getBaidu().setVideoEnabled(true);
        var tasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", tasks);
        var row = discovery(999L, "BAIDU", "https://pan.baidu.com/s/1fixture");
        row.setCode("abcd"); when(discoveryService.getById(999L)).thenReturn(row);
        when(tasks.save(any())).thenReturn(true);
        assertTrue(service.ensureTransferTask(999L));
        var captured = ArgumentCaptor.forClass(com.gying.movie.entity.BaiduTransferTask.class);
        verify(tasks).save(captured.capture());
        assertEquals("NEW", captured.getValue().getPhase()); assertEquals("PENDING", captured.getValue().getStatus());
        assertEquals(movie.getId(), captured.getValue().getMovieId());
        verify(quarkTransferTaskService, never()).save(any()); verify(xunleiTransferTaskService, never()).save(any());
        row.setOriginalUrl("https://example.invalid/s/1fixture");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.ensureTransferTask(999L));
        verify(tasks, org.mockito.Mockito.times(1)).save(any());
    }

    @Test void noVideoResultsWithVideoTypesDisabledDoesNotDowngradeAnAvailableMovie() {
        properties.getQuark().setVideoEnabled(false); properties.getXunlei().setVideoEnabled(false);
        properties.getQuark().setTorrentEnabled(true); properties.getXunlei().setTorrentEnabled(true);
        movie.setResourceStatus("AVAILABLE");
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of());
        assertEquals("SUCCEEDED", service.runTask(1L).getStatus());
        assertEquals("AVAILABLE", movie.getResourceStatus());
        verify(movieService, never()).updateById(any(MovieMetadata.class));
        verify(panSouClient, never()).searchQuark(anyString(), anyInt());
        verify(panSouClient, never()).searchClouds(anyString(), any(), anyInt());
    }

    @Test void disablingAllDiscoveryPreservesExistingMovieResourceStatus() {
        properties.getQuark().setDiscoveryEnabled(false); properties.getXunlei().setDiscoveryEnabled(false);
        movie.setResourceStatus("AVAILABLE");
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of());
        var result = service.runTask(1L);
        assertEquals("SUCCEEDED", result.getStatus()); assertEquals(0, result.getDiscovered());
        assertEquals("AVAILABLE", movie.getResourceStatus());
        verify(movieService, never()).updateById(any(MovieMetadata.class));
        verify(panSouClient, never()).searchQuark(anyString(), anyInt());
        verify(panSouClient, never()).searchClouds(anyString(), any(), anyInt());
    }

    @Test void baiduQueryFailureDoesNotTurnEmptyLegacyResultsIntoTrailer() {
        properties.getBaidu().setDiscoveryEnabled(true); properties.getBaidu().setVideoEnabled(true);
        movie.setResourceStatus("AVAILABLE");
        when(gyingWorkflow.discoverResources(movie, 10)).thenReturn(List.of());
        when(panSouClient.searchQuark(anyString(), anyInt())).thenReturn(List.of());
        when(panSouClient.searchClouds(anyString(), any(), anyInt())).thenAnswer(call -> {
            if (((java.util.Set<?>) call.getArgument(1)).contains("BAIDU")) throw new IllegalStateException("Fixture source unavailable");
            return List.of();
        });
        var result = service.runTask(1L);
        assertEquals("SUCCEEDED", result.getStatus()); assertEquals("AVAILABLE", movie.getResourceStatus());
        verify(movieService, never()).updateById(any(MovieMetadata.class));
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
    @Test void explicitBaiduScopeQueriesAndPersistsOnlyBaiduWithoutCreatingOtherProviderTasks() {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setDiscoveryEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        task.setSource("PANSOU");
        task.setPayload("{\"movieId\":\"" + movie.getId() + "\",\"source\":\"PANSOU\",\"providers\":[\"BAIDU\"],\"deferTransfer\":true}");
        DiscoveredResource baidu = resource("肖申克的救赎 4K", "https://pan.baidu.com/s/1fixture", "PANSOU"); baidu.setProvider("BAIDU");
        DiscoveredResource quark = resource("肖申克的救赎 4K", "https://pan.quark.cn/s/fixture", "PANSOU");
        when(panSouClient.searchClouds(anyString(), any(), anyInt())).thenReturn(List.of(baidu, quark));
        ResourceDiscoveryRunResult result = service.runTask(1L);
        assertEquals(1, result.getDiscovered());
        verify(panSouClient).searchClouds(anyString(), org.mockito.ArgumentMatchers.eq(java.util.Set.of("BAIDU")), anyInt());
        verify(panSouClient, never()).searchQuark(anyString(), anyInt());
        org.mockito.Mockito.verifyNoInteractions(quarkTransferTaskService, xunleiTransferTaskService, gyingWorkflow);
        ArgumentCaptor<ResourceDiscoveryResult> saved = ArgumentCaptor.forClass(ResourceDiscoveryResult.class);
        verify(discoveryService).save(saved.capture()); assertEquals("BAIDU", saved.getValue().getProvider());
        verify(movieService, never()).updateById(any(MovieMetadata.class));
    }
    @Test void emptyOrUnknownExplicitProviderScopeIsRejectedBeforeEnqueue() {
        var request = new com.gying.movie.dto.ResourceDiscoveryRequest(); request.setMovieId(movie.getId()); request.setProviders(List.of());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.enqueue(request));
        request.setProviders(List.of("UNKNOWN"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.enqueue(request));
        org.mockito.Mockito.verifyNoInteractions(taskService);
    }


    @Test void selectedTemporaryCloneCannotCreateOrReuseAPermanentTask() {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setVideoEnabled(true);
        var baiduTasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", baiduTasks);
        var clone = discovery(990L, "BAIDU", "https://pan.baidu.com/s/1fixture");
        clone.setSource("QQ_BOT"); clone.setSourceRef("BAIDU_TEMP:" + TEMP_SCOPE);
        when(discoveryService.getById(990L)).thenReturn(clone);
        assertFalse(service.ensureTransferTask(990L));
        org.mockito.Mockito.verifyNoInteractions(baiduTasks);
        assertEquals("BAIDU_TEMP:" + TEMP_SCOPE, clone.getSourceRef());
    }

    @Test void reconciliationSkipsScopedClonesAndCreatesASeparatePermanentBaiduTask() {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setVideoEnabled(true);
        var baiduTasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", baiduTasks);
        var clone = discovery(990L, "BAIDU", "https://pan.baidu.com/s/1fixture");
        clone.setSourceRef("BAIDU_TEMP:" + TEMP_SCOPE);
        var permanent = discovery(991L, "BAIDU", clone.getOriginalUrl());
        when(discoveryService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(call -> { permanentDiscoveryQuery(call.getArgument(0)); return List.of(clone, permanent); });
        when(discoveryService.getById(991L)).thenReturn(permanent);
        when(baiduTasks.count(any())).thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return 0L; });
        when(baiduTasks.getOne(any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return null; });
        when(baiduTasks.save(any())).thenReturn(true);
        assertEquals(1, service.reconcileDiscoveredTransferTasks(1));
        var saved = ArgumentCaptor.forClass(com.gying.movie.entity.BaiduTransferTask.class);
        verify(baiduTasks).save(saved.capture());
        assertEquals("PERMANENT", saved.getValue().getScopeKey());
        assertEquals(991L, saved.getValue().getDiscoveryResultId());
        verify(discoveryService, never()).getById(990L);
    }

    @Test void permanentInsertRaceOnlyChecksThePermanentScope() {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setVideoEnabled(true);
        var baiduTasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", baiduTasks);
        var row = discovery(991L, "BAIDU", "https://pan.baidu.com/s/1fixture");
        when(discoveryService.getById(991L)).thenReturn(row);
        when(baiduTasks.getOne(any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return null; });
        when(baiduTasks.save(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("fixture race"));
        when(baiduTasks.count(any())).thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return 1L; });
        assertTrue(service.ensureTransferTask(991L));
        var saved = ArgumentCaptor.forClass(com.gying.movie.entity.BaiduTransferTask.class);
        verify(baiduTasks).save(saved.capture()); assertEquals("PERMANENT", saved.getValue().getScopeKey());
    }

    @Test void temporaryLinksCannotBlockPermanentDiscoveryAsDuplicates() {
        when(resourceLinkService.count(any())).thenAnswer(call -> { permanentResourceQuery(call.getArgument(0)); return 0L; });
        Boolean duplicate = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "isDuplicate", movie.getId(), "fixture-hash", "https://pan.baidu.com/s/1fixture");
        assertFalse(duplicate);
    }

}
