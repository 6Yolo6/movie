package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TvmazeUpdatesCheckpointTest {
    final ObjectMapper mapper = new ObjectMapper();
    final IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    final TvmazeUpdatesCheckpoint checkpoint = new TvmazeUpdatesCheckpoint(tasks, mapper);
    final long now = 1700000000;
    @BeforeEach void fixture() {
        when(tasks.enqueue(any())).thenAnswer(c -> { ResourceHubTask t=c.getArgument(0); t.setId(9L); t.setStatus("PENDING"); t.setAttempts(0); return t; });
    }
    ObjectNode state() { return (ObjectNode) checkpoint.read(checkpoint.enqueue(null, 10)).path("crawl"); }

    @Test void boundsGlobalDiscoveriesButKnownChangesAlwaysTakePriority() {
        var state=state(); Map<Long,Long> feed=new LinkedHashMap<>();
        for (long id=1;id<=500;id++) feed.put(id,now-500+id);
        feed.put(999999L,now-999);
        checkpoint.merge(state,feed,Map.of(999999L,0L),now);
        assertEquals(101,state.path("pending").size());
        assertEquals("999999",checkpoint.ordered(state).get(0));
        assertTrue(state.path("pending").path("500").isObject());
        assertTrue(state.path("pending").path("1").isMissingNode());
    }
    @Test void unchangedKnownVersionsNeverEnterTheDetailBacklog() {
        var state=state();
        assertEquals(1,checkpoint.merge(state,Map.of(1L,now),Map.of(1L,now),now));
        assertTrue(state.path("pending").isEmpty());
    }
    @Test void overlappingFeedCannotLowerAnUpstreamVersionOrResetFailures() {
        var state=state(); checkpoint.merge(state,Map.of(1L,now),Map.of(),now);
        ((ObjectNode)state.path("pending").path("1")).put("attempts",2);
        checkpoint.merge(state,Map.of(1L,now-1),Map.of(),now+30);
        assertEquals(now,state.path("pending").path("1").path("updated").asLong());
        assertEquals(2,state.path("pending").path("1").path("attempts").asInt());
        checkpoint.merge(state,Map.of(1L,now+1),Map.of(),now+60);
        assertEquals(0,state.path("pending").path("1").path("attempts").asInt());
    }
    @Test void sevenDayExpirationIncludesUnmatchedAndUnprocessedDiscoveries() {
        var state=state(); checkpoint.merge(state,Map.of(1L,now,2L,now),Map.of(),now);
        ObjectNode pending=(ObjectNode)state.path("pending"), deferred=(ObjectNode)state.path("deferred");
        ObjectNode unmatched=(ObjectNode)pending.remove("2"); unmatched.put("reason","NO_EXACT_TMDB_ID"); deferred.set("2",unmatched);
        checkpoint.merge(state,Map.of(),Map.of(),now+TvmazeUpdatesCheckpoint.DISCOVERY_TTL+1);
        assertTrue(pending.isEmpty()); assertTrue(deferred.isEmpty());
    }
    @Test void changedGlobalDiscoveriesDoNotExpandTheCombinedPool() {
        var state=state(); Map<Long,Long> feed=new LinkedHashMap<>();
        for(long id=1;id<=100;id++) feed.put(id,now);
        checkpoint.merge(state,feed,Map.of(),now);
        ObjectNode pending=(ObjectNode)state.path("pending"), deferred=(ObjectNode)state.path("deferred");
        ObjectNode unmatched=(ObjectNode)pending.remove("1"); unmatched.put("reason","NO_EXACT_TMDB_ID"); deferred.set("1",unmatched);
        checkpoint.merge(state,Map.of(1L,now+1,101L,now+1),Map.of(),now+10);
        assertEquals(100,pending.size()+deferred.size());
    }
    @Test void windowExpandsForMissedDaysAndWeeks() {
        assertEquals("day",TvmazeUpdatesCheckpoint.window(0,now));
        assertEquals("day",TvmazeUpdatesCheckpoint.window(now-86400,now));
        assertEquals("week",TvmazeUpdatesCheckpoint.window(now-86401,now));
        assertEquals("month",TvmazeUpdatesCheckpoint.window(now-8*86400,now));
    }
    @Test void restartInheritsPendingItemsFailuresAndTheDailyBudget() {
        ResourceHubTask first=checkpoint.enqueue(null,10); ObjectNode payload=checkpoint.read(first), state=(ObjectNode)payload.path("crawl");
        checkpoint.merge(state,Map.of(1L,now),Map.of(),now);
        state.put("dailyAttempts",8); state.put("budgetStartedAt",now); state.put("roundAttempts",8);
        ((ObjectNode)state.path("pending").path("1")).put("attempts",2); first.setPayload(payload.toString());
        var inherited=checkpoint.read(checkpoint.enqueue(first,10)).path("crawl");
        assertEquals(8,inherited.path("dailyAttempts").asInt()); assertEquals(now,inherited.path("lastSuccessfulScanAt").asLong());
        assertEquals(2,inherited.path("pending").path("1").path("attempts").asInt());
        assertEquals(0,inherited.path("roundAttempts").asInt()); assertFalse(inherited.path("scanDone").asBoolean());
    }
    @Test void malformedCheckpointDoesNotDisappearIntoANewEmptyState() {
        var task=checkpoint.enqueue(null,10); task.setPayload("{\"crawl\":{\"mode\":\"UPDATES\",\"provider\":\"TVMAZE\"}}");
        assertThrows(IllegalArgumentException.class,()->checkpoint.enqueue(task,10));
        verify(tasks,times(1)).enqueue(any());
    }
    @Test void ownershipConflictCannotOverwriteCancellation() {
        var task=checkpoint.enqueue(null,10); task.setStatus("RUNNING"); task.setAttempts(1);
        var payload=checkpoint.read(task);
        when(tasks.update(any())).thenReturn(false);
        assertThrows(MetadataUpdatesCheckpoint.CheckpointConflict.class,()->checkpoint.persist(task,payload));
        verify(tasks,never()).updateById(any());
    }
}
