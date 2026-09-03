package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.GyingSourceClient;
import com.gying.movie.client.PanSouClient;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.dto.DiscoveredResource;
import com.gying.movie.dto.MovieSearchCandidate;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IMovieSourceIdentityService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GyingSourceWorkflowServiceTest {
    private GyingSourceClient gyingSourceClient;
    private TmdbClient tmdbClient;
    private PosterStorageService posterStorageService;
    private PanSouClient panSouClient;
    private IMovieMetadataService movieService;
    private IMovieSourceIdentityService sourceIdentityService;
    private IResourceLinkService resourceLinkService;
    private IQuarkTransferRunnerService transferRunnerService;
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
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IQuarkTransferTaskService transferService = mock(IQuarkTransferTaskService.class);
        transferRunnerService = mock(IQuarkTransferRunnerService.class);
        IResourceHubPublishService publishService = mock(IResourceHubPublishService.class);
        IQuarkShareService shareService = mock(IQuarkShareService.class);
        service = new GyingSourceWorkflowService(
                gyingSourceClient,
                tmdbClient,
                posterStorageService,
                panSouClient,
                movieService,
                sourceIdentityService,
                resourceLinkService,
                discoveryService,
                transferService,
                transferRunnerService,
                publishService,
                shareService);
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

        Map<String, Object> result = service.ensureMovieResource("tv", "dlvj");

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

        assertEquals(0, result.get("discovered"));
        ArgumentCaptor<MovieSourceIdentity> identityCaptor = ArgumentCaptor.forClass(MovieSourceIdentity.class);
        verify(sourceIdentityService, times(2)).save(identityCaptor.capture());
        MovieSourceIdentity gyingIdentity = identityCaptor.getAllValues().stream()
                .filter(identity -> "GYING".equals(identity.getSource()))
                .findFirst()
                .orElseThrow();
        assertEquals(movie.getId(), gyingIdentity.getMovieId());
        assertEquals("GY100", gyingIdentity.getExternalId());
        assertEquals("AUTO", gyingIdentity.getMatchStatus());
        verify(gyingSourceClient, never()).post(eq("/ingest"), any());
    }

    @Test
    void repairMissingPostersPrefersTmdbWithoutGyingIdentity() throws Exception {
        MovieMetadata movie = movie("tmdb_movie_200", "海报测试", "mv", "AVAILABLE");
        movie.setTmdbId(200L);
        movie.setTmdbType("movie");
        when(movieService.list(any(Wrapper.class))).thenReturn(List.of(movie));
        when(movieService.getById(movie.getId())).thenReturn(movie);
        when(tmdbClient.fetchDetails("movie", 200L))
                .thenReturn(new ObjectMapper().readTree("{\"poster_path\":\"/poster.jpg\"}"));
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

        assertEquals("FAILED", result.get("status"));
        assertEquals("image unavailable", result.get("reason"));
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

        Map<String, Object> result = service.syncCatalogMetadata("HITS_MOVIE", 1, 10);

        assertEquals(1, result.get("inserted"));
        assertEquals(List.of(saved.getId()), result.get("movieIds"));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(gyingSourceClient).post(eq("/ingest"), payload.capture());
        assertEquals(false, payload.getValue().get("includeResources"));
        assertEquals("gying_mv_NEW1", payload.getValue().get("targetMovieId"));
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
        verify(gyingSourceClient, times(1)).get("/movie/mv/EGER");
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
