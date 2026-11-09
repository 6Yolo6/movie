package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduSessionVault;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IBaiduTransferRunnerService;
import com.gying.movie.service.IBaiduTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.BaiduTemporaryScope;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** User selection has its own durable scope; no remote call runs in a database transaction. */
@Service
public class BaiduTemporaryTransferService {
    public record SelectionResult(ResourceLink resource, String status, Long taskId, LocalDateTime expiresAt) {}
    private final ResourceHubProperties properties;
    private final BaiduWebClient client;
    private final BaiduSessionVault vault;
    private final IBaiduTransferTaskService tasks;
    private final IResourceDiscoveryResultService discoveries;
    private final IResourceLinkService links;
    private final IBaiduTransferRunnerService runner;
    private final QqTransferCleanupSettings settings;
    private final BaiduTemporaryCleanupService cleanup;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public BaiduTemporaryTransferService(ResourceHubProperties properties, BaiduWebClient client, BaiduSessionVault vault,
            IBaiduTransferTaskService tasks, IResourceDiscoveryResultService discoveries, IResourceLinkService links,
            IBaiduTransferRunnerService runner, QqTransferCleanupSettings settings, BaiduTemporaryCleanupService cleanup,
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.properties = properties; this.client = client; this.vault = vault; this.tasks = tasks;
        this.discoveries = discoveries; this.links = links; this.runner = runner; this.settings = settings;
        this.cleanup = cleanup; this.jdbc = jdbc; this.transactions = transactions; this.mapper = mapper;
    }

    public SelectionResult select(Long discoveryId, String userKey) {
        if (!ready()) return outcome(null, "BAIDU_NOT_READY");
        ResourceDiscoveryResult source = discoveryId == null ? null : discoveries.getById(discoveryId);
        if (!validSource(source)) return outcome(null, "INVALID_RESOURCE");
        BaiduTransferTask task = null;
        try {
            task = findOrCreate(source, userKey);
            if (expired(task)) return outcome(task, "EXPIRED");
            if (Set.of("CANCELED", "REVIEW_REQUIRED", "INITIALIZING").contains(task.getStatus())) return outcome(task, task.getStatus());
            boolean unknownIntent = Set.of("TRANSFER_SUBMITTING", "SHARE_SUBMITTING").contains(task.getPhase());
            if (!"SUCCEEDED".equals(task.getStatus()) && !unknownIntent && task.getAttempts() != null && task.getAttempts() >= 3) {
                return outcome(task, "ACTION_REQUIRED");
            }
            runner.submitOne(task.getId());
            task = tasks.getById(task.getId());
            if (task == null || !BaiduTemporaryScope.isTemporary(task)) return outcome(task, "INVALID_RESOURCE");
            if (expired(task)) return outcome(task, "EXPIRED");
            if (!"SUCCEEDED".equals(task.getStatus()) || !"SHARED".equals(task.getPhase())
                    || task.getShareConfirmedAt() == null || task.getCleanupEvidence() == null || task.getShareUrl() == null) {
                return outcome(task, task.getStatus());
            }
            // Scheduling reads the already persisted confirmation snapshot and never moves the clock.
            cleanup.scheduleConfirmed(task);
            synchronized (vault) {
                if (!Objects.equals(task.getAccountUid(), vault.metadata().uid()) || !client.validateOwnShare(task.getShareUrl())) {
                    return outcome(task, "WAITING_SHARE");
                }
                var plan = mapper.readValue(task.getResponsePayload(), BaiduWebClient.TransferPlan.class);
                var evidence = mapper.readValue(task.getCleanupEvidence(), BaiduWebClient.DestinationEvidence.class);
                if (!BaiduWebClient.matchesDestinationEvidence(plan, evidence)
                        || !Objects.equals(task.getAccountUid(), evidence.accountUid())
                        || client.inspectTemporaryDirectory(evidence) != BaiduWebClient.DestinationState.MATCHING) {
                    return outcome(task, "WAITING_SHARE");
                }
            }
            ResourceLink resource = bindLocalResource(task);
            cleanup.bindResource(task.getId(), task.getDiscoveryResultId(), resource.getId(), task.getShareUrlHash());
            if (expired(task)) return outcome(task, "EXPIRED");
            return new SelectionResult(resource, "SUCCEEDED", task.getId(), cleanup.expiresAt(task.getId()));
        } catch (IllegalStateException invalid) {
            if ("CLEANUP_BINDING_EXPIRED".equals(invalid.getMessage())) return outcome(task, "EXPIRED");
            return outcome(task, "ACTION_REQUIRED");
        } catch (Exception unavailable) {
            // Persisted submitting phases remain authoritative; another selection reuses this same task.
            return outcome(task, task == null ? "BAIDU_NOT_READY" : "WAITING_SHARE");
        }
    }

