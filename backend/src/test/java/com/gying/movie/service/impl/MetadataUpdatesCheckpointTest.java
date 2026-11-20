package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MetadataUpdatesCheckpointTest {
    final ObjectMapper mapper = new ObjectMapper();
    final IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    final MetadataUpdatesCheckpoint checkpoints = new MetadataUpdatesCheckpoint(tasks, mapper);

    ResourceHubTask task() {
        ResourceHubTask task = new ResourceHubTask(); task.setId(1L); task.setTaskType("METADATA_SYNC");
        task.setSource("TMDB"); task.setKeyword("UPDATES"); task.setStatus("RUNNING"); task.setAttempts(1);
        task.setPayload("{\"source\":\"UPDATES\",\"maxItems\":10,\"crawl\":{\"mode\":\"UPDATES\",\"roundAttempts\":0,\"roundBudget\":10,\"completed\":{},\"pending\":{}}}");
        when(tasks.update(any())).thenReturn(true); return task;
    }
    MetadataUpdatesCheckpoint.Candidate candidate(String source, int id) {
        ObjectNode data = mapper.createObjectNode().put("tmdbId", id).put("title", "Film " + id);
        return new MetadataUpdatesCheckpoint.Candidate(source, "movie/" + id, data);
    }
    ResourceHubTask next(ResourceHubTask previous) {
        when(tasks.getOne(any(), eq(false))).thenReturn(previous);
        doAnswer(call -> {
            ResourceHubTask task = call.getArgument(0); task.setId(previous.getId() + 1);
            task.setStatus("RUNNING"); task.setAttempts(1); return task;
        }).when(tasks).enqueue(any());
        return checkpoints.enqueue("TMDB", List.of("TIME", "POPULAR"), 20);
    }

    @Test void trustedCompletedFilmsSeedWithoutDetailsAndUnchangedHeadDoesNoWork() throws Exception {
        var task = task(); var item = candidate("TIME", 1); List<String> collected = new ArrayList<>();
        var first = checkpoints.run(task, List.of(item), Set.of("TIME"), 10, c -> true, c -> collected.add(c.key()));
        assertEquals(1, first.seeded()); assertEquals(0, first.processed()); assertTrue(collected.isEmpty());
        var daily = next(task);
        var second = checkpoints.run(daily, List.of(item), Set.of("TIME"), 10,
                c -> { fail("Unchanged completion must not need reseeding"); return false; }, c -> collected.add(c.key()));
        assertEquals(1, second.skipped()); assertEquals(0, second.pending()); assertTrue(collected.isEmpty());
        assertEquals(0, mapper.readTree(daily.getPayload()).path("crawl").path("roundAttempts").asInt());
        var query = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(tasks).getOne(query.capture(), eq(false));
        assertTrue(query.getValue().getCustomSqlSegment().contains("'UPDATES'"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("UPDATES"));
    }

    @Test void sharedTenBudgetPersistsAllSourcesAndCarriesOffHeadBacklogToNextDay() throws Exception {
        var task = task(); List<MetadataUpdatesCheckpoint.Candidate> head = new ArrayList<>();
        for (int i = 1; i <= 16; i++) head.add(candidate(i <= 8 ? "TIME" : "POPULAR", i));
        List<String> collected = new ArrayList<>();
        var first = checkpoints.run(task, head, Set.of("TIME", "POPULAR"), 20, c -> false, c -> {
            var state = read(task);
            assertEquals(16, state.path("completed").size() + state.path("pending").size());
            assertTrue(state.path("roundAttempts").asInt() > 0);
            collected.add(c.key());
        });
        assertEquals(10, first.processed()); assertEquals(6, first.pending());
        assertEquals(10, read(task).path("roundAttempts").asInt());
        var daily = next(task);
        assertEquals(0, read(daily).path("roundAttempts").asInt());
        var second = checkpoints.run(daily, List.of(), Set.of("TIME", "POPULAR"), 20, c -> false, c -> collected.add(c.key()));
        assertEquals(6, second.processed()); assertEquals(0, second.pending()); assertEquals(16, collected.size());
        assertEquals(16, Set.copyOf(collected).size());
    }

    @Test void retryingTheSameJobCannotSpendAnotherTenRequests() {
        var task = task(); List<MetadataUpdatesCheckpoint.Candidate> head = new ArrayList<>();
        for (int i = 1; i <= 15; i++) head.add(candidate("TIME", i));
        List<String> work = new ArrayList<>();
        checkpoints.run(task, head, Set.of("TIME"), 20, c -> false, c -> work.add(c.key()));
        var retry = checkpoints.run(task, List.of(), Set.of("TIME"), 20, c -> false, c -> work.add(c.key()));
        assertEquals(0, retry.processed()); assertEquals(5, retry.pending()); assertEquals(10, work.size());
    }

    @Test void failedItemsRetainThreeAttemptBudgetAcrossDaysAndSourceMoves() {
        var task = task(); var item = candidate("OLD", 1);
        var first = checkpoints.run(task, List.of(item), Set.of("OLD"), 10, c -> false,
                c -> { throw new IllegalStateException("fixture secret must not be persisted"); });
        assertEquals(1, first.failed()); assertFalse(task.getPayload().contains("fixture secret"));
        for (int day = 2; day <= 3; day++) {
            task = next(task);
            var round = checkpoints.run(task, List.of(candidate("TIME", 1)), Set.of("TIME"), 10, c -> false,
                    c -> { throw new IllegalStateException("fixture failure"); });
            assertEquals(1, round.failed());
        }
        var exhausted = checkpoints.run(next(task), List.of(), Set.of("TIME"), 10, c -> false,
                c -> fail("Exhausted pending item cannot be retried"));
        assertEquals(1, exhausted.exhausted()); assertEquals(1, exhausted.pending());
    }

    @Test void changedSemanticFingerprintQueuesOneRefreshButFieldOrderDoesNotChangeFingerprint() {
        var task = task(); var original = candidate("TIME", 1);
        checkpoints.run(task, List.of(original), Set.of("TIME"), 10, c -> true, c -> fail());
        ObjectNode reordered = mapper.createObjectNode().put("title", "Film 1").put("tmdbId", 1);
        assertEquals(original.fingerprint(), new MetadataUpdatesCheckpoint.Candidate("POPULAR", "movie/1", reordered).fingerprint());
        ObjectNode changed = original.data().deepCopy().put("title", "Corrected Film");
        var round = checkpoints.run(task, List.of(new MetadataUpdatesCheckpoint.Candidate("TIME", "movie/1", changed)),
                Set.of("TIME"), 10, c -> true, c -> {});
        assertEquals(1, round.processed()); assertEquals(0, round.seeded());
    }

    @Test void cancellationOrConcurrentCheckpointChangeStopsBeforeRemoteWorkWithoutFullRowWrites() {
        var task = task(); String original = task.getPayload();
        when(tasks.update(any())).thenReturn(false);
        assertThrows(MetadataUpdatesCheckpoint.CheckpointConflict.class, () -> checkpoints.run(task,
                List.of(candidate("TIME", 1)), Set.of("TIME"), 10, c -> false, c -> fail("No remote work after CAS loss")));
        assertEquals(original, task.getPayload()); verify(tasks, never()).updateById(any());
        var capture = ArgumentCaptor.forClass(UpdateWrapper.class); verify(tasks).update(capture.capture());
        var query = capture.getValue(); String conditions = query.getCustomSqlSegment();
        assertTrue(conditions.contains("status =")); assertTrue(conditions.contains("attempts ="));
        assertTrue(conditions.contains("BINARY CAST(payload AS CHAR)"));
        assertFalse(query.getSqlSet().contains("status=")); assertFalse(query.getSqlSet().contains("attempts="));
    }

    @Test void cancellationAfterDetailWorkNeverResurrectsTaskOrResetsBudget() throws Exception {
        var task = task(); when(tasks.update(any())).thenReturn(true, true, false);
        List<String> work = new ArrayList<>();
        assertThrows(MetadataUpdatesCheckpoint.CheckpointConflict.class, () -> checkpoints.run(task,
                List.of(candidate("TIME", 1)), Set.of("TIME"), 10, c -> false, c -> work.add(c.key())));
        assertEquals(List.of("movie/1"), work); assertEquals(1, read(task).path("roundAttempts").asInt());
        assertEquals(1, read(task).path("pending").path("movie/1").path("attempts").asInt());
        assertEquals(0, read(task).path("completed").size()); verify(tasks, never()).updateById(any());
    }

    @Test void corruptPendingDataFailsBeforeAnyRemoteOrCheckpointWrite() throws Exception {
        var task = task(); checkpoints.run(task, List.of(candidate("TIME", 1)), Set.of("TIME"), 10, c -> false,
                c -> { throw new IllegalStateException(); });
        ObjectNode payload = (ObjectNode) mapper.readTree(task.getPayload());
        ((ObjectNode) payload.path("crawl").path("pending").path("movie/1").path("data")).put("tmdbId", 999);
        task.setPayload(payload.toString()); clearInvocations(tasks);
        assertThrows(IllegalArgumentException.class, () -> checkpoints.run(task, List.of(), Set.of("TIME"), 10,
                c -> false, c -> fail())); verifyNoInteractions(tasks);
    }

    com.fasterxml.jackson.databind.JsonNode read(ResourceHubTask task) {
        try { return mapper.readTree(task.getPayload()).path("crawl"); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
