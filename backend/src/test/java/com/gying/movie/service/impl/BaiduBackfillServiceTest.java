package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.*;
import com.gying.movie.service.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BaiduBackfillServiceTest {
    BaiduBackfillTaskStore store = mock(BaiduBackfillTaskStore.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ResourceHubProperties properties = new ResourceHubProperties();
    IResourceHubConfigService config = mock(IResourceHubConfigService.class);
    IBaiduTransferTaskService transfers = mock(IBaiduTransferTaskService.class);
    IBaiduTransferRunnerService runner = mock(IBaiduTransferRunnerService.class);
    IResourceDiscoveryResultService discoveries = mock(IResourceDiscoveryResultService.class);
    IResourceDiscoveryService discovery = mock(IResourceDiscoveryService.class);
    IResourceHubPublishService publisher = mock(IResourceHubPublishService.class);
    GyingSourceWorkflowService gying = mock(GyingSourceWorkflowService.class);
    IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    IResourceLinkService links = mock(IResourceLinkService.class);
    IMovieMetadataService movies = mock(IMovieMetadataService.class);
    P2pArchiveTaskService archives = mock(P2pArchiveTaskService.class);
    BaiduWebClient client = mock(BaiduWebClient.class);
    ObjectMapper mapper = new ObjectMapper();
    BaiduBackfillTaskStore.State state = new BaiduBackfillTaskStore.State();
    BaiduBackfillService service;
    BaiduTransferTask child;

    @BeforeEach void setup() {
        properties.setEnabled(true); properties.getWorker().setEnabled(true); properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true); properties.getBaidu().setDiscoveryEnabled(true);
        properties.getBaidu().setTorrentEnabled(true); properties.getBaidu().setVideoEnabled(false);
        when(client.isConfigured()).thenReturn(true);
        state.taskId = 100L; state.enabled = true; state.leaseOwner = "test-owner"; state.status = "RUNNING";
        when(store.claim(anyBoolean())).thenReturn(state); when(store.read()).thenAnswer(call -> state);
        when(store.checkpoint(any(), any())).thenAnswer(call -> {
            state = call.getArgument(0); Consumer<BaiduBackfillTaskStore.State> mutation = call.getArgument(1);
            if (mutation != null) mutation.accept(state); return state;
        });
        when(store.finish(any())).thenAnswer(call -> {
            state = call.getArgument(0); state.status = "COMPLETE".equals(state.phase) ? "COMPLETE" : state.blockedReason != null ? "BLOCKED" : state.enabled ? "PENDING" : "PAUSED";
            return state;
        });
        MovieMetadata movie = new MovieMetadata(); movie.setId("movie1"); movie.setStatus("ACTIVE");
        when(movies.getById("movie1")).thenReturn(movie);
        child = new BaiduTransferTask(); child.setId(4L); child.setMovieId("movie1"); child.setStatus("PENDING"); child.setAttempts(0); child.setPhase("NEW"); child.setDiscoveryResultId(9L);
        when(transfers.getById(4L)).thenReturn(child);
        var published = new com.gying.movie.dto.ResourceHubPublishResult(); published.setPublished(1);
        when(publisher.publishDiscovery(9L)).thenReturn(published);
        when(runner.submitOne(4L)).thenAnswer(call -> { child.setStatus("SUCCEEDED"); return new QuarkTransferRunResult(); });
        service = new BaiduBackfillService(store, jdbc, properties, config, transfers, runner, discoveries, discovery, tasks, publisher, gying, links, movies, archives, client, mapper);
    }
    void restoreChild(String kind) { state.currentKind = kind; state.currentId = 4L; state.currentChildId = 4L; state.currentMovieId = "movie1"; }

    @Test void existingTaskIsProcessedBeforeAnySourceSearch() {
        state.taskMaxId = 5;
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of(4L));
        var result = service.runOnce();
        assertEquals("EXISTING_TASKS", result.phase()); assertEquals(1, result.succeeded()); assertEquals(4, state.taskCursor);
        verify(runner).submitOne(4L); verifyNoInteractions(discovery); verifyNoInteractions(archives);
    }
    @Test void restartingAnAttachedDiscoveryNeverAllocatesTheChildAgain() {
        restoreChild("DISCOVERY"); state.phase = "EXISTING_DISCOVERIES";
        ResourceDiscoveryResult item = new ResourceDiscoveryResult(); item.setId(4L); item.setMovieId("movie1"); item.setProvider("BAIDU"); item.setStatus("DISCOVERED");
        when(discoveries.getById(4L)).thenReturn(item);
        service.runOnce();
        verify(discovery, never()).ensureTransferTask(anyLong()); verify(runner).submitOne(4L); assertEquals(4, state.discoveryCursor);
    }
    @Test void disablingDiscoveryStillAllowsExistingTransfersButBlocksSourceSearch() {
        properties.getBaidu().setDiscoveryEnabled(false); restoreChild("BAIDU_TASK");
        assertEquals(1, service.runOnce().succeeded());
        state.phase = "SOURCE_SEARCH"; state.currentKind = null;
        assertEquals("DISCOVERY_DISABLED", service.runOnce().blockedReason());
        verifyNoInteractions(discovery); verifyNoInteractions(jdbc);
    }
    @Test void unknownIntentRemainsAttachedAndNeverConsumesAnotherAttempt() {
        restoreChild("BAIDU_TASK"); child.setStatus("UNKNOWN"); child.setPhase("TRANSFER_SUBMITTING"); child.setAttempts(2);
        var result = service.runOnce();
        assertEquals("CHILD_UNKNOWN", result.blockedReason()); assertEquals(4L, state.currentChildId); assertEquals(0, state.taskCursor);
        assertEquals(2, child.getAttempts()); verifyNoInteractions(runner, discovery, jdbc);
    }
    @Test void missingSessionLeavesTheChildAndItsBudgetUntouched() {
        restoreChild("BAIDU_TASK"); when(client.isConfigured()).thenReturn(false);
        assertEquals("SESSION_REQUIRED", service.runOnce().blockedReason());
        assertEquals(4L, state.currentChildId); assertEquals(0, child.getAttempts()); verifyNoInteractions(runner, transfers, discovery, jdbc);
    }
    @Test void actionRequiredAndLiveLeaseCannotBeReplayed() {
        restoreChild("BAIDU_TASK"); child.setStatus("ACTION_REQUIRED");
        assertEquals("CHILD_ACTION_REQUIRED", service.runOnce().blockedReason()); verifyNoInteractions(runner);
        child.setStatus("PENDING"); child.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
        assertEquals("CHILD_RUNNING", service.runOnce().blockedReason()); verifyNoInteractions(runner);
    }
    @Test void changedFilePolicyPreservesAnUncertainVideoIntent() throws Exception {
        restoreChild("BAIDU_TASK"); child.setPhase("TRANSFER_SUBMITTING");
        child.setResponsePayload(mapper.writeValueAsString(new BaiduWebClient.TransferPlan("https://pan.baidu.com/s/1test", null, "/test", "1", "2", List.of("3"),
                List.of(new BaiduWebClient.ExpectedFile("movie.mkv", 100, false)))));
        assertEquals("CHILD_REVIEW_REQUIRED", service.runOnce().blockedReason()); assertEquals(4L, state.currentChildId); verifyNoInteractions(runner);
    }
    @Test void pausingBetweenItemsPreventsASecondChildEvenDuringManualBatch() {
        restoreChild("BAIDU_TASK"); state.batchLimit = 5;
        when(runner.submitOne(4L)).thenAnswer(call -> { child.setStatus("SUCCEEDED"); state.enabled = false; state.settingsRevision++; return new QuarkTransferRunResult(); });
        assertEquals("PAUSED", service.runOnce().status()); verifyNoInteractions(jdbc); verify(runner, times(1)).submitOne(4L);
    }
    @Test void p2pTypeDisabledNeverCreatesOrExecutesAnArchiveChild() {
        state.phase = "EXISTING_P2P"; restoreChild("P2P_REFERENCE"); state.currentChildId = null;
        properties.getBaidu().setVideoEnabled(true); properties.getBaidu().setTorrentEnabled(false);
        assertEquals(1, service.runOnce().skipped()); verifyNoInteractions(archives);
    }
    @Test void successfulTransferPublishesOnlyAfterTheRunnerVerifiesIt() {
        restoreChild("BAIDU_TASK");
        assertEquals(1, service.runOnce().succeeded());
        var order = inOrder(runner, publisher); order.verify(runner).submitOne(4L); order.verify(publisher).publishDiscovery(9L);
        verifyNoInteractions(gying);
    }
    @Test void incompleteLocalPublicationKeepsTheVerifiedChildForReview() {
        restoreChild("BAIDU_TASK"); when(publisher.publishDiscovery(9L)).thenReturn(new com.gying.movie.dto.ResourceHubPublishResult());
        assertEquals("CHILD_REVIEW_REQUIRED", service.runOnce().blockedReason()); assertEquals(4L, state.currentChildId); assertEquals(0, state.processed);
    }
    @Test void uncertainExternalPublicationIsNeverSubmittedAgainAfterRestart() {
        restoreChild("BAIDU_TASK"); state.currentGyingSubmitting = true;
        assertEquals("CHILD_UNKNOWN", service.runOnce().blockedReason()); verifyNoInteractions(runner, publisher, gying);
    }

    @Test void staleReadOnlySourceSearchResumesTheSameChildAfterRestart() {
        state.phase = "SOURCE_SEARCH"; state.currentKind = "SOURCE_MOVIE"; state.currentMovieId = "movie1"; state.currentDiscoveryTaskId = 19L;
        ResourceHubTask search = new ResourceHubTask(); search.setId(19L); search.setStatus("RUNNING"); search.setStartedAt(LocalDateTime.now().minusHours(1));
        search.setAttempts(1); search.setMaxAttempts(3); when(tasks.getById(19L)).thenReturn(search);
        when(discovery.runTask(19L)).thenAnswer(call -> { search.setStatus("SUCCEEDED"); return new com.gying.movie.dto.ResourceDiscoveryRunResult(); });
        when(discoveries.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        assertEquals(1, service.runOnce().skipped()); verify(discovery).runTask(19L); verify(discovery, never()).enqueue(any());
    }

    @Test void smallerBatchLimitTakesEffectAtTheNextItemBoundary() {
        restoreChild("BAIDU_TASK"); state.batchLimit = 5;
        when(runner.submitOne(4L)).thenAnswer(call -> { child.setStatus("SUCCEEDED"); state.batchLimit = 1; state.settingsRevision++; return new QuarkTransferRunResult(); });
        assertEquals(1, service.runOnce().processed()); verifyNoInteractions(jdbc); verify(runner, times(1)).submitOne(4L);
    }

    @Test void gyingFalseKeepsDurableIntentAndCannotTriggerASecondSubmission() {
        restoreChild("BAIDU_TASK"); properties.getBaidu().setGyingPublishEnabled(true);
        var published = new com.gying.movie.dto.ResourceHubPublishResult(); published.setPublished(1); published.getResourceIds().add(50L);
        when(publisher.publishDiscovery(9L)).thenReturn(published);
        ResourceLink resource = new ResourceLink(); resource.setId(50L); resource.setType("DISK"); when(links.getById(50L)).thenReturn(resource);
        when(gying.publishResourceToGying(resource)).thenReturn(false);
        assertEquals("CHILD_UNKNOWN", service.runOnce().blockedReason()); assertTrue(state.currentGyingSubmitting); assertEquals(50L, state.currentResourceLinkId);
        assertEquals("CHILD_UNKNOWN", service.runOnce().blockedReason()); verify(gying, times(1)).publishResourceToGying(resource); verify(publisher, times(1)).publishDiscovery(9L);
    }

}