    public boolean automaticCleanupEnabled() {
        return settings.enabled() && settings.baiduPermanentDeleteEnabled();
    }

    private boolean ready() {
        return properties.isEnabled() && properties.isDriveShareTransferEnabled("BAIDU") && client.isConfigured();
    }

    private boolean expired(BaiduTransferTask task) {
        return task != null && (cleanup.isExpired(task.getId()) || cleanup.isCleaned(task.getId()));
    }

    private SelectionResult outcome(BaiduTransferTask task, String status) {
        return new SelectionResult(null, status == null ? "WAITING_SHARE" : status,
                task == null ? null : task.getId(), task == null ? null : cleanup.expiresAt(task.getId()));
    }

    private boolean validSource(ResourceDiscoveryResult source) {
        return source != null && source.getId() != null && source.getId() > 0 && "BAIDU".equals(source.getProvider())
                && source.getMovieId() != null && !source.getMovieId().isBlank()
                && source.getOriginalUrl() != null && !BaiduTemporaryScope.isTemporaryDiscovery(source);
    }

    BaiduTransferTask findOrCreate(ResourceDiscoveryResult source, String userKey) {
        String original = BaiduWebClient.normalizeShareUrl(source.getOriginalUrl(), source.getCode());
        String sourceHash = ResourceHubHashUtils.sha256(original);
        String requestHash = BaiduTemporaryScope.requestHash(userKey, source.getMovieId(), sourceHash);
        return transactions.execute(tx -> {
            // Existing movie rows serialize allocation for the same source/user across instances and restarts.
            List<String> movie = jdbc.queryForList("SELECT id FROM movie_metadata WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL FOR UPDATE",
                    String.class, source.getMovieId());
            if (movie.size() != 1) throw new IllegalStateException("BAIDU_TEMPORARY_MOVIE_UNAVAILABLE");
            BaiduTransferTask existing = tasks.getOne(new QueryWrapper<BaiduTransferTask>()
                    .eq("movie_id", source.getMovieId()).eq("search_request_hash", requestHash)
                    .orderByDesc("id").last("LIMIT 1 FOR UPDATE"), false);
            if (existing != null && !cleanup.isCleaned(existing.getId())) {
                if (!BaiduTemporaryScope.isTemporary(existing)) throw new IllegalStateException("BAIDU_TEMPORARY_SCOPE_INVALID");
                BaiduTemporaryScope.requireTarget(existing, settings.baiduTarget(existing.getId()));
                return existing;
            }
            LocalDateTime now = LocalDateTime.now();
            BaiduTransferTask task = new BaiduTransferTask();
            task.setMovieId(source.getMovieId()); task.setOriginalUrl(original); task.setOriginalUrlHash(sourceHash);
            task.setScopeKey("QQ_BOT:" + UUID.randomUUID()); task.setSearchRequestHash(requestHash);
            task.setStatus("INITIALIZING"); task.setPhase("NEW"); task.setAttempts(0);
            task.setCreatedAt(now); task.setUpdatedAt(now);
            if (!tasks.save(task) || task.getId() == null) throw new IllegalStateException("BAIDU_TEMPORARY_TASK_NOT_PERSISTED");
            ResourceDiscoveryResult clone = cloneSource(source, task, original, sourceHash, now);
            if (!discoveries.save(clone) || clone.getId() == null) throw new IllegalStateException("BAIDU_TEMPORARY_DISCOVERY_NOT_PERSISTED");
            task.setDiscoveryResultId(clone.getId());
            task.setRequestPayload(BaiduTemporaryScope.payload(task.getScopeKey(), requestHash, source.getId(), settings.baiduTarget(task.getId()), settings.delayMinutes()));
            task.setStatus("PENDING");
            if (!tasks.update(new UpdateWrapper<BaiduTransferTask>().eq("id", task.getId()).eq("scope_key", task.getScopeKey())
                    .eq("status", "INITIALIZING").set("discovery_result_id", clone.getId())
                    .set("request_payload", task.getRequestPayload()).set("status", "PENDING").set("updated_at", now))) {
                throw new IllegalStateException("BAIDU_TEMPORARY_MARKER_NOT_PERSISTED");
            }
            return task;
        });
    }

