package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.TmdbListItem;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TmdbMetadataCrawlTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    private final TmdbClient client = mock(TmdbClient.class);
    private final IMovieMetadataService movies = mock(IMovieMetadataService.class);
    private final ResourceHubProperties properties = new ResourceHubProperties();

    private TmdbMetadataSyncServiceImpl service() {
        properties.setEnabled(true);
        properties.getTmdb().setApiKey("test-only-placeholder");
        properties.getTmdb().setAutoDiscoveryEnabled(true);
        return new TmdbMetadataSyncServiceImpl(client, mock(PosterStorageService.class), properties,
                movies, mock(IMovieSourceIdentityService.class), tasks,
                mock(IResourceDiscoveryService.class), mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class), mapper);
    }

    private ResourceHubTask batch() throws Exception {
        var planner = new MetadataCrawlPlanner(tasks, mapper);
        var task = new ResourceHubTask();
        task.setId(30L);
        task.setTaskType("METADATA_SYNC");
        task.setSource("TMDB");
        task.setPayload(planner.attach("{\"source\":\"POPULAR_MOVIE\",\"page\":2,\"maxItems\":1}",
                new MetadataCrawlPlanner.Position(2, 5, 2, 1, "PENDING", null)));
        when(tasks.getById(30L)).thenReturn(task);
        when(client.normalizeSource(anyString())).thenAnswer(call -> call.getArgument(0));
        List<TmdbListItem> items = java.util.stream.LongStream.rangeClosed(1, 3).mapToObj(id -> {
            var item = new TmdbListItem(); item.setTmdbId(id); item.setMediaType("movie"); return item;
        }).toList();
        when(client.fetchListPage("POPULAR_MOVIE", 2)).thenReturn(
                new com.gying.movie.dto.TmdbListPage(2, 5, 100, items));
        when(client.fetchDetails("movie", 2L)).thenReturn(mapper.readTree(
                "{\"id\":2,\"title\":\"Test Movie\",\"release_date\":\"2026-01-01\"}"));
        return task;
    }

    @Test void processesOnlyUnfinishedBatchAndCheckpointsOffset() throws Exception {
        var task = batch();
        var result = service().runTask(30L);
        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(1, result.getInserted());
        verify(client).fetchDetails("movie", 2L);
        verify(client, never()).fetchDetails("movie", 1L);
        verify(client, never()).fetchDetails("movie", 3L);
        verify(movies).save(any());
        verify(tasks, never()).enqueue(any());
        assertEquals(2, mapper.readTree(task.getPayload()).path("crawl").path("nextOffset").asInt());
    }

    @Test void failedItemDoesNotAdvanceCheckpoint() throws Exception {
        var task = batch();
        when(client.fetchDetails("movie", 2L)).thenThrow(new IllegalStateException("test upstream failure"));
        assertEquals("FAILED", service().runTask(30L).getStatus());
        assertFalse(mapper.readTree(task.getPayload()).path("crawl").has("nextPage"));
        verifyNoInteractions(movies);
    }

    @Test void enqueueAutomaticUsesDurablePositionRatherThanAlwaysStartPage() throws Exception {
        var previous = batch();
        new MetadataCrawlPlanner(tasks, mapper).completed(previous, 3, 1, 0);
        previous.setStatus("SUCCEEDED");
        properties.getTmdb().setAutoSyncPage(2);
        properties.getTmdb().setAutoSyncEndPage(5);
        when(tasks.getOne(any(), eq(false))).thenReturn(previous);
        when(tasks.enqueue(any())).thenAnswer(call -> call.getArgument(0));
        var next = service().enqueueAutomatic("POPULAR_MOVIE");
        assertEquals(2, mapper.readTree(next.getPayload()).path("crawl").path("offset").asInt());
        assertEquals(2, mapper.readTree(next.getPayload()).path("page").asInt());
    }
    @Test void automaticEnqueueUsesEachCatalogsOwnBounds() throws Exception {
        properties.getTmdb().setAutoSyncPage(1);
        properties.getTmdb().setAutoSyncEndPage(500);
        properties.getTmdb().setAutoSyncRanges(java.util.Map.of(
                "POPULAR_MOVIE", new com.gying.movie.dto.MetadataCrawlRange(151, 500),
                "POPULAR_TV", new com.gying.movie.dto.MetadataCrawlRange(121, 400)));
        when(client.normalizeSource(anyString())).thenAnswer(call -> call.getArgument(0));
        when(tasks.enqueue(any())).thenAnswer(call -> call.getArgument(0));
        var service = service();
        var movie = mapper.readTree(service.enqueueAutomatic("POPULAR_MOVIE").getPayload());
        var tv = mapper.readTree(service.enqueueAutomatic("POPULAR_TV").getPayload());
        assertEquals(151, movie.path("page").asInt());
        assertEquals(121, tv.path("page").asInt());
        assertEquals(400, tv.path("crawl").path("endPage").asInt());
    }
    @Test void selectedIdentityLoadsOneMovieWithoutRepeatingKeywordSearch() throws Exception {
        var candidate = new com.gying.movie.dto.MovieSearchCandidate(998L, "movie", "选定电影", "Selected Film", 2026, 180);
        when(client.fetchDetails("movie", 998L)).thenReturn(mapper.readTree(
                "{\"id\":998,\"title\":\"选定电影\",\"original_title\":\"Selected Film\",\"release_date\":\"2026-01-01\"}"));
        var movie = service().syncSelectedCandidate(candidate);
        assertEquals(998L, movie.getTmdbId());
        assertEquals("选定电影", movie.getTitleCn());
        verify(client).fetchDetails("movie", 998L);
        verify(client, never()).searchMulti(anyString(), anyInt());
        verifyNoMoreInteractions(client);
        verify(movies).save(any());
    }

    @Test void invalidSelectedIdentityStopsBeforeAnyRemoteRequest() {
        var candidate = new com.gying.movie.dto.MovieSearchCandidate(-1L, "movie", "无效电影", null, 2026, 180);
        assertThrows(IllegalArgumentException.class, () -> service().syncSelectedCandidate(candidate));
        verifyNoInteractions(client, movies);
    }

    @Test void emptyAutomaticRangeRecordsBoundsAndDoesNotCreateAnotherTaskDuringCooldown() throws Exception {
        var task = batch();
        task.setPayload(new MetadataCrawlPlanner(tasks, mapper).attach(
                "{\"source\":\"TOP_RATED_TV\",\"page\":360,\"maxItems\":20}",
                new MetadataCrawlPlanner.Position(360, 500, 360, 0, "PENDING", null)));
        when(client.fetchListPage("TOP_RATED_TV", 360)).thenReturn(
                new com.gying.movie.dto.TmdbListPage(360, 131, 2610, List.of()));
        var result = service().runTask(30L);
        assertEquals("SUCCEEDED", result.getStatus()); assertEquals(0, result.getProcessed());
        var crawl = mapper.readTree(task.getPayload()).path("crawl");
        assertEquals("EMPTY_RANGE", crawl.path("availability").asText());
        assertEquals(131, crawl.path("observedTotalPages").asInt());
        assertEquals(360, crawl.path("nextPage").asInt());
        assertTrue(crawl.has("nextCheckAt"));
        verifyNoInteractions(movies);
        properties.getTmdb().setAutoSyncRanges(java.util.Map.of(
                "TOP_RATED_TV", new com.gying.movie.dto.MetadataCrawlRange(360, 500)));
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
        assertNull(service().enqueueAutomatic("TOP_RATED_TV"));
        verify(tasks, never()).enqueue(any());
        verify(client, never()).fetchList(anyString(), anyInt());
    }

    @Test void failedPaginationKeepsPositionAndNeverCreatesAnEmptyRangeCooldown() throws Exception {
        var task = batch();
        when(client.fetchListPage("POPULAR_MOVIE", 2)).thenThrow(
                new IllegalStateException("TMDB catalog pagination is invalid"));
        assertEquals("FAILED", service().runTask(30L).getStatus());
        var crawl = mapper.readTree(task.getPayload()).path("crawl");
        assertEquals(1, crawl.path("offset").asInt());
        assertFalse(crawl.has("nextPage")); assertFalse(crawl.has("nextCheckAt"));
        verifyNoInteractions(movies);
        verify(client, never()).fetchList(anyString(), anyInt());
    }

    @Test void manualSyncRemainsIndependentOfAutomaticCooldownAndPaginationContract() throws Exception {
        var task = batch();
        task.setPayload("{\"source\":\"POPULAR_MOVIE\",\"page\":2,\"maxItems\":1}");
        when(client.fetchList("POPULAR_MOVIE", 2)).thenReturn(List.of());
        assertEquals("SUCCEEDED", service().runTask(30L).getStatus());
        assertFalse(mapper.readTree(task.getPayload()).has("crawl"));
        verify(client, never()).fetchListPage(anyString(), anyInt());
    }

}
