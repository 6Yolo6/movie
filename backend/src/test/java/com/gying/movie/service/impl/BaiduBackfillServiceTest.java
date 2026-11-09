package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.*;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.*;
import com.gying.movie.service.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BaiduBackfillServiceTest {
    BaiduBackfillTaskStore store = mock(BaiduBackfillTaskStore.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ResourceHubProperties properties = new ResourceHubProperties();
    IResourceHubConfigService config = mock(IResourceHubConfigService.class);
    GyingSourceWorkflowService gying = mock(GyingSourceWorkflowService.class);
    IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    IMovieMetadataService movies = mock(IMovieMetadataService.class);
    IMovieSourceIdentityService identities = mock(IMovieSourceIdentityService.class);
    P2pArchiveTaskService archives = mock(P2pArchiveTaskService.class);
    BaiduWebClient client = mock(BaiduWebClient.class);
    ObjectMapper mapper = new ObjectMapper();
    BaiduBackfillTaskStore.State state = new BaiduBackfillTaskStore.State();
    BaiduBackfillService service;
    ResourceHubTask child;
    MovieSourceIdentity identity;
    List<ResourceLink> selection;

    @BeforeEach void setup() {
        properties.setEnabled(true); properties.getWorker().setEnabled(true); properties.getBaidu().setEnabled(true);
        properties.getBaidu().setShareEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        properties.getBaidu().setVideoEnabled(false); properties.getBaidu().setDiscoveryEnabled(false);
        when(client.isConfigured()).thenReturn(true);
        state.taskId = 100L; state.identityMaxId = 10; state.enabled = true; state.leaseOwner = "fixture-owner"; state.status = "RUNNING";
        when(store.claim(anyBoolean())).thenReturn(state); when(store.read()).thenAnswer(call -> state);
        when(store.checkpoint(any(), any())).thenAnswer(call -> {
            state = call.getArgument(0); Consumer<BaiduBackfillTaskStore.State> mutation = call.getArgument(1);
            if (mutation != null) mutation.accept(state); return state;
        });
        when(store.finish(any())).thenAnswer(call -> {
            state = call.getArgument(0); state.status = "COMPLETE".equals(state.phase) ? "COMPLETE" : state.blockedReason != null ? "BLOCKED" : state.enabled ? "PENDING" : "PAUSED";
            return state;
        });
        MovieMetadata movie = new MovieMetadata(); movie.setId("movie1"); movie.setStatus("ACTIVE"); movie.setCategory("mv"); when(movies.getById("movie1")).thenReturn(movie);
        identity = new MovieSourceIdentity(); identity.setId(2L); identity.setMovieId("movie1"); identity.setSource("GYING");
        identity.setSourceType("mv"); identity.setExternalId("CASE1"); identity.setMatchStatus("CONFIRMED"); when(identities.getById(2L)).thenReturn(identity);
        ResourceLink magnet = new ResourceLink(); magnet.setId(31L); magnet.setMovieId("movie1"); magnet.setType("MAGNET");
        magnet.setProvider("P2P"); magnet.setSource("GYING"); magnet.setSourceRef("BT1"); magnet.setQuality("1080P");
        magnet.setSubtitle("中文字幕（来源标注）"); magnet.setAuditStatus(1); magnet.setStatus("ACTIVE");
        magnet.setUrl("magnet:?xt=urn:btih:" + "a".repeat(40)); magnet.setName("Movie BT1");
        selection = List.of(magnet, P2pArchiveTaskService.torrentDescriptor(magnet));
        child = new ResourceHubTask(); child.setId(4L); child.setTaskType("P2P_ARCHIVE"); child.setSource("BAIDU"); child.setMovieId("movie1");
        child.setStatus("PENDING"); child.setAttempts(0); child.setMaxAttempts(3);
        child.setPayload("{\"typeCode\":\"mv\",\"mid\":\"CASE1\",\"stage\":\"UPLOAD_TORRENT\",\"backfillParentId\":100}");
        when(tasks.getById(4L)).thenReturn(child); when(archives.selectionForTask(child)).thenReturn(selection);
        when(gying.collectMovieP2pResources("movie1", 2L)).thenReturn(new GyingSourceWorkflowService.P2pCollection("movie1", 2L, "mv", "CASE1", 1, selection));
        when(archives.enqueueForProvider(eq("movie1"), eq("mv"), eq("CASE1"), eq(selection), eq("BAIDU"), eq(100L))).thenReturn(child);
        when(archives.submitOne(4L, "BAIDU", 100L)).thenAnswer(call -> { child.setStatus("SUCCEEDED"); return child; });
        service = new BaiduBackfillService(store, jdbc, properties, config, tasks, gying, movies, identities, archives, client, mapper);
    }
    void restoreIdentity() { state.currentIdentityId = 2L; state.currentMovieId = "movie1"; state.currentGyingType = "mv"; state.currentGyingId = "CASE1"; state.currentAction = "COLLECT_GYING_TORRENTS"; }
    void restoreChild() { restoreIdentity(); state.currentChildId = 4L; state.currentResourceIds = List.of(31L); state.currentSeedNames = List.of("Movie BT1"); state.currentSeedCount = 1; }

    @Test void linkedIdentityIsCheckpointedBeforeExactCollectionAndOnlyBaiduArchive() {
        when(jdbc.queryForList(eq(BaiduBackfillService.NEXT_IDENTITY_SQL), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 2L, "movie_id", "movie1", "source_type", "mv", "external_id", "CASE1")));
        when(gying.collectMovieP2pResources("movie1", 2L)).thenAnswer(call -> {
            assertEquals(2L, state.currentIdentityId); assertEquals("CASE1", state.currentGyingId);
            return new GyingSourceWorkflowService.P2pCollection("movie1", 2L, "mv", "CASE1", 1, selection);
        });
        var result = service.runOnce(); assertEquals(1, result.succeeded()); assertEquals(2, state.identityCursor);
        var order = inOrder(gying, archives); order.verify(archives).protectedArchive("movie1", "BAIDU");
        order.verify(gying).collectMovieP2pResources("movie1", 2L);
        order.verify(archives).enqueueForProvider("movie1", "mv", "CASE1", selection, "BAIDU", 100L);
        verify(archives).submitOne(4L, "BAIDU", 100L);
        verify(gying, never()).discoverResources(any(), anyInt()); verify(gying, never()).publishResourceToGying(any());
    }
    @Test void restartingAttachedChildNeverCollectsOrEnqueuesAgain() {
        restoreChild(); when(archives.submitOne(4L, "BAIDU", 100L)).thenAnswer(call -> { assertEquals(4L, state.currentChildId); child.setStatus("SUCCEEDED"); return child; });
        assertEquals(1, service.runOnce().succeeded()); verifyNoInteractions(gying, identities); verify(archives, never()).enqueueForProvider(any(), any(), any(), any(), any(), any());
    }
    @Test void oldVideoShareReviewDoesNotGateTheIndependentTorrentWorkflow() {
        restoreIdentity(); when(store.legacyTaskId()).thenReturn(17580L); properties.getBaidu().setGyingPublishEnabled(true);
        var result = service.runOnce(); assertEquals(1, result.succeeded()); assertEquals(17580L, result.legacyTaskId());
        assertEquals("TORRENTS_ARCHIVED", result.lastResult()); verify(gying, never()).publishResourceToGying(any());
    }
    @Test void protectedUnknownSeedKeepsItsReferencesWithoutCollectionOrReplaying() {
        restoreIdentity(); child.setStatus("UNKNOWN"); child.setAttempts(2); String original = child.getPayload();
        when(archives.protectedArchive("movie1", "BAIDU")).thenReturn(child);
        var result = service.runOnce(); assertEquals("CHILD_UNKNOWN", result.blockedReason()); assertEquals(4L, result.currentChildId());
        assertEquals(List.of(31L), result.currentResourceIds()); assertEquals(1, result.currentSeedCount()); assertEquals("UPLOAD_TORRENT", result.currentAction());
        assertEquals(original, child.getPayload()); assertEquals(2, child.getAttempts()); verifyNoInteractions(gying);
        verify(archives, never()).submitOne(anyLong(), anyString(), any()); verify(archives, never()).enqueueForProvider(any(), any(), any(), any(), any(), any());
    }
    void assertRealUploadFailureStopsParentWithoutRecollectingOrResubmitting(boolean uncertain) throws Exception {
        restoreChild();
        var links = mock(IResourceLinkService.class); var source = mock(GyingSourceClient.class);
        when(links.getById(31L)).thenReturn(selection.get(0));
        var payload = mapper.createObjectNode(); payload.put("version", 2); payload.put("typeCode", "mv"); payload.put("mid", "CASE1");
        payload.put("backfillParentId", 100L); payload.put("fingerprint", P2pArchiveTaskService.fingerprint(selection));
        payload.putArray("resourceIds").add(31); payload.putArray("torrentMagnetIds").add(31); child.setPayload(payload.toString());
        when(tasks.update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        when(tasks.updateById(any(ResourceHubTask.class))).thenReturn(true);
        when(tasks.update(any(ResourceHubTask.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        byte[] torrent = "d4:infod6:pieces20:xxxxxxxxxxxxxxxxxxxxee".getBytes(StandardCharsets.UTF_8);
        when(source.get("/torrent-file/mv/CASE1/BT1")).thenReturn(Map.of("infoHash", "a".repeat(40),
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(torrent)),
                "dataBase64", Base64.getEncoder().encodeToString(torrent)));
        when(client.p2pFolder(any(), anyString())).thenReturn("/fixture/metadata");
        when(client.p2pUpload(eq("/fixture/metadata"), anyString(), any())).thenReturn("confirmed-magnet")
                .thenThrow(new BaiduActionException(uncertain ? "UPLOAD_OUTCOME_UNKNOWN" : "SESSION_EXPIRED", false, !uncertain, uncertain));
        var adapter = new P2pCloudArchiveClient(mock(QuarkShareClient.class), mock(XunleiClient.class), client, mapper);
        archives = spy(new P2pArchiveTaskService(properties, tasks, links, movies, mock(IQuarkTransferTaskService.class),
                mock(IXunleiTransferTaskService.class), mock(IBaiduTransferTaskService.class), source, adapter, mapper));
        service = new BaiduBackfillService(store, jdbc, properties, config, tasks, gying, movies, identities, archives, client, mapper);
        var first = service.runOnce();
        String reason = uncertain ? "CHILD_UNKNOWN" : "CHILD_ACTION_REQUIRED";
        assertEquals(reason, first.blockedReason()); assertEquals(uncertain ? "UNKNOWN" : "ACTION_REQUIRED", first.currentChildStatus());
        assertEquals("BaiduActionException", first.currentChildError()); assertEquals("UPLOAD_TORRENT", first.currentAction());
        assertEquals(4L, first.currentChildId()); assertEquals(List.of(31L), first.currentResourceIds()); assertEquals(1, first.currentSeedCount());
        assertEquals(0, state.identityCursor); assertEquals(0, first.failed()); assertEquals(uncertain ? 1 : 0, child.getAttempts());
        String persisted = child.getPayload();
        assertEquals(1, mapper.readTree(persisted).path("uploadedFiles").size());
        assertEquals(reason, service.runOnce().blockedReason()); assertEquals(persisted, child.getPayload());
        assertEquals(uncertain ? 1 : 0, child.getAttempts()); verify(archives, times(1)).submitOne(4L, "BAIDU", 100L);
        verify(client).p2pFolder(isNull(), eq("/GYing Resource Hub/mv/movie1（movie1）"));
        verify(client, times(2)).p2pUpload(eq("/fixture/metadata"), anyString(), any()); verify(client, never()).createOwnShare(anyString());
        verify(client, never()).findOwnShare(anyString()); verify(source, times(1)).get("/torrent-file/mv/CASE1/BT1");
        verify(links, never()).save(any()); verifyNoInteractions(gying, identities, jdbc);
        verify(archives, never()).enqueueForProvider(any(), any(), any(), any(), any(), any());
    }
    @Test void realAdapterUploadSessionFailureKeepsParentAndOwnedChildBlockedWithoutReplay() throws Exception {
        assertRealUploadFailureStopsParentWithoutRecollectingOrResubmitting(false);
    }
    @Test void realAdapterUncertainUploadKeepsParentAndOwnedChildBlockedWithoutReplay() throws Exception {
        assertRealUploadFailureStopsParentWithoutRecollectingOrResubmitting(true);
    }

    @Test void persistedShareIntentBlocksRecoveryAndAnyRepeatedSubmit() {
        restoreChild(); child.setStatus("RUNNING"); child.setPayload("{\"typeCode\":\"mv\",\"mid\":\"CASE1\",\"stage\":\"CREATE_METADATA_SHARE\",\"backfillParentId\":100,\"baiduShareSubmitting\":true}");
        assertEquals("CHILD_UNKNOWN", service.runOnce().blockedReason()); verify(store, never()).recoverInterruptedArchive(any());
        verify(archives, never()).submitOne(anyLong(), anyString(), any()); verifyNoInteractions(gying);
    }
    @Test void interruptedOwnedUploadIsRecoveredOnlyThroughTheFreshParentLease() {
        restoreChild(); child.setStatus("RUNNING"); when(store.recoverInterruptedArchive(state)).thenAnswer(call -> { child.setStatus("FAILED"); return true; });
        assertEquals(1, service.runOnce().succeeded()); verify(store).recoverInterruptedArchive(state); verify(archives).submitOne(4L, "BAIDU", 100L);
    }
    @Test void anotherRunningChildCannotBeRecoveredOrExecuted() {
        restoreChild(); child.setStatus("RUNNING"); when(store.recoverInterruptedArchive(state)).thenReturn(false);
        assertEquals("CHILD_RUNNING", service.runOnce().blockedReason()); verify(archives, never()).submitOne(anyLong(), anyString(), any());
    }
    @Test void changedVerifiedIdentityNeverFetchesTheWrongGyingEntry() {
        restoreIdentity(); identity.setExternalId("OTHER");
        assertEquals("GYING_IDENTITY_CHANGED", service.runOnce().blockedReason()); verifyNoInteractions(gying, archives); assertEquals(0, state.identityCursor);
    }
    @Test void identityChangeDuringCollectionPreventsChildAllocation() {
        restoreIdentity(); when(gying.collectMovieP2pResources("movie1", 2L)).thenAnswer(call -> { identity.setMatchStatus("REVIEW"); return new GyingSourceWorkflowService.P2pCollection("movie1", 2L, "mv", "CASE1", 1, selection); });
        assertEquals("GYING_IDENTITY_CHANGED", service.runOnce().blockedReason()); verify(archives, never()).enqueueForProvider(any(), any(), any(), any(), any(), any());
    }
    @Test void noActualTorrentConsumesOneIntervalWithoutArchivingMagnetTextAlone() {
        restoreIdentity(); when(gying.collectMovieP2pResources("movie1", 2L)).thenReturn(new GyingSourceWorkflowService.P2pCollection("movie1", 2L, "mv", "CASE1", 1, List.of(selection.get(0))));
        var result = service.runOnce(); assertEquals(1, result.skipped()); assertEquals("NO_ELIGIBLE_GYING_TORRENTS", result.lastResult());
        verify(archives, never()).enqueueForProvider(any(), any(), any(), any(), any(), any());
    }
    @Test void sourceFailureRetainsExactIdentityAndCountsFailureOnce() {
        restoreIdentity(); when(gying.collectMovieP2pResources("movie1", 2L)).thenThrow(new IllegalStateException("fixture source unavailable"));
        assertEquals("GYING_SOURCE_UNAVAILABLE", service.runOnce().blockedReason()); assertEquals(1, state.failed); assertEquals(2L, state.currentIdentityId);
        service.runOnce(); assertEquals(1, state.failed); verify(archives, never()).enqueueForProvider(any(), any(), any(), any(), any(), any());
    }
    @Test void torrentDisabledBlocksEvenWhenVideoIsEnabled() {
        restoreChild(); properties.getBaidu().setTorrentEnabled(false); properties.getBaidu().setVideoEnabled(true);
        assertEquals("TORRENT_DISABLED", service.runOnce().blockedReason()); verifyNoInteractions(gying, archives); assertEquals(4L, state.currentChildId);
    }
    @Test void missingSessionDoesNotConsumeChildAttempts() {
        restoreChild(); when(client.isConfigured()).thenReturn(false);
        assertEquals("SESSION_REQUIRED", service.runOnce().blockedReason()); assertEquals(0, child.getAttempts()); verifyNoInteractions(gying, archives);
    }
    @Test void pauseDuringItemStopsManualBatchBeforeAnotherIdentityIsSelected() {
        restoreChild(); state.batchLimit = 5;
        when(archives.submitOne(4L, "BAIDU", 100L)).thenAnswer(call -> { child.setStatus("SUCCEEDED"); state.enabled = false; state.settingsRevision++; return child; });
        assertEquals("PAUSED", service.runOnce().status()); verifyNoInteractions(jdbc); verify(archives, times(1)).submitOne(4L, "BAIDU", 100L);
    }
    @Test void smallerBatchLimitTakesEffectAtTheNextMovieBoundary() {
        restoreChild(); state.batchLimit = 5;
        when(archives.submitOne(4L, "BAIDU", 100L)).thenAnswer(call -> { child.setStatus("SUCCEEDED"); state.batchLimit = 1; state.settingsRevision++; return child; });
        assertEquals(1, service.runOnce().processed()); verifyNoInteractions(jdbc); verify(archives, times(1)).submitOne(4L, "BAIDU", 100L);
    }
    @Test void publicErrorsAndActionsExposeOnlyBoundedCategories() {
        restoreChild(); child.setStatus("UNKNOWN"); child.setPayload("{\"stage\":\"secret-payload\",\"errorCategory\":\"cookie-ticket-url\"}");
        var result = service.runOnce(); assertEquals("OTHER", result.currentChildError()); assertEquals("ARCHIVE_METADATA_FILES", result.currentAction());
    }
    @Test void nullableDatabaseChildStatusMapsToUnknownAndNeverRunsIt() {
        restoreChild(); child.setStatus(null);
        var result = service.runOnce(); assertEquals("UNKNOWN", result.currentChildStatus()); assertEquals("CHILD_REVIEW_REQUIRED", result.blockedReason());
        verify(archives, never()).submitOne(anyLong(), anyString(), any());
    }

}
