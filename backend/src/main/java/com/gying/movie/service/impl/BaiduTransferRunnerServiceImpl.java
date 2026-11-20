package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduActionException;
import com.gying.movie.client.BaiduSessionVault;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IBaiduTransferRunnerService;
import com.gying.movie.service.IBaiduTransferTaskService;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.ResourceHubHashUtils;
import com.gying.movie.utils.BaiduArchivePaths;
import com.gying.movie.utils.BaiduTemporaryScope;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Persist intent before every remote write. An uncertain write is only reconciled by reads. */
@Service
public class BaiduTransferRunnerServiceImpl implements IBaiduTransferRunnerService {
    private static final int MAX_ATTEMPTS = 3;
    private static final Set<String> AUTOMATIC_STATES = Set.of("PENDING", "FAILED", "WAITING_TRANSFER", "WAITING_SHARE", "RUNNING");
    private static final Set<String> PHASES = Set.of("NEW", "PREPARED", "TRANSFER_SUBMITTING", "TRANSFERRED", "SHARE_SUBMITTING", "SHARED");
    private final ResourceHubProperties properties;
    private final BaiduWebClient client;
    private final BaiduSessionVault vault;
    private final IBaiduTransferTaskService tasks;
    private final IResourceDiscoveryResultService discoveries;
    private final IResourceLinkService links;
    private final IMovieMetadataService movies;
    private final ObjectMapper mapper;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private QqTransferCleanupSettings temporarySettings;
    private BaiduTemporaryCleanupService temporaryCleanup;

    @Autowired
    public void configureTemporaryTransfers(QqTransferCleanupSettings settings, BaiduTemporaryCleanupService cleanup) {
        this.temporarySettings = settings;
        this.temporaryCleanup = cleanup;
    }

    public BaiduTransferRunnerServiceImpl(ResourceHubProperties properties, BaiduWebClient client,
            BaiduSessionVault vault, IBaiduTransferTaskService tasks, IResourceDiscoveryResultService discoveries,
            IResourceLinkService links, IMovieMetadataService movies, ObjectMapper mapper) {
        this.properties = properties; this.client = client; this.vault = vault; this.tasks = tasks;
        this.discoveries = discoveries; this.links = links; this.movies = movies; this.mapper = mapper;
    }

    @Override
    public QuarkTransferRunResult submitPending(int limit) {
        requireEnabled();
        var result = new QuarkTransferRunResult();
        List<BaiduTransferTask> pending = tasks.list(new QueryWrapper<BaiduTransferTask>()
                .eq("scope_key", BaiduTemporaryScope.PERMANENT)
                .in("status", AUTOMATIC_STATES)
                .and(q -> q.ne("status", "FAILED").or(r -> r.lt("attempts", MAX_ATTEMPTS)))
                .and(q -> q.isNull("lease_until").or().lt("lease_until", LocalDateTime.now()))
                .orderByAsc("updated_at", "id").last("LIMIT " + Math.min(Math.max(limit, 1), 20)));
        if (pending != null) pending.forEach(task -> submitGuarded(task, result, false));
        return result;
    }

