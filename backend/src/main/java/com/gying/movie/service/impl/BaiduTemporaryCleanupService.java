package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduActionException;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.entity.BaiduTemporaryCleanupJob;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.mapper.BaiduTemporaryCleanupJobMapper;
import com.gying.movie.service.IBaiduTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.BaiduTemporaryScope;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Dedicated task-bound cleanup. An ambiguous trash or purge is only reconciled with GETs. */
@Service
public class BaiduTemporaryCleanupService {
    private static final Logger log = LoggerFactory.getLogger(BaiduTemporaryCleanupService.class);
    private final QqTransferCleanupSettings settings;
    private final BaiduTemporaryCleanupJobMapper jobs;
    private final IBaiduTransferTaskService tasks;
    private final IResourceDiscoveryResultService discoveries;
    private final IResourceLinkService links;
    private final BaiduWebClient client;
    private final ObjectMapper json;

    public BaiduTemporaryCleanupService(QqTransferCleanupSettings settings, BaiduTemporaryCleanupJobMapper jobs,
            IBaiduTransferTaskService tasks, IResourceDiscoveryResultService discoveries,
            IResourceLinkService links, BaiduWebClient client, ObjectMapper json) {
        this.settings=settings; this.jobs=jobs; this.tasks=tasks; this.discoveries=discoveries;
        this.links=links; this.client=client; this.json=json;
    }

    /** Repeated delivery and compensation neither extend the deadline nor revive any historical job. */
    public void scheduleConfirmed(BaiduTransferTask supplied) {
        if (supplied == null || supplied.getId() == null) return;
        BaiduTransferTask task = tasks.getById(supplied.getId());
        if (task == null || !BaiduTemporaryScope.isTemporary(task) || task.getShareConfirmedAt() == null) return;
        if (jobs.findByTaskId(task.getId()) != null) return;
        try {
            String target = settings.baiduTarget(task.getId());
            BaiduTemporaryScope.requireTarget(task,target);
            var plan=json.readValue(task.getResponsePayload(),BaiduWebClient.TransferPlan.class);
            var evidence=json.readValue(task.getCleanupEvidence(),BaiduWebClient.DestinationEvidence.class);
            requireConfirmed(task,target,plan,evidence);
            requireTemporaryClone(task);
            LocalDateTime now=LocalDateTime.now();
            var job=new BaiduTemporaryCleanupJob();
            job.setTransferTaskId(task.getId()); job.setScopeKey(task.getScopeKey()); job.setMovieId(task.getMovieId());
            job.setDiscoveryResultId(task.getDiscoveryResultId()); job.setOriginalUrl(task.getOriginalUrl());
            job.setOriginalUrlHash(task.getOriginalUrlHash()); job.setShareUrlHash(task.getShareUrlHash());
            job.setAccountUid(evidence.accountUid()); job.setTargetPath(target); job.setDirectoryId(evidence.directoryId());
            job.setPlanJson(task.getResponsePayload()); job.setEvidenceJson(task.getCleanupEvidence());
            job.setShareConfirmedAt(task.getShareConfirmedAt()); job.setDeleteAfter(task.getShareConfirmedAt().plusMinutes(BaiduTemporaryScope.retentionMinutes(task)));
            job.setDeleteMode("PERMANENT"); job.setStatus("PENDING"); job.setAttempts(0); job.setCreatedAt(now); job.setUpdatedAt(now);
            try { if (jobs.insert(job)!=1) throw new IllegalStateException("CLEANUP_NOT_PERSISTED"); }
            catch (DuplicateKeyException duplicate) { if(jobs.findByTaskId(task.getId())==null) throw duplicate; }
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("CLEANUP_EVIDENCE_INVALID"); }
    }

