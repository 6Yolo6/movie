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
    private ITmdbMetadataSyncService tmdbMetadataSyncService;
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
        tmdbMetadataSyncService = mock(ITmdbMetadataSyncService.class);
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
                mock(IResourceDiscoveryResultService.class),
                mock(IQuarkShareService.class),
                mock(IQuarkTransferTaskService.class),
                mock(IQuarkTransferRunnerService.class),
                mock(IResourceHubPublishService.class),
                tmdbMetadataSyncService,
                mock(IQqBotSearchLogService.class));
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

        assertTrue(reply.contains("已附带 1 条自有夸克分享，并按百度返回 1 条"));
        assertTrue(reply.contains("https://pan.quark.cn/s/owned-share"));
        assertTrue(reply.contains("BAIDU - 哈哈哈哈哈 第二季"));
        assertTrue(reply.contains("https://pan.baidu.com/s/fallback?pwd=7d32"));
        assertTrue(reply.contains("提取码：7d32"));
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

        assertTrue(reply.contains("已附带 1 条自有夸克分享，并按百度返回 2 条"));
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
