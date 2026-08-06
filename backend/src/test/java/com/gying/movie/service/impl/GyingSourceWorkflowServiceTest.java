package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                "q", "揭秘日", "typeCode", "mv", "limit", 20)))
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