    public void bindResource(Long taskId,Long discoveryId,Long linkId,String shareHash) {
        if (taskId==null || discoveryId==null || linkId==null) return;
        BaiduTransferTask task=tasks.getById(taskId);
        if(task==null || !BaiduTemporaryScope.isTemporary(task) || !discoveryId.equals(task.getDiscoveryResultId())
                || !Objects.equals(shareHash,task.getShareUrlHash())) throw new IllegalStateException("CLEANUP_BINDING_INVALID");
        scheduleConfirmed(task);
        var discovery=discoveries.getById(discoveryId); var link=links.getById(linkId);
        if(discovery==null || link==null || !BaiduTemporaryScope.isTemporaryDiscovery(discovery)
                || !("BAIDU_TEMP:"+task.getScopeKey()).equals(discovery.getSourceRef())
                || !"QQ_BOT".equals(discovery.getSource()) || !"BAIDU".equals(discovery.getProvider()) || !linkId.equals(discovery.getResourceLinkId())
                || !shareHash.equals(discovery.getShareUrlHash()) || !"BAIDU".equals(link.getProvider())
                || !"BAIDU_TEMP".equals(link.getSource()) || !task.getScopeKey().equals(link.getSourceRef())
                || !shareHash.equals(link.getUrlHash()) || !task.getOriginalUrl().equals(link.getSourceUrl())
                || !Objects.equals(task.getMovieId(),discovery.getMovieId()) || !Objects.equals(task.getMovieId(),link.getMovieId())) {
            throw new IllegalStateException("CLEANUP_BINDING_INVALID");
        }
        if(jobs.bindResource(taskId,discoveryId,linkId,shareHash,LocalDateTime.now())!=1) throw new IllegalStateException("CLEANUP_BINDING_NOT_PERSISTED");
        var bound=jobs.findByTaskId(taskId);
        if(bound!=null && ("REMOTE_DELETED".equals(bound.getStatus()) || "SUCCEEDED".equals(bound.getStatus()))) {
            bound.setResourceLinkId(linkId);
            jobs.invalidateLink(bound,LocalDateTime.now()); jobs.invalidateDiscovery(bound,LocalDateTime.now());
            throw new IllegalStateException("CLEANUP_BINDING_EXPIRED");
        }
    }

    public LocalDateTime expiresAt(Long taskId) { var job=taskId==null?null:jobs.findByTaskId(taskId); return job==null?null:job.getDeleteAfter(); }
    public boolean isExpired(Long taskId) { var expiry=expiresAt(taskId); return expiry!=null && !expiry.isAfter(LocalDateTime.now()); }
    public boolean isCleaned(Long taskId) { var job=taskId==null?null:jobs.findByTaskId(taskId); return job!=null && "SUCCEEDED".equals(job.getStatus()); }

    @Scheduled(fixedDelayString="${qq-bot.baidu-temporary-cleanup-check-ms:60000}")
    public void cleanupDueTransfers() {
        for(var task:jobs.findConfirmedMissingCleanup()) {
            try { scheduleConfirmed(task); }
            catch(Exception error) { log.warn("Baidu cleanup scheduling requires review for task {}",task.getId()); }
        }
        if(!settings.enabled() || !settings.baiduPermanentDeleteEnabled()) return;
        for(var job:jobs.findDue(LocalDateTime.now())) cleanup(job);
    }

