package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.*;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.service.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BaiduTransferRunnerServiceImplTest {
    ResourceHubProperties props; BaiduWebClient client; BaiduSessionVault vault;
    IBaiduTransferTaskService tasks; BaiduTransferTask task; BaiduTransferRunnerServiceImpl runner; IMovieMetadataService movies;
    ObjectMapper mapper=new ObjectMapper(); List<String> persisted;
    @BeforeEach void setup() throws Exception {
        props=new ResourceHubProperties(); props.setEnabled(true); props.getBaidu().setEnabled(true); props.getBaidu().setShareEnabled(true); props.getBaidu().setVideoEnabled(true); props.getBaidu().setPollAttempts(1); props.getBaidu().setPollIntervalMs(0);
        client=mock(BaiduWebClient.class); vault=mock(BaiduSessionVault.class); tasks=mock(IBaiduTransferTaskService.class);
        task=new BaiduTransferTask(); task.setId(1L); task.setStatus("PENDING"); task.setPhase("PREPARED"); task.setOriginalUrl("https://pan.baidu.com/s/1source");task.setAccountUid("100");task.setAttempts(0);task.setSavedPath("/target");task.setResponsePayload(mapper.writeValueAsString(plan()));
        persisted=new ArrayList<>();
        when(client.isConfigured()).thenReturn(true);when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(true,"100","fixture",1L));when(tasks.getById(1L)).thenReturn(task);
        when(tasks.update(any(Wrapper.class))).thenAnswer(invocation->{
            var update=(UpdateWrapper<BaiduTransferTask>)invocation.getArgument(0); update.getSqlSet();
            update.getParamNameValuePairs().values().stream().filter(v->v instanceof String && v.toString().matches("[0-9a-f-]{36}")).findFirst().ifPresent(v->task.setLeaseOwner(v.toString()));
            persisted.add(task.getPhase()); return true;
        });
        movies=mock(IMovieMetadataService.class);
        runner=new BaiduTransferRunnerServiceImpl(props,client,vault,tasks,mock(IResourceDiscoveryResultService.class),mock(IResourceLinkService.class),movies,mapper);
    }
    @Test void intentIsPersistedBeforeTransferAndShare() {
        when(client.verifyDestination(any())).thenReturn(false,true);
        when(client.transfer(any())).thenAnswer(invocation->{assertEquals("TRANSFER_SUBMITTING",task.getPhase());assertEquals("WAITING_TRANSFER",task.getStatus());assertTrue(persisted.contains("TRANSFER_SUBMITTING"));return new BaiduWebClient.Submission("task1","PENDING");});
        when(client.findOwnShare("/target")).thenReturn(null);when(client.createOwnShare("/target")).thenAnswer(invocation->{assertEquals("SHARE_SUBMITTING",task.getPhase());assertTrue(persisted.contains("SHARE_SUBMITTING"));return "https://pan.baidu.com/s/1owned?pwd=abcd";});when(client.validateOwnShare(any())).thenReturn(true);
        var result=runner.submitOne(1L);assertEquals(1,result.getSubmitted());assertEquals("SUCCEEDED",task.getStatus());assertEquals("SHARED",task.getPhase());
        var writes = org.mockito.ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(tasks, atLeastOnce()).update(writes.capture());
        for (var write : writes.getAllValues()) {
            assertTrue(write.getSqlSegment().contains("BINARY scope_key"));
            assertTrue(write.getSqlSegment().contains("request_payload IS NULL"));
            assertTrue(write.getParamNameValuePairs().containsValue("PERMANENT"));
        }
    }
    @Test void ambiguousTransferRecoveryOnlyReadsAndNeverPostsAgain() {
        task.setPhase("TRANSFER_SUBMITTING");task.setStatus("WAITING_TRANSFER");when(client.verifyDestination(any())).thenReturn(false);
        var result=runner.submitOne(1L);assertEquals(1,result.getFailed());assertEquals("TRANSFER_SUBMITTING",task.getPhase());verify(client,never()).transfer(any());verify(client,never()).createOwnShare(any());
    }
    @Test void ambiguousShareRecoveryOnlySearchesRecords() {
        task.setPhase("SHARE_SUBMITTING");task.setStatus("WAITING_SHARE");when(client.findOwnShare("/target")).thenReturn(null);
        runner.submitOne(1L);assertEquals("SHARE_SUBMITTING",task.getPhase());assertEquals("WAITING_SHARE",task.getStatus());verify(client,never()).transfer(any());verify(client,never()).createOwnShare(any());
    }
    @Test void readFailureDuringRecoveryPreservesSubmittingIntent() {
        task.setPhase("TRANSFER_SUBMITTING");task.setStatus("WAITING_TRANSFER");when(client.verifyDestination(any())).thenThrow(new BaiduActionException("NETWORK_UNAVAILABLE",true,false,false));
        runner.submitOne(1L);assertEquals("TRANSFER_SUBMITTING",task.getPhase());assertEquals("WAITING_TRANSFER",task.getStatus());verify(client,never()).transfer(any());
    }
    @Test void successfulWriteFollowedByReadFailureCannotBecomeRetryablePost() {
        when(client.verifyDestination(any())).thenReturn(false).thenThrow(new BaiduActionException("NETWORK_UNAVAILABLE",true,false,false));when(client.transfer(any())).thenReturn(new BaiduWebClient.Submission("accepted","PENDING"));
        runner.submitOne(1L);assertEquals("TRANSFER_SUBMITTING",task.getPhase());assertEquals("WAITING_TRANSFER",task.getStatus());verify(client,times(1)).transfer(any());
    }
    @Test void definiteTransferRejectionCanReturnToPreparedButAmbiguousCannot() {
        when(client.verifyDestination(any())).thenReturn(false);when(client.transfer(any())).thenThrow(new BaiduActionException("API_REJECTED",false,false,false));
        runner.submitOne(1L);assertEquals("PREPARED",task.getPhase());assertEquals("FAILED",task.getStatus());
        task.setStatus("PENDING");doThrow(new BaiduActionException("NETWORK_UNAVAILABLE",true,false,true)).when(client).transfer(any());
        runner.submitOne(1L);assertEquals("TRANSFER_SUBMITTING",task.getPhase());assertEquals("WAITING_TRANSFER",task.getStatus());
    }
    @Test void leaseCannotBeClaimedSkipsEveryRemoteOperation() {
        when(tasks.update(any(Wrapper.class))).thenReturn(false);
        assertEquals(1,runner.submitOne(1L).getSkipped());verify(client,never()).verifyDestination(any());verify(client,never()).transfer(any());verify(client,never()).createOwnShare(any());
    }
    @Test void failedIntentPersistencePreventsRemoteTransfer() {
        when(client.verifyDestination(any())).thenReturn(false);
        when(tasks.update(any(Wrapper.class))).thenAnswer(invocation->{var update=(UpdateWrapper<BaiduTransferTask>)invocation.getArgument(0);update.getSqlSet();update.getParamNameValuePairs().values().stream().filter(v->v instanceof String && v.toString().matches("[0-9a-f-]{36}")).findFirst().ifPresent(v->task.setLeaseOwner(v.toString()));return !"TRANSFER_SUBMITTING".equals(task.getPhase());});
        runner.submitOne(1L);verify(client,never()).transfer(any());verify(client,never()).createOwnShare(any());
    }
    @Test void accountMismatchAndDisabledFilePolicyNeverWrite() {
        when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(true,"999","fixture",1));runner.submitOne(1L);assertEquals("ACTION_REQUIRED",task.getStatus());verify(client,never()).transfer(any());
        task.setStatus("PENDING");when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(true,"100","fixture",1));props.getBaidu().setVideoEnabled(false);props.getBaidu().setTorrentEnabled(true);
        assertEquals(1,runner.submitOne(1L).getSkipped());assertEquals("PENDING",task.getStatus());verify(client,never()).transfer(any());verify(client,never()).createOwnShare(any());
    }
    @Test void succeededVideoTaskIsSkippedAfterSwitchingToTorrentOnly() {
        task.setStatus("SUCCEEDED"); task.setPhase("SHARED");
        task.setShareUrl("https://pan.baidu.com/s/1owned?pwd=abcd");
        task.setDiscoveryResultId(42L);
        var discoveries=mock(IResourceDiscoveryResultService.class); var links=mock(IResourceLinkService.class);
        runner=new BaiduTransferRunnerServiceImpl(props,client,vault,tasks,discoveries,links,mock(IMovieMetadataService.class),mapper);
        props.getBaidu().setVideoEnabled(false); props.getBaidu().setTorrentEnabled(true);
        var result=runner.submitOne(1L);
        assertEquals(1,result.getSkipped()); assertEquals(0,result.getSubmitted()); assertEquals(0,result.getFailed());
        assertEquals("SUCCEEDED",task.getStatus()); assertEquals("SHARED",task.getPhase()); assertEquals(0,task.getAttempts());
        verifyNoInteractions(discoveries,links);
        verify(client,never()).validateOwnShare(any()); verify(client,never()).verifyDestination(any());
        verify(client,never()).transfer(any()); verify(client,never()).createOwnShare(any());
    }
    @Test void unknownPhaseNeverPosts() {
        task.setPhase("UNKNOWN");runner.submitOne(1L);verify(client,never()).transfer(any());verify(client,never()).createOwnShare(any());assertEquals("FAILED",task.getStatus());
    }

    @ParameterizedTest
    @CsvSource({"mv,mv", "tv,tv", "ac,ac", "movie,mv", "anime,ac"})
    void newBaiduVideoPlansUseTheSamePermanentCategoryRootsAsTorrentFolders(String category, String expected) {
        MovieMetadata movie = new MovieMetadata(); movie.setId("movie1"); movie.setTitleCn("分类作品"); movie.setCategory(category);
        when(movies.getById("movie1")).thenReturn(movie); task.setMovieId("movie1");
        task.setPhase("NEW"); task.setSavedPath(null); task.setResponsePayload(null); props.getBaidu().setShareEnabled(false);
        props.getBaidu().setSavePath("/GYing Resource Hub/");
        String destination = "/GYing Resource Hub/" + expected + "/分类作品（movie1）";
        when(client.planTransfer(anyString(), any(), eq(destination), eq(true), eq(false))).thenReturn(
                new BaiduWebClient.TransferPlan(task.getOriginalUrl(), null, destination, "1", "200", List.of("11"), List.of(new BaiduWebClient.ExpectedFile("Movie.mkv", 100, false))));
        when(client.verifyDestination(any())).thenReturn(true);
        runner.submitOne(1L); assertEquals(destination, task.getSavedPath()); assertEquals("TRANSFERRED", task.getPhase());
        assertTrue(task.getResponsePayload().contains(destination));
        verify(client).planTransfer(anyString(), any(), eq(destination), eq(true), eq(false));
        verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
    }

    @Test void unknownCategoryStopsANewVideoPlanBeforeAnyTransferRequest() {
        MovieMetadata movie = new MovieMetadata(); movie.setId("movie1"); movie.setCategory("music");
        when(movies.getById("movie1")).thenReturn(movie); task.setMovieId("movie1");
        task.setPhase("NEW"); task.setSavedPath(null); task.setResponsePayload(null);
        runner.submitOne(1L); assertEquals("ACTION_REQUIRED", task.getStatus()); assertEquals("CATEGORY_REQUIRED", task.getLastError());
        verify(client, never()).planTransfer(anyString(), any(), anyString(), anyBoolean(), anyBoolean());
        verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
    }

    @Test void historicalSubmittingVideoPlanRetainsItsDestinationWhenCurrentCategoryChanges() {
        MovieMetadata movie = new MovieMetadata(); movie.setId("movie1"); movie.setCategory("tv");
        when(movies.getById("movie1")).thenReturn(movie); task.setMovieId("movie1"); props.getBaidu().setSavePath("/GYing Resource Hub");
        task.setPhase("TRANSFER_SUBMITTING"); task.setStatus("WAITING_TRANSFER");
        String original = task.getResponsePayload(); when(client.verifyDestination(any())).thenReturn(false);
        runner.submitOne(1L);
        assertEquals("/target", task.getSavedPath()); assertEquals(original, task.getResponsePayload()); assertEquals("TRANSFER_SUBMITTING", task.getPhase());
        verify(client, never()).planTransfer(anyString(), any(), anyString(), anyBoolean(), anyBoolean());
        verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
        verifyNoInteractions(movies);
    }


    @Test void temporaryPlanUsesTheExactTaskDirectoryAndNeverThePermanentMovieFolder() throws Exception {
        configureTemporary();
        task.setPhase("NEW"); task.setSavedPath(null); task.setResponsePayload(null); props.getBaidu().setShareEnabled(false);
        String target = "/GYing QQ Temp/baidu-1";
        when(client.planTransfer(anyString(), any(), eq(target), eq(true), eq(false))).thenReturn(temporaryPlan());
        when(client.verifyDestination(any())).thenReturn(true);
        runner.submitOne(1L);
        assertEquals(target, task.getSavedPath()); assertEquals("TRANSFERRED", task.getPhase());
        verify(client).planTransfer(anyString(), any(), eq(target), eq(true), eq(false));
        verifyNoInteractions(movies);
    }

    @Test void malformedTemporaryMarkerStopsBeforeAnyCloudRequest() throws Exception {
        configureTemporary();
        task.setRequestPayload(task.getRequestPayload().replace("baidu-1", "baidu-2"));
        runner.submitOne(1L);
        verify(client, never()).planTransfer(anyString(), any(), anyString(), anyBoolean(), anyBoolean());
        verify(client, never()).verifyDestination(any()); verify(client, never()).findOwnShare(any());
        verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
    }

    @Test void firstConfirmedSharePersistsItsSnapshotAndClockDespiteALocalSchedulingFailure() throws Exception {
        BaiduTemporaryCleanupService cleanup = configureTemporary();
        when(client.verifyDestination(any())).thenReturn(true);
        when(client.findOwnShare(task.getSavedPath())).thenReturn("https://pan.baidu.com/s/1owned?pwd=abcd");
        when(client.validateOwnShare(any())).thenReturn(true);
        var evidence = new BaiduWebClient.DestinationEvidence("100", task.getSavedPath(), "55",
                List.of(new BaiduWebClient.DestinationFile("Movie.mkv", "56", 100, false, null)));
        when(client.captureDestinationEvidence(any())).thenReturn(evidence);
        when(client.inspectTemporaryDirectory(any())).thenReturn(BaiduWebClient.DestinationState.MATCHING);
        doThrow(new IllegalStateException("fixture scheduling storage failure")).when(cleanup).scheduleConfirmed(any());
        runner.submitOne(1L);
        assertEquals("SUCCEEDED", task.getStatus()); assertEquals("SHARED", task.getPhase());
        assertNotNull(task.getShareConfirmedAt()); assertEquals(task.getShareConfirmedAt(), task.getFinishedAt());
        var first = task.getShareConfirmedAt(); String snapshot = task.getCleanupEvidence();
        assertEquals(evidence, mapper.readValue(snapshot, BaiduWebClient.DestinationEvidence.class));
        runner.submitOne(1L);
        assertEquals(first, task.getShareConfirmedAt()); assertEquals(first, task.getFinishedAt()); assertEquals(snapshot, task.getCleanupEvidence());
        verify(client, times(1)).captureDestinationEvidence(any());
        verify(cleanup, times(2)).scheduleConfirmed(any());
        verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
    }

    @Test void expiredTemporaryTaskNeverRecreatesOrResynchronizesItsShare() throws Exception {
        BaiduTemporaryCleanupService cleanup = configureTemporary();
        when(cleanup.isExpired(1L)).thenReturn(true);
        assertEquals(1, runner.submitOne(1L).getSkipped());
        verify(client, never()).verifyDestination(any()); verify(client, never()).findOwnShare(any());
        verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
    }

    @Test void permanentPendingLaneDoesNotConsumeATemporaryTaskEvenFromAMalformedQueryResult() throws Exception {
        configureTemporary();
        when(tasks.list(any(Wrapper.class))).thenReturn(List.of(task));
        assertEquals(1, runner.submitPending(5).getSkipped());
        verify(client, never()).verifyDestination(any()); verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
        var query = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(tasks).list(query.capture()); assertTrue(query.getValue().getSqlSegment().contains("scope_key"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("PERMANENT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope", "payload"})
    void changedScopeOrMarkerBetweenCandidateAndClaimedReadNeverExecutes(String changed) throws Exception {
        configureTemporary(); String originalScope = task.getScopeKey(); String originalPayload = task.getRequestPayload();
        when(tasks.update(any(Wrapper.class))).thenAnswer(invocation -> {
            var update = (UpdateWrapper<BaiduTransferTask>) invocation.getArgument(0); update.getSqlSet();
            update.getParamNameValuePairs().values().stream()
                    .filter(v -> v instanceof String && v.toString().matches("[0-9a-f-]{36}"))
                    .findFirst().ifPresent(v -> task.setLeaseOwner(v.toString()));
            if ("scope".equals(changed)) task.setScopeKey("PERMANENT");
            else task.setRequestPayload(originalPayload.replace("\"retentionMinutes\":10", "\"retentionMinutes\":60"));
            return true;
        });
        var result = runner.submitOne(1L);
        assertEquals(1, result.getFailed()); assertTrue(result.getErrors().contains("BAIDU_SCOPE_CHANGED"));
        verify(client, never()).verifyDestination(any()); verify(client, never()).inspectTemporaryDirectory(any());
        verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
        var writes = org.mockito.ArgumentCaptor.forClass(UpdateWrapper.class); verify(tasks, atLeastOnce()).update(writes.capture());
        for (var write : writes.getAllValues()) {
            write.getSqlSegment();
            assertTrue(write.getParamNameValuePairs().containsValue(originalScope));
            assertTrue(write.getParamNameValuePairs().containsValue(originalPayload));
        }
    }

    @Test void succeededTemporaryTaskRejectsAReplacedTreeWithoutRecapturingOrResynchronizing() throws Exception {
        var cleanup = configureTemporary();
        task.setStatus("SUCCEEDED"); task.setPhase("SHARED"); task.setShareConfirmedAt(java.time.LocalDateTime.now());
        task.setShareUrl("https://pan.baidu.com/s/1owned?pwd=abcd");
        var evidence = new BaiduWebClient.DestinationEvidence("100", task.getSavedPath(), "55",
                List.of(new BaiduWebClient.DestinationFile("Movie.mkv", "56", 100, false, null)));
        task.setCleanupEvidence(mapper.writeValueAsString(evidence));
        when(client.validateOwnShare(any())).thenReturn(true);
        when(client.inspectTemporaryDirectory(evidence)).thenReturn(BaiduWebClient.DestinationState.CHANGED);
        assertEquals(1, runner.submitOne(1L).getFailed());
        verify(client).inspectTemporaryDirectory(evidence); verify(cleanup, never()).scheduleConfirmed(any());
        verify(client, never()).captureDestinationEvidence(any()); verify(client, never()).transfer(any());
        verify(client, never()).createOwnShare(any()); assertEquals("SUCCEEDED", task.getStatus());
    }

    @Test void succeededPermanentTaskRequiresItsOriginalDestinationBeforeResynchronizing() {
        task.setStatus("SUCCEEDED"); task.setPhase("SHARED"); task.setShareUrl("https://pan.baidu.com/s/1owned?pwd=abcd");
        when(client.validateOwnShare(any())).thenReturn(true); when(client.verifyDestination(any())).thenReturn(false);
        assertEquals(1, runner.submitOne(1L).getFailed()); verify(client).verifyDestination(any());
        verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
    }

    private BaiduTemporaryCleanupService configureTemporary() throws Exception {
        task.setScopeKey("QQ_BOT:11111111-1111-1111-1111-111111111111"); task.setSearchRequestHash("a".repeat(64));
        task.setMovieId("movie1"); task.setOriginalUrlHash(com.gying.movie.utils.ResourceHubHashUtils.sha256(task.getOriginalUrl()));
        task.setSavedPath("/GYing QQ Temp/baidu-1"); task.setDiscoveryResultId(42L);
        task.setRequestPayload(com.gying.movie.utils.BaiduTemporaryScope.payload(task.getScopeKey(), task.getSearchRequestHash(), 40L, task.getSavedPath()));
        task.setResponsePayload(mapper.writeValueAsString(temporaryPlan()));
        var discovery = new com.gying.movie.entity.ResourceDiscoveryResult();
        discovery.setId(42L); discovery.setMovieId("movie1"); discovery.setProvider("BAIDU"); discovery.setSource("QQ_BOT");
        discovery.setSourceRef(com.gying.movie.utils.BaiduTemporaryScope.DISCOVERY_PREFIX + task.getScopeKey());
        discovery.setOriginalUrl(task.getOriginalUrl()); discovery.setOriginalUrlHash(task.getOriginalUrlHash());
        var discoveries = mock(IResourceDiscoveryResultService.class);
        when(discoveries.getById(42L)).thenReturn(discovery); when(discoveries.update(any(Wrapper.class))).thenReturn(true);
        runner = new BaiduTransferRunnerServiceImpl(props, client, vault, tasks, discoveries, mock(IResourceLinkService.class), movies, mapper);
        var settings = mock(QqTransferCleanupSettings.class); when(settings.baiduTarget(1L)).thenReturn(task.getSavedPath());
        var cleanup = mock(BaiduTemporaryCleanupService.class); runner.configureTemporaryTransfers(settings, cleanup);
        return cleanup;
    }

    private static BaiduWebClient.TransferPlan temporaryPlan() {
        return new BaiduWebClient.TransferPlan("https://pan.baidu.com/s/1source", null, "/GYing QQ Temp/baidu-1", "1", "200",
                List.of("11"), List.of(new BaiduWebClient.ExpectedFile("Movie.mkv", 100, false)));
    }

    private static BaiduWebClient.TransferPlan plan(){return new BaiduWebClient.TransferPlan("https://pan.baidu.com/s/1source",null,"/target","1","200",List.of("11"),List.of(new BaiduWebClient.ExpectedFile("Movie.mkv",100,false)));}
}
