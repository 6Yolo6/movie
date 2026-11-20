package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduActionException;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.entity.BaiduTemporaryCleanupJob;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.mapper.BaiduTemporaryCleanupJobMapper;
import com.gying.movie.service.IBaiduTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.BaiduTemporaryScope;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;

class BaiduTemporaryCleanupServiceTest {
    final ObjectMapper json=new ObjectMapper();
    QqTransferCleanupSettings settings; BaiduTemporaryCleanupJobMapper jobs; IBaiduTransferTaskService tasks;
    IResourceDiscoveryResultService discoveries; IResourceLinkService links; BaiduWebClient client; BaiduTemporaryCleanupService service;
    final Map<Long,BaiduTemporaryCleanupJob> database=new HashMap<>();
    BaiduTransferTask task; boolean remoteMissing,purged;
    @BeforeEach void setup() throws Exception {
        settings=mock(QqTransferCleanupSettings.class);when(settings.enabled()).thenReturn(true);when(settings.baiduPermanentDeleteEnabled()).thenReturn(true);
        when(settings.baiduTarget(anyLong())).thenAnswer(i->"/GYing QQ Temp/baidu-"+i.getArgument(0));
        jobs=mock(BaiduTemporaryCleanupJobMapper.class);tasks=mock(IBaiduTransferTaskService.class);discoveries=mock(IResourceDiscoveryResultService.class);links=mock(IResourceLinkService.class);client=mock(BaiduWebClient.class);
        task=new BaiduTransferTask();task.setId(7L);task.setDiscoveryResultId(8L);task.setMovieId("mv-7");task.setScopeKey("QQ_BOT:"+UUID.randomUUID());
        task.setSearchRequestHash("a".repeat(64));task.setOriginalUrl("https://pan.baidu.com/s/1source");task.setOriginalUrlHash(ResourceHubHashUtils.sha256(task.getOriginalUrl()));
        task.setSavedPath("/GYing QQ Temp/baidu-7");task.setAccountUid("100");task.setShareUrl("https://pan.baidu.com/s/1own?pwd=abcd");task.setShareUrlHash(ResourceHubHashUtils.sha256(task.getShareUrl()));
        task.setStatus("SUCCEEDED");task.setPhase("SHARED");task.setShareConfirmedAt(LocalDateTime.now().minusMinutes(11).truncatedTo(ChronoUnit.MICROS));
        task.setFinishedAt(task.getShareConfirmedAt());task.setRequestPayload(BaiduTemporaryScope.payload(task.getScopeKey(),task.getSearchRequestHash(),1L,task.getSavedPath()));
        task.setResponsePayload(json.writeValueAsString(new BaiduWebClient.TransferPlan(task.getOriginalUrl(),null,task.getSavedPath(),"1","200",List.of("11"),List.of(new BaiduWebClient.ExpectedFile("Movie.mkv",100,false)))));
        task.setCleanupEvidence(json.writeValueAsString(new BaiduWebClient.DestinationEvidence("100",task.getSavedPath(),"20",List.of(new BaiduWebClient.DestinationFile("Movie.mkv","30",100,false,"opaque")))));
        when(tasks.getById(7L)).thenAnswer(i->task);
        var clone=new ResourceDiscoveryResult();clone.setId(8L);clone.setMovieId(task.getMovieId());clone.setSource("QQ_BOT");clone.setProvider("BAIDU");clone.setSourceRef("BAIDU_TEMP:"+task.getScopeKey());clone.setOriginalUrl(task.getOriginalUrl());clone.setOriginalUrlHash(task.getOriginalUrlHash());when(discoveries.getById(8L)).thenReturn(clone);
        when(jobs.findByTaskId(anyLong())).thenAnswer(i->database.values().stream().filter(j->j.getTransferTaskId().equals(i.getArgument(0))).findFirst().map(this::copy).orElse(null));
        when(jobs.selectById(any())).thenAnswer(i->copy(database.get(i.getArgument(0))));
        when(jobs.insert(any(BaiduTemporaryCleanupJob.class))).thenAnswer(i->{var job=i.getArgument(0,BaiduTemporaryCleanupJob.class);if(!database.isEmpty())throw new DuplicateKeyException("fixture duplicate");job.setId(1L);database.put(1L,copy(job));return 1;});
        when(jobs.claim(anyLong(),anyString(),anyString(),any(),any())).thenAnswer(i->{var job=database.get(i.getArgument(0));LocalDateTime now=i.getArgument(3);if(job==null || !job.getStatus().equals(i.getArgument(1)) || job.getDeleteAfter().isAfter(now) || job.getLeaseUntil()!=null&&!job.getLeaseUntil().isBefore(now))return 0;job.setLeaseOwner(i.getArgument(2));job.setLeaseUntil(i.getArgument(4));job.setAttempts(job.getAttempts()+1);return 1;});
        when(jobs.transition(anyLong(),anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class),any(),any())).thenAnswer(i->{var job=database.get(i.getArgument(0));LocalDateTime now=i.getArgument(6);if(job==null || !Objects.equals(job.getLeaseOwner(),i.getArgument(1)) || !job.getStatus().equals(i.getArgument(2)) || !job.getLeaseUntil().isAfter(now))return 0;job.setStatus(i.getArgument(3));job.setLastError(i.getArgument(4));if(i.getArgument(5)!=null)job.setRecycleEvidence(i.getArgument(5));job.setLeaseUntil(i.getArgument(7));if("SUCCEEDED".equals(job.getStatus()))job.setFinishedAt(now);return 1;});
        when(jobs.release(anyLong(),anyString())).thenAnswer(i->{var job=database.get(i.getArgument(0));if(job!=null&&Objects.equals(job.getLeaseOwner(),i.getArgument(1))){job.setLeaseOwner(null);job.setLeaseUntil(null);return 1;}return 0;});
        when(jobs.bindResource(anyLong(),anyLong(),anyLong(),anyString(),any())).thenAnswer(i->{var job=database.get(1L);if(job==null || !job.getDiscoveryResultId().equals(i.getArgument(1)) || !job.getShareUrlHash().equals(i.getArgument(3)) || job.getResourceLinkId()!=null&&!job.getResourceLinkId().equals(i.getArgument(2)))return 0;job.setResourceLinkId(i.getArgument(2));return 1;});
        when(jobs.findConfirmedMissingCleanup()).thenAnswer(i->database.isEmpty()?List.of(task):List.of());
        when(jobs.findDue(any())).thenAnswer(i->database.values().stream().filter(j->!Set.of("SUCCEEDED","REVIEW_REQUIRED").contains(j.getStatus())).map(this::copy).toList());
        when(jobs.invalidateLink(any(),any())).thenReturn(1);when(jobs.invalidateDiscovery(any(),any())).thenReturn(1);
        when(client.inspectTemporaryDirectory(any())).thenAnswer(i->remoteMissing?BaiduWebClient.DestinationState.MISSING:BaiduWebClient.DestinationState.MATCHING);
        when(client.findTemporaryRecycle(any())).thenAnswer(i->remoteMissing&&!purged?recycle():null);
        doAnswer(i->{assertEquals("TRASH_SUBMITTING",stored().getStatus());remoteMissing=true;return null;}).when(client).trashTemporaryDirectory(any());
        doAnswer(i->{assertEquals("PURGE_SUBMITTING",stored().getStatus());assertNotNull(stored().getRecycleEvidence());purged=true;return null;}).when(client).purgeTemporaryDirectory(any(),any());
        when(client.isTemporaryDirectoryPurged(any(),any())).thenAnswer(i->remoteMissing&&purged);
        service=new BaiduTemporaryCleanupService(settings,jobs,tasks,discoveries,links,client,json);
    }
    @Test void compensationFreezesFirstConfirmedTimeAndNeverExtendsOrRevivesExistingJobs() {
        service.scheduleConfirmed(task);LocalDateTime deadline=stored().getDeleteAfter();assertEquals(task.getShareConfirmedAt().plusMinutes(10),deadline);
        task.setFinishedAt(LocalDateTime.now().plusHours(1));stored().setStatus("SUCCEEDED");stored().setFinishedAt(LocalDateTime.now());
        service.scheduleConfirmed(task);assertEquals(deadline,stored().getDeleteAfter());assertEquals("SUCCEEDED",stored().getStatus());assertNotNull(stored().getFinishedAt());
        verify(jobs,times(1)).insert(any(BaiduTemporaryCleanupJob.class));verifyNoInteractions(client);
    }
    @Test void compensationUsesRetentionFrozenInTaskRatherThanLaterSettings() {
        task.setRequestPayload(BaiduTemporaryScope.payload(task.getScopeKey(),task.getSearchRequestHash(),1L,task.getSavedPath(),3));
        when(settings.delayMinutes()).thenReturn(60);
        service.scheduleConfirmed(task);
        assertEquals(task.getShareConfirmedAt().plusMinutes(3),stored().getDeleteAfter());
        verify(settings,never()).delayMinutes();verifyNoInteractions(client);
    }
    @Test void changedFrozenRetentionOrPersistedDeadlineStopsAllRemoteCalls() {
        service.scheduleConfirmed(task);
        task.setRequestPayload(BaiduTemporaryScope.payload(task.getScopeKey(),task.getSearchRequestHash(),1L,task.getSavedPath(),9));
        service.cleanup(copy(stored()));assertEquals("REVIEW_REQUIRED",stored().getStatus());verifyNoInteractions(client);
        task.setRequestPayload(BaiduTemporaryScope.payload(task.getScopeKey(),task.getSearchRequestHash(),1L,task.getSavedPath(),10));
        stored().setStatus("PENDING");stored().setDeleteAfter(task.getShareConfirmedAt().plusMinutes(9));
        service.cleanup(copy(stored()));assertEquals("REVIEW_REQUIRED",stored().getStatus());verifyNoInteractions(client);
    }
    @Test void disabledExecutionStillPersistsImmutableScheduleAndNeverSendsRemoteRequests() {
        when(settings.enabled()).thenReturn(false);service.cleanupDueTransfers();assertNotNull(stored());assertTrue(service.isExpired(7L));assertFalse(service.isCleaned(7L));verifyNoInteractions(client);
    }
    @Test void permanentAndUnconfirmedTasksAreNeverScheduled() {
        task.setScopeKey("PERMANENT");service.scheduleConfirmed(task);assertTrue(database.isEmpty());
        task.setScopeKey("QQ_BOT:"+UUID.randomUUID());task.setShareConfirmedAt(null);service.scheduleConfirmed(task);assertTrue(database.isEmpty());verifyNoInteractions(client);
    }
    @Test void fullSuccessPersistsBothIntentsBeforePostsThenFinishesLocalCasOnly() {
        service.scheduleConfirmed(task);stored().setResourceLinkId(9L);service.cleanup(copy(stored()));
        assertEquals("SUCCEEDED",stored().getStatus());assertTrue(service.isCleaned(7L));assertNull(stored().getLeaseOwner());
        var order=inOrder(client,jobs);order.verify(client).inspectTemporaryDirectory(any());order.verify(jobs).transition(anyLong(),anyString(),eq("PENDING"),eq("TRASH_SUBMITTING"),isNull(),isNull(),any(),any());order.verify(client).trashTemporaryDirectory(any());
        verify(client,times(1)).purgeTemporaryDirectory(any(),any());verify(jobs).invalidateLink(argThat(j->Long.valueOf(9).equals(j.getResourceLinkId())),any());verify(jobs).invalidateDiscovery(any(),any());
        service.scheduleConfirmed(task);assertEquals("SUCCEEDED",stored().getStatus());
    }
    @Test void unexpiredDeadlineAndAnotherLeasePreventAllRemoteReadsAndWrites() {
        service.scheduleConfirmed(task);stored().setDeleteAfter(LocalDateTime.now().plusMinutes(1));service.cleanup(copy(stored()));verifyNoInteractions(client);
        stored().setDeleteAfter(LocalDateTime.now().minusMinutes(1));stored().setLeaseOwner("other");stored().setLeaseUntil(LocalDateTime.now().plusMinutes(5));service.cleanup(copy(stored()));verifyNoInteractions(client);
    }
    @Test void changedMarkerRootAccountPlanAndEvidenceAllFailBeforeRemoteCalls() throws Exception {
        service.scheduleConfirmed(task);String original=task.getRequestPayload();task.setRequestPayload(BaiduTemporaryScope.payload(task.getScopeKey(),task.getSearchRequestHash(),1L,"/GYing QQ Temp/baidu-8"));service.cleanup(copy(stored()));assertEquals("REVIEW_REQUIRED",stored().getStatus());verifyNoInteractions(client);
        stored().setStatus("PENDING");task.setRequestPayload(original);when(settings.baiduTarget(7L)).thenReturn("/New/GYing QQ Temp/baidu-7");service.cleanup(copy(stored()));verifyNoInteractions(client);
        when(settings.baiduTarget(7L)).thenReturn(task.getSavedPath());stored().setStatus("PENDING");task.setAccountUid("999");service.cleanup(copy(stored()));verifyNoInteractions(client);
        task.setAccountUid("100");stored().setStatus("PENDING");task.setCleanupEvidence("{}");service.cleanup(copy(stored()));verifyNoInteractions(client);
    }
    @Test void changedTreeBeforeFreshTrashStopsCleanupAndFailedIntentCasNeverSendsPost() {
        service.scheduleConfirmed(task);when(client.inspectTemporaryDirectory(any())).thenReturn(BaiduWebClient.DestinationState.CHANGED);service.cleanup(copy(stored()));assertEquals("REVIEW_REQUIRED",stored().getStatus());verify(client,never()).trashTemporaryDirectory(any());
        stored().setStatus("PENDING");when(client.inspectTemporaryDirectory(any())).thenReturn(BaiduWebClient.DestinationState.MATCHING);
        when(jobs.transition(anyLong(),anyString(),eq("PENDING"),eq("TRASH_SUBMITTING"),nullable(String.class),nullable(String.class),any(),any())).thenReturn(0);
        service.cleanup(copy(stored()));verify(client,never()).trashTemporaryDirectory(any());
    }
    @Test void timeoutAndProcessDeathAfterTrashIntentOnlyGetReconcileAndNeverRepeatTrash() {
        service.scheduleConfirmed(task);doThrow(new BaiduActionException("NETWORK_UNAVAILABLE",true,false,true)).when(client).trashTemporaryDirectory(any());service.cleanup(copy(stored()));assertEquals("TRASH_UNKNOWN",stored().getStatus());
        service.cleanup(copy(stored()));assertEquals("TRASH_UNKNOWN",stored().getStatus());verify(client,times(1)).trashTemporaryDirectory(any());verify(client,never()).purgeTemporaryDirectory(any(),any());
        stored().setStatus("TRASH_SUBMITTING");service.cleanup(copy(stored()));verify(client,times(1)).trashTemporaryDirectory(any());
        remoteMissing=true;service.cleanup(copy(stored()));assertEquals("SUCCEEDED",stored().getStatus());verify(client,times(1)).trashTemporaryDirectory(any());verify(client,times(1)).purgeTemporaryDirectory(any(),any());
    }
    @Test void absentRootWithoutExactRecycleObjectStaysUnknownWithoutPurge() {
        service.scheduleConfirmed(task);stored().setStatus("TRASH_SUBMITTING");remoteMissing=true;when(client.findTemporaryRecycle(any())).thenReturn(null);
        service.cleanup(copy(stored()));assertEquals("TRASH_UNKNOWN",stored().getStatus());verify(client,never()).trashTemporaryDirectory(any());verify(client,never()).purgeTemporaryDirectory(any(),any());verify(jobs,never()).invalidateDiscovery(any(),any());
    }
    @Test void timeoutAndProcessDeathAfterPurgeIntentOnlyGetAndNeverRepeatPurge() throws Exception {
        service.scheduleConfirmed(task);stored().setStatus("PURGE_SUBMITTING");stored().setRecycleEvidence(json.writeValueAsString(recycle()));remoteMissing=true;
        service.cleanup(copy(stored()));assertEquals("PURGE_UNKNOWN",stored().getStatus());verify(client,never()).trashTemporaryDirectory(any());verify(client,never()).purgeTemporaryDirectory(any(),any());
        purged=true;service.cleanup(copy(stored()));assertEquals("SUCCEEDED",stored().getStatus());verify(client,never()).purgeTemporaryDirectory(any(),any());
    }
    @Test void localDatabaseFailureAfterRemoteDeletionRetriesOnlyLocalInvalidation() {
        service.scheduleConfirmed(task);doThrow(new IllegalStateException("fixture db failure")).doReturn(1).when(jobs).invalidateDiscovery(any(),any());
        service.cleanup(copy(stored()));assertEquals("REMOTE_DELETED",stored().getStatus());service.cleanup(copy(stored()));assertEquals("SUCCEEDED",stored().getStatus());
        verify(client,times(1)).trashTemporaryDirectory(any());verify(client,times(1)).purgeTemporaryDirectory(any(),any());verify(jobs,times(2)).invalidateDiscovery(any(),any());
    }
    @Test void lateBindingAfterRemoteDeletionIsCasInvalidatedWithoutRevivingOrDelayingJob() {
        service.scheduleConfirmed(task);var deadline=stored().getDeleteAfter();stored().setStatus("SUCCEEDED");
        var discovery=new ResourceDiscoveryResult();discovery.setId(8L);discovery.setMovieId(task.getMovieId());discovery.setProvider("BAIDU");discovery.setSource("QQ_BOT");discovery.setSourceRef("BAIDU_TEMP:"+task.getScopeKey());discovery.setShareUrlHash(task.getShareUrlHash());discovery.setResourceLinkId(9L);discovery.setOriginalUrl(task.getOriginalUrl());discovery.setOriginalUrlHash(task.getOriginalUrlHash());when(discoveries.getById(8L)).thenReturn(discovery);
        var link=new ResourceLink();link.setId(9L);link.setMovieId(task.getMovieId());link.setProvider("BAIDU");link.setSource("BAIDU_TEMP");link.setSourceRef(task.getScopeKey());link.setUrlHash(task.getShareUrlHash());link.setSourceUrl(task.getOriginalUrl());when(links.getById(9L)).thenReturn(link);
        assertThrows(IllegalStateException.class,()->service.bindResource(7L,8L,9L,task.getShareUrlHash()));
        assertEquals("SUCCEEDED",stored().getStatus());assertEquals(deadline,stored().getDeleteAfter());verify(jobs).invalidateLink(argThat(j->Long.valueOf(9).equals(j.getResourceLinkId())),any());verifyNoInteractions(client);
    }
    private BaiduWebClient.RecycleEvidence recycle(){return new BaiduWebClient.RecycleEvidence("100",task.getSavedPath(),"20");}
    private BaiduTemporaryCleanupJob stored(){return database.get(1L);}
    private BaiduTemporaryCleanupJob copy(BaiduTemporaryCleanupJob job){if(job==null)return null;var result=new BaiduTemporaryCleanupJob();BeanUtils.copyProperties(job,result);return result;}
}
