package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.GyingSourceClient;
import com.gying.movie.client.PanSouClient;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.dto.DiscoveredResource;
import com.gying.movie.dto.MovieSearchCandidate;
import com.gying.movie.dto.TmdbListItem;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IMovieSourceIdentityService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IXunleiTransferTaskService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GyingSourceWorkflowServiceTest {
    private GyingSourceClient gyingSourceClient;
    private TmdbClient tmdbClient;
    private PosterStorageService posterStorageService;
    private PanSouClient panSouClient;
    private IMovieMetadataService movieService;
    private IMovieSourceIdentityService sourceIdentityService;
    private IResourceLinkService resourceLinkService;
    private IResourceDiscoveryResultService discoveryService;
    private IQuarkTransferTaskService transferTaskService;
    private IQuarkTransferRunnerService transferRunnerService;
    private IResourceHubPublishService publishService;
    private IXunleiTransferTaskService xunleiTransferTaskService;
    private IXunleiTransferRunnerService xunleiTransferRunnerService;
    private GyingSourceWorkflowService service;

    @BeforeEach
    void setUp() {
        gyingSourceClient = mock(GyingSourceClient.class);
        tmdbClient = mock(TmdbClient.class);
        posterStorageService = mock(PosterStorageService.class);
        panSouClient = mock(PanSouClient.class);
        movieService = mock(IMovieMetadataService.class);
        sourceIdentityService = mock(IMovieSourceIdentityService.class);
        resourceLinkService = mock(IResourceLinkService.class);
        discoveryService = mock(IResourceDiscoveryResultService.class);
        transferTaskService = mock(IQuarkTransferTaskService.class);
        transferRunnerService = mock(IQuarkTransferRunnerService.class);
        publishService = mock(IResourceHubPublishService.class);
        IQuarkShareService shareService = mock(IQuarkShareService.class);
        xunleiTransferTaskService = mock(IXunleiTransferTaskService.class);
        xunleiTransferRunnerService = mock(IXunleiTransferRunnerService.class);
        service = new GyingSourceWorkflowService(
                gyingSourceClient,
                tmdbClient,
                posterStorageService,
                panSouClient,
                movieService,
                sourceIdentityService,
                resourceLinkService,
                discoveryService,
                transferTaskService,
                transferRunnerService,
                publishService,
                shareService,
                xunleiTransferTaskService,
                xunleiTransferRunnerService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUARK", "XUNLEI"})
    void skipsPublishingWhenMovieDetailsAlreadyContainTheOwnedResource(String provider) {
        ResourceLink resource = new ResourceLink();
        resource.setId(42L);
        resource.setMovieId("local-movie");
        resource.setType("DISK");
        resource.setProvider(provider);
        resource.setUrl("QUARK".equals(provider)
                ? "https://pan.quark.cn/s/owned" : "https://pan.xunlei.com/s/owned");
        MovieSourceIdentity identity = new MovieSourceIdentity();
        identity.setSourceType("mv");
        identity.setExternalId("SITE1");
        when(sourceIdentityService.getOne(any(Wrapper.class), eq(false))).thenReturn(identity);
        when(gyingSourceClient.get("/movie/mv/SITE1")).thenReturn(Map.of(
                "ownResources", List.of(Map.of("source_id", "OWNED1", "url", resource.getUrl()))));

        assertTrue(service.publishResourceToGying(resource));

        verify(gyingSourceClient).get("/movie/mv/SITE1");
        verifyNoMoreInteractions(gyingSourceClient);
    }

    @Test
    void ensureMovieMetadataDoesNotImportOrTransferDefaultResources() {
        MovieMetadata movie = movie("META1", "元数据候选", "mv", "UNKNOWN");
        when(gyingSourceClient.get("/movie/mv/META1")).thenReturn(Map.of(
                "title", movie.getTitleCn(),
                "year", 2026,
                "resources", List.of(Map.of(
                        "source_id", "SOURCE1",
                        "provider", "QUARK",
                        "title", "元数据候选 720P",
                        "url", "https://pan.quark.cn/s/source1")),
                "ownResources", List.of()));
        when(movieService.getById(movie.getId())).thenReturn(movie);

        Map<String, Object> result = service.ensureMovieMetadata("mv", "META1");

        assertEquals("METADATA_READY", result.get("status"));
        assertEquals(movie.getId(), result.get("localMovieId"));
        assertEquals(1, result.get("siteResourceCount"));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(gyingSourceClient).post(eq("/ingest"), payload.capture());
        assertEquals(false, payload.getValue().get("includeResources"));
        verify(transferRunnerService, never()).submitOne(any());
        verify(resourceLinkService, never()).save(any());
    }

    @Test
    void ensureMoviePublishesExistingLocalResourceWithoutTransfer() {
        MovieMetadata movie = movie("EGER", "后室", "mv", "TRAILER");
        ResourceLink local = new ResourceLink();
        local.setId(42L);
        local.setMovieId(movie.getId());
        local.setName("后室 4K HDR 夸克网盘");
        local.setType("DISK");
        local.setProvider("QUARK");
        local.setUrl("https://pan.quark.cn/s/local");
        local.setCode("");
        local.setStatus("ACTIVE");
        local.setAuditStatus(1);

        when(gyingSourceClient.get("/movie/mv/EGER"))
                .thenReturn(Map.of(
                        "title", "后室",
                        "resources", List.of(),
                        "ownResources", List.of()))
                .thenReturn(Map.of(
                        "title", "后室",
                        "resources", List.of(),
                        "ownResources", List.of(Map.of(
                                "source_id", "NEW01",
                                "url", local.getUrl()))));
        when(gyingSourceClient.post(eq("/ingest"), any())).thenReturn(Map.of("movieId", "EGER"));
        when(movieService.getById("EGER")).thenReturn(movie);
        when(resourceLinkService.getOne(any(Wrapper.class), eq(false))).thenReturn(local);
        when(gyingSourceClient.post(eq("/publish"), any())).thenReturn(Map.of(
                "action", "published",
                "sourceId", "NEW01",
                "site", Map.of("code", 200)));

        Map<String, Object> result = service.ensureMovieResource("mv", "EGER");

        assertEquals("PUBLISHED", result.get("status"));
        assertEquals(42L, result.get("resourceId"));
        assertEquals(false, result.get("transferMode"));
        verify(transferRunnerService, never()).submitOne(any());
    }

    @Test
    void ensureMoviePublishesBothQuarkAndXunleiProviders() {
        MovieMetadata movie = movie("XL1", "测试剧集", "tv", "TRAILER");
        String quarkUrl = "https://pan.quark.cn/s/public-quark";
        String xunleiUrl = "https://pan.xunlei.com/s/public-xunlei?pwd=abcd";
        when(gyingSourceClient.get("/movie/tv/XL1"))
                .thenReturn(Map.of(
                        "title", movie.getTitleCn(),
                        "resources", List.of(
                                Map.of("source_id", "Q1", "provider", "QUARK", "title", "夸克", "url", quarkUrl),
                                Map.of("source_id", "X1", "provider", "XUNLEI", "title", "迅雷", "url", xunleiUrl)),
                        "ownResources", List.of()))
                .thenReturn(Map.of(
                        "title", movie.getTitleCn(),
                        "resources", List.of(),
                        "ownResources", List.of(Map.of("source_id", "OWN-Q1", "url", quarkUrl, "provider", "QUARK"))))
                .thenReturn(Map.of(
                        "title", movie.getTitleCn(),
                        "resources", List.of(
                                Map.of("source_id", "Q1", "provider", "QUARK", "title", "夸克", "url", quarkUrl),
                                Map.of("source_id", "X1", "provider", "XUNLEI", "title", "迅雷", "url", xunleiUrl)),
                        "ownResources", List.of(Map.of("source_id", "OWN-Q1", "url", quarkUrl, "provider", "QUARK"))))
                .thenReturn(Map.of(
                        "title", movie.getTitleCn(),
                        "resources", List.of(),
                        "ownResources", List.of(
                                Map.of("source_id", "OWN-Q1", "url", quarkUrl, "provider", "QUARK"),
                                Map.of("source_id", "OWN-X1", "url", "https://pan.xunlei.com/s/own", "provider", "XUNLEI"))));
        when(gyingSourceClient.post(eq("/ingest"), any())).thenReturn(Map.of("movieId", movie.getId()));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        ResourceLink localQuark = new ResourceLink();
        localQuark.setId(302L);
        localQuark.setMovieId(movie.getId());
        localQuark.setProvider("QUARK");
        localQuark.setUrl(quarkUrl);
        when(resourceLinkService.getOne(any(Wrapper.class), eq(false)))
                .thenReturn(localQuark)
                .thenReturn((ResourceLink) null);
        when(panSouClient.checkLinksByProvider(any())).thenReturn(Map.of());

        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(101L);
        when(discoveryService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
        when(discoveryService.save(any())).thenAnswer(invocation -> {
            ((ResourceDiscoveryResult) invocation.getArgument(0)).setId(discovery.getId());
            return true;
        });
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(202L);
        AtomicReference<XunleiTransferTask> taskRef = new AtomicReference<>();
        when(xunleiTransferTaskService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
        when(xunleiTransferTaskService.save(any())).thenAnswer(invocation -> {
            XunleiTransferTask saved = invocation.getArgument(0);
            saved.setId(task.getId());
            taskRef.set(saved);
            return true;
        });
        when(xunleiTransferTaskService.getById(task.getId())).thenAnswer(invocation -> taskRef.get());
        when(xunleiTransferRunnerService.submitOne(task.getId())).thenAnswer(invocation -> {
            taskRef.get().setShareUrl("https://pan.xunlei.com/s/own");
            return new QuarkTransferRunResult();
        });
        ResourceHubPublishResult publish = new ResourceHubPublishResult();
        publish.getResourceIds().add(303L);
        when(publishService.publishDiscovery(discovery.getId())).thenReturn(publish);
        ResourceLink localXunlei = new ResourceLink();
        localXunlei.setId(303L);
        localXunlei.setMovieId(movie.getId());
        localXunlei.setProvider("XUNLEI");
        localXunlei.setUrl("https://pan.xunlei.com/s/own");
        when(resourceLinkService.getById(303L)).thenReturn(localXunlei);
        when(gyingSourceClient.post(eq("/publish"), any()))
                .thenReturn(Map.of("sourceId", "OWN-Q1"), Map.of("sourceId", "OWN-X1"));

        Map<String, Object> result = service.ensureMovieResource("tv", "XL1");

        assertEquals("PUBLISHED", result.get("status"));
        assertEquals(1, result.get("providersPublished"));
        verify(xunleiTransferRunnerService).submitOne(task.getId());
        verify(transferRunnerService, never()).submitOne(any());
        verify(gyingSourceClient, times(2)).post(eq("/publish"), any());
    }

    @Test
    void ensureMovieRoutesInvalidPublishedResourceToHealthRepairWithoutRepublishing() {
        MovieMetadata movie = movie("dlvj", "气体人第一号", "tv", "AVAILABLE");
        String url = "https://pan.quark.cn/s/dead";
        when(gyingSourceClient.get("/movie/tv/dlvj")).thenReturn(Map.of(
                "title", "气体人第一号",
                "resources", List.of(),
                "ownResources", List.of(Map.of(
                        "source_id", "SITE-DEAD",
                        "url", url,
                        "provider", "QUARK"))));
        when(gyingSourceClient.post(eq("/ingest"), any())).thenReturn(Map.of("movieId", movie.getId()));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(panSouClient.checkLinksByProvider(Map.of(url, "QUARK"))).thenReturn(Map.of(
                url, new LinkCheckResult(url, true, false, "invalid")));

        Map<String, Object> result = service.ensureWeeklyMovieResource("tv", "dlvj");

        assertEquals("ALREADY_PUBLISHED_NEEDS_REPAIR", result.get("status"));
        assertEquals(true, result.get("repairRequired"));
        assertEquals("SITE-DEAD", result.get("sourceId"));
        verify(gyingSourceClient, never()).post(eq("/publish"), any());
    }

    @Test
    void searchCandidatesExcludesGyingMoviesWithoutCloudResources() {
        when(gyingSourceClient.get(eq("/search"), any())).thenReturn(Map.of(
                "items", List.of(
                        Map.of("typeCode", "mv", "mid", "with-link", "title", "有资源影片", "year", 2026),
                        Map.of("typeCode", "mv", "mid", "without-link", "title", "空资源影片", "year", 2026))));
        when(gyingSourceClient.get("/movie/mv/with-link")).thenReturn(Map.of(
                "resources", List.of(Map.of(
                        "provider", "QUARK",
                        "url", "https://pan.quark.cn/s/owned"))));
        when(gyingSourceClient.get("/movie/mv/without-link")).thenReturn(Map.of(
                "resources", List.of()));
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of());

        List<MovieSearchCandidate> candidates = service.searchCandidates("影片", 10);

        assertEquals(List.of("with-link"), candidates.stream()
                .map(MovieSearchCandidate::getSourceId)
                .toList());
    }

    @Test
    void ensureMovieUsesCanonicalLocalMovieButPublishesToSiteMovieId() {
        MovieMetadata canonical = movie("tmdb_movie_1368314", "鬼上车", "mv", "TRAILER");
        canonical.setYear(2026);
        ResourceLink local = new ResourceLink();
        local.setId(77L);
        local.setMovieId(canonical.getId());
        local.setName("鬼上车 4K HDR");
        local.setType("DISK");
        local.setProvider("QUARK");
        local.setUrl("https://pan.quark.cn/s/ghost");
        local.setStatus("ACTIVE");
        local.setAuditStatus(1);

        when(gyingSourceClient.get("/movie/mv/x01w"))
                .thenReturn(Map.of(
                        "title", "鬼上车",
                        "year", 2026,
                        "resources", List.of(),
                        "ownResources", List.of()))
                .thenReturn(Map.of(
                        "title", "鬼上车",
                        "year", 2026,
                        "resources", List.of(),
                        "ownResources", List.of(Map.of(
                                "source_id", "GHOST1",
                                "url", local.getUrl()))));
        when(movieService.getById("x01w")).thenReturn(null);
        when(movieService.getById(canonical.getId())).thenReturn(canonical);
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of(canonical));
        when(resourceLinkService.getOne(any(Wrapper.class), eq(false))).thenReturn(local);
        when(gyingSourceClient.post(eq("/publish"), any())).thenReturn(Map.of(
                "sourceId", "GHOST1",
                "site", Map.of("code", 200)));

        Map<String, Object> result = service.ensureMovieResource("mv", "x01w");

        assertEquals("PUBLISHED", result.get("status"));
        assertEquals(canonical.getId(), result.get("localMovieId"));
        ArgumentCaptor<Map<String, Object>> ingestPayload = ArgumentCaptor.forClass(Map.class);
        verify(gyingSourceClient).post(eq("/ingest"), ingestPayload.capture());
        assertEquals(canonical.getId(), ingestPayload.getValue().get("targetMovieId"));
        ArgumentCaptor<Map<String, Object>> publishPayload = ArgumentCaptor.forClass(Map.class);
        verify(gyingSourceClient).post(eq("/publish"), publishPayload.capture());
        assertEquals("x01w", publishPayload.getValue().get("mid"));
    }

    @Test
    void ensureRemainingSeasonsDiscoversAndSavesGyingIdentity() {
        MovieMetadata movie = movie("tmdb_tv_100", "示例剧", "tv", "TRAILER");
        movie.setYear(2024);
        movie.setSeason(1);
        movie.setTmdbId(100L);
        movie.setTmdbType("tv");

        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(movieService.getById("GY100")).thenReturn(null);
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of(movie));
        when(gyingSourceClient.get("/catalog?typeCode=tv&sort=score&page=1&limit=60"))
                .thenReturn(Map.of("items", List.of(Map.of(
                        "typeCode", "tv",
                        "mid", "GY100",
                        "title", "示例剧",
                        "year", 2024,
                        "season", 1))));
        when(gyingSourceClient.get("/series?typeCode=tv&mid=GY100&maxPages=2"))
                .thenReturn(Map.of("items", List.of()));

        Map<String, Object> result = service.ensureRemainingSeasons(movie.getId(), 2);

        assertEquals(1, result.get("discovered"));
        ArgumentCaptor<MovieSourceIdentity> identityCaptor = ArgumentCaptor.forClass(MovieSourceIdentity.class);
        verify(sourceIdentityService, times(2)).save(identityCaptor.capture());
        MovieSourceIdentity gyingIdentity = identityCaptor.getAllValues().stream()
                .filter(identity -> "GYING".equals(identity.getSource()))
                .findFirst()
                .orElseThrow();
        assertEquals(movie.getId(), gyingIdentity.getMovieId());
        assertEquals("GY100", gyingIdentity.getExternalId());
        assertEquals("AUTO", gyingIdentity.getMatchStatus());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(gyingSourceClient).post(eq("/ingest"), payload.capture());
        assertEquals(false, payload.getValue().get("includeResources"));
    }

    @Test
    void repairMissingPostersFallsBackToTmdbWithoutGyingIdentity() throws Exception {
        MovieMetadata movie = movie("tmdb_movie_200", "海报测试", "mv", "AVAILABLE");
        movie.setTmdbId(200L);
        movie.setTmdbType("movie");
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of(movie));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(tmdbClient.fetchDetails("movie", 200L))
                .thenReturn(new ObjectMapper().readTree("{\"id\":200,\"poster_path\":\"/poster.jpg\"}"));
        when(posterStorageService.storeTmdbPoster("movie", 200L, "/poster.jpg"))
                .thenReturn("tmdb/movie/200/poster.jpg");

        Map<String, Object> result = service.repairMissingPosters(10);

        assertEquals(1, result.get("repaired"));
        assertEquals("tmdb/movie/200/poster.jpg", movie.getPosterUrl());
        verify(movieService).updateById(movie);
        verify(gyingSourceClient, never()).post(eq("/poster"), any());
    }

    @Test
    void repairMoviePosterDoesNotReportSuccessWhenGyingReturnsNoPoster() {
        MovieMetadata movie = movie("gying_mv_vPW8", "钢铁侠", "mv", "AVAILABLE");
        MovieSourceIdentity identity = new MovieSourceIdentity();
        identity.setMovieId(movie.getId());
        identity.setSource("GYING");
        identity.setSourceType("mv");
        identity.setExternalId("vPW8");
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(sourceIdentityService.getOne(any(Wrapper.class), eq(false))).thenReturn(identity);
        when(gyingSourceClient.post(eq("/poster"), any()))
                .thenReturn(Map.of("status", "FAILED", "reason", "image unavailable"));

        Map<String, Object> result = service.repairMoviePoster(movie.getId());

        assertEquals("SKIPPED", result.get("status"));
        assertTrue(String.valueOf(result.get("reason")).contains("保留原图"));
        verify(movieService, never()).updateById(any());
    }

    @Test
    void ensureLocalMovieUsesStrictSearchMatchBeforeCatalogFallback() {
        MovieMetadata movie = movie("tmdb_movie_1275779", "揭秘日", "mv", "TRAILER");
        movie.setTitleEn("Disclosure Day");
        movie.setYear(2026);
        movie.setSeason(1);
        movie.setTmdbId(1275779L);
        movie.setTmdbType("movie");
        movie.setDirectors(List.of("史蒂文·斯皮尔伯格"));
        movie.setActors(List.of("艾米莉·布朗特"));

        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(movieService.getById("0pEK")).thenReturn(null);
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of(movie));
        when(gyingSourceClient.get("/recent?limit=100"))
                .thenReturn(Map.of("items", List.of()));
        when(gyingSourceClient.get("/search", Map.of(
                "q", "揭秘日", "typeCode", "mv", "mode", 3, "limit", 20)))
                .thenReturn(Map.of("items", List.of(
                        Map.of(
                                "typeCode", "mv",
                                "mid", "0pEK",
                                "title", "揭秘日",
                                "titleEn", "Disclosure Day",
                                "year", 2026,
                                "directors", List.of("史蒂文·斯皮尔伯格"),
                                "actors", List.of("艾米莉·布朗特")),
                        Map.of(
                                "typeCode", "mv",
                                "mid", "xR48",
                                "title", "临近的揭秘日",
                                "titleEn", "The Day Before Disclosure",
                                "year", 2010,
                                "directors", List.of("Terje Toftenes"),
                                "actors", List.of("Edgar D. Mitchell")))));
        when(gyingSourceClient.get("/movie/mv/0pEK"))
                .thenReturn(Map.of(
                        "title", "揭秘日",
                        "titleEn", "Disclosure Day",
                        "year", 2026,
                        "directors", List.of("史蒂文·斯皮尔伯格"),
                        "actors", List.of("艾米莉·布朗特"),
                        "resources", List.of(),
                        "ownResources", List.of(Map.of(
                                "source_id", "OWN01",
                                "url", "https://pan.quark.cn/s/own"))));
        when(gyingSourceClient.post(eq("/ingest"), any()))
                .thenReturn(Map.of("movieId", movie.getId()));
        when(panSouClient.checkLinksByProvider(any()))
                .thenReturn(Map.of("https://pan.quark.cn/s/own",
                        new LinkCheckResult("https://pan.quark.cn/s/own", true, true, "ok")));

        Map<String, Object> result = service.ensureLocalMovieResource(movie.getId());

        assertEquals("ALREADY_PUBLISHED", result.get("status"));
        assertEquals("0pEK", result.get("mid"));
        ArgumentCaptor<MovieSourceIdentity> identities =
                ArgumentCaptor.forClass(MovieSourceIdentity.class);
        verify(sourceIdentityService, times(4)).save(identities.capture());
        MovieSourceIdentity searchIdentity = identities.getAllValues().stream()
                .filter(identity -> "STRICT_SEARCH_METADATA".equals(identity.getMatchMethod()))
                .findFirst()
                .orElseThrow();
        assertEquals("0pEK", searchIdentity.getExternalId());
        assertEquals(0, searchIdentity.getSeason());
        verify(gyingSourceClient, never())
                .get("/catalog?typeCode=mv&sort=score&page=1&limit=60");
    }

    @Test
    void discoversStrictGyingQuarkResourcesBeforePanSou() {
        MovieMetadata movie = movie("tmdb_tv_1431", "犯罪现场调查", "tv", "TRAILER");
        movie.setSeason(1);
        MovieSourceIdentity identity = new MovieSourceIdentity();
        identity.setMovieId(movie.getId());
        identity.setSource("GYING");
        identity.setSourceType("tv");
        identity.setExternalId("E9xe");
        when(sourceIdentityService.getOne(any(Wrapper.class), eq(false))).thenReturn(identity);
        when(gyingSourceClient.get("/movie/tv/E9xe")).thenReturn(Map.of(
                "title", "犯罪现场调查 第一季",
                "resources", List.of(Map.of(
                        "title", "犯罪现场调查 全15季 1080P",
                        "provider", "QUARK",
                        "url", "https://pan.quark.cn/s/csi",
                        "source_id", "DRKXX"))));

        List<DiscoveredResource> resources = service.discoverResources(movie, 5);

        assertEquals(1, resources.size());
        assertEquals("GYING", resources.get(0).getSource());
        assertEquals("DRKXX", resources.get(0).getSourceRef());
    }

    @Test
    void automaticCatalogBatchReadsFullPageButOnlyIngestsRemainingItems() {
        MovieMetadata saved = movie("gying_mv_NEW2", "续采电影", "mv", "UNKNOWN");
        when(gyingSourceClient.get("/catalog?typeCode=mv&sort=hits&page=3&limit=100"))
                .thenReturn(Map.of("items", List.of(
                        Map.of("mid", "DONE1", "title", "已处理电影", "year", 2026),
                        Map.of("mid", "NEW2", "title", "续采电影", "year", 2026))));
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of());
        when(movieService.getById("gying_mv_NEW2")).thenReturn(saved);
        when(gyingSourceClient.post(eq("/ingest"), any())).thenReturn(Map.of("movieId", saved.getId()));
        Map<String, Object> result = service.syncCatalogMetadata("HITS_MOVIE", 3, 1, 1);
        assertEquals(2, result.get("pageSize"));
        assertEquals(1, result.get("processed"));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(gyingSourceClient).post(eq("/ingest"), payload.capture());
        assertEquals("NEW2", payload.getValue().get("mid"));
        assertEquals(false, payload.getValue().get("includeResources"));
    }

    @Test
    void syncCatalogMetadataDoesNotImportThirdPartyResources() {
        MovieMetadata saved = movie("gying_mv_NEW1", "目录电影", "mv", "UNKNOWN");
        when(gyingSourceClient.get("/catalog?typeCode=mv&sort=hits&page=1&limit=10"))
                .thenReturn(Map.of("items", List.of(Map.of(
                        "typeCode", "mv",
                        "mid", "NEW1",
                        "title", "目录电影",
                        "year", 2026))));
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of());
        when(movieService.getById("gying_mv_NEW1")).thenReturn(saved);
        when(gyingSourceClient.post(eq("/ingest"), any())).thenReturn(Map.of("movieId", saved.getId()));

        when(gyingSourceClient.get("/resources/mv/NEW1")).thenReturn(Map.of("resources", List.of(
                Map.of("type", "MAGNET", "provider", "P2P", "url", "magnet:?xt=urn:btih:fixture", "title", "磁力"),
                Map.of("type", "TORRENT", "provider", "P2P", "url", "https://example.invalid/test.torrent", "title", "种子"),
                Map.of("type", "DISK", "provider", "QUARK", "url", "https://pan.quark.cn/s/ignored"))));
        when(resourceLinkService.save(any(ResourceLink.class))).thenReturn(true);
        Map<String, Object> result = service.syncCatalogMetadata("HITS_MOVIE", 1, 10);

        assertEquals(1, result.get("inserted"));
        assertEquals(2, result.get("directResourceLinks"));
        ArgumentCaptor<ResourceLink> links = ArgumentCaptor.forClass(ResourceLink.class);
        verify(resourceLinkService, times(2)).save(links.capture());
        assertEquals(List.of("MAGNET", "TORRENT"), links.getAllValues().stream().map(ResourceLink::getType).toList());
        verifyNoInteractions(transferTaskService, transferRunnerService, xunleiTransferTaskService, xunleiTransferRunnerService, publishService);
        assertEquals(List.of(saved.getId()), result.get("movieIds"));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(gyingSourceClient).post(eq("/ingest"), payload.capture());
        assertEquals(false, payload.getValue().get("includeResources"));
        assertEquals("gying_mv_NEW1", payload.getValue().get("targetMovieId"));
    }


    @Test
    void rotatingTorrentTicketUpdatesTheSameSourceResource() {
        String oldUrl = "https://example.invalid/dbt/BT1/old-ticket";
        String newUrl = "https://example.invalid/dbt/BT1/new-ticket";
        stubP2pCatalog(List.of(Map.of("type", "TORRENT", "provider", "P2P", "url", oldUrl,
                "source_ref", "BT1", "title", "目录电影 1080p")));
        ResourceLink stored = new ResourceLink();
        when(resourceLinkService.getOne(any(Wrapper.class), eq(false)))
                .thenReturn(null, null, null, stored);
        when(resourceLinkService.save(any(ResourceLink.class))).thenAnswer(call -> {
            ResourceLink link = call.getArgument(0);
            stored.setId(91L); stored.setMovieId(link.getMovieId()); stored.setCreatedAt(link.getCreatedAt());
            return true;
        });
        when(resourceLinkService.updateById(stored)).thenReturn(true);
        assertEquals(1, service.syncCatalogMetadata("HITS_MOVIE", 1, 10).get("directResourceLinks"));
        var createdAt = stored.getCreatedAt();
        when(gyingSourceClient.get("/resources/mv/NEW1")).thenReturn(Map.of("resources", List.of(
                Map.of("type", "TORRENT", "provider", "P2P", "url", newUrl,
                        "source_ref", "BT1", "title", "目录电影 4K"))));
        assertEquals(1, service.syncCatalogMetadata("HITS_MOVIE", 1, 10).get("directResourceLinks"));
        assertEquals(91L, stored.getId());
        assertEquals(newUrl, stored.getUrl());
        assertEquals(com.gying.movie.utils.ResourceHubHashUtils.sha256(newUrl), stored.getUrlHash());
        assertEquals(createdAt, stored.getCreatedAt());
        assertEquals("BT1", stored.getSourceRef());
        verify(resourceLinkService).save(any(ResourceLink.class));
        verify(resourceLinkService).updateById(stored);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ResourceLink>> queries =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(resourceLinkService, times(4)).getOne(queries.capture(), eq(false));
        var fallback = queries.getAllValues().get(3);
        String sql = fallback.getSqlSegment();
        for (String column : List.of("movie_id", "source", "source_ref", "type", "provider", "deleted_at IS NULL")) {
            assertTrue(sql.contains(column), sql);
        }
        assertTrue(fallback.getParamNameValuePairs().values()
                .containsAll(List.of("gying_mv_NEW1", "GYING", "BT1", "TORRENT", "P2P")));
        verifyNoInteractions(discoveryService, transferTaskService, transferRunnerService,
                xunleiTransferTaskService, xunleiTransferRunnerService, publishService);
    }

    @Test
    void repeatedMagnetIsUpdatedByUrlWithoutTorrentIdentityFallback() {
        String url = "magnet:?xt=urn:btih:" + "a".repeat(40);
        stubP2pCatalog(List.of(Map.of("type", "MAGNET", "provider", "P2P", "url", url, "source_id", "BT1")));
        ResourceLink existing = new ResourceLink(); existing.setId(92L); existing.setMovieId("gying_mv_NEW1");
        when(resourceLinkService.getOne(any(Wrapper.class), eq(false))).thenReturn(null, existing);
        when(resourceLinkService.save(any(ResourceLink.class))).thenReturn(true);
        when(resourceLinkService.updateById(existing)).thenReturn(true);
        assertEquals(1, service.syncCatalogMetadata("HITS_MOVIE", 1, 10).get("directResourceLinks"));
        assertEquals(1, service.syncCatalogMetadata("HITS_MOVIE", 1, 10).get("directResourceLinks"));
        assertEquals(url, existing.getUrl());
        assertEquals("BT1", existing.getSourceRef());
        verify(resourceLinkService).save(any(ResourceLink.class));
        verify(resourceLinkService).updateById(existing);
        verify(resourceLinkService, times(2)).getOne(any(Wrapper.class), eq(false));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unsuccessfulP2pPersistenceIsAuditedWithoutDiscardingMetadata(boolean update) {
        stubP2pCatalog(List.of(Map.of("type", "MAGNET", "provider", "P2P",
                "url", "magnet:?xt=urn:btih:" + "a".repeat(40))));
        if (update) {
            ResourceLink existing = new ResourceLink(); existing.setId(93L);
            when(resourceLinkService.getOne(any(Wrapper.class), eq(false))).thenReturn(existing);
            when(resourceLinkService.updateById(existing)).thenReturn(false);
        } else {
            when(resourceLinkService.save(any(ResourceLink.class))).thenReturn(false);
        }
        var result = service.syncCatalogMetadata("HITS_MOVIE", 1, 10);
        assertEquals(1, result.get("inserted"));
        assertEquals(0, result.get("failed"));
        assertEquals(0, result.get("directResourceLinks"));
        assertEquals(1, result.get("directResourceFailures"));
        assertTrue(result.get("errors").toString().contains("P2P resource was not persisted"));
        verifyNoInteractions(discoveryService, transferTaskService, transferRunnerService,
                xunleiTransferTaskService, xunleiTransferRunnerService, publishService);
    }

    @Test
    void existingMetadataAlsoAuditsUpstreamP2pFailure() {
        stubP2pCatalog(List.of());
        MovieMetadata existing = movie("tmdb_mv_123", "目录电影", "mv", "UNKNOWN");
        existing.setYear(2026);
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of(existing));
        when(gyingSourceClient.get("/resources/mv/NEW1")).thenThrow(new IllegalStateException("fixture P2P unavailable"));
        var result = service.syncCatalogMetadata("HITS_MOVIE", 1, 10);
        assertEquals(1, result.get("linked"));
        assertEquals(0, result.get("inserted"));
        assertEquals(1, result.get("directResourceFailures"));
        verify(gyingSourceClient, never()).post(eq("/ingest"), any());
        verify(resourceLinkService, never()).save(any(ResourceLink.class));
    }

    private void stubP2pCatalog(List<Map<String, Object>> resources) {
        MovieMetadata saved = movie("gying_mv_NEW1", "目录电影", "mv", "UNKNOWN");
        when(gyingSourceClient.get("/catalog?typeCode=mv&sort=hits&page=1&limit=10"))
                .thenReturn(Map.of("items", List.of(Map.of("mid", "NEW1", "title", "目录电影", "year", 2026))));
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of());
        when(movieService.getById(saved.getId())).thenReturn(saved);
        when(gyingSourceClient.post(eq("/ingest"), any())).thenReturn(Map.of("movieId", saved.getId()));
        when(gyingSourceClient.get("/resources/mv/NEW1")).thenReturn(Map.of("resources", resources));
    }

    @Test
    void checkPublishedResourcesMarksMappedInvalidLink() {
        String url = "https://pan.quark.cn/s/dead";
        ResourceLink local = new ResourceLink();
        local.setId(8L);
        local.setMovieId("EGER");
        local.setSourceRef("SITE1");
        local.setUrl(url);

        when(gyingSourceClient.get("/my-resources?limit=20")).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "source_id", "SITE1",
                        "mid", "EGER",
                        "type_code", "mv",
                        "title", "后室 4K",
                        "url", url,
                        "provider", "QUARK"))));
        when(panSouClient.checkLinksByProvider(Map.of(url, "QUARK"))).thenReturn(Map.of(
                url, new LinkCheckResult(url, true, false, "invalid")));
        when(resourceLinkService.getOne(any(Wrapper.class), eq(false))).thenReturn(local);

        Map<String, Object> result = service.checkPublishedResources(20, true);

        assertEquals(1, result.get("invalid"));
        assertEquals("mv/EGER", ((List<Map<String, Object>>) result.get("items")).get(0).get("gyingResourceId"));
        assertEquals("INVALID", local.getLinkStatus());
        assertFalse(local.getLastCheckError().isBlank());
        verify(resourceLinkService).updateById(local);
    }

    @Test
    void checkPublishedResourcesBySourceIdsFiltersAndReportsMissingIds() {
        String url = "https://pan.quark.cn/s/dead";
        when(gyingSourceClient.get(eq("/my-resources"), any(Map.class))).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "source_id", "SITE1",
                        "mid", "EGER",
                        "type_code", "mv",
                        "title", "后室 4K",
                        "url", url,
                        "provider", "QUARK"))));
        when(panSouClient.checkLinksByProvider(Map.of(url, "QUARK"))).thenReturn(Map.of(
                url, new LinkCheckResult(url, true, false, "invalid")));

        Map<String, Object> result = service.checkPublishedResourcesBySourceIds(
                List.of("SITE1", "MISSING"), true);

        assertEquals(1, result.get("checked"));
        assertEquals(List.of("MISSING"), result.get("notFound"));
        assertEquals("INVALID", ((List<Map<String, Object>>) result.get("items")).get(0).get("checkStatus"));
        ArgumentCaptor<Map<String, ?>> query = ArgumentCaptor.forClass(Map.class);
        verify(gyingSourceClient).get(eq("/my-resources"), query.capture());
        assertEquals("SITE1,MISSING", query.getValue().get("sourceIds"));
    }

    @Test
    void syncMyPublishedResourcesSkipsExistingSourceAndUrl() {
        ResourceLink existing = new ResourceLink();
        existing.setId(77L);
        existing.setMovieId("EGER");
        when(gyingSourceClient.get("/my-resources?limit=20")).thenReturn(Map.of("items", List.of(
                Map.of("source_id", "OWN-1", "type_code", "mv", "mid", "EGER",
                        "title", "已有资源", "url", "https://pan.quark.cn/s/existing", "provider", "QUARK"))));
        when(resourceLinkService.getOne(any(Wrapper.class), eq(false))).thenReturn(existing);

        Map<String, Object> result = service.syncMyPublishedResources(20);

        assertEquals(1, result.get("checked"));
        assertEquals(0, result.get("imported"));
        assertEquals(1, result.get("skipped"));
        assertEquals("SKIPPED", ((List<Map<String, Object>>) result.get("items")).get(0).get("status"));
        verify(gyingSourceClient, never()).post(eq("/ingest"), any());
        verify(resourceLinkService, never()).save(any());
    }

    @Test
    void syncMyPublishedResourcesImportsNewOwnedLinkOnce() {
        MovieMetadata movie = movie("OWNMOVIE", "我的已发布影片", "mv", "UNKNOWN");
        String url = "https://pan.quark.cn/s/owned-new";
        when(gyingSourceClient.get("/my-resources?limit=20")).thenReturn(Map.of("items", List.of(
                Map.of("source_id", "OWN-2", "type_code", "mv", "mid", "OWNMOVIE",
                        "title", "我的已发布影片 4K", "url", url, "provider", "QUARK"))));
        when(resourceLinkService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
        when(gyingSourceClient.get("/movie/mv/OWNMOVIE")).thenReturn(Map.of(
                "title", movie.getTitleCn(), "year", 2026, "resources", List.of(), "ownResources", List.of()));
        when(movieService.getById(movie.getId())).thenReturn(movie);

        Map<String, Object> result = service.syncMyPublishedResources(20);

        assertEquals(1, result.get("imported"));
        assertEquals(0, result.get("skipped"));
        ArgumentCaptor<ResourceLink> resource = ArgumentCaptor.forClass(ResourceLink.class);
        verify(resourceLinkService).save(resource.capture());
        assertEquals("GYING_PUBLISHED", resource.getValue().getSource());
        assertEquals("OWN-2", resource.getValue().getSourceRef());
        assertEquals(url, resource.getValue().getUrl());
    }

    @Test
    void ensureMovieResourcesDeduplicatesSelectedRecentCandidates() {
        MovieMetadata movie = movie("EGER", "后室", "mv", "TRAILER");
        when(gyingSourceClient.get("/movie/mv/EGER")).thenReturn(Map.of(
                "title", "后室",
                "resources", List.of(),
                "ownResources", List.of()));
        when(gyingSourceClient.post(eq("/ingest"), any())).thenReturn(Map.of("movieId", "EGER"));
        when(movieService.getById("EGER")).thenReturn(movie);

        Map<String, Object> result = service.ensureMovieResources(List.of(
                Map.of("typeCode", "mv", "mid", "EGER"),
                Map.of("typeCode", "mv", "mid", "EGER")));

        assertEquals(1, result.get("checked"));
        assertEquals(1, result.get("succeeded"));
        assertTrue(((List<?>) result.get("items")).size() == 1);
        verify(gyingSourceClient, times(2)).get("/movie/mv/EGER");
    }

    @Test
    void ensureRemainingSeasonsNeverTransfersFromPansouWhenGyingUnavailable() {
        MovieMetadata movie = movie("tmdb_tv_900", "示例剧 第二季", "tv", "TRAILER");
        movie.setSeason(2); movie.setSeriesName("示例剧");
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(gyingSourceClient.get(anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GYING unavailable"));
        Map<String, Object> result = service.ensureRemainingSeasons(movie.getId(), 2);
        assertEquals("METADATA_AND_EXISTING_COLLECTION", result.get("mode"));
        assertEquals(0, result.get("completed")); assertEquals(0, result.get("bound"));
        verify(transferRunnerService, never()).submitOne(any());
        verify(xunleiTransferRunnerService, never()).submitOne(any());
        verify(publishService, never()).publishDiscovery(any());
        verify(gyingSourceClient, never()).post(anyString(), any());
    }

    @Test
    void ensureRemainingSeasonsReportsSkipWhenPansouFindsNothing() {
        MovieMetadata movie = movie("tmdb_tv_901", "示例剧 第三季", "tv", "TRAILER");
        movie.setSeason(3);
        movie.setSeriesName("示例剧");
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of(movie));
        when(gyingSourceClient.get(anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GYING unavailable"));

        Map<String, Object> result = service.ensureRemainingSeasons(movie.getId(), 2);

        assertEquals("SKIPPED", result.get("status"));
        assertEquals(Boolean.TRUE, result.get("gyingUnavailable"));
        assertEquals(0, result.get("completed"));
        assertTrue(String.valueOf(result.get("reason")).contains("GYING"));
        verify(transferRunnerService, never()).submitOne(any());
    }

    @Test
    void repairMoviePosterSearchPrefersExactSequelOverSeriesTitle() throws Exception {
        MovieMetadata movie = movie("local_mv_avengers2", "复仇者联盟2：奥创纪元", "mv", "AVAILABLE");
        movie.setSeriesName("复仇者联盟");
        movie.setSeason(2);
        movie.setYear(2015);
        when(movieService.getById(movie.getId())).thenReturn(movie);

        TmdbListItem series = new TmdbListItem();
        series.setTmdbId(24428L);
        series.setMediaType("movie");
        series.setTitle("复仇者联盟");
        series.setOriginalTitle("The Avengers");
        series.setReleaseDate("2012-04-25");

        TmdbListItem sequel = new TmdbListItem();
        sequel.setTmdbId(99861L);
        sequel.setMediaType("movie");
        sequel.setTitle("复仇者联盟2：奥创纪元");
        sequel.setOriginalTitle("Avengers: Age of Ultron");
        sequel.setReleaseDate("2015-04-22");

        when(tmdbClient.searchMulti("复仇者联盟2：奥创纪元", 5)).thenReturn(List.of(series, sequel));
        when(tmdbClient.fetchDetails("movie", 99861L))
                .thenReturn(new ObjectMapper().readTree("{\"id\":99861,\"poster_path\":\"/avengers2.jpg\"}"));
        when(posterStorageService.storeTmdbPoster("movie", 99861L, "/avengers2.jpg"))
                .thenReturn("tmdb/movie/99861/poster.jpg");

        Map<String, Object> result = service.repairMoviePoster(movie.getId());

        assertEquals("UPDATED", result.get("status"));
        assertEquals(99861L, result.get("tmdbId"));
        assertEquals(99861L, movie.getTmdbId());
        assertEquals("movie", movie.getTmdbType());
        assertEquals("tmdb/movie/99861/poster.jpg", movie.getPosterUrl());
        verify(posterStorageService, never()).storeTmdbPoster(eq("movie"), eq(24428L), any());
    }
    @Test
    void repairMoviePosterFallsBackToTmdbSearch() throws Exception {
        MovieMetadata movie = movie("local_mv_777", "钢铁侠2", "mv", "AVAILABLE");
        when(movieService.getById(movie.getId())).thenReturn(movie);
        TmdbListItem item = new TmdbListItem();
        item.setTmdbId(10138L);
        item.setMediaType("movie");
        item.setTitle("钢铁侠2");
        item.setOriginalTitle("Iron Man 2");
        when(tmdbClient.searchMulti("钢铁侠2", 5)).thenReturn(List.of(item));
        when(tmdbClient.fetchDetails("movie", 10138L))
                .thenReturn(new ObjectMapper().readTree("{\"id\":10138,\"poster_path\":\"/iron2.jpg\"}"));
        when(posterStorageService.storeTmdbPoster("movie", 10138L, "/iron2.jpg"))
                .thenReturn("tmdb/movie/10138/poster.jpg");

        Map<String, Object> result = service.repairMoviePoster(movie.getId());

        assertEquals("UPDATED", result.get("status"));
        assertEquals("TMDB_SEARCH", result.get("source"));
        assertEquals("tmdb/movie/10138/poster.jpg", movie.getPosterUrl());
        verify(movieService).updateById(movie);
        verify(gyingSourceClient).get(eq("/search"), anyMap());
    }

    @Test
    void repairMoviePosterSkipsWhenGyingUnavailableWithoutOtherPoster() {
        MovieMetadata movie = movie("local_mv_778", "冷门影片", "mv", "AVAILABLE");
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(gyingSourceClient.get(anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GYING unavailable"));

        Map<String, Object> result = service.repairMoviePoster(movie.getId());

        assertEquals("SKIPPED", result.get("status"));
        assertTrue(String.valueOf(result.get("reason")).contains("保留原图"));
        assertTrue(String.valueOf(result.get("reason")).contains("GYING"));
        verify(movieService, never()).updateById(any());
    }

    @Test
    void gyingPosterHasPriorityForTmdbOriginWithoutReplacingIdentity() {
        MovieMetadata movie = movie("tmdb_tv_103516_s2", "星际迷航：奇异新世界 第2季", "tv", "AVAILABLE");
        movie.setTmdbId(103516L); movie.setTmdbType("tv"); movie.setSeason(2);
        MovieSourceIdentity gying = new MovieSourceIdentity(); gying.setMovieId(movie.getId());
        gying.setSource("GYING"); gying.setSourceType("tv"); gying.setExternalId("DDBy");
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(sourceIdentityService.getOne(any(Wrapper.class), eq(false))).thenReturn(gying);
        when(gyingSourceClient.post(eq("/poster"), any())).thenReturn(Map.of("status", "UPDATED", "posterUrl", "tv/DDBy/384.avif"));
        assertEquals("tv/DDBy/384.avif", service.repairMoviePoster(movie.getId()).get("posterUrl"));
        assertEquals(103516L, movie.getTmdbId());
        verifyNoInteractions(tmdbClient, posterStorageService);
    }

    @Test
    void tmdbFallbackUsesSeasonSpecificArtworkAndNeverSeriesPoster() throws Exception {
        MovieMetadata movie = movie("tmdb_tv_103516_s3", "星际迷航：奇异新世界 第3季", "tv", "AVAILABLE");
        movie.setTmdbId(103516L); movie.setTmdbType("tv"); movie.setSeason(3);
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(gyingSourceClient.get(anyString(), anyMap())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        when(tmdbClient.fetchSeasonDetails(103516L, 3)).thenReturn(new ObjectMapper().readTree("{\"season_number\":3,\"poster_path\":\"/season3.jpg\"}"));
        when(posterStorageService.storeTmdbSeasonPoster(103516L, 3, "/season3.jpg")).thenReturn("tmdb/tv/103516/season-3/poster.jpg");
        assertEquals("UPDATED", service.repairMoviePoster(movie.getId()).get("status"));
        assertEquals("tmdb/tv/103516/season-3/poster.jpg", movie.getPosterUrl());
        verify(posterStorageService, never()).storeTmdbPoster(any(), any(), any());
    }

    @Test
    void selectedPosterBatchCannotBecomeUnboundedRefresh() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.repairMissingPosters(20, List.of(), true));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.repairMissingPosters(20, null, true));
        service.repairMissingPosters(20, List.of("selected"), false);
        ArgumentCaptor<Wrapper<MovieMetadata>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(movieService).list(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("id IN"));
        assertTrue(query.getValue().getSqlSegment().contains("poster_url"));
        assertTrue(query.getValue().getSqlSegment().contains("LIMIT 1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void weeklyQuarkCandidateRequiresDurableOneShotPolicy(boolean persisted) {
        MovieMetadata movie = movie("ONE", "周榜电影", "mv", "UNKNOWN");
        when(gyingSourceClient.get("/movie/mv/ONE")).thenReturn(Map.of("title", "周榜电影", "ownResources", List.of(),
                "resources", List.of(Map.of("provider", "QUARK", "url", "https://pan.quark.cn/s/fixture", "source_id", "SRC1", "title", "周榜电影"))));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        var discovery = new ResourceDiscoveryResult(); discovery.setId(8L); discovery.setMovieId(movie.getId());
        when(discoveryService.getOne(any(Wrapper.class), eq(false))).thenReturn(discovery);
        var transfer = new QuarkTransferTask(); transfer.setId(9L); transfer.setDiscoveryResultId(8L); transfer.setStatus("PENDING");
        when(transferTaskService.getOne(any(Wrapper.class), eq(false))).thenReturn(transfer);
        when(transferTaskService.updateById(transfer)).thenReturn(persisted);
        var failed = new QuarkTransferRunResult(); failed.setFailed(1); failed.getErrors().add("fixture stopped before cloud writes");
        when(transferRunnerService.submitOne(9L)).thenReturn(failed);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.ensureWeeklyMovieResource("mv", "ONE"));
        assertTrue(transfer.getRequestPayload().contains("GYING_WEEKLY_ONE_SHOT"));
        if (persisted) verify(transferRunnerService).submitOne(9L);
        else verify(transferRunnerService, never()).submitOne(any());
    }

    private MovieMetadata movie(String id, String title, String category, String resourceStatus) {
        MovieMetadata movie = new MovieMetadata();
        movie.setId(id);
        movie.setTitleCn(title);
        movie.setCategory(category);
        movie.setResourceStatus(resourceStatus);
        movie.setStatus("ACTIVE");
        return movie;
    }
}
