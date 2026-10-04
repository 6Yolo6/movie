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
        properties.getTmdb().setAutoDiscoveryEnabled(false);
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
        when(client.fetchList("POPULAR_MOVIE", 2)).thenReturn(items);
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
}
