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

    @Test void rangeChangeResetsPosition() {
        planner.completed(task(3, 15), 30, 15, 0);
        var next = planner.next("GYING", "HITS_MOVIE", 1, 7);
        assertEquals(1, next.page());
        assertEquals(0, next.offset());
        assertEquals("NOT_STARTED", next.status());
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
}
