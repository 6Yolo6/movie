package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.TmdbListItem;
import com.gying.movie.dto.TmdbListPage;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataUpdatesProvidersTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ResourceHubProperties properties = new ResourceHubProperties();
    final IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    final TmdbClient tmdb = mock(TmdbClient.class);
    final IMovieMetadataService movies = mock(IMovieMetadataService.class);
    final IMovieSourceIdentityService identities = mock(IMovieSourceIdentityService.class);
    final GyingSourceWorkflowService workflow = mock(GyingSourceWorkflowService.class);
    final IResourceDiscoveryService discoveries = mock(IResourceDiscoveryService.class);

    ResourceHubTask task(String provider) {
        properties.setEnabled(true);
        properties.getTmdb().setAutoSyncEnabled(true); properties.getTmdb().setAutoSyncUpdatesOnly(true);
        properties.getGying().setAutoSyncEnabled(true); properties.getGying().setAutoSyncUpdatesOnly(true);
        when(tasks.update(any())).thenReturn(true);
        when(tasks.enqueue(any())).thenAnswer(call -> {
            ResourceHubTask task = call.getArgument(0); task.setId(9L); task.setStatus("PENDING"); task.setAttempts(0); return task;
        });
        ResourceHubTask task = new MetadataUpdatesCheckpoint(tasks, mapper).enqueue(provider, List.of("TIME_MOVIE"), 20);
        when(tasks.getById(9L)).thenReturn(task); return task;
    }
    final TmdbGyingResourceFollowUpService followups = mock(TmdbGyingResourceFollowUpService.class);
    TmdbMetadataSyncServiceImpl tmdbService() {
        when(followups.persistMetadataAndEnqueue(anyLong(), any(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        return new TmdbMetadataSyncServiceImpl(tmdb, mock(PosterStorageService.class), properties, movies, identities,
                tasks, discoveries, mock(IResourceDiscoveryResultService.class), mock(IResourceLinkService.class), mapper, followups);
    }
    GyingMetadataSyncServiceImpl gyingService() {
        return new GyingMetadataSyncServiceImpl(properties, tasks, workflow, discoveries, mock(IResourceLinkService.class), mapper);
    }
    TmdbListItem item(long id) {
        var item = new TmdbListItem(); item.setTmdbId(id); item.setMediaType("movie");
        item.setTitle("Film " + id); item.setOriginalTitle("Film " + id); item.setReleaseDate("2026-01-01"); return item;
    }
    void details() {
        when(movies.save(any())).thenReturn(true); when(identities.save(any())).thenReturn(true);
        when(tmdb.fetchDetails(anyString(), anyLong())).thenAnswer(call -> mapper.createObjectNode()
                .put("id", (Long) call.getArgument(1)).put("title", "Film").put("release_date", "2026-01-01"));
    }

    @Test void tmdbChecksAllSelectedHeadsAndSharesTenDetailsAcrossProvidersSources() throws Exception {
        var task = task("TMDB"); details();
        properties.getTmdb().setAutoSyncSources("TRENDING_MOVIE_DAY,TRENDING_TV_DAY,UPCOMING_MOVIE");
        when(tmdb.normalizeSource(anyString())).thenAnswer(call -> call.getArgument(0));
        when(tmdb.fetchListPage(anyString(), anyInt())).thenAnswer(call -> {
            String source = call.getArgument(0); int page = call.getArgument(1);
            int base = List.of("TRENDING_MOVIE_DAY", "TRENDING_TV_DAY", "UPCOMING_MOVIE").indexOf(source) * 100 + page * 10;
            List<TmdbListItem> items = new ArrayList<>(); for (int i = 1; i <= 6; i++) items.add(item(base + i));
            return new TmdbListPage(page, 10, 200, items);
        });
        var result = tmdbService().runTask(9L);
        assertEquals("SUCCEEDED", result.getStatus()); assertEquals(10, result.getProcessed());
        for (String source : List.of("TRENDING_MOVIE_DAY", "TRENDING_TV_DAY", "UPCOMING_MOVIE"))
            for (int page = 1; page <= 2; page++) verify(tmdb).fetchListPage(source, page);
        verify(tmdb, times(10)).fetchDetails(anyString(), anyLong());
        verify(followups, times(10)).persistMetadataAndEnqueue(eq(9L), any(), any());
        assertEquals(26, mapper.readTree(task.getPayload()).path("crawl").path("pending").size());
        verify(tasks, never()).updateById(any()); verifyNoInteractions(discoveries);
    }

    @Test void tmdbExactTrustedCanonicalIdentitySeedsWithoutDetailCalls() {
        var task = task("TMDB"); properties.getTmdb().setAutoSyncSources("POPULAR_MOVIE");
        when(tmdb.normalizeSource(anyString())).thenAnswer(call -> call.getArgument(0));
        when(tmdb.fetchListPage(eq("POPULAR_MOVIE"), anyInt())).thenAnswer(call -> new TmdbListPage(call.getArgument(1), 2, 40, List.of(item(1))));
        MovieSourceIdentity identity = new MovieSourceIdentity(); identity.setId(1L); identity.setSource("TMDB");
        identity.setSourceType("movie"); identity.setExternalId("1"); identity.setSeason(0); identity.setMatchStatus("CONFIRMED"); identity.setMovieId("canonical");
        MovieMetadata movie = new MovieMetadata(); movie.setId("canonical"); movie.setTmdbId(1L); movie.setTmdbType("movie");
        movie.setStatus("ACTIVE"); movie.setTmdbLastSyncAt(LocalDateTime.now());
        when(identities.getOne(any(), eq(false))).thenReturn(identity); when(movies.getById("canonical")).thenReturn(movie);
        var result = tmdbService().runTask(9L);
        assertEquals("SUCCEEDED", result.getStatus()); assertEquals(1, result.getSkipped()); assertEquals(0, result.getProcessed());
        verify(tmdb, never()).fetchDetails(anyString(), anyLong());
        verify(movies, never()).save(any()); verify(movies, never()).updateById(any());
        verifyNoInteractions(followups);
    }

    @Test void tmdbPopularityMovementDoesNotRefreshButStableTitleChangeDoes() {
        var task = task("TMDB"); details(); properties.getTmdb().setAutoSyncSources("POPULAR_MOVIE");
        when(tmdb.normalizeSource(anyString())).thenAnswer(call -> call.getArgument(0));
        var item = item(1);
        when(tmdb.fetchListPage(eq("POPULAR_MOVIE"), anyInt())).thenAnswer(call -> new TmdbListPage(call.getArgument(1), 2, 40, List.of(item)));
        var service = tmdbService(); assertEquals(1, service.runTask(9L).getProcessed());
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
        task = service.enqueueUpdates(List.of("POPULAR_MOVIE")); when(tasks.getById(9L)).thenReturn(task);
        item.setPopularity(999.0); assertEquals(0, service.runTask(9L).getProcessed());
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
        task = service.enqueueUpdates(List.of("POPULAR_MOVIE")); when(tasks.getById(9L)).thenReturn(task);
        item.setTitle("Changed semantic title"); assertEquals(1, service.runTask(9L).getProcessed());
        verify(tmdb, times(2)).fetchDetails("movie", 1L);
    }

    @Test void failedTmdbPersistenceOrWrongDetailIdentityNeverMarksUpdateComplete() throws Exception {
        var task = task("TMDB"); properties.getTmdb().setAutoSyncSources("POPULAR_MOVIE");
        when(tmdb.normalizeSource(anyString())).thenAnswer(call -> call.getArgument(0));
        when(tmdb.fetchListPage(eq("POPULAR_MOVIE"), anyInt())).thenAnswer(call -> new TmdbListPage(call.getArgument(1), 2, 40, List.of(item(1))));
        when(tmdb.fetchDetails("movie", 1L)).thenReturn(mapper.createObjectNode().put("id", 999));
        assertEquals("FAILED", tmdbService().runTask(9L).getStatus());
        assertEquals(0, mapper.readTree(task.getPayload()).path("crawl").path("completed").size());
        verifyNoInteractions(movies); // Seed identity is absent; wrong details never reach metadata writes.
    }

    @Test void conflictingTmdbCanonicalIdentityStopsBeforeCreatingReplacementMetadata() {
        task("TMDB"); details(); properties.getTmdb().setAutoSyncSources("POPULAR_MOVIE");
        when(tmdb.normalizeSource(anyString())).thenAnswer(call -> call.getArgument(0));
        when(tmdb.fetchListPage(eq("POPULAR_MOVIE"), anyInt())).thenAnswer(call -> new TmdbListPage(call.getArgument(1), 2, 40, List.of(item(1))));
        MovieSourceIdentity identity = new MovieSourceIdentity(); identity.setId(1L); identity.setSource("TMDB");
        identity.setSourceType("movie"); identity.setExternalId("1"); identity.setSeason(0); identity.setMatchStatus("CONFIRMED");
        identity.setMovieId("retiredCanonical"); when(identities.getOne(any(), eq(false))).thenReturn(identity);
        assertEquals("FAILED", tmdbService().runTask(9L).getStatus());
        verify(movies, never()).save(any()); verify(movies, never()).updateById(any());
        verify(identities, never()).save(any()); verify(identities, never()).updateById(any());
    }

    @Test void gyingAllSelectedHeadsShareTenAndPausedSourceStaysOutOfScan() throws Exception {
        var task = task("GYING"); properties.getGying().setAutoSyncSources("TIME_MOVIE,TIME_TV,TIME_ANIME,CSCORE_MOVIE");
        properties.getGying().setAutoSyncPausedSources(Map.of("CSCORE_MOVIE", true));
        when(workflow.catalogUpdateHead(anyString(), anyInt())).thenAnswer(call -> {
            String source = call.getArgument(0); int page = call.getArgument(1); List<Map<String,Object>> items = new ArrayList<>();
            for (int i = 1; i <= 6; i++) items.add(Map.of("mid", "ID" + page + i, "title", "Film " + i, "year", 2026, "season", 1));
            return items;
        });
        when(workflow.collectCatalogUpdate(anyMap())).thenReturn(true);
        var result = gyingService().runTask(9L); assertEquals("SUCCEEDED", result.getStatus()); assertEquals(10, result.getProcessed());
        for (String source : List.of("TIME_MOVIE", "TIME_TV", "TIME_ANIME"))
            for (int page = 1; page <= 2; page++) verify(workflow).catalogUpdateHead(source, page);
        verify(workflow, never()).catalogUpdateHead(eq("CSCORE_MOVIE"), anyInt());
        verify(workflow, times(10)).collectCatalogUpdate(anyMap());
        assertEquals(26, mapper.readTree(task.getPayload()).path("crawl").path("pending").size());
        verifyNoInteractions(discoveries); verify(tasks, never()).updateById(any());
    }

    @Test void gyingTrustedCollectionSeedsAndPartialP2pFailureRetainsPending() throws Exception {
        var task = task("GYING"); properties.getGying().setAutoSyncSources("TIME_MOVIE");
        var item = Map.<String,Object>of("mid", "DONE1", "title", "Film", "year", 2026);
        when(workflow.catalogUpdateHead(eq("TIME_MOVIE"), anyInt())).thenReturn(List.of(item));
        when(workflow.catalogUpdateComplete(anyMap())).thenReturn(true);
        assertEquals(1, gyingService().runTask(9L).getSkipped()); verify(workflow, never()).collectCatalogUpdate(anyMap());
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
        task = gyingService().enqueueUpdates(List.of("TIME_MOVIE")); when(tasks.getById(9L)).thenReturn(task);
        var changed = Map.<String,Object>of("mid", "DONE1", "title", "Changed Film", "year", 2026);
        when(workflow.catalogUpdateHead(eq("TIME_MOVIE"), anyInt())).thenReturn(List.of(changed));
        when(workflow.collectCatalogUpdate(anyMap())).thenThrow(new IllegalStateException("P2P fixture failure"));
        assertEquals("FAILED", gyingService().runTask(9L).getStatus());
        assertEquals(1, mapper.readTree(task.getPayload()).path("crawl").path("pending").size());
    }

    @Test void updateStartCasLossDoesNoSourceOrCloudWork() {
        var task = task("GYING"); when(tasks.update(any())).thenReturn(false);
        gyingService().runTask(9L); assertEquals("PENDING", task.getStatus());
        verifyNoInteractions(workflow, discoveries); verify(tasks, never()).updateById(any());
    }
}