    private ResourceDiscoveryResult cloneSource(ResourceDiscoveryResult source, BaiduTransferTask task,
            String original, String hash, LocalDateTime now) {
        ResourceDiscoveryResult clone = new ResourceDiscoveryResult();
        clone.setTaskId(source.getTaskId()); clone.setMovieId(source.getMovieId()); clone.setSource("QQ_BOT");
        clone.setSourceRef(BaiduTemporaryScope.DISCOVERY_PREFIX + task.getScopeKey());
        clone.setTitle(source.getTitle()); clone.setProvider("BAIDU"); clone.setResourceType(source.getResourceType());
        clone.setOriginalUrl(original); clone.setOriginalUrlHash(hash); clone.setCode(source.getCode());
        clone.setQuality(source.getQuality()); clone.setSubtitle(source.getSubtitle()); clone.setFileSize(source.getFileSize());
        clone.setVersionNote(source.getVersionNote()); clone.setConfidence(source.getConfidence());
        clone.setStatus("DISCOVERED"); clone.setCreatedAt(now); clone.setUpdatedAt(now);
        return clone;
    }

    private ResourceLink bindLocalResource(BaiduTransferTask confirmation) {
        return transactions.execute(tx -> {
            jdbc.queryForObject("SELECT id FROM baidu_transfer_task WHERE id=? FOR UPDATE", Long.class, confirmation.getId());
            BaiduTransferTask task = tasks.getById(confirmation.getId());
            if (task == null || !BaiduTemporaryScope.isTemporary(task) || expired(task)
                    || !"SHARED".equals(task.getPhase()) || !"SUCCEEDED".equals(task.getStatus())
                    || !Objects.equals(task.getShareUrlHash(), confirmation.getShareUrlHash())) {
                throw new IllegalStateException("BAIDU_TEMPORARY_CONFIRMATION_CHANGED");
            }
            ResourceDiscoveryResult clone = discoveries.getById(task.getDiscoveryResultId());
            if (clone == null || !"QQ_BOT".equals(clone.getSource())
                    || !(BaiduTemporaryScope.DISCOVERY_PREFIX + task.getScopeKey()).equals(clone.getSourceRef())
                    || !Objects.equals(task.getMovieId(), clone.getMovieId())
                    || !Objects.equals(task.getShareUrlHash(), clone.getShareUrlHash())) {
                throw new IllegalStateException("BAIDU_TEMPORARY_DISCOVERY_MISMATCH");
            }
            if (clone.getResourceLinkId() != null) {
                ResourceLink linked = links.getById(clone.getResourceLinkId());
                if (!matchesResource(linked, task)) throw new IllegalStateException("BAIDU_TEMPORARY_RESOURCE_MISMATCH");
                return linked;
            }
            BaiduWebClient.TransferPlan plan;
            try { plan = mapper.readValue(task.getResponsePayload(), BaiduWebClient.TransferPlan.class); }
            catch (Exception invalid) { throw new IllegalStateException("BAIDU_TEMPORARY_PLAN_INVALID"); }
            if (plan == null || !Objects.equals(plan.destination(), task.getSavedPath())) throw new IllegalStateException("BAIDU_TEMPORARY_PLAN_INVALID");
            LocalDateTime now = LocalDateTime.now();
            ResourceLink link = new ResourceLink();
            link.setMovieId(task.getMovieId()); link.setName(clone.getTitle());
            link.setProvider("BAIDU"); link.setType(BaiduWebClient.hasVideoFiles(plan) ? "DISK" : "TORRENT");
            link.setUrl(task.getShareUrl()); link.setUrlHash(task.getShareUrlHash()); link.setCode(BaiduWebClient.extractShareCode(task.getShareUrl()));
            link.setSource(BaiduTemporaryScope.RESOURCE_SOURCE); link.setSourceRef(task.getScopeKey()); link.setSourceUrl(task.getOriginalUrl());
            link.setAutoCollected(true); link.setAuditStatus(1); link.setStatus("ACTIVE"); link.setLinkStatus("NORMAL");
            link.setQuality(clone.getQuality()); link.setSubtitle(clone.getSubtitle()); link.setFileSize(clone.getFileSize()); link.setVersionNote(clone.getVersionNote());
            link.setCreatedAt(now); link.setUpdatedAt(now); link.setValidatedAt(now);
            if (!links.save(link) || link.getId() == null) throw new IllegalStateException("BAIDU_TEMPORARY_RESOURCE_NOT_PERSISTED");
            if (!discoveries.update(new UpdateWrapper<ResourceDiscoveryResult>().eq("id", clone.getId())
                    .eq("source", "QQ_BOT").eq("source_ref", BaiduTemporaryScope.DISCOVERY_PREFIX + task.getScopeKey())
                    .eq("share_url_hash", task.getShareUrlHash()).isNull("resource_link_id")
                    .set("resource_link_id", link.getId()).set("status", "SAVED").set("updated_at", now))) {
                throw new IllegalStateException("BAIDU_TEMPORARY_RESOURCE_NOT_PERSISTED");
            }
            return link;
        });
    }

