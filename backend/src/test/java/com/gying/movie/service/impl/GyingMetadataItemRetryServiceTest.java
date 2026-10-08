package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GyingMetadataItemRetryServiceTest {
    private final ResourceHubProperties properties = new ResourceHubProperties();
    private final IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    private final GyingSourceWorkflowService workflow = mock(GyingSourceWorkflowService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final GyingMetadataItemRetryService service = new GyingMetadataItemRetryService(properties, tasks, workflow, mapper);

    private Map<String, Object> item(String mid) {
        return Map.of("typeCode", "mv", "mid", mid, "title", "测试电影", "year", 1942,
                "stage", "P2P", "errorCategory", "ResponseStatusException");
    }

    private ResourceHubTask task() throws Exception {
        ResourceHubTask task = new ResourceHubTask();
        task.setId(9L); task.setTaskType(GyingMetadataItemRetryService.TYPE); task.setSource("GYING");
        task.setStatus("FAILED"); task.setAttempts(1); task.setMaxAttempts(3); task.setLastError("previous error");
        task.setPayload(mapper.writeValueAsString(item("dWXo")));
        return task;
    }

    @Test void persistsBoundedIndependentItemsAndPreservesOpaqueIdCase() throws Exception {
        AtomicLong sequence = new AtomicLong(20);
        when(tasks.enqueue(any())).thenAnswer(call -> {
            ResourceHubTask task = call.getArgument(0); task.setId(sequence.incrementAndGet()); return task;
        });
        assertEquals(List.of(21L, 22L), service.defer(List.of(item("dWXo"), item("dwXo"))));
        ArgumentCaptor<ResourceHubTask> records = ArgumentCaptor.forClass(ResourceHubTask.class);
        verify(tasks, times(2)).enqueue(records.capture());
        var first = records.getAllValues().get(0);
        var second = records.getAllValues().get(1);
        assertNotEquals(first.getKeyword(), second.getKeyword());
        assertEquals("METADATA_ITEM_RETRY", first.getTaskType());
        assertEquals("GYING", first.getSource());
        assertEquals(3, first.getMaxAttempts());
        assertEquals("dWXo", mapper.readTree(first.getPayload()).path("mid").asText());
        verifyNoInteractions(workflow);
    }

    @Test void neverResetsExhaustedItemOrReplaysItForEveryCatalogPass() throws Exception {
        ResourceHubTask exhausted = task(); exhausted.setAttempts(3);
        when(tasks.getOne(any(), eq(false))).thenReturn(exhausted);
        assertEquals(List.of(9L), service.defer(List.of(item("dWXo"))));
        assertEquals(3, exhausted.getAttempts());
        verify(tasks, never()).enqueue(any());
        verifyNoInteractions(workflow);
    }

    @Test void refusesToAdvanceWithoutPersistedId() {
        when(tasks.enqueue(any())).thenAnswer(call -> call.getArgument(0));
        assertThrows(IllegalStateException.class, () -> service.defer(List.of(item("dWXo"))));
    }

    @Test void retryPayloadIsAllowlistedAndBounded() {
        Map<String, Object> input = new HashMap<>(item("dWXo"));
        input.put("authorization", "never persist this");
        input.put("errorCategory", "https://invalid.example/?credential=fixture");
        var safe = GyingMetadataItemRetryService.safeItem(input);
        assertFalse(safe.containsKey("authorization"));
        assertEquals("Unknown", safe.get("errorCategory"));
        assertThrows(IllegalArgumentException.class, () -> service.defer(List.of(item("../bad"))));
        assertThrows(IllegalArgumentException.class, () -> service.defer(Collections.nCopies(21, item("dWXo"))));
    }

    @Test void workerHonorsAllSwitchesAndQueriesOnlyItsOwnBoundedQueue() {
        properties.setEnabled(true); properties.getWorker().setEnabled(true);
        properties.getGying().setAutoSyncEnabled(false);
        service.runDue(); verifyNoInteractions(tasks, workflow);
        properties.getGying().setAutoSyncEnabled(true);
        when(tasks.list(any(QueryWrapper.class))).thenReturn(List.of());
        service.runDue();
        ArgumentCaptor<QueryWrapper<ResourceHubTask>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(tasks).list(query.capture());
        String sql = query.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("attempts < max_attempts")); assertTrue(sql.contains("LIMIT 1"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("METADATA_ITEM_RETRY"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("GYING"));
    }

    @Test void claimFailureNeverExecutesItem() throws Exception {
        service.runTask(task());
        verifyNoInteractions(workflow);
    }

    @Test void successfulRetryClearsOldErrorAndDoesNotCreateANewQueueEntry() throws Exception {
        when(tasks.update(any(UpdateWrapper.class))).thenReturn(true);
        when(tasks.update(any(ResourceHubTask.class), any(UpdateWrapper.class))).thenReturn(true);
        ResourceHubTask task = task();
        service.runTask(task);
        assertEquals("SUCCEEDED", task.getStatus()); assertNull(task.getLastError());
        assertEquals(2, task.getAttempts());
        verify(workflow).retryCatalogItem(argThat(candidate -> "dWXo".equals(candidate.get("mid"))));
        verify(tasks, never()).enqueue(any());
        ArgumentCaptor<UpdateWrapper<ResourceHubTask>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(tasks).update(eq(task), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("last_error"));
    }

    @Test void failedRetryKeepsEvidenceAndNeverLogsRawUpstreamSecret() throws Exception {
        when(tasks.update(any(UpdateWrapper.class))).thenReturn(true);
        when(tasks.update(any(ResourceHubTask.class), any(UpdateWrapper.class))).thenReturn(true);
        doThrow(new IllegalStateException("https://invalid.example/?credential=sensitive-fixture")).when(workflow).retryCatalogItem(any());
        ResourceHubTask task = task(); String originalPayload = task.getPayload();
        service.runTask(task);
        assertEquals("FAILED", task.getStatus()); assertEquals(2, task.getAttempts());
        assertEquals(originalPayload, task.getPayload());
        assertFalse(task.getLastError().contains("sensitive-fixture"));
        assertNotNull(task.getScheduledAt());
    }

    @Test void recoveryNeverTouchesLegacyVideoQueues() {
        service.recoverInterruptedItems();
        ArgumentCaptor<UpdateWrapper<ResourceHubTask>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(tasks).update(update.capture());
        update.getValue().getSqlSegment();
        assertTrue(update.getValue().getParamNameValuePairs().containsValue("METADATA_ITEM_RETRY"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue("RUNNING"));
        verifyNoInteractions(workflow);
    }
}