    void cleanup(BaiduTemporaryCleanupJob candidate) {
        if(!settings.enabled() || !settings.baiduPermanentDeleteEnabled()) return;
        String owner=UUID.randomUUID().toString(); LocalDateTime now=LocalDateTime.now();
        if(jobs.claim(candidate.getId(),candidate.getStatus(),owner,now,now.plusMinutes(10))!=1) return;
        var job=jobs.selectById(candidate.getId());
        try {
            if(job==null || !owner.equals(job.getLeaseOwner())) throw new IllegalStateException("CLEANUP_LEASE_LOST");
            if("REMOTE_DELETED".equals(job.getStatus())) { invalidate(job,owner); return; }
            var evidence=guard(job);
            if("PENDING".equals(job.getStatus())) {
                if(client.inspectTemporaryDirectory(evidence)!=BaiduWebClient.DestinationState.MATCHING) {
                    transition(job,owner,"REVIEW_REQUIRED","DESTINATION_CHANGED",null); return;
                }
                transition(job,owner,"TRASH_SUBMITTING",null,null);
                try { client.trashTemporaryDirectory(evidence); }
                catch(Exception error) { transition(job,owner,"TRASH_UNKNOWN",code(error),null); return; }
            }
            if("TRASH_SUBMITTING".equals(job.getStatus()) || "TRASH_UNKNOWN".equals(job.getStatus())) {
                if(client.inspectTemporaryDirectory(evidence)!=BaiduWebClient.DestinationState.MISSING) {
                    transition(job,owner,"TRASH_UNKNOWN","TRASH_UNCONFIRMED",null); return;
                }
                var recycle=client.findTemporaryRecycle(evidence);
                if(recycle==null) { transition(job,owner,"TRASH_UNKNOWN","RECYCLE_UNCONFIRMED",null); return; }
                transition(job,owner,"TRASHED",null,json.writeValueAsString(recycle));
            }
            if("TRASHED".equals(job.getStatus())) {
                evidence=guard(job);
                var recycle=json.readValue(job.getRecycleEvidence(),BaiduWebClient.RecycleEvidence.class);
                if(!recycle.equals(client.findTemporaryRecycle(evidence))) {
                    transition(job,owner,"REVIEW_REQUIRED","RECYCLE_CHANGED",null); return;
                }
                transition(job,owner,"PURGE_SUBMITTING",null,null);
                try { client.purgeTemporaryDirectory(evidence,recycle); }
                catch(Exception error) { transition(job,owner,"PURGE_UNKNOWN",code(error),null); return; }
            }
            if("PURGE_SUBMITTING".equals(job.getStatus()) || "PURGE_UNKNOWN".equals(job.getStatus())) {
                var recycle=json.readValue(job.getRecycleEvidence(),BaiduWebClient.RecycleEvidence.class);
                if(!client.isTemporaryDirectoryPurged(evidence,recycle)) {
                    transition(job,owner,"PURGE_UNKNOWN","PURGE_UNCONFIRMED",null); return;
                }
                transition(job,owner,"REMOTE_DELETED",null,null);
            }
            if("REMOTE_DELETED".equals(job.getStatus())) invalidate(job,owner);
        } catch(Exception error) {
            if(job!=null && owner.equals(job.getLeaseOwner())) {
                String next=switch(job.getStatus()) {
                    case "TRASH_SUBMITTING","TRASH_UNKNOWN" -> "TRASH_UNKNOWN";
                    case "PURGE_SUBMITTING","PURGE_UNKNOWN" -> "PURGE_UNKNOWN";
                    case "REMOTE_DELETED" -> "REMOTE_DELETED";
                    default -> error instanceof BaiduActionException baidu && baidu.retryable() && !baidu.actionRequired()
                            && job.getAttempts()<5 ? job.getStatus() : "REVIEW_REQUIRED";
                };
                try { transition(job,owner,next,code(error),null); } catch(Exception ignored) { }
            }
            log.warn("Baidu temporary cleanup requires reconciliation for job {}",candidate.getId());
        } finally { jobs.release(candidate.getId(),owner); }
    }