    @Override
    public QuarkTransferRunResult submitOne(Long id) {
        requireEnabled();
        var task = tasks.getById(id);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "百度转存任务不存在");
        var result = new QuarkTransferRunResult(); result.setTaskId(id);
        submitGuarded(task, result, true);
        return result;
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || !properties.isDriveShareTransferEnabled("BAIDU")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "百度转存已关闭");
        }
        if (!client.isConfigured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "请先扫码登录百度网盘");
    }

    private void submitGuarded(BaiduTransferTask candidate, QuarkTransferRunResult result, boolean manual) {
        if (candidate.getId() == null || !running.add(candidate.getId())) { skip(result); return; }
        String owner = UUID.randomUUID().toString();
        boolean claimed = false;
        BaiduTransferTask task = candidate;
        String expectedScope = candidate.getScopeKey();
        String expectedPayload = candidate.getRequestPayload();
        try {
            if (!BaiduTemporaryScope.isPermanent(task) && !BaiduTemporaryScope.isTemporary(task)) {
                fail(result, "BAIDU_SCOPE_INVALID"); return;
            }
            if (!manual && !BaiduTemporaryScope.isPermanent(task)) { skip(result); return; }
            if (BaiduTemporaryScope.isTemporary(task)) {
                requireTemporaryContext(task);
                if (temporaryCleanup.isExpired(task.getId()) || temporaryCleanup.isCleaned(task.getId())) {
                    skip(result); return;
                }
            }
            if (Set.of("CANCELED", "REVIEW_REQUIRED", "INITIALIZING").contains(task.getStatus())
                    || (!manual && ("ACTION_REQUIRED".equals(task.getStatus())
                    || ("FAILED".equals(task.getStatus()) && task.getAttempts() != null && task.getAttempts() >= MAX_ATTEMPTS)))) {
                skip(result); return;
            }
            claimed = tasks.update(scopedUpdate(candidate.getId(), expectedScope, expectedPayload)
                    .eq("status", candidate.getStatus())
                    .and(q -> q.isNull("lease_until").or().lt("lease_until", LocalDateTime.now()))
                    .set("lease_owner", owner).set("lease_until", LocalDateTime.now().plusMinutes(10)));
            if (!claimed) { skip(result); return; }
            task = tasks.getById(candidate.getId());
            if (task == null) throw new IllegalStateException("STATE_MISSING");
            if (!owner.equals(task.getLeaseOwner())) throw new IllegalStateException("LEASE_LOST");
            if (!java.util.Objects.equals(expectedScope, task.getScopeKey())
                    || !java.util.Objects.equals(expectedPayload, task.getRequestPayload())) {
                fail(result, "BAIDU_SCOPE_CHANGED"); return;
            }
            synchronized (vault) { execute(task, owner, result); }
        } catch (BaiduActionException failure) {
            if (claimed && task != null) recordFailure(task, owner, failure.code(), failure.actionRequired(), failure.uncertain(), result);
            else fail(result, "BAIDU_NOT_READY");
        } catch (Exception failure) {
            if (claimed && task != null) recordFailure(task, owner, "BAIDU_STATE_OR_STORAGE_FAILURE", false, true, result);
            else fail(result, "BAIDU_STATE_OR_STORAGE_FAILURE");
        } finally {
            if (claimed) tasks.update(scopedUpdate(candidate.getId(), expectedScope, expectedPayload)
                    .eq("lease_owner", owner).set("lease_owner", null).set("lease_until", null));
            running.remove(candidate.getId());
        }
    }

    private void execute(BaiduTransferTask task, String owner, QuarkTransferRunResult result) throws Exception {
        if (!BaiduTemporaryScope.isPermanent(task) && !BaiduTemporaryScope.isTemporary(task)) {
            throw new IllegalStateException("BAIDU_SCOPE_INVALID");
        }
        String phase = task.getPhase() == null ? "NEW" : task.getPhase();
        if (!PHASES.contains(phase)) throw new IllegalStateException("INVALID_PHASE");
        if (BaiduTemporaryScope.isTemporary(task)) requireTemporaryContext(task);
        var account = vault.metadata();
        if (!account.configured() || (hasText(task.getAccountUid()) && !task.getAccountUid().equals(account.uid()))) {
            task.setStatus("ACTION_REQUIRED"); task.setLastError("BAIDU_ACCOUNT_MISMATCH"); persist(task, owner); fail(result, "BAIDU_ACCOUNT_MISMATCH"); return;
        }
        if ("SUCCEEDED".equals(task.getStatus()) && hasText(task.getShareUrl())) {
            if (!hasText(task.getResponsePayload())) throw new IllegalStateException("MISSING_PLAN");
            var completedPlan = mapper.readValue(task.getResponsePayload(), BaiduWebClient.TransferPlan.class);
            if (completedPlan == null || !java.util.Objects.equals(task.getSavedPath(), completedPlan.destination())) {
                throw new IllegalStateException("INVALID_PLAN");
            }
            if (!BaiduWebClient.matchesFilePolicy(completedPlan, properties.isDriveVideoEnabled("BAIDU"),
                    properties.isDriveTorrentEnabled("BAIDU"))) { skip(result); return; }
            if (!client.validateOwnShare(task.getShareUrl())) { fail(result, "BAIDU_SHARE_UNAVAILABLE"); return; }
            if (BaiduTemporaryScope.isTemporary(task)) {
                var evidence = mapper.readValue(task.getCleanupEvidence(), BaiduWebClient.DestinationEvidence.class);
                if (!BaiduWebClient.matchesDestinationEvidence(completedPlan, evidence)
                        || !java.util.Objects.equals(task.getAccountUid(), evidence.accountUid())
                        || client.inspectTemporaryDirectory(evidence) != BaiduWebClient.DestinationState.MATCHING) {
                    fail(result, "BAIDU_TEMPORARY_DESTINATION_CHANGED"); return;
                }
                temporaryCleanup.scheduleConfirmed(task);
            } else if (!client.verifyDestination(completedPlan)) {
                fail(result, "BAIDU_DESTINATION_CHANGED"); return;
            }
            updateDiscovery(task); skip(result); return;
        }
        task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
        task.setStartedAt(LocalDateTime.now()); task.setAccountUid(account.uid());
        BaiduWebClient.TransferPlan plan;
        if ("NEW".equals(phase)) {
            var discovery = task.getDiscoveryResultId() == null ? null : discoveries.getById(task.getDiscoveryResultId());
            String code = discovery == null ? null : discovery.getCode();
            plan = client.planTransfer(task.getOriginalUrl(), code, transferPath(task, discovery),
                    properties.isDriveVideoEnabled("BAIDU"), properties.isDriveTorrentEnabled("BAIDU"));
            task.setResponsePayload(mapper.writeValueAsString(plan)); task.setSavedPath(plan.destination());
            task.setPhase("PREPARED"); task.setStatus("RUNNING"); persist(task, owner);
        } else {
            if (!hasText(task.getResponsePayload())) throw new IllegalStateException("MISSING_PLAN");
            plan = mapper.readValue(task.getResponsePayload(), BaiduWebClient.TransferPlan.class);
            if (plan == null || !task.getSavedPath().equals(plan.destination()) || !hasText(plan.sourceUrl())) throw new IllegalStateException("INVALID_PLAN");
        }
        if (!BaiduWebClient.matchesFilePolicy(plan, properties.isDriveVideoEnabled("BAIDU"),
                properties.isDriveTorrentEnabled("BAIDU"))) { skip(result); return; }
        if ("PREPARED".equals(task.getPhase())) {
            if (client.verifyDestination(plan)) {
                task.setPhase("TRANSFERRED"); task.setStatus("WAITING_SHARE"); persist(task, owner);
            } else {
                // This persisted phase survives process death between send and response.
                task.setPhase("TRANSFER_SUBMITTING"); task.setStatus("WAITING_TRANSFER"); persist(task, owner);
                if (!properties.isDriveShareTransferEnabled("BAIDU")
                        || !BaiduWebClient.matchesFilePolicy(plan, properties.isDriveVideoEnabled("BAIDU"),
                                properties.isDriveTorrentEnabled("BAIDU"))) { skip(result); return; }
                try {
                    var submitted = client.transfer(plan);
                    task.setRemoteTaskId(submitted.remoteTaskId()); persist(task, owner);
                } catch (BaiduActionException rejection) {
                    // Only this fresh submission may retract intent on a definite rejection.
                    if (!rejection.uncertain()) task.setPhase("PREPARED");
                    throw rejection;
                }
            }
        }
        if ("TRANSFER_SUBMITTING".equals(task.getPhase())) {
            if (!awaitDestination(plan)) {
                task.setStatus("WAITING_TRANSFER"); task.setLastError("BAIDU_TRANSFER_UNCONFIRMED"); persist(task, owner);
                fail(result, "BAIDU_TRANSFER_UNCONFIRMED"); return;
            }
            task.setPhase("TRANSFERRED"); task.setStatus("WAITING_SHARE"); task.setLastError(null); persist(task, owner);
        }
        if (!properties.isDriveShareEnabled("BAIDU")) {
            task.setStatus("WAITING_SHARE"); task.setLastError("BAIDU_SHARE_DISABLED"); persist(task, owner); skip(result); return;
        }
        String share = client.findOwnShare(plan.destination());
        if (!hasText(share) && "TRANSFERRED".equals(task.getPhase())) {
            task.setPhase("SHARE_SUBMITTING"); task.setStatus("WAITING_SHARE"); persist(task, owner);
            if (!properties.isDriveShareEnabled("BAIDU")) { skip(result); return; }
            try { share = client.createOwnShare(plan.destination()); }
            catch (BaiduActionException rejection) {
                if (!rejection.uncertain()) task.setPhase("TRANSFERRED");
                throw rejection;
            }
        }
        if (!hasText(share)) {
            task.setStatus("WAITING_SHARE"); task.setLastError("BAIDU_SHARE_UNCONFIRMED"); persist(task, owner);
            fail(result, "BAIDU_SHARE_UNCONFIRMED"); return;
        }
        if (!client.validateOwnShare(share)) {
            task.setStatus("WAITING_SHARE"); task.setLastError("BAIDU_SHARE_UNAVAILABLE"); persist(task, owner); fail(result, "BAIDU_SHARE_UNAVAILABLE"); return;
        }
        if (BaiduTemporaryScope.isTemporary(task) && task.getShareConfirmedAt() == null) {
            var evidence = client.captureDestinationEvidence(plan);
            if (evidence == null || !task.getAccountUid().equals(evidence.accountUid())
                    || !plan.destination().equals(evidence.destination())) {
                throw new IllegalStateException("BAIDU_TEMPORARY_EVIDENCE_INVALID");
            }
            task.setCleanupEvidence(mapper.writeValueAsString(evidence));
            task.setShareConfirmedAt(LocalDateTime.now());
        }
        task.setShareUrl(share); task.setShareUrlHash(ResourceHubHashUtils.sha256(share));
        task.setPhase("SHARED"); task.setStatus("SUCCEEDED"); task.setLastError(null);
        task.setFinishedAt(task.getShareConfirmedAt() == null ? LocalDateTime.now() : task.getShareConfirmedAt()); persist(task, owner);
        if (BaiduTemporaryScope.isTemporary(task)) temporaryCleanup.scheduleConfirmed(task);
        updateDiscovery(task); result.setSubmitted(result.getSubmitted() + 1);
    }

    private boolean awaitDestination(BaiduWebClient.TransferPlan plan) {
        int attempts = Math.min(Math.max(properties.getBaidu().getPollAttempts(), 1), 30);
        for (int i = 0; i < attempts; i++) {
            if (client.verifyDestination(plan)) return true;
            if (i + 1 < attempts) {
                try { Thread.sleep(Math.min(Math.max(properties.getBaidu().getPollIntervalMs(), 0), 2000)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private void persist(BaiduTransferTask task, String owner) {
        task.setUpdatedAt(LocalDateTime.now());
        // Compare the lease on every write. Updating this object directly would lose null clears.
        var update = scopedUpdate(task.getId(), task.getScopeKey(), task.getRequestPayload()).eq("lease_owner", owner)
                .gt("lease_until", LocalDateTime.now()).set("status", task.getStatus()).set("phase", task.getPhase())
                .set("account_uid", task.getAccountUid()).set("saved_path", task.getSavedPath())
                .set("share_url", task.getShareUrl()).set("share_url_hash", task.getShareUrlHash())
                .set("response_payload", task.getResponsePayload()).set("remote_task_id", task.getRemoteTaskId())
                .set("attempts", task.getAttempts()).set("last_error", task.getLastError())
                .set("started_at", task.getStartedAt()).set("finished_at", task.getFinishedAt())
                .set("share_confirmed_at", task.getShareConfirmedAt()).set("cleanup_evidence", task.getCleanupEvidence())
                .set("updated_at", task.getUpdatedAt()).set("lease_until", LocalDateTime.now().plusMinutes(10));
        if (!tasks.update(update)) throw new IllegalStateException("BAIDU_PROGRESS_NOT_PERSISTED");
    }

    private static UpdateWrapper<BaiduTransferTask> scopedUpdate(Long id, String scope, String payload) {
        var update = new UpdateWrapper<BaiduTransferTask>().eq("id", id).apply("BINARY scope_key=BINARY {0}", scope);
        return payload == null ? update.isNull("request_payload") : update.apply("BINARY request_payload=BINARY {0}", payload);
    }

    private void recordFailure(BaiduTransferTask task, String owner, String code, boolean actionRequired,
            boolean uncertain, QuarkTransferRunResult result) {
        String safeCode = code != null && code.matches("[A-Z0-9_]{1,100}") ? code : "BAIDU_REQUEST_FAILED";
        // Read failures during recovery never retract an already persisted remote-write intent.
        if ("SHARED".equals(task.getPhase()) && task.getShareConfirmedAt() != null
                && hasText(task.getCleanupEvidence()) && hasText(task.getShareUrl())) task.setStatus("SUCCEEDED");
        else if (actionRequired) task.setStatus("ACTION_REQUIRED");
        else if ("TRANSFER_SUBMITTING".equals(task.getPhase())) task.setStatus("WAITING_TRANSFER");
        else if ("SHARE_SUBMITTING".equals(task.getPhase())) task.setStatus("WAITING_SHARE");
        else task.setStatus("FAILED");
        task.setLastError(safeCode);
        if (task.getShareConfirmedAt() == null) task.setFinishedAt(LocalDateTime.now());
        try { persist(task, owner); } catch (Exception ignored) { /* Already persisted intent is retained. */ }
        fail(result, safeCode);
    }

    private void updateDiscovery(BaiduTransferTask task) {
        if (task.getDiscoveryResultId() == null || !hasText(task.getShareUrl())) return;
        ResourceDiscoveryResult discovery = discoveries.getById(task.getDiscoveryResultId());
        if (discovery == null || !"BAIDU".equalsIgnoreCase(discovery.getProvider())) return;
        boolean temporary = BaiduTemporaryScope.isTemporary(task);
        if (temporary && (!"QQ_BOT".equals(discovery.getSource())
                || !(BaiduTemporaryScope.DISCOVERY_PREFIX + task.getScopeKey()).equals(discovery.getSourceRef())
                || !java.util.Objects.equals(task.getMovieId(), discovery.getMovieId())
                || !java.util.Objects.equals(task.getOriginalUrlHash(), discovery.getOriginalUrlHash()))) {
            throw new IllegalStateException("BAIDU_TEMPORARY_DISCOVERY_MISMATCH");
        }
        String code = BaiduWebClient.extractShareCode(task.getShareUrl());
        String resourceType;
        try {
            var plan = mapper.readValue(task.getResponsePayload(), BaiduWebClient.TransferPlan.class);
            resourceType = BaiduWebClient.hasVideoFiles(plan) ? "DISK" : "TORRENT";
        } catch (Exception invalid) { throw new IllegalStateException("BAIDU_PLAN_INVALID"); }
        if (discovery.getResourceLinkId() != null) {
            ResourceLink link = links.getById(discovery.getResourceLinkId());
            if (link != null && "BAIDU".equalsIgnoreCase(link.getProvider())) {
                if (temporary && (!BaiduTemporaryScope.RESOURCE_SOURCE.equals(link.getSource())
                        || !task.getScopeKey().equals(link.getSourceRef())
                        || !task.getShareUrlHash().equals(link.getUrlHash())
                        || link.getDeletedAt() != null || !"ACTIVE".equals(link.getStatus()))) {
                    throw new IllegalStateException("BAIDU_TEMPORARY_RESOURCE_MISMATCH");
                }
                var linkUpdate = new UpdateWrapper<ResourceLink>().eq("id", link.getId());
                if (temporary) linkUpdate.eq("source", BaiduTemporaryScope.RESOURCE_SOURCE)
                        .eq("source_ref", task.getScopeKey()).eq("url_hash", task.getShareUrlHash()).isNull("deleted_at");
                boolean updated = links.update(linkUpdate
                        .set("url", task.getShareUrl()).set("url_hash", task.getShareUrlHash()).set("code", code).set("type", resourceType)
                        .set("status", "ACTIVE").set("link_status", "NORMAL").set("last_check_error", null)
                        .set("validated_at", LocalDateTime.now()).set("updated_at", LocalDateTime.now()));
                if (!updated) throw new IllegalStateException("BAIDU_RESOURCE_NOT_PERSISTED");
            }
        }
        var discoveryUpdate = new UpdateWrapper<ResourceDiscoveryResult>().eq("id", discovery.getId());
        if (temporary) discoveryUpdate.eq("source", "QQ_BOT")
                .eq("source_ref", BaiduTemporaryScope.DISCOVERY_PREFIX + task.getScopeKey())
                .eq("original_url_hash", task.getOriginalUrlHash())
                .and(q -> q.ne("status", "IGNORED").or().isNull("status"));
        if (!discoveries.update(discoveryUpdate
                .set("share_url", task.getShareUrl()).set("share_url_hash", task.getShareUrlHash()).set("code", code).set("resource_type", resourceType)
                .set("status", discovery.getResourceLinkId() == null ? "DISCOVERED" : "SAVED")
                .set("failure_reason", null).set("updated_at", LocalDateTime.now()))) {
            throw new IllegalStateException("BAIDU_DISCOVERY_NOT_PERSISTED");
        }
    }

    private String requireTemporaryContext(BaiduTransferTask task) {
        if (temporarySettings == null || temporaryCleanup == null) throw new IllegalStateException("BAIDU_TEMPORARY_NOT_READY");
        String target = BaiduTemporaryScope.requireTarget(task, temporarySettings.baiduTarget(task.getId()));
        if (task.getSavedPath() != null && !target.equals(task.getSavedPath())) throw new IllegalStateException("BAIDU_TEMPORARY_PATH_MISMATCH");
        ResourceDiscoveryResult discovery = task.getDiscoveryResultId() == null ? null : discoveries.getById(task.getDiscoveryResultId());
        if (discovery == null || !"BAIDU".equals(discovery.getProvider()) || !"QQ_BOT".equals(discovery.getSource())
                || !(BaiduTemporaryScope.DISCOVERY_PREFIX + task.getScopeKey()).equals(discovery.getSourceRef())
                || !java.util.Objects.equals(task.getMovieId(), discovery.getMovieId())
                || !java.util.Objects.equals(task.getOriginalUrl(), discovery.getOriginalUrl())
                || !java.util.Objects.equals(task.getOriginalUrlHash(), discovery.getOriginalUrlHash())) {
            throw new IllegalStateException("BAIDU_TEMPORARY_DISCOVERY_MISMATCH");
        }
        return target;
    }

    String transferPath(BaiduTransferTask task, ResourceDiscoveryResult discovery) {
        if (BaiduTemporaryScope.isTemporary(task)) return requireTemporaryContext(task);
        if (!BaiduTemporaryScope.isPermanent(task)) throw new IllegalStateException("BAIDU_SCOPE_INVALID");
        MovieMetadata movie = task.getMovieId() == null ? null : movies.getById(task.getMovieId());
        String title = movie == null ? null : hasText(movie.getTitleCn()) ? movie.getTitleCn() : movie.getTitleEn();
        if (!hasText(title) && discovery != null) title = discovery.getTitle();
        String id = safeSegment(task.getMovieId() == null ? "unknown" : task.getMovieId());
        String base = BaiduArchivePaths.categoryRoot(properties.getBaidu().getSavePath(), movie == null ? null : movie.getCategory());
        return base + "/" + (hasText(title) ? safeSegment(title) + "（" + id + "）" : id);
    }

    private static String safeSegment(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return safe.isEmpty() || ".".equals(safe) || "..".equals(safe) ? "unknown" : safe.substring(0, Math.min(safe.length(), 200));
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static void skip(QuarkTransferRunResult result) { result.setSkipped(result.getSkipped() + 1); }
    private static void fail(QuarkTransferRunResult result, String code) {
        result.setFailed(result.getFailed() + 1); if (result.getErrors().size() < 10) result.getErrors().add(code);
    }
}
