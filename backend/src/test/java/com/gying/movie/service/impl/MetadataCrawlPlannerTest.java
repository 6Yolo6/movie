package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MetadataCrawlPlannerTest {
    private final IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MetadataCrawlPlanner planner = new MetadataCrawlPlanner(tasks, mapper);

    private ResourceHubTask task(int page, int offset) {
        ResourceHubTask task = new ResourceHubTask();
        task.setId(12L);
        task.setStatus("SUCCEEDED");
        task.setPayload(planner.attach("{\"source\":\"HITS_MOVIE\",\"page\":" + page + ",\"maxItems\":15}",
                new MetadataCrawlPlanner.Position(2, 4, page, offset, "PENDING", null)));
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
        return task;
    }

    @Test void startsAtConfiguredBeginningAndQueriesOnlyThisAutomaticCatalog() {
        assertEquals(3, planner.next("GYING", "HITS_MOVIE", 3, 8).page());
        ArgumentCaptor<QueryWrapper<ResourceHubTask>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(tasks).getOne(query.capture(), eq(false));
        String sql = query.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("$.crawl.mode"));
        assertTrue(sql.contains("ORDER BY id DESC"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("GYING"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("HITS_MOVIE"));
    }

    @Test void resumesWithinPageAcrossPlannerRecreation() {
        ResourceHubTask task = task(2, 0);
        planner.completed(task, 30, 15, 0);
        var restarted = new MetadataCrawlPlanner(tasks, new ObjectMapper());
        assertEquals(2, restarted.next("GYING", "HITS_MOVIE", 2, 4).page());
        assertEquals(15, restarted.next("GYING", "HITS_MOVIE", 2, 4).offset());
    }

    @Test void advancesAfterLastBatchOfPage() {
        planner.completed(task(2, 15), 30, 15, 0);
        var next = planner.next("GYING", "HITS_MOVIE", 2, 4);
        assertEquals(3, next.page());
        assertEquals(0, next.offset());
    }

    @Test void wrapsAtInclusiveEndPage() {
        planner.completed(task(4, 15), 30, 15, 0);
        assertEquals(2, planner.next("GYING", "HITS_MOVIE", 2, 4).page());
    }

    @Test void emptyUpstreamPageEndsPassEarly() {
        planner.completed(task(3, 0), 0, 0, 0);
        assertEquals(2, planner.next("GYING", "HITS_MOVIE", 2, 4).page());
    }

    @Test void partialFailureRetainsBatchAndReportsRetry() {
        planner.completed(task(3, 15), 30, 15, 1);
        var next = planner.next("GYING", "HITS_MOVIE", 2, 4);
        assertEquals(3, next.page());
        assertEquals(15, next.offset());
        assertEquals("RETRY", next.status());
    }

    @Test void failedTaskDoesNotTrustAnOldCompletionMarker() {
        ResourceHubTask task = task(3, 15);
        planner.completed(task, 30, 15, 0);
        task.setStatus("FAILED");
        assertEquals(15, planner.next("GYING", "HITS_MOVIE", 2, 4).offset());
        assertEquals(3, planner.next("GYING", "HITS_MOVIE", 2, 4).page());
    }

    @Test void rangeChangePreservesPositionWithinNewBounds() {
        planner.completed(task(3, 15), 30, 15, 0);
        var next = planner.next("GYING", "HITS_MOVIE", 1, 7);
        assertEquals(4, next.page());
        assertEquals(0, next.offset());
        assertEquals("SUCCEEDED", next.status());
    }

    @Test void activeOrCanceledTaskRetainsOriginalPosition() {
        ResourceHubTask task = task(3, 15);
        for (String status : new String[] {"PENDING", "RUNNING", "CANCELED"}) {
            task.setStatus(status);
            assertEquals(3, planner.next("GYING", "HITS_MOVIE", 2, 4).page());
            assertEquals(15, planner.next("GYING", "HITS_MOVIE", 2, 4).offset());
        }
    }

    @Test void manualTaskPayloadIsNotChanged() {
        ResourceHubTask task = new ResourceHubTask();
        task.setPayload("{\"page\":3,\"maxItems\":10}");
        planner.completed(task, 20, 10, 0);
        assertFalse(planner.automatic(task));
        assertEquals("{\"page\":3,\"maxItems\":10}", task.getPayload());
    }

    @Test void shrinkingRankedPageDoesNotLeaveCursorStuck() {
        planner.completed(task(3, 15), 10, 0, 0);
        assertEquals(4, planner.next("GYING", "HITS_MOVIE", 2, 4).page());
    }

    @Test void corruptCheckpointFailsClosedInsteadOfSkippingPages() {
        ResourceHubTask task = task(2, 0);
        task.setPayload("bad-json");
        assertThrows(IllegalArgumentException.class, () -> planner.next("GYING", "HITS_MOVIE", 2, 4));
    }
    @Test void durableIsolatedFailuresAllowProgressWithoutLosingFailureCount() throws Exception {
        ResourceHubTask task = task(3, 15);
        planner.completed(task, 30, 15, 2, 2);
        var next = planner.next("GYING", "HITS_MOVIE", 2, 4);
        assertEquals(4, next.page()); assertEquals(0, next.offset());
        var audit = mapper.readTree(task.getPayload()).path("crawl");
        assertEquals(2, audit.path("failed").asInt());
        assertEquals(2, audit.path("deferred").asInt());
    }

    @Test void incompleteDeferralKeepsBatchAndInvalidCountsFailClosed() {
        ResourceHubTask task = task(3, 15);
        planner.completed(task, 30, 15, 2, 1);
        assertEquals(15, planner.next("GYING", "HITS_MOVIE", 2, 4).offset());
        assertThrows(IllegalArgumentException.class, () -> planner.completed(task, 30, 15, 1, 2));
    }

    @Test void rangeChangesPreserveIndependentMovieAndTvOffsetsForBothProviders() {
        for (String provider : new String[] {"TMDB", "GYING"}) {
            ResourceHubTask movie = checkpoint(150, 12, "SUCCEEDED");
            ResourceHubTask tv = checkpoint(121, 7, "FAILED");
            doAnswer(call -> {
                QueryWrapper<ResourceHubTask> query = call.getArgument(0);
                query.getCustomSqlSegment();
                return query.getParamNameValuePairs().containsValue("MOVIE") ? movie : tv;
            }).when(tasks).getOne(any(), eq(false));
            assertEquals(150, planner.next(provider, "MOVIE", 100, 500).page());
            assertEquals(12, planner.next(provider, "MOVIE", 100, 500).offset());
            assertEquals(121, planner.next(provider, "TV", 100, 500).page());
            assertEquals(7, planner.next(provider, "TV", 100, 500).offset());
            assertEquals(151, planner.next(provider, "MOVIE", 151, 500).page());
            assertEquals(121, planner.next(provider, "TV", 100, 500).page());
        }
    }

    @Test void extendingCompletedEndContinuesForwardButEmptyPageStillWraps() {
        ResourceHubTask task = task(4, 15);
        planner.completed(task, 30, 15, 0);
        assertEquals(5, planner.next("GYING", "HITS_MOVIE", 2, 8).page());
        planner.completed(task, 0, 0, 0);
        assertEquals(2, planner.next("GYING", "HITS_MOVIE", 2, 8).page());
    }

    @Test void shrinkingBoundsResetsOnlyAnOutOfRangeCheckpoint() {
        planner.completed(task(3, 15), 30, 15, 0);
        var next = planner.next("GYING", "HITS_MOVIE", 1, 3);
        assertEquals(1, next.page());
        assertEquals(0, next.offset());
        assertEquals("NOT_STARTED", next.status());
    }

    private final java.time.Instant observedAt = java.time.Instant.parse("2026-11-02T10:00:00Z");

    private MetadataCrawlPlanner at(java.time.Instant instant) {
        return new MetadataCrawlPlanner(tasks, mapper, java.time.Clock.fixed(instant, java.time.ZoneOffset.UTC));
    }

    private ResourceHubTask tmdbTask(int start, int end, int page, int offset) {
        ResourceHubTask task = checkpoint(page, offset, "SUCCEEDED");
        task.setSource("TMDB");
        task.setPayload(planner.attach("{\"page\":" + page + "}",
                new MetadataCrawlPlanner.Position(start, end, page, offset, "PENDING", null)));
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
        return task;
    }

    private com.gying.movie.dto.TmdbListPage tmdbPage(int page, int totalPages, int count) {
        var items = java.util.stream.LongStream.rangeClosed(1, count).mapToObj(id -> {
            var item = new com.gying.movie.dto.TmdbListItem();
            item.setTmdbId(id); item.setMediaType("tv"); return item;
        }).toList();
        return new com.gying.movie.dto.TmdbListPage(page, totalPages, totalPages * 20L, items);
    }

    @Test void emptyRangeSurvivesRestartAndOnlyRechecksAfterTwentyFourHours() throws Exception {
        ResourceHubTask task = tmdbTask(360, 500, 360, 0);
        at(observedAt).completedTmdb(task, tmdbPage(360, 131, 0), 0, 0);
        var restarted = at(observedAt.plusSeconds(3600));
        var next = restarted.next("TMDB", "TOP_RATED_TV", 360, 500);
        assertEquals(360, next.page()); assertEquals(0, next.offset());
        assertEquals(131, next.observedTotalPages());
        assertEquals("EMPTY_RANGE", next.availability());
        assertFalse(restarted.canEnqueue(next));
        assertFalse(at(observedAt.plusSeconds(86399)).canEnqueue(next));
        assertTrue(at(observedAt.plusSeconds(86400)).canEnqueue(next));
        assertEquals("2026-11-03T10:00:00Z", restarted.progress("TMDB", "TOP_RATED_TV", 360, 500).nextCheckAt());
        assertEquals(0, mapper.readTree(task.getPayload()).path("crawl").path("attempted").asInt());
    }

    @Test void changingOnlyThisRangesBoundsCancelsItsOldCooldown() {
        ResourceHubTask task = tmdbTask(360, 500, 360, 0);
        at(observedAt).completedTmdb(task, tmdbPage(360, 131, 0), 0, 0);
        var next = at(observedAt).next("TMDB", "TOP_RATED_TV", 360, 400);
        assertTrue(at(observedAt).canEnqueue(next));
        assertNull(next.availability()); assertEquals(360, next.page());
        next = at(observedAt).next("TMDB", "TOP_RATED_TV", 100, 131);
        assertTrue(at(observedAt).canEnqueue(next)); assertEquals(100, next.page());
    }

    @Test void anEmptyStaleCursorRestartsAnOtherwiseAvailableRangeWithoutCooldown() {
        ResourceHubTask task = tmdbTask(100, 500, 360, 0);
        at(observedAt).completedTmdb(task, tmdbPage(360, 131, 0), 0, 0);
        var next = at(observedAt).next("TMDB", "TOP_RATED_TV", 100, 500);
        assertEquals(100, next.page()); assertEquals("RANGE_EXHAUSTED", next.availability());
        assertTrue(at(observedAt).canEnqueue(next));
    }

    @Test void completeLastUpstreamPageWrapsWithoutRequestingAFalseNextPage() {
        ResourceHubTask task = tmdbTask(100, 500, 131, 8);
        at(observedAt).completedTmdb(task, tmdbPage(131, 131, 10), 2, 0);
        var next = at(observedAt).next("TMDB", "TOP_RATED_TV", 100, 500);
        assertEquals(100, next.page()); assertEquals(0, next.offset());
        assertEquals("AVAILABLE", next.availability()); assertNull(next.nextCheckAt());
    }

    @Test void partialLastPageAndFailedItemsKeepTheirCheckpoint() {
        ResourceHubTask task = tmdbTask(100, 500, 131, 5);
        at(observedAt).completedTmdb(task, tmdbPage(131, 131, 10), 3, 0);
        var next = at(observedAt).next("TMDB", "TOP_RATED_TV", 100, 500);
        assertEquals(131, next.page()); assertEquals(8, next.offset());
        assertNull(next.nextCheckAt());
        task = tmdbTask(100, 500, 131, 8);
        at(observedAt).completedTmdb(task, tmdbPage(131, 131, 10), 2, 1);
        next = at(observedAt).next("TMDB", "TOP_RATED_TV", 100, 500);
        assertEquals(131, next.page()); assertEquals(8, next.offset());
        assertEquals("RETRY", next.status()); assertNull(next.nextCheckAt());
    }

    @Test void recheckCanResumeWhenUpstreamGrowsIntoConfiguredRange() {
        ResourceHubTask task = tmdbTask(360, 500, 360, 0);
        at(observedAt).completedTmdb(task, tmdbPage(360, 131, 0), 0, 0);
        var later = at(observedAt.plusSeconds(86400));
        var position = later.next("TMDB", "TOP_RATED_TV", 360, 500);
        assertTrue(later.canEnqueue(position));
        task.setPayload(later.attach("{\"page\":360}", position));
        later.completedTmdb(task, tmdbPage(360, 361, 20), 20, 0);
        var next = later.next("TMDB", "TOP_RATED_TV", 360, 500);
        assertEquals(361, next.page()); assertEquals("AVAILABLE", next.availability());
        assertNull(next.nextCheckAt()); assertTrue(later.canEnqueue(next));
    }

    @Test void upstreamMoreThanFiveHundredPagesDoesNotExpandConfiguredBoundary() {
        ResourceHubTask task = tmdbTask(450, 500, 500, 0);
        planner.completedTmdb(task, tmdbPage(500, 700, 20), 20, 0);
        assertEquals(450, planner.next("TMDB", "TOP_RATED_TV", 450, 500).page());
    }

    @Test void failedOrInFlightTasksDoNotApplyAPreviousEmptyRangeMarker() {
        ResourceHubTask task = tmdbTask(360, 500, 360, 0);
        at(observedAt).completedTmdb(task, tmdbPage(360, 131, 0), 0, 0);
        for (String status : new String[] {"FAILED", "RUNNING", "PENDING", "CANCELED"}) {
            task.setStatus(status);
            var next = at(observedAt).next("TMDB", "TOP_RATED_TV", 360, 500);
            assertNull(next.availability()); assertTrue(at(observedAt).canEnqueue(next));
        }
    }

    @Test void legacyEmptyTasksMustBeRecheckedRatherThanGuessingUpstreamBounds() {
        ResourceHubTask task = tmdbTask(360, 500, 360, 0);
        planner.completed(task, 0, 0, 0);
        var next = planner.next("TMDB", "TOP_RATED_TV", 360, 500);
        assertNull(next.observedTotalPages()); assertTrue(planner.canEnqueue(next));
    }

    private ResourceHubTask checkpoint(int page, int offset, String status) {
        ResourceHubTask task = new ResourceHubTask();
        task.setId((long) page);
        task.setStatus(status);
        task.setPayload(planner.attach("{\"page\":" + page + "}",
                new MetadataCrawlPlanner.Position(1, 500, page, offset, status, null)));
        return task;
    }
}
