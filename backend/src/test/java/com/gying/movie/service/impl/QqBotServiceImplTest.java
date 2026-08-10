package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.ResourceLink;
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
                mock(IQuarkTransferRunnerService.class),
                mock(IResourceHubPublishService.class),
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
        when(gyingSourceWorkflowService.ensureMovieResource("tv", "test-mid"))
                .thenReturn(java.util.Map.of(
                        "status", "PUBLISHED",
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
        verify(gyingSourceWorkflowService, org.mockito.Mockito.never())
                .ensureMovieResource(anyString(), anyString());

        String selected = service.buildSearchReply("1", "gying-candidate-user");

        assertTrue(selected.contains("片名：伦敦生活"));
        assertTrue(selected.contains(published.getUrl()));
        verify(gyingSourceWorkflowService).ensureMovieResource("tv", "test-mid");
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
        verify(resourceDiscoveryService, org.mockito.Mockito.times(2)).enqueue(requests.capture());
        assertEquals(
                List.of("福尔摩斯：基本演绎法 2012", "福尔摩斯：基本演绎法"),
                requests.getAllValues().stream().map(ResourceDiscoveryRequest::getKeyword).toList());
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
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(), List.of(), List.of(published));
        when(panSouClient.checkLink(published.getUrl()))
                .thenReturn(new LinkCheckResult(published.getUrl(), true, true, "ok"));

        String reply = service.buildSearchReply("蜘蛛侠英雄无归", "gying-first-user");

        assertTrue(reply.contains("https://pan.quark.cn/s/gying-share"));
        verify(gyingSourceWorkflowService).ensureLocalMovieResource(movie.getId());
        verify(resourceDiscoveryService, org.mockito.Mockito.never()).enqueue(any());
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

        verify(gyingSourceWorkflowService).ensureLocalMovieResource(movie.getId());
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
    void includesOwnedQuarkWhenUserRequestsAnotherProvider() {
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
        DiscoveredResource baidu = new DiscoveredResource();
        baidu.setTitle("哈哈哈哈哈 第二季 (2021) 1080P");
        baidu.setProvider("BAIDU");
        baidu.setUrl("https://pan.baidu.com/s/fallback?pwd=7d32");
        baidu.setCode("7d32");
        when(panSouClient.searchClouds(
                "哈哈哈哈哈 2020",
                Set.of("BAIDU"),
                30)).thenReturn(List.of(baidu));
        when(panSouClient.checkLink(baidu))
                .thenReturn(new LinkCheckResult(baidu.getUrl(), true, true, "ok"));

        service.buildSearchReply("哈哈哈哈哈", "user-3");
        String reply = service.buildSearchReply("百度 1", "user-3");

        assertTrue(reply.contains("已附带 1 条夸克分享，并按百度返回 1 条"));
        assertTrue(reply.contains("https://pan.quark.cn/s/owned-share"));
        assertTrue(reply.contains("BAIDU - 哈哈哈哈哈 第二季"));
        assertTrue(reply.contains("https://pan.baidu.com/s/fallback?pwd=7d32"));
        assertTrue(reply.contains("提取码：7d32"));
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

        assertTrue(reply.contains(published.getUrl()));
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

        assertTrue(reply.contains(published.getUrl()));
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

        assertTrue(reply.contains(published.getUrl()));
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
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(ownedQuark), List.of(ownedQuark), List.of());
        when(panSouClient.checkLink(ownedQuark.getUrl()))
                .thenReturn(new LinkCheckResult(ownedQuark.getUrl(), true, true, "ok"));
        when(panSouClient.searchClouds(anyString(), anySet(), anyInt())).thenReturn(List.of());
        service.buildSearchReply("哈哈哈哈哈", "preference-user");

        DiscoveredResource first = cloud("BAIDU", "哈哈哈哈哈 第一季", "https://pan.baidu.com/s/one");
        DiscoveredResource second = cloud("BAIDU", "哈哈哈哈哈 第二季", "https://pan.baidu.com/s/two");
        DiscoveredResource third = cloud("BAIDU", "哈哈哈哈哈 第三季", "https://pan.baidu.com/s/three");
        when(panSouClient.searchClouds(anyString(), eq(Set.of("BAIDU")), anyInt()))
                .thenReturn(List.of(first, second, third));
        when(panSouClient.checkLink(any(DiscoveredResource.class)))
                .thenReturn(new LinkCheckResult("test", false, false, "not checked"));

        String reply = service.buildSearchReply("百度 2", "preference-user");

        assertTrue(reply.contains("已附带 1 条夸克分享，并按百度返回 2 条"));
        assertTrue(reply.contains("https://pan.quark.cn/s/context-share"));
        assertTrue(reply.contains("https://pan.baidu.com/s/one"));
        assertTrue(reply.contains("https://pan.baidu.com/s/two"));
        assertTrue(!reply.contains("https://pan.baidu.com/s/three"));
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
                .thenReturn(List.of(libraryBaidu, ownedQuark));
        when(panSouClient.checkLink(ownedQuark.getUrl()))
                .thenReturn(new LinkCheckResult(ownedQuark.getUrl(), true, true, "ok"));
        when(resourceDiscoveryService.enqueue(any())).thenReturn(task(21L));
        ResourceDiscoveryRunResult discovered = emptyDiscovery(21L);
        discovered.setDiscovered(1);
        when(resourceDiscoveryService.runTask(21L)).thenReturn(discovered);

        String initialReply = service.buildSearchReply("权力的游戏", "library-user");
        String quarkReply = service.buildSearchReply("夸克 2", "library-user");

        assertTrue(initialReply.contains("https://pan.quark.cn/s/owned-share"));
        assertFalse(initialReply.contains("https://pan.baidu.com/s/library"));
        assertTrue(quarkReply.contains("已按夸克返回 1 条"));
        assertTrue(quarkReply.contains("https://pan.quark.cn/s/owned-share"));
        ArgumentCaptor<ResourceDiscoveryRequest> request = ArgumentCaptor.forClass(ResourceDiscoveryRequest.class);
        verify(resourceDiscoveryService).enqueue(request.capture());
        assertEquals("权力的游戏 2011", request.getValue().getKeyword());
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
                .sendGroupMessage(123L, 456L, "\u8bf7\u7a0d\u540e..");
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

        assertTrue(initial.contains("本次未返回第三方网盘"));
        assertTrue(requested.contains("本次未返回第三方网盘"));
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
        completeSeries.setSource("USER");
        completeSeries.setUploaderId(1L);
        when(resourceLinkService.list(any(QueryWrapper.class)))
                .thenReturn(List.of(singleEpisode, completeSeries));
        when(panSouClient.checkLink(completeSeries.getUrl()))
                .thenReturn(new LinkCheckResult(completeSeries.getUrl(), true, true, "ok"));

        String reply = service.buildSearchReply("\u6743\u529b\u7684\u6e38\u620f \u7b2c\u516b\u5b63", "season-eight-user");

        assertTrue(reply.contains(completeSeries.getUrl()));
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
                .thenReturn(List.of(), List.of(prequel, completeSeries));
        when(panSouClient.checkLink(completeSeries.getUrl()))
                .thenReturn(new LinkCheckResult(completeSeries.getUrl(), true, true, "ok"));

        String reply = service.buildSearchReply("权力的游戏 第一季", "series-user");

        assertTrue(reply.contains("https://pan.quark.cn/s/complete-series"));
        assertFalse(reply.contains("https://pan.quark.cn/s/unrelated-prequel"));
        verify(resourceDiscoveryService, org.mockito.Mockito.never()).enqueue(any());
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