    private BaiduWebClient.DestinationEvidence guard(BaiduTemporaryCleanupJob job) throws Exception {
        var task=tasks.getById(job.getTransferTaskId());
        if(task==null || !"PERMANENT".equals(job.getDeleteMode()) || !Objects.equals(job.getScopeKey(),task.getScopeKey())
                || !Objects.equals(job.getDiscoveryResultId(),task.getDiscoveryResultId()) || !Objects.equals(job.getMovieId(),task.getMovieId())
                || !Objects.equals(job.getOriginalUrlHash(),task.getOriginalUrlHash()) || !Objects.equals(job.getOriginalUrl(),task.getOriginalUrl())
                || !Objects.equals(job.getShareUrlHash(),task.getShareUrlHash()) || !Objects.equals(job.getPlanJson(),task.getResponsePayload())
                || !Objects.equals(job.getEvidenceJson(),task.getCleanupEvidence()) || !Objects.equals(job.getShareConfirmedAt(),task.getShareConfirmedAt())
                || !Objects.equals(job.getAccountUid(),task.getAccountUid()) || !Objects.equals(job.getTargetPath(),settings.baiduTarget(task.getId()))) {
            throw new IllegalStateException("CLEANUP_TASK_CHANGED");
        }
        BaiduTemporaryScope.requireTarget(task,job.getTargetPath());
        if (!Objects.equals(job.getDeleteAfter(), task.getShareConfirmedAt().plusMinutes(BaiduTemporaryScope.retentionMinutes(task)))) {
            throw new IllegalStateException("CLEANUP_DEADLINE_CHANGED");
        }
        requireTemporaryClone(task);
        var plan=json.readValue(job.getPlanJson(),BaiduWebClient.TransferPlan.class);
        var evidence=json.readValue(job.getEvidenceJson(),BaiduWebClient.DestinationEvidence.class);
        requireConfirmed(task,job.getTargetPath(),plan,evidence);
        if(!job.getDirectoryId().equals(evidence.directoryId())) throw new IllegalStateException("CLEANUP_EVIDENCE_CHANGED");
        return evidence;
    }
    private void requireTemporaryClone(BaiduTransferTask task) {
        var clone=discoveries.getById(task.getDiscoveryResultId());
        if(clone==null || !"QQ_BOT".equals(clone.getSource()) || !"BAIDU".equals(clone.getProvider())
                || !("BAIDU_TEMP:"+task.getScopeKey()).equals(clone.getSourceRef())
                || !Objects.equals(task.getMovieId(),clone.getMovieId())
                || !Objects.equals(task.getOriginalUrlHash(),clone.getOriginalUrlHash())
                || !Objects.equals(task.getOriginalUrl(),clone.getOriginalUrl())) throw new IllegalStateException("CLEANUP_CLONE_CHANGED");
    }
    private void requireConfirmed(BaiduTransferTask task,String target,BaiduWebClient.TransferPlan plan,BaiduWebClient.DestinationEvidence evidence) {
        if(!"SHARED".equals(task.getPhase()) || task.getShareConfirmedAt()==null || task.getDiscoveryResultId()==null
                || !target.equals(task.getSavedPath()) || !Objects.equals(task.getAccountUid(),evidence.accountUid())
                || !BaiduWebClient.matchesDestinationEvidence(plan,evidence) || !target.equals(evidence.destination())
                || task.getShareUrl()==null || !ResourceHubHashUtils.sha256(task.getShareUrl()).equals(task.getShareUrlHash())
                || task.getOriginalUrlHash()==null || !task.getOriginalUrlHash().matches("[0-9a-f]{64}")
                || !BaiduWebClient.normalizeShareUrl(task.getOriginalUrl(),plan.code()).equals(plan.sourceUrl())) {
            throw new IllegalStateException("CLEANUP_CONFIRMATION_INVALID");
        }
    }
    private void invalidate(BaiduTemporaryCleanupJob job,String owner) {
        LocalDateTime now=LocalDateTime.now();
        var current=jobs.selectById(job.getId());
        if(current!=null && owner.equals(current.getLeaseOwner())) job.setResourceLinkId(current.getResourceLinkId());
        // Both SQLs compare current task, scope, provider, clone association and share hash in the same statement.
        // A later permanent binding remains intact; zero rows is a safe, completed physical cleanup.
        if(job.getResourceLinkId()!=null) jobs.invalidateLink(job,now);
        jobs.invalidateDiscovery(job,now);
        transition(job,owner,"SUCCEEDED",null,null);
    }
    private void transition(BaiduTemporaryCleanupJob job,String owner,String next,String error,String recycleEvidence) {
        LocalDateTime now=LocalDateTime.now();
        if(jobs.transition(job.getId(),owner,job.getStatus(),next,error,recycleEvidence,now,now.plusMinutes(10))!=1) {
            throw new IllegalStateException("CLEANUP_PROGRESS_NOT_PERSISTED");
        }
        job.setStatus(next); if(recycleEvidence!=null) job.setRecycleEvidence(recycleEvidence);
    }
    private static String code(Exception error) { return error instanceof BaiduActionException baidu?baidu.code():"CLEANUP_STATE_OR_STORAGE_FAILURE"; }
}
