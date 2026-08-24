package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.NapCatClient;
import com.gying.movie.client.PanSouClient;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.client.QqOfficialBotClient;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.DiscoveredResource;
import com.gying.movie.dto.MovieSearchCandidate;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQqBotSearchLogService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IXunleiTransferTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QqBotServiceImplTest {

    private QqBotProperties qqBotProperties;
    private ResourceHubProperties resourceHubProperties;
    private IMovieMetadataService movieService;
    private IResourceLinkService resourceLinkService;
    private IResourceDiscoveryService resourceDiscoveryService;
    private IResourceDiscoveryResultService discoveryResultService;
    private IQuarkTransferTaskService quarkTransferTaskService;
    private IQuarkTransferRunnerService quarkTransferRunnerService;
    private IXunleiTransferTaskService xunleiTransferTaskService;
    private IXunleiTransferRunnerService xunleiTransferRunnerService;
    private IResourceHubPublishService resourceHubPublishService;
    private ITmdbMetadataSyncService tmdbMetadataSyncService;
    private GyingSourceWorkflowService gyingSourceWorkflowService;
    private PanSouClient panSouClient;
    private NapCatClient napCatClient;
    private QqBotServiceImpl service;

    @BeforeEach
    void setUp() {
        qqBotProperties = new QqBotProperties();
        qqBotProperties.setRateLimitPerMinute(0);
        resourceHubProperties = new ResourceHubProperties();
        movieService = mock(IMovieMetadataService.class);
        resourceLinkService = mock(IResourceLinkService.class);
        resourceDiscoveryService = mock(IResourceDiscoveryService.class);
        discoveryResultService = mock(IResourceDiscoveryResultService.class);
        quarkTransferTaskService = mock(IQuarkTransferTaskService.class);
        quarkTransferRunnerService = mock(IQuarkTransferRunnerService.class);
        xunleiTransferTaskService = mock(IXunleiTransferTaskService.class);
        xunleiTransferRunnerService = mock(IXunleiTransferRunnerService.class);
        resourceHubPublishService = mock(IResourceHubPublishService.class);
        tmdbMetadataSyncService = mock(ITmdbMetadataSyncService.class);
        gyingSourceWorkflowService = mock(GyingSourceWorkflowService.class);
        panSouClient = mock(PanSouClient.class);
        napCatClient = mock(NapCatClient.class);
        service = new QqBotServiceImpl(
                qqBotProperties,
                resourceHubProperties,
                napCatClient,
                panSouClient,
                mock(QqOfficialBotClient.class),
                movieService,
                resourceLinkService,
                resourceDiscoveryService,
                discoveryResultService,
                mock(IQuarkShareService.class),
                quarkTransferTaskService,
                quarkTransferRunnerService,
                xunleiTransferTaskService,
                xunleiTransferRunnerService,
                resourceHubPublishService,
                tmdbMetadataSyncService,
                mock(IQqBotSearchLogService.class),
                gyingSourceWorkflowService);
        when(resourceLinkService.list(any(QueryWrapper.class))).thenReturn(List.of());
    }

    @Test
    void resolvesCandidateNumberBeforeMinimumKeywordLengthValidation() {
        MovieSearchCandidate first = candidate(1L, "福尔摩斯先生", 2015, 140);
        MovieSearchCandidate second = candidate(2L, "福尔摩斯：基本演绎法", 2012, 130);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of());
        when(tmdbMetadataSyncService.searchCandidatesByKeyword("福尔摩斯", 10))
                .thenReturn(List.of(first, second));
        MovieMetadata selectedMovie = movie("tv_2", "福尔摩斯：基本演绎法", 2012);
        when(tmdbMetadataSyncService.syncExactByKeyword("福尔摩斯：基本演绎法"))
                .thenReturn(selectedMovie);

        String candidates = service.buildSearchReply("福尔摩斯", "user-1");
        String selected = service.buildSearchReply("2", "user-1");

        assertTrue(candidates.contains("2. 剧集 福尔摩斯：基本演绎法 (2012)"));
        assertTrue(candidates.contains("直接回复序号即可，例如：1"));
        assertTrue(selected.contains("片名：福尔摩斯：基本演绎法"));
        verify(tmdbMetadataSyncService).syncExactByKeyword("福尔摩斯：基本演绎法");
    }

    @Test
    void showsGyingCandidatesBeforeTransferAndUsesSelectedSourceIdentity() {
        MovieSearchCandidate gyingCandidate = new MovieSearchCandidate(
                null,
                "tv",
                "伦敦生活",
                "Fleabag",
                2016,
                180,
                "GYING",
                "tv",
                "test-mid",
                null);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of());
        when(gyingSourceWorkflowService.searchCandidates("伦敦生活", 10))
                .thenReturn(List.of(gyingCandidate));
        MovieMetadata selectedMovie = movie("gying_london_life", "伦敦生活", 2016);
        when(gyingSourceWorkflowService.ensureMovieMetadata("tv", "test-mid"))
                .thenReturn(java.util.Map.of(
                        "status", "METADATA_READY",
                        "localMovieId", selectedMovie.getId()));
        when(movieService.getById(selectedMovie.getId())).thenReturn(selectedMovie);
        ResourceLink published = link(
                "QUARK",
                "伦敦生活 GYING",
                "https://pan.quark.cn/s/gying-london-life");
        published.setSource("GYING_PUBLISHED");
        when(resourceLinkService.list(any(QueryWrapper.class))).thenReturn(List.of(published));
        when(panSouClient.checkLink(published.getUrl()))
                .thenReturn(new LinkCheckResult(published.getUrl(), true, true, "ok"));

        String candidates = service.buildSearchReply("伦敦生活", "gying-candidate-user");

        assertTrue(candidates.contains("1. 剧集 伦敦生活 (2016) [GYING]"));
        verify(gyingSourceWorkflowService, never())
                .ensureMovieResource(anyString(), anyString());
        verify(gyingSourceWorkflowService, never())
                .ensureMovieMetadata(anyString(), anyString());

        String selected = service.buildSearchReply("1", "gying-candidate-user");

        assertTrue(selected.contains(published.getName()));
        assertFalse(selected.contains(published.getUrl()));
        verify(gyingSourceWorkflowService).ensureMovieMetadata("tv", "test-mid");
        verify(gyingSourceWorkflowService, never())
                .ensureMovieResource(anyString(), anyString());
        assertTrue(service.buildSearchReply("1", "gying-candidate-user").contains(published.getUrl()));
    }

    @Test
    void showsCandidatesWhenExactLocalTitleHasSameNamedGyingMovie() {
        MovieMetadata localSeries = movie("tv_london-life", "伦敦生活", 2016);
        localSeries.setTmdbType("tv");
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(localSeries));
        MovieSearchCandidate gyingMovie = new MovieSearchCandidate(
                null,
                "movie",
                "伦敦生活",
                "National Theatre Live: Fleabag",
                2019,
                220,
                "GYING",
                "mv",
                "739Y",
                null);
        MovieSearchCandidate gyingSeries = new MovieSearchCandidate(
                null,
                "tv",
                "伦敦生活 第一季",
                "Fleabag Season 1",
                2016,
                210,
                "GYING",
                "tv",
                "wxLe",
                null);
        when(gyingSourceWorkflowService.searchCandidates("伦敦生活", 10))
                .thenReturn(List.of(gyingMovie, gyingSeries));
        when(tmdbMetadataSyncService.searchCandidatesByKeyword("伦敦生活", 10))
                .thenReturn(List.of());

        String reply = service.buildSearchReply("伦敦生活", "ambiguous-london-user");

        assertTrue(reply.contains("请选择要搜索的影片"));
        assertTrue(reply.contains("电影 伦敦生活 (2019) [GYING]"));
        assertTrue(reply.contains("剧集 伦敦生活 第一季 (2016) [GYING]"));
        verify(gyingSourceWorkflowService, org.mockito.Mockito.never())
                .ensureMovieResource(anyString(), anyString());
        verify(resourceDiscoveryService, org.mockito.Mockito.never()).enqueue(any());
    }

    @Test
    void retriesPanSouWithoutYearWhenYearQueryFindsNoQuarkResources() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("tv_2", "福尔摩斯：基本演绎法", 2012);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(1L), task(2L));
        when(resourceDiscoveryService.runTask(1L)).thenReturn(emptyDiscovery(1L));
        when(resourceDiscoveryService.runTask(2L)).thenReturn(emptyDiscovery(2L));

        service.buildSearchReply("福尔摩斯：基本演绎法", "user-2");

        ArgumentCaptor<ResourceDiscoveryRequest> requests = ArgumentCaptor.forClass(ResourceDiscoveryRequest.class);
        verify(resourceDiscoveryService, times(2)).enqueue(requests.capture());
        assertEquals(
                List.of("福尔摩斯：基本演绎法 2012", "福尔摩斯：基本演绎法"),
                requests.getAllValues().stream().map(ResourceDiscoveryRequest::getKeyword).toList());
        assertTrue(requests.getAllValues().stream().allMatch(ResourceDiscoveryRequest::getDeferTransfer));
        assertTrue(requests.getAllValues().stream().allMatch(request -> "AUTO".equals(request.getSource())));
    }

    @Test
    void prefersGyingWorkflowBeforePanSouDiscovery() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("tmdb_movie_1275779", "蜘蛛侠：英雄无归", 2021);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(gyingSourceWorkflowService.ensureLocalMovieResource(movie.getId()))
                .thenReturn(java.util.Map.of("status", "PUBLISHED"));
        ResourceLink published = link(
                "QUARK",
                "蜘蛛侠：英雄无归 GYING",
                "https://pan.quark.cn/s/gying-share");
        published.setSource("GYING_PUBLISHED");
        ResourceLink publishedXunlei = link(
                "XUNLEI",
                "蜘蛛侠：英雄无归 GYING 迅雷",
                "https://pan.xunlei.com/s/gying-share");
        publishedXunlei.setSource("GYING_PUBLISHED");
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(published, publishedXunlei));
        when(panSouClient.checkLink(published.getUrl()))
                .thenReturn(new LinkCheckResult(published.getUrl(), true, true, "ok"));
        when(panSouClient.checkLink(publishedXunlei.getUrl()))
                .thenReturn(new LinkCheckResult(publishedXunlei.getUrl(), true, true, "ok"));

        String reply = service.buildSearchReply("蜘蛛侠英雄无归", "gying-first-user");

        assertTrue(reply.contains(published.getName()));
        assertFalse(reply.contains("https://pan.quark.cn/s/gying-share"));
        verify(gyingSourceWorkflowService, org.mockito.Mockito.never()).ensureLocalMovieResource(movie.getId());
    }

    @Test
    void fallsBackToPanSouWhenGyingTransferFails() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("tmdb_movie_1275779", "蜘蛛侠：英雄无归", 2021);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(gyingSourceWorkflowService.ensureLocalMovieResource(movie.getId()))
                .thenThrow(new IllegalStateException("GYING transfer failed"));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(101L));
        when(resourceDiscoveryService.runTask(101L)).thenReturn(emptyDiscovery(101L));

        service.buildSearchReply("蜘蛛侠英雄无归", "gying-fallback-user");

        verify(gyingSourceWorkflowService, org.mockito.Mockito.never()).ensureLocalMovieResource(movie.getId());
        verify(resourceDiscoveryService, org.mockito.Mockito.times(3)).enqueue(any());
    }

    @Test
    void syncsMetadataWhenCompactKeywordOmitsChinesePunctuation() {
        MovieMetadata movie = movie("tmdb_movie_1275779", "蜘蛛侠：英雄无归", 2021);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of());
        when(tmdbMetadataSyncService.syncExactByKeyword("蜘蛛侠英雄无归")).thenReturn(movie);

        String reply = service.buildSearchReply("蜘蛛侠英雄无归", "compact-title-user");

        assertTrue(reply.contains("蜘蛛侠：英雄无归"));
        verify(tmdbMetadataSyncService).syncExactByKeyword("蜘蛛侠英雄无归");
    }

    @Test
    void rejectsBaiduProviderWithoutSearchingOrReturningThirdPartyLinks() {
        MovieMetadata movie = movie("tv_112732", "哈哈哈哈哈", 2020);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        ResourceLink ownedQuark = link(
                "QUARK",
                "哈哈哈哈哈 自有分享",
                "https://pan.quark.cn/s/owned-share");
        ownedQuark.setSource("RESOURCE_HUB");
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(ownedQuark), List.of(ownedQuark), List.of());
        when(panSouClient.checkLink(ownedQuark.getUrl()))
                .thenReturn(new LinkCheckResult(ownedQuark.getUrl(), true, true, "ok"));
        service.buildSearchReply("哈哈哈哈哈", "user-3");
        String reply = service.buildSearchReply("百度 1", "user-3");

        assertTrue(reply.contains("暂不支持百度网盘服务"));
        assertFalse(reply.contains("https://pan.baidu.com"));
        verify(panSouClient, org.mockito.Mockito.never())
                .searchClouds(anyString(), eq(Set.of("BAIDU")), anyInt());
    }

    @Test
    void returnsGyingPublishedQuarkWithoutUploader() {
        MovieMetadata movie = movie("tmdb_movie_1081003", "超级少女", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        ResourceLink published = link(
                "QUARK",
                "超级少女 2026",
                "https://pan.quark.cn/s/d89781f22043");
        published.setSource("GYING_PUBLISHED");
        when(resourceLinkService.list(any(QueryWrapper.class))).thenReturn(List.of(published));
        when(panSouClient.checkLink(published.getUrl()))
                .thenReturn(new LinkCheckResult(published.getUrl(), true, true, "ok"));

        String reply = service.buildSearchReply("超级少女", "gying-published-user");

        assertTrue(reply.contains(published.getName()));
        assertFalse(reply.contains(published.getUrl()));
        verify(resourceDiscoveryService, org.mockito.Mockito.never()).enqueue(any());
    }

    @Test
    void returnsTransferredGyingShareAfterLegacySourceOverwrite() {
        MovieMetadata movie = movie("tmdb_tv_67070", "伦敦生活 第一季", 2016);
        movie.setTmdbType("tv");
        movie.setSeason(1);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        ResourceLink published = link(
                "QUARK",
                "伦敦生活 第1季 (2016)",
                "https://pan.quark.cn/s/92d2370ddc08");
        published.setId(1959L);
        published.setSource("GYING");
        when(resourceLinkService.list(any(QueryWrapper.class))).thenReturn(List.of(published));
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(3599L);
        when(discoveryResultService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(discovery);
        when(quarkTransferTaskService.count(any(QueryWrapper.class))).thenReturn(1L);
        when(panSouClient.checkLink(published.getUrl()))
                .thenReturn(new LinkCheckResult(published.getUrl(), true, true, "ok"));

        String reply = service.buildSearchReply("伦敦生活 第一季", "legacy-gying-owned-user");

        assertTrue(reply.contains(published.getName()));
        assertFalse(reply.contains(published.getUrl()));
        verify(resourceDiscoveryService, org.mockito.Mockito.never()).enqueue(any());
    }

    @Test
    void keepsKnownNormalQuarkWhenLiveValidationIsUnavailable() {
        MovieMetadata movie = movie("tmdb_movie_1081003", "超级少女", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        ResourceLink published = link(
                "QUARK",
                "超级少女 2026",
                "https://pan.quark.cn/s/d89781f22043");
        published.setSource("GYING_PUBLISHED");
        when(resourceLinkService.list(any(QueryWrapper.class))).thenReturn(List.of(published));
        when(panSouClient.checkLink(published.getUrl()))
                .thenReturn(new LinkCheckResult(published.getUrl(), false, false, "service timeout"));

        String reply = service.buildSearchReply("超级少女", "validation-timeout-user");

        assertTrue(reply.contains(published.getName()));
        assertFalse(reply.contains(published.getUrl()));
        assertEquals("NORMAL", published.getLinkStatus());
        assertTrue(published.getLastCheckError().contains("service timeout"));
        verify(resourceLinkService).updateById(published);
        verify(resourceDiscoveryService, org.mockito.Mockito.never()).enqueue(any());
    }

    @Test
    void treatsSeasonQualifiedKeywordAsTheBaseShowAndKeepsSeasonInDiscovery() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("tv_271408", "密室大逃脱", 2019);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of());
        when(tmdbMetadataSyncService.syncExactByKeyword("密室大逃脱")).thenReturn(movie);
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(11L));
        ResourceDiscoveryRunResult discovered = emptyDiscovery(11L);
        discovered.setDiscovered(1);
        when(resourceDiscoveryService.runTask(11L)).thenReturn(discovered);

        String reply = service.buildSearchReply("密室大逃脱第七季", "season-user");

        ArgumentCaptor<ResourceDiscoveryRequest> request = ArgumentCaptor.forClass(ResourceDiscoveryRequest.class);
        verify(resourceDiscoveryService).enqueue(request.capture());
        assertEquals("密室大逃脱第七季", request.getValue().getKeyword());
        assertTrue(reply.contains("片名：密室大逃脱"));
        verify(tmdbMetadataSyncService).syncExactByKeyword("密室大逃脱");
        verify(tmdbMetadataSyncService, org.mockito.Mockito.never())
                .searchCandidatesByKeyword(anyString(), anyInt());
    }

    @Test
    void returnsRequestedProviderAndCountFromRecentMovieContext() {
        MovieMetadata movie = movie("tv_112732", "哈哈哈哈哈", 2020);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        ResourceLink ownedQuark = link(
                "QUARK",
                "哈哈哈哈哈 自有分享",
                "https://pan.quark.cn/s/context-share");
        ownedQuark.setSource("RESOURCE_HUB");
        ResourceLink ownedXunlei = link(
                "XUNLEI",
                "哈哈哈哈哈 迅雷自有分享",
                "https://pan.xunlei.com/s/context-share");
        ownedXunlei.setSource("RESOURCE_HUB");
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(ownedQuark, ownedXunlei), List.of(ownedQuark, ownedXunlei),
                        List.of(ownedQuark, ownedXunlei), List.of(ownedQuark, ownedXunlei),
                        List.of(ownedQuark, ownedXunlei), List.of(ownedQuark, ownedXunlei));
        when(panSouClient.checkLink(ownedQuark.getUrl()))
                .thenReturn(new LinkCheckResult(ownedQuark.getUrl(), true, true, "ok"));
        when(panSouClient.checkLink(ownedXunlei.getUrl()))
                .thenReturn(new LinkCheckResult(ownedXunlei.getUrl(), true, true, "ok"));
        service.buildSearchReply("哈哈哈哈哈", "preference-user");

        String reply = service.buildSearchReply("迅雷 2", "preference-user");

        assertTrue(reply.contains(ownedXunlei.getName()));
        assertFalse(reply.contains("https://pan.xunlei.com/s/context-share"));
        assertFalse(reply.contains("https://pan.baidu.com"));
        assertTrue(service.buildSearchReply("1", "preference-user")
                .contains("https://pan.xunlei.com/s/context-share"));
    }

    @Test
    void ignoresQuarkCountCommandAndWaitsForOneResourceSelection() {
        MovieMetadata movie = movie("movie_batch_guard", "批量保护测试", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        ResourceLink quark = link(
                "QUARK",
                "批量保护测试 4K HDR",
                "https://pan.quark.cn/s/batch-guard");
        quark.setSource("RESOURCE_HUB");
        ResourceLink xunlei = link(
                "XUNLEI",
                "批量保护测试 1080P",
                "https://pan.xunlei.com/s/batch-guard");
        xunlei.setSource("RESOURCE_HUB");
        when(resourceLinkService.list(any(QueryWrapper.class))).thenReturn(List.of(quark, xunlei));
        when(panSouClient.checkLink(quark.getUrl()))
                .thenReturn(new LinkCheckResult(quark.getUrl(), true, true, "ok"));
        when(panSouClient.checkLink(xunlei.getUrl()))
                .thenReturn(new LinkCheckResult(xunlei.getUrl(), true, true, "ok"));

        String initial = service.buildSearchReply("批量保护测试", "batch-guard-user");
        String filtered = service.buildSearchReply("夸克10", "batch-guard-user");
        String switched = service.buildSearchReply("迅雷", "batch-guard-user");
        String restored = service.buildSearchReply("资源", "batch-guard-user");

        assertTrue(initial.contains(quark.getName()));
        assertTrue(initial.contains(xunlei.getName()));
        assertTrue(filtered.contains("已忽略数量指令"));
        assertTrue(filtered.contains(quark.getName()));
        assertFalse(filtered.contains(xunlei.getName()));
        assertFalse(filtered.contains(quark.getUrl()));
        assertTrue(switched.contains(xunlei.getName()));
        assertFalse(switched.contains(quark.getName()));
        assertTrue(restored.contains(quark.getName()));
        assertTrue(restored.contains(xunlei.getName()));
        verify(quarkTransferRunnerService, never()).submitOne(any());
        verify(xunleiTransferRunnerService, never()).submitOne(any());

        assertTrue(service.buildSearchReply("1", "batch-guard-user").contains(quark.getUrl()));
        verify(quarkTransferRunnerService, never()).submitOne(any());
        verify(xunleiTransferRunnerService, never()).submitOne(any());
    }

    @Test
    void tellsUserWhenResourceCandidatesHaveExpired() {
        QqBotServiceImpl timedService = spy(service);
        Instant initialTime = Instant.parse("2026-08-20T03:00:00Z");
        doReturn(initialTime).when(timedService).now();
        MovieMetadata movie = movie("movie_expired_choice", "过期候选测试", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        ResourceLink quark = link(
                "QUARK",
                "过期候选测试 4K",
                "https://pan.quark.cn/s/expired-choice");
        quark.setSource("RESOURCE_HUB");
        when(resourceLinkService.list(any(QueryWrapper.class))).thenReturn(List.of(quark));
        when(panSouClient.checkLink(quark.getUrl()))
                .thenReturn(new LinkCheckResult(quark.getUrl(), true, true, "ok"));

        assertTrue(timedService.buildSearchReply("过期候选测试", "expired-user")
                .contains(quark.getName()));
        doReturn(initialTime.plusSeconds(301)).when(timedService).now();

        assertTrue(timedService.buildSearchReply("1", "expired-user")
                .contains("资源候选已过期，请重新搜索影片并选择资源"));
    }

    @Test
    void createsAndRunsOnlySelectedQuarkTransferTask() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("movie_quark_choice", "夸克候选测试", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(301L));
        ResourceDiscoveryRunResult run = emptyDiscovery(301L);
        run.setDiscovered(1);
        when(resourceDiscoveryService.runTask(301L)).thenReturn(run);

        ResourceDiscoveryResult discovery = discovery(
                401L,
                movie.getId(),
                "QUARK",
                "夸克候选测试 REMUX",
                "https://pan.quark.cn/s/source-choice");
        discovery.setQuality("4K HDR");
        discovery.setResourceLinkId(601L);
        when(discoveryResultService.list(any(QueryWrapper.class))).thenReturn(List.of(discovery));
        when(discoveryResultService.getById(discovery.getId())).thenReturn(discovery);

        QuarkTransferTask transfer = new QuarkTransferTask();
        transfer.setId(501L);
        transfer.setDiscoveryResultId(discovery.getId());
        transfer.setMovieId(movie.getId());
        transfer.setStatus("PENDING");
        transfer.setShareUrl("https://pan.quark.cn/s/my-choice");
        when(quarkTransferTaskService.getOne(any(QueryWrapper.class), eq(false)))
                .thenReturn(null, transfer, transfer);
        when(quarkTransferTaskService.getById(transfer.getId())).thenReturn(transfer);
        when(resourceDiscoveryService.ensureTransferTask(discovery.getId())).thenReturn(true);
        when(quarkTransferRunnerService.submitOne(transfer.getId())).thenReturn(successfulTransfer());

        ResourceLink ready = link("QUARK", "夸克候选测试 4K HDR REMUX", transfer.getShareUrl());
        ready.setId(601L);
        ready.setSource("RESOURCE_HUB");
        when(resourceLinkService.getById(ready.getId())).thenReturn(ready);
        when(panSouClient.checkLink(ready.getUrl()))
                .thenReturn(new LinkCheckResult(ready.getUrl(), true, true, "ok"));

        String candidates = service.buildSearchReply("夸克候选测试", "quark-choice-user");
        String selected = service.buildSearchReply("1", "quark-choice-user");

        assertTrue(candidates.contains("夸克候选测试 REMUX · 4K HDR"));
        assertFalse(candidates.contains(discovery.getOriginalUrl()));
        assertTrue(selected.contains(ready.getName()));
        assertTrue(selected.contains(ready.getUrl()));
        verify(resourceDiscoveryService).ensureTransferTask(discovery.getId());
        verify(quarkTransferRunnerService).submitOne(transfer.getId());
        verify(xunleiTransferRunnerService, never()).submitOne(any());
        ArgumentCaptor<ResourceDiscoveryRequest> request =
                ArgumentCaptor.forClass(ResourceDiscoveryRequest.class);
        verify(resourceDiscoveryService).enqueue(request.capture());
        assertTrue(request.getValue().getDeferTransfer());
    }

    @Test
    void runsOnlySelectedXunleiTransferTask() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("movie_xunlei_choice", "迅雷候选测试", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(302L));
        ResourceDiscoveryRunResult run = emptyDiscovery(302L);
        run.setDiscovered(1);
        when(resourceDiscoveryService.runTask(302L)).thenReturn(run);

        ResourceDiscoveryResult discovery = discovery(
                402L,
                movie.getId(),
                "XUNLEI",
                "迅雷候选测试 WEB-DL 1080P",
                "https://pan.xunlei.com/s/source-choice");
        discovery.setResourceLinkId(602L);
        when(discoveryResultService.list(any(QueryWrapper.class))).thenReturn(List.of(discovery));
        when(discoveryResultService.getById(discovery.getId())).thenReturn(discovery);

        XunleiTransferTask transfer = new XunleiTransferTask();
        transfer.setId(502L);
        transfer.setDiscoveryResultId(discovery.getId());
        transfer.setMovieId(movie.getId());
        transfer.setStatus("PENDING");
        transfer.setShareUrl("https://pan.xunlei.com/s/my-choice");
        when(xunleiTransferTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(transfer);
        when(xunleiTransferTaskService.getById(transfer.getId())).thenReturn(transfer);
        when(xunleiTransferRunnerService.submitOne(transfer.getId())).thenReturn(successfulTransfer());

        ResourceLink ready = link("XUNLEI", "迅雷候选测试 WEB-DL 1080P", transfer.getShareUrl());
        ready.setId(602L);
        ready.setSource("RESOURCE_HUB");
        when(resourceLinkService.getById(ready.getId())).thenReturn(ready);
        when(panSouClient.checkLink(ready.getUrl()))
                .thenReturn(new LinkCheckResult(ready.getUrl(), true, true, "ok"));

        String candidates = service.buildSearchReply("迅雷候选测试", "xunlei-choice-user");
        String selected = service.buildSearchReply("1", "xunlei-choice-user");

        assertTrue(candidates.contains(discovery.getTitle()));
        assertFalse(candidates.contains(discovery.getOriginalUrl()));
        assertTrue(selected.contains(ready.getUrl()));
        verify(xunleiTransferRunnerService).submitOne(transfer.getId());
        verify(quarkTransferRunnerService, never()).submitOne(any());
        verify(resourceDiscoveryService, never()).ensureTransferTask(discovery.getId());
    }

    @Test
    void explainsWhenSelectedShareContainsNoVideoFiles() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("movie_no_video", "无视频测试", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(303L));
        ResourceDiscoveryRunResult run = emptyDiscovery(303L);
        run.setDiscovered(1);
        when(resourceDiscoveryService.runTask(303L)).thenReturn(run);
        ResourceDiscoveryResult discovery = discovery(
                403L,
                movie.getId(),
                "QUARK",
                "无视频测试 4K",
                "https://pan.quark.cn/s/no-video");
        when(discoveryResultService.list(any(QueryWrapper.class))).thenReturn(List.of(discovery));
        when(discoveryResultService.getById(discovery.getId())).thenReturn(discovery);
        QuarkTransferTask transfer = new QuarkTransferTask();
        transfer.setId(503L);
        transfer.setStatus("PENDING");
        when(quarkTransferTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(transfer);
        when(quarkTransferRunnerService.submitOne(transfer.getId()))
                .thenThrow(new IllegalStateException("no transferred media files"));

        service.buildSearchReply("无视频测试", "no-video-user");
        String reply = service.buildSearchReply("1", "no-video-user");
        assertTrue(reply.contains("转存失败"));
        assertTrue(reply.contains("请继续回复其他资源序号"));
    }

    @Test
    void explainsWhenSelectedShareIsBlockedForPolicyViolation() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("movie_violation", "违规测试", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(304L));
        ResourceDiscoveryRunResult run = emptyDiscovery(304L);
        run.setDiscovered(1);
        when(resourceDiscoveryService.runTask(304L)).thenReturn(run);
        ResourceDiscoveryResult discovery = discovery(
                404L,
                movie.getId(),
                "XUNLEI",
                "违规测试 1080P",
                "https://pan.xunlei.com/s/violation");
        when(discoveryResultService.list(any(QueryWrapper.class))).thenReturn(List.of(discovery));
        when(discoveryResultService.getById(discovery.getId())).thenReturn(discovery);
        XunleiTransferTask transfer = new XunleiTransferTask();
        transfer.setId(504L);
        transfer.setStatus("PENDING");
        when(xunleiTransferTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(transfer);
        when(xunleiTransferRunnerService.submitOne(transfer.getId()))
                .thenThrow(new IllegalStateException("policy violation: forbidden share"));

        service.buildSearchReply("违规测试", "violation-user");
        String reply = service.buildSearchReply("1", "violation-user");
        assertTrue(reply.contains("转存失败"));
        assertTrue(reply.contains("请继续回复其他资源序号"));
    }

    @Test
    void searchesMissingQuarkAfterInitialLibraryResourceHit() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("tv_1399", "权力的游戏", 2011);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        ResourceLink libraryBaidu = link(
                "BAIDU",
                "权力的游戏 全八季",
                "https://pan.baidu.com/s/library");
        ResourceLink ownedQuark = link(
                "QUARK",
                "权力的游戏 全八季",
                "https://pan.quark.cn/s/owned-share");
        ownedQuark.setSource("RESOURCE_HUB");
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(libraryBaidu, ownedQuark), List.of(libraryBaidu, ownedQuark),
                        List.of(libraryBaidu, ownedQuark), List.of(libraryBaidu, ownedQuark),
                        List.of(libraryBaidu, ownedQuark), List.of(libraryBaidu, ownedQuark));
        when(panSouClient.checkLink(ownedQuark.getUrl()))
                .thenReturn(new LinkCheckResult(ownedQuark.getUrl(), true, true, "ok"));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(21L));
        ResourceDiscoveryRunResult discovered = emptyDiscovery(21L);
        discovered.setDiscovered(1);
        when(resourceDiscoveryService.runTask(21L)).thenReturn(discovered);

        String initialReply = service.buildSearchReply("权力的游戏", "library-user");
        String quarkReply = service.buildSearchReply("夸克 2", "library-user");

        assertTrue(initialReply.contains(ownedQuark.getName()));
        assertFalse(initialReply.contains("https://pan.quark.cn/s/owned-share"));
        assertFalse(initialReply.contains("https://pan.baidu.com/s/library"));
        assertTrue(quarkReply.contains(ownedQuark.getName()));
        assertFalse(quarkReply.contains("https://pan.quark.cn/s/owned-share"));
        ArgumentCaptor<ResourceDiscoveryRequest> request = ArgumentCaptor.forClass(ResourceDiscoveryRequest.class);
        verify(resourceDiscoveryService).enqueue(request.capture());
        assertEquals("权力的游戏 2011", request.getAllValues().get(0).getKeyword());
    }

    @Test
    void repliesWithHelpAndMentionsUserForUnknownAtMessage() throws Exception {
        qqBotProperties.setEnabled(true);
        JsonNode event = new ObjectMapper().readTree("""
                {
                  "post_type": "message",
                  "message_type": "group",
                  "group_id": 123,
                  "user_id": 456,
                  "self_id": 789,
                  "message": [
                    {"type": "at", "data": {"qq": "789"}},
                    {"type": "text", "data": {"text": " 不认识的指令"}}
                  ]
                }
                """);

        assertTrue(service.handleOneBotEvent(event));
        verify(napCatClient).sendGroupMessage(
                123L,
                456L,
                qqBotProperties.getDefaultReply());
    }

    @Test
    void sendsImmediatePleaseWaitMessageForMentionedSearch() throws Exception {
        qqBotProperties.setEnabled(true);
        when(movieService.list(any(QueryWrapper.class))).thenThrow(new IllegalStateException("test failure"));
        JsonNode event = new ObjectMapper().readTree("""
                {
                  "post_type": "message",
                  "message_type": "group",
                  "group_id": 123,
                  "user_id": 456,
                  "self_id": 789,
                  "message": [
                    {"type": "at", "data": {"qq": "789"}},
                    {"type": "text", "data": {"text": " 搜索蜘蛛侠英雄无归"}}
                  ]
                }
                """);

        assertTrue(service.handleOneBotEvent(event));
        verify(napCatClient, org.mockito.Mockito.timeout(1000))
                .sendGroupMessage(123L, 456L, "正在搜索资源，请稍后...");
    }

    @Test
    void ignoresUnknownGroupMessageThatDoesNotMentionBot() throws Exception {
        qqBotProperties.setEnabled(true);
        JsonNode event = new ObjectMapper().readTree("""
                {
                  "post_type": "message",
                  "message_type": "group",
                  "group_id": 123,
                  "user_id": 456,
                  "self_id": 789,
                  "message": "普通聊天"
                }
                """);

        assertFalse(service.handleOneBotEvent(event));
    }

    @Test
    void doesNotReturnOtherProvidersWithoutAValidOwnedQuarkShare() {
        MovieMetadata movie = movie("movie_404", "没有夸克的影片", 2024);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(resourceLinkService.list(any(QueryWrapper.class))).thenReturn(List.of());
        DiscoveredResource baidu = cloud(
                "BAIDU",
                "没有夸克的影片 1080P",
                "https://pan.baidu.com/s/should-not-send");
        when(panSouClient.searchClouds(anyString(), anySet(), anyInt())).thenReturn(List.of(baidu));

        String initial = service.buildSearchReply("没有夸克的影片", "no-quark-user");
        String requested = service.buildSearchReply("百度 1", "no-quark-user");

        assertTrue(initial.contains("多次搜索后仍未找到可供选择的夸克或迅雷资源"));
        assertTrue(requested.contains("暂不支持百度网盘服务"));
        assertFalse(initial.contains(baidu.getUrl()));
        assertFalse(requested.contains(baidu.getUrl()));
        verify(panSouClient, org.mockito.Mockito.never())
                .searchClouds(anyString(), anySet(), anyInt());
    }

    @Test
    void prefersUserConfirmedCompleteShareOverAutomaticShareWithSameTitle() {
        MovieMetadata eighthSeason = movie("season_8", "\u6743\u529b\u7684\u6e38\u620f \u7b2c\u516b\u5b63", 2019);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(eighthSeason));
        ResourceLink singleEpisode = link(
                "QUARK",
                "\u6743\u529b\u7684\u6e38\u620f \u51688\u5b63 4K 1TB",
                "https://pan.quark.cn/s/single-episode");
        singleEpisode.setSource("RESOURCE_HUB");
        ResourceLink completeSeries = link(
                "QUARK",
                "\u6743\u529b\u7684\u6e38\u620f \u51688\u5b63 4K 1TB",
                "https://pan.quark.cn/s/complete-user-share");
        completeSeries.setSource("RESOURCE_HUB");
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(singleEpisode, completeSeries));
        when(panSouClient.checkLink(completeSeries.getUrl()))
                .thenReturn(new LinkCheckResult(completeSeries.getUrl(), true, true, "ok"));

        String reply = service.buildSearchReply("\u6743\u529b\u7684\u6e38\u620f \u7b2c\u516b\u5b63", "season-eight-user");

        assertTrue(reply.contains(completeSeries.getName()));
        assertFalse(reply.contains(completeSeries.getUrl()));
        assertFalse(reply.contains(singleEpisode.getUrl()));
        verify(resourceDiscoveryService, org.mockito.Mockito.never()).enqueue(any());
    }

    @Test
    void reusesOwnedCompleteSeriesShareFromAnotherSeason() {
        MovieMetadata firstSeason = movie("season_1", "权力的游戏 第一季", 2011);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(firstSeason));
        ResourceLink completeSeries = link(
                "QUARK",
                "权力的游戏 全8季 4K",
                "https://pan.quark.cn/s/complete-series");
        completeSeries.setSource("RESOURCE_HUB");
        ResourceLink prequel = link(
                "QUARK",
                "权力的游戏前传：龙族 4K",
                "https://pan.quark.cn/s/unrelated-prequel");
        prequel.setSource("RESOURCE_HUB");
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(completeSeries));
        when(panSouClient.checkLink(completeSeries.getUrl()))
                .thenReturn(new LinkCheckResult(completeSeries.getUrl(), true, true, "ok"));

        String reply = service.buildSearchReply("权力的游戏 第一季", "series-user");

        assertTrue(reply.contains(completeSeries.getName()));
        assertFalse(reply.contains("https://pan.quark.cn/s/complete-series"));
        assertFalse(reply.contains("https://pan.quark.cn/s/unrelated-prequel"));
        verify(resourceDiscoveryService, org.mockito.Mockito.never()).enqueue(any());
    }

    @Test
    void keepsResourceCandidatesAfterInvalidShareSoUserCanChooseAnotherNumber() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("retry_selection", "候选重试测试", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(701L));
        ResourceDiscoveryRunResult run = emptyDiscovery(701L);
        run.setDiscovered(2);
        when(resourceDiscoveryService.runTask(701L)).thenReturn(run);

        ResourceDiscoveryResult invalid = discovery(
                801L, movie.getId(), "QUARK", "候选重试测试 4K 失效", "https://pan.quark.cn/s/invalid");
        ResourceDiscoveryResult valid = discovery(
                802L, movie.getId(), "XUNLEI", "候选重试测试 1080P 可用", "https://pan.xunlei.com/s/valid");
        when(discoveryResultService.list(any(QueryWrapper.class))).thenReturn(List.of(invalid, valid));
        when(discoveryResultService.getById(invalid.getId())).thenReturn(invalid);
        when(discoveryResultService.getById(valid.getId())).thenReturn(valid);

        QuarkTransferTask quarkTask = new QuarkTransferTask();
        quarkTask.setId(901L);
        quarkTask.setStatus("PENDING");
        when(quarkTransferTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(quarkTask);
        when(quarkTransferRunnerService.submitOne(quarkTask.getId()))
                .thenThrow(new IllegalStateException("read quark share directory failed"));

        XunleiTransferTask xunleiTask = new XunleiTransferTask();
        xunleiTask.setId(902L);
        xunleiTask.setStatus("PENDING");
        when(xunleiTransferTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(xunleiTask);
        when(xunleiTransferRunnerService.submitOne(xunleiTask.getId())).thenReturn(successfulTransfer());
        ResourceLink ready = link(
                "XUNLEI", "候选重试测试 1080P 可用", "https://pan.xunlei.com/s/valid-owned");
        valid.setResourceLinkId(1001L);
        when(resourceLinkService.getById(1001L)).thenReturn(ready);
        when(panSouClient.checkLink(ready.getUrl()))
                .thenReturn(new LinkCheckResult(ready.getUrl(), true, true, "ok"));

        String candidates = service.buildSearchReply("候选重试测试", "retry-selection-user");
        String invalidReply = service.buildSearchReply("1", "retry-selection-user");
        String selectedReply = service.buildSearchReply("2", "retry-selection-user");

        assertTrue(candidates.contains("1. 候选重试测试 4K 失效"));
        assertTrue(candidates.contains("2. 候选重试测试 1080P 可用"));
        assertTrue(invalidReply.contains("该分享已失效，不可访问"));
        assertTrue(invalidReply.contains("请继续回复其他资源序号"));
        assertTrue(selectedReply.contains("https://pan.xunlei.com/s/valid-owned"));
        verify(xunleiTransferRunnerService).submitOne(xunleiTask.getId());
    }

    @Test
    void returnsAtMostTenResourceChoicesWithQuarkPreferred() {
        resourceHubProperties.setEnabled(true);
        MovieMetadata movie = movie("ten_choices", "十条候选测试", 2026);
        when(movieService.list(any(QueryWrapper.class))).thenReturn(List.of(movie));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(702L));
        ResourceDiscoveryRunResult run = emptyDiscovery(702L);
        run.setDiscovered(12);
        when(resourceDiscoveryService.runTask(702L)).thenReturn(run);
        List<ResourceDiscoveryResult> discoveries = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            discoveries.add(discovery(810L + i, movie.getId(), "QUARK", "夸克候选 " + i,
                    "https://pan.quark.cn/s/q" + i));
        }
        for (int i = 1; i <= 4; i++) {
            discoveries.add(discovery(820L + i, movie.getId(), "XUNLEI", "迅雷候选 " + i,
                    "https://pan.xunlei.com/s/x" + i));
        }
        when(discoveryResultService.list(any(QueryWrapper.class))).thenReturn(discoveries);

        String reply = service.buildSearchReply("十条候选测试", "ten-choice-user");
        long numbered = reply.lines().filter(line -> line.matches("\\d+\\. .*" )).count();

        assertEquals(10, numbered);
        assertTrue(reply.indexOf("夸克候选 7") < reply.indexOf("迅雷候选 1"));
        assertTrue(reply.contains("迅雷候选 3"));
        assertFalse(reply.contains("夸克候选 8"));
        assertFalse(reply.contains("迅雷候选 4"));
    }

    private MovieSearchCandidate candidate(Long tmdbId, String title, int year, int score) {
        return new MovieSearchCandidate(tmdbId, "tv", title, null, year, score);
    }

    private MovieMetadata movie(String id, String title, int year) {
        MovieMetadata movie = new MovieMetadata();
        movie.setId(id);
        movie.setTitleCn(title);
        movie.setYear(year);
        movie.setStatus("ACTIVE");
        movie.setTmdbId(2L);
        return movie;
    }

    private DiscoveredResource cloud(String provider, String title, String url) {
        DiscoveredResource resource = new DiscoveredResource();
        resource.setProvider(provider);
        resource.setTitle(title);
        resource.setUrl(url);
        return resource;
    }

    private ResourceLink link(String provider, String name, String url) {
        ResourceLink link = new ResourceLink();
        link.setProvider(provider);
        link.setName(name);
        link.setUrl(url);
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setAuditStatus(1);
        return link;
    }

    private ResourceDiscoveryResult discovery(
            long id,
            String movieId,
            String provider,
            String title,
            String url) {
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(id);
        discovery.setMovieId(movieId);
        discovery.setProvider(provider);
        discovery.setTitle(title);
        discovery.setOriginalUrl(url);
        discovery.setOriginalUrlHash("hash-" + id);
        discovery.setStatus("DISCOVERED");
        return discovery;
    }

    private QuarkTransferRunResult successfulTransfer() {
        QuarkTransferRunResult result = new QuarkTransferRunResult();
        result.setSubmitted(1);
        return result;
    }

    private ResourceHubTask task(long id) {
        ResourceHubTask task = new ResourceHubTask();
        task.setId(id);
        return task;
    }

    private ResourceDiscoveryRunResult emptyDiscovery(long taskId) {
        ResourceDiscoveryRunResult result = new ResourceDiscoveryRunResult();
        result.setTaskId(taskId);
        result.setErrors(List.of());
        return result;
    }
}
