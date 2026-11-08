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
import com.gying.movie.service.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BaiduTransferRunnerServiceImplTest {
    ResourceHubProperties props; BaiduWebClient client; BaiduSessionVault vault;
    IBaiduTransferTaskService tasks; BaiduTransferTask task; BaiduTransferRunnerServiceImpl runner;
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
        runner=new BaiduTransferRunnerServiceImpl(props,client,vault,tasks,mock(IResourceDiscoveryResultService.class),mock(IResourceLinkService.class),mock(IMovieMetadataService.class),mapper);
    }
    @Test void intentIsPersistedBeforeTransferAndShare() {
        when(client.verifyDestination(any())).thenReturn(false,true);
        when(client.transfer(any())).thenAnswer(invocation->{assertEquals("TRANSFER_SUBMITTING",task.getPhase());assertEquals("WAITING_TRANSFER",task.getStatus());assertTrue(persisted.contains("TRANSFER_SUBMITTING"));return new BaiduWebClient.Submission("task1","PENDING");});
        when(client.findOwnShare("/target")).thenReturn(null);when(client.createOwnShare("/target")).thenAnswer(invocation->{assertEquals("SHARE_SUBMITTING",task.getPhase());assertTrue(persisted.contains("SHARE_SUBMITTING"));return "https://pan.baidu.com/s/1owned?pwd=abcd";});when(client.validateOwnShare(any())).thenReturn(true);
        var result=runner.submitOne(1L);assertEquals(1,result.getSubmitted());assertEquals("SUCCEEDED",task.getStatus());assertEquals("SHARED",task.getPhase());
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
    private static BaiduWebClient.TransferPlan plan(){return new BaiduWebClient.TransferPlan("https://pan.baidu.com/s/1source",null,"/target","1","200",List.of("11"),List.of(new BaiduWebClient.ExpectedFile("Movie.mkv",100,false)));}
}