    private boolean matchesResource(ResourceLink link, BaiduTransferTask task) {
        return link != null && "BAIDU".equals(link.getProvider()) && BaiduTemporaryScope.RESOURCE_SOURCE.equals(link.getSource())
                && task.getScopeKey().equals(link.getSourceRef()) && task.getMovieId().equals(link.getMovieId())
                && task.getShareUrl().equals(link.getUrl()) && task.getShareUrlHash().equals(link.getUrlHash())
                && link.getDeletedAt() == null && "ACTIVE".equals(link.getStatus());
    }

    /** Formal-library links require current own-account evidence even if the database says NORMAL. */
    public boolean validateOwnedResource(ResourceLink link) {
        if (link == null || !"BAIDU".equalsIgnoreCase(link.getProvider()) || link.getUrl() == null
                || BaiduTemporaryScope.RESOURCE_SOURCE.equals(link.getSource()) || link.getDeletedAt() != null
                || !Set.of("DISK", "TORRENT").contains(link.getType())) return false;
        synchronized (vault) {
            if (!client.isConfigured()) return false;
            boolean video = "DISK".equals(link.getType());
            if (video ? !client.validateOwnVideoShare(link.getUrl()) : !client.validateOwnShare(link.getUrl())) return false;
            BaiduTransferTask task = tasks.getOne(new QueryWrapper<BaiduTransferTask>()
                    .eq("scope_key", BaiduTemporaryScope.PERMANENT).eq("share_url", link.getUrl()).eq("status", "SUCCEEDED")
                    .eq("phase", "SHARED").orderByDesc("id").last("LIMIT 1"), false);
            if (task == null) return true;
            if (!BaiduTemporaryScope.isPermanent(task) || !Objects.equals(task.getAccountUid(), vault.metadata().uid())
                    || task.getResponsePayload() == null) return false;
            try {
                var plan = mapper.readValue(task.getResponsePayload(), BaiduWebClient.TransferPlan.class);
                return plan != null && Objects.equals(task.getSavedPath(), plan.destination())
                        && (!video || BaiduWebClient.hasVideoFiles(plan)) && client.verifyDestination(plan);
            } catch (Exception invalid) { return false; }
        }
    }

    /** A committed selection survives a lost HTTP reply; only this scoped lane resumes it. */
    @Scheduled(fixedDelayString = "${qq-bot.transfer-cleanup-check-ms:60000}")
    public void resumePendingSelections() {
        if (!ready()) return;
        List<BaiduTransferTask> pending = tasks.list(new QueryWrapper<BaiduTransferTask>().likeRight("scope_key", "QQ_BOT:")
                .in("status", List.of("PENDING", "RUNNING", "WAITING_TRANSFER", "WAITING_SHARE"))
                .and(q -> q.isNull("lease_until").or().lt("lease_until", LocalDateTime.now()))
                .orderByAsc("updated_at", "id").last("LIMIT 10"));
        if (pending == null) return;
        for (BaiduTransferTask task : pending) {
            if (!BaiduTemporaryScope.isTemporary(task) || expired(task)) continue;
            try { runner.submitOne(task.getId()); } catch (Exception ignored) { /* Durable intent is retained. */ }
        }
    }
}
