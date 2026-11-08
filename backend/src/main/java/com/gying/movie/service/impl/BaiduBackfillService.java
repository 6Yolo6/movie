package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduActionException;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.*;
import com.gying.movie.service.*;
import com.gying.movie.utils.QqTransferMarker;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Slowly consumes a fixed local snapshot, then performs Baidu-only source discovery. */
@Service
public class BaiduBackfillService {
    private final BaiduBackfillTaskStore store;
    private final JdbcTemplate jdbc;
    private final ResourceHubProperties properties;
    private final IResourceHubConfigService config;
    private final IBaiduTransferTaskService transfers;
    private final IBaiduTransferRunnerService runner;
    private final IResourceDiscoveryResultService discoveries;
    private final IResourceDiscoveryService discovery;
    private final IResourceHubTaskService tasks;
    private final IResourceHubPublishService publisher;
    private final GyingSourceWorkflowService gying;
    private final IResourceLinkService links;
    private final IMovieMetadataService movies;
    private final P2pArchiveTaskService archives;
    private final BaiduWebClient client;
    private final ObjectMapper mapper;

    public BaiduBackfillService(BaiduBackfillTaskStore store, JdbcTemplate jdbc, ResourceHubProperties properties,
            IResourceHubConfigService config, IBaiduTransferTaskService transfers, IBaiduTransferRunnerService runner,
            IResourceDiscoveryResultService discoveries, IResourceDiscoveryService discovery, IResourceHubTaskService tasks,
            IResourceHubPublishService publisher, GyingSourceWorkflowService gying,
            IResourceLinkService links, IMovieMetadataService movies, P2pArchiveTaskService archives, BaiduWebClient client, ObjectMapper mapper) {
        this.store = store; this.jdbc = jdbc; this.properties = properties; this.config = config;
        this.transfers = transfers; this.runner = runner; this.discoveries = discoveries; this.discovery = discovery;
        this.tasks = tasks; this.publisher = publisher; this.gying = gying; this.links = links; this.movies = movies; this.archives = archives; this.client = client; this.mapper = mapper;
    }
    public record Status(Long taskId, boolean enabled, String status, String phase, int intervalMinutes, int batchLimit,
            long processed, long succeeded, long skipped, long failed, String nextRunAt, String blockedReason, String currentMovieId) { }

    public Status status() { config.reload(); return view(store.read()); }
    public Status settings(boolean enabled, int intervalMinutes, int batchLimit) {
        config.reload(); return view(store.settings(enabled, intervalMinutes, batchLimit));
    }
    public Status runOnce() { return run(true); }

    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void runDue() { run(false); }

    private Status run(boolean manual) {
        config.reload();
        BaiduBackfillTaskStore.State state = store.claim(manual);
        if (state == null) return view(store.read());
        String runOwner = state.leaseOwner;
        try {
            long startingSettingsRevision = state.settingsRevision;
            int maximum = state.batchLimit;
            int completed = 0;
            while (completed < Math.min(maximum, state.batchLimit) && !"COMPLETE".equals(state.phase)) {
                String gate = blocked(state, manual);
                if (gate != null) { state.blockedReason = gate; break; }
                if (state.currentKind == null) {
                    state = select(state);
                    if (state.currentKind == null) continue;
                }
                long before = state.processed;
                state = process(state);
                if (state.blockedReason != null) break;
                if (state.processed > before) completed++;
                // Observe pauses between items; a manual run may start with the parent paused.
                if (!state.enabled && (!manual || state.settingsRevision != startingSettingsRevision)) break;
            }
        } catch (BaiduActionException failure) {
            state = store.read();
            if (!runOwner.equals(state.leaseOwner)) return view(state);
            state.blockedReason = failure.actionRequired() ? "CHILD_ACTION_REQUIRED" : failure.uncertain() ? "CHILD_UNKNOWN" : "PROCESSING_ERROR";
        } catch (Exception failure) {
            // Reload the durable child pointer: a transaction may have committed before an exception.
            BaiduBackfillTaskStore.State latest = store.read();
            if (!runOwner.equals(latest.leaseOwner)) return view(latest);
            state = latest; state.blockedReason = "PROCESSING_ERROR";
        }
        try { return view(store.finish(state)); }
        catch (IllegalStateException lostLease) { return view(store.read()); }
    }

    private BaiduBackfillTaskStore.State select(BaiduBackfillTaskStore.State state) {
        switch (state.phase) {
            case "EXISTING_TASKS" -> {
                Long id = nextLong("SELECT id FROM baidu_transfer_task WHERE id>? AND id<=? ORDER BY id LIMIT 1", state.taskCursor, state.taskMaxId);
                if (id == null) { state.phase = "EXISTING_DISCOVERIES"; return store.checkpoint(state, null); }
                BaiduTransferTask child = transfers.getById(id);
                state.currentKind = "BAIDU_TASK"; state.currentId = id; state.currentChildId = id;
                state.currentMovieId = child == null ? null : child.getMovieId();
            }
            case "EXISTING_DISCOVERIES" -> {
                Long id = nextLong("SELECT id FROM resource_discovery_result WHERE provider='BAIDU' AND id>? AND id<=? ORDER BY id LIMIT 1", state.discoveryCursor, state.discoveryMaxId);
                if (id != null) {
                    ResourceDiscoveryResult item = discoveries.getById(id);
                    state.currentKind = "DISCOVERY"; state.currentId = id; state.currentMovieId = item == null ? null : item.getMovieId();
                } else {
                    Long linkId = nextLong("SELECT id FROM resource_link WHERE provider='BAIDU' AND type='DISK' AND status='ACTIVE' AND audit_status=1 AND link_status='NORMAL' AND deleted_at IS NULL AND id>? AND id<=? ORDER BY id LIMIT 1", state.linkCursor, state.linkMaxId);
                    if (linkId == null) { state.phase = "EXISTING_P2P"; return store.checkpoint(state, null); }
                    ResourceLink item = links.getById(linkId);
                    state.currentKind = "RESOURCE_LINK"; state.currentId = linkId; state.currentMovieId = item == null ? null : item.getMovieId();
                }
            }
            case "EXISTING_P2P" -> {
                Long id = nextLong("SELECT id FROM resource_hub_task WHERE task_type='P2P_ARCHIVE' AND id>? AND id<=? ORDER BY id LIMIT 1", state.p2pCursor, state.p2pMaxId);
                if (id == null) { state.phase = "SOURCE_SEARCH"; return store.checkpoint(state, null); }
                ResourceHubTask item = tasks.getById(id);
                state.currentKind = "P2P_REFERENCE"; state.currentId = id; state.currentMovieId = item == null ? null : item.getMovieId();
            }
            case "SOURCE_SEARCH" -> {
                String types = properties.isDriveVideoEnabled("BAIDU") ? properties.isDriveTorrentEnabled("BAIDU") ? "'DISK','TORRENT'" : "'DISK'" : "'TORRENT'";
                // Quark/Xunlei availability never removes a movie from the Baidu gap.
                List<String> ids = jdbc.queryForList("SELECT m.id FROM movie_metadata m WHERE m.deleted_at IS NULL AND m.status='ACTIVE' AND m.created_at<=? AND m.id>? AND NOT EXISTS(SELECT 1 FROM resource_link r WHERE r.movie_id=m.id AND r.provider='BAIDU' AND r.type IN(" + types + ") AND r.status='ACTIVE' AND r.audit_status=1 AND r.link_status='NORMAL' AND r.deleted_at IS NULL) ORDER BY m.id LIMIT 1", String.class, state.movieCutoff, state.movieCursor);
                if (ids.isEmpty()) { state.phase = "COMPLETE"; return store.checkpoint(state, null); }
                state.currentKind = "SOURCE_MOVIE"; state.currentMovieId = ids.get(0);
            }
            default -> throw new IllegalStateException("BACKFILL_PHASE_INVALID");
        }
        return store.checkpoint(state, null);
    }

    private BaiduBackfillTaskStore.State process(BaiduBackfillTaskStore.State state) {
        if (state.currentGyingSubmitting) { state.blockedReason = "CHILD_UNKNOWN"; return store.checkpoint(state, null); }
        if (!activeMovie(state.currentMovieId)) return complete(state, false);
        return switch (state.currentKind) {
            case "BAIDU_TASK" -> processTransfer(state);
            case "DISCOVERY" -> processDiscovery(state);
            case "RESOURCE_LINK" -> processLink(state);
            case "P2P_REFERENCE" -> processP2p(state);
            case "SOURCE_MOVIE" -> processSource(state);
            default -> throw new IllegalStateException("BACKFILL_ITEM_INVALID");
        };
    }

    private BaiduBackfillTaskStore.State processDiscovery(BaiduBackfillTaskStore.State state) {
        ResourceDiscoveryResult item = discoveries.getById(state.currentId);
        if (item == null || !"BAIDU".equals(item.getProvider()) || !Set.of("DISCOVERED", "FAILED", "SAVED").contains(item.getStatus())) return complete(state, false);
        if (state.currentChildId == null) {
            state = store.checkpoint(state, saved -> {
                if (!discovery.ensureTransferTask(item.getId())) throw new IllegalStateException("BACKFILL_CHILD_NOT_CREATED");
                BaiduTransferTask child = transfers.getOne(new QueryWrapper<BaiduTransferTask>().eq("movie_id", item.getMovieId())
                        .eq("original_url_hash", originalHash(item)).last("LIMIT 1"), false);
                if (child == null) throw new IllegalStateException("BACKFILL_CHILD_MISSING");
                saved.currentChildId = child.getId();
            });
        }
        return processTransfer(state);
    }

    private BaiduBackfillTaskStore.State processLink(BaiduBackfillTaskStore.State state) {
        ResourceLink link = links.getById(state.currentId);
        if (link == null || !"BAIDU".equals(link.getProvider()) || link.getDeletedAt() != null || !"ACTIVE".equals(link.getStatus())
                || !Integer.valueOf(1).equals(link.getAuditStatus()) || !"NORMAL".equals(link.getLinkStatus())) return complete(state, false);
        String source = baiduUrl(link.getSourceUrl()) ? link.getSourceUrl() : link.getUrl();
        if (!baiduUrl(source)) return complete(state, false);
        if (state.currentChildId == null) {
            state = store.checkpoint(state, saved -> {
                String hash = ResourceHubHashUtils.sha256(source);
                ResourceDiscoveryResult candidate = discoveries.getOne(new QueryWrapper<ResourceDiscoveryResult>().eq("movie_id", link.getMovieId())
                        .eq("provider", "BAIDU").eq("original_url_hash", hash).orderByAsc("id").last("LIMIT 1"), false);
                if (candidate == null) {
                    candidate = new ResourceDiscoveryResult(); candidate.setMovieId(link.getMovieId()); candidate.setProvider("BAIDU");
                    candidate.setSource("EXISTING_RESOURCE"); candidate.setSourceRef("LINK:" + link.getId()); candidate.setResourceType("DISK");
                    candidate.setTitle(link.getName()); candidate.setOriginalUrl(source); candidate.setOriginalUrlHash(hash); candidate.setCode(link.getCode());
                    candidate.setQuality(link.getQuality()); candidate.setSubtitle(link.getSubtitle()); candidate.setStatus("DISCOVERED");
                    candidate.setCreatedAt(LocalDateTime.now()); candidate.setUpdatedAt(LocalDateTime.now());
                    if (!discoveries.save(candidate)) throw new IllegalStateException("BACKFILL_DISCOVERY_NOT_CREATED");
                }
                if (!discovery.ensureTransferTask(candidate.getId())) throw new IllegalStateException("BACKFILL_CHILD_NOT_CREATED");
                BaiduTransferTask child = transfers.getOne(new QueryWrapper<BaiduTransferTask>().eq("movie_id", link.getMovieId())
                        .eq("original_url_hash", hash).last("LIMIT 1"), false);
                if (child == null) throw new IllegalStateException("BACKFILL_CHILD_MISSING");
                saved.currentChildId = child.getId();
            });
        }
        return processTransfer(state);
    }

    private BaiduBackfillTaskStore.State processTransfer(BaiduBackfillTaskStore.State state) {
        BaiduTransferTask task = transfers.getById(state.currentChildId);
        if (task == null || QqTransferMarker.isTemporary(task.getRequestPayload())) return complete(state, false);
        if ("CANCELED".equals(task.getStatus())) return complete(state, false);
        String stop = childBlock(task.getStatus());
        if (stop != null) { state.blockedReason = stop; return store.checkpoint(state, null); }
        if ("FAILED".equals(task.getStatus()) && task.getAttempts() != null && task.getAttempts() >= 3) {
            state.blockedReason = "CHILD_FAILED"; return store.checkpoint(state, null);
        }
        if (task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(LocalDateTime.now())) {
            state.blockedReason = "CHILD_RUNNING"; return store.checkpoint(state, null);
        }
        if (task.getResponsePayload() != null && !task.getResponsePayload().isBlank()) {
            try {
                var plan = mapper.readValue(task.getResponsePayload(), BaiduWebClient.TransferPlan.class);
                if (!BaiduWebClient.matchesFilePolicy(plan, properties.isDriveVideoEnabled("BAIDU"), properties.isDriveTorrentEnabled("BAIDU"))) {
                    // Preserve an unresolved intent if the file policy changed while a remote write was pending.
                    if (Set.of("TRANSFER_SUBMITTING", "SHARE_SUBMITTING").contains(task.getPhase())) {
                        state.blockedReason = "CHILD_REVIEW_REQUIRED"; return store.checkpoint(state, null);
                    }
                    return complete(state, false);
                }
            } catch (Exception invalid) { state.blockedReason = "CHILD_REVIEW_REQUIRED"; return store.checkpoint(state, null); }
        }
        QuarkTransferRunResult result = runner.submitOne(task.getId());
        BaiduTransferTask after = transfers.getById(task.getId());
        if (result.getFailed() > 0 && !state.currentFailureCounted) { state.failed++; state.currentFailureCounted = true; }
        if (after != null && "SUCCEEDED".equals(after.getStatus()) && result.getFailed() == 0) return publishTransfer(state, after);
        String reason = after == null ? "CHILD_REVIEW_REQUIRED" : childBlock(after.getStatus());
        state.blockedReason = reason == null ? after != null && Set.of("WAITING_TRANSFER", "WAITING_SHARE", "RUNNING").contains(after.getStatus()) ? "CHILD_RETRY_WAIT" : "CHILD_FAILED" : reason;
        return store.checkpoint(state, null);
    }

    private BaiduBackfillTaskStore.State publishTransfer(BaiduBackfillTaskStore.State state, BaiduTransferTask transfer) {
        if (state.currentGyingSubmitting) { state.blockedReason = "CHILD_UNKNOWN"; return store.checkpoint(state, null); }
        if (transfer.getDiscoveryResultId() == null) {
            final BaiduTransferTask original = transfer;
            state = store.checkpoint(state, saved -> {
                ResourceDiscoveryResult item = discoveries.getOne(new QueryWrapper<ResourceDiscoveryResult>().eq("movie_id", original.getMovieId())
                        .eq("provider", "BAIDU").eq("original_url_hash", original.getOriginalUrlHash()).orderByAsc("id").last("LIMIT 1"), false);
                if (item == null) {
                    MovieMetadata movie = movies.getById(original.getMovieId());
                    item = new ResourceDiscoveryResult(); item.setMovieId(original.getMovieId()); item.setProvider("BAIDU"); item.setSource("EXISTING_TRANSFER");
                    item.setSourceRef("BAIDU_TASK:" + original.getId()); item.setTitle(movie.getTitleCn() == null ? movie.getTitleEn() : movie.getTitleCn());
                    item.setOriginalUrl(original.getOriginalUrl()); item.setOriginalUrlHash(original.getOriginalUrlHash());
                    try {
                        var plan = mapper.readValue(original.getResponsePayload(), BaiduWebClient.TransferPlan.class);
                        item.setResourceType(BaiduWebClient.hasVideoFiles(plan) ? "DISK" : "TORRENT");
                    } catch (Exception invalid) { throw new IllegalStateException("BACKFILL_PUBLICATION_PLAN_INVALID"); }
                    item.setShareUrl(original.getShareUrl()); item.setShareUrlHash(original.getShareUrlHash());
                    item.setCode(BaiduWebClient.extractShareCode(original.getShareUrl())); item.setStatus("DISCOVERED");
                    item.setCreatedAt(LocalDateTime.now()); item.setUpdatedAt(LocalDateTime.now());
                    if (!discoveries.save(item)) throw new IllegalStateException("BACKFILL_PUBLICATION_DISCOVERY_NOT_CREATED");
                }
                if (!transfers.update(new UpdateWrapper<BaiduTransferTask>().eq("id", original.getId()).eq("status", "SUCCEEDED")
                        .isNull("discovery_result_id").set("discovery_result_id", item.getId()))) throw new IllegalStateException("BACKFILL_PUBLICATION_NOT_ATTACHED");
            });
            transfer = transfers.getById(transfer.getId());
            QuarkTransferRunResult rechecked = runner.submitOne(transfer.getId());
            if (rechecked.getFailed() > 0) { state.blockedReason = "CHILD_REVIEW_REQUIRED"; return store.checkpoint(state, null); }
        }
        var result = publisher.publishDiscovery(transfer.getDiscoveryResultId());
        if (result.getFailed() > 0 || result.getPublished() + result.getUpdated() == 0) {
            state.blockedReason = "CHILD_REVIEW_REQUIRED"; return store.checkpoint(state, null);
        }
        Long resourceId = result.getResourceIds().isEmpty() ? null : result.getResourceIds().get(0);
        if (resourceId != null && properties.isDriveGyingPublishEnabled("BAIDU")) {
            ResourceLink resource = links.getById(resourceId);
            if (resource != null && "DISK".equals(resource.getType())) {
                state.currentResourceLinkId = resourceId; state.currentGyingSubmitting = true;
                state = store.checkpoint(state, null);
                if (!gying.publishResourceToGying(resource)) { state.blockedReason = "CHILD_UNKNOWN"; return store.checkpoint(state, null); }
                state.currentGyingSubmitting = false;
            }
        }
        return complete(state, true);
    }

    private BaiduBackfillTaskStore.State processP2p(BaiduBackfillTaskStore.State state) {
        if (!properties.isDriveTorrentEnabled("BAIDU") && state.currentChildId == null) return complete(state, false);
        if (state.currentChildId == null) {
            try { state = store.checkpoint(state, saved -> saved.currentChildId = archives.cloneExistingForProvider(saved.currentId, "BAIDU", saved.taskId).getId()); }
            catch (IllegalArgumentException superseded) { return complete(state, false); }
        }
        ResourceHubTask child = tasks.getById(state.currentChildId);
        if (child == null || !"BAIDU".equals(child.getSource()) || !P2pArchiveTaskService.TYPE.equals(child.getTaskType())) throw new IllegalStateException("BACKFILL_CHILD_INVALID");
        if ("CANCELED".equals(child.getStatus())) return complete(state, false);
        String stop = childBlock(child.getStatus());
        if (stop != null) { state.blockedReason = stop; return store.checkpoint(state, null); }
        if ("RUNNING".equals(child.getStatus())) { state.blockedReason = "CHILD_RUNNING"; return store.checkpoint(state, null); }
        if (!properties.isDriveTorrentEnabled("BAIDU")) {
            if ("PENDING".equals(child.getStatus()) || "SUCCEEDED".equals(child.getStatus())) return complete(state, false);
            state.blockedReason = "CHILD_REVIEW_REQUIRED"; return store.checkpoint(state, null);
        }
        if (child.getAttempts() != null && child.getMaxAttempts() != null && child.getAttempts() >= child.getMaxAttempts()
                && !"SUCCEEDED".equals(child.getStatus())) { state.blockedReason = "CHILD_FAILED"; return store.checkpoint(state, null); }
        if ("FAILED".equals(child.getStatus()) && child.getScheduledAt() != null && child.getScheduledAt().isAfter(LocalDateTime.now())) {
            state.blockedReason = "CHILD_RETRY_WAIT"; return store.checkpoint(state, null);
        }
        ResourceHubTask after = archives.submitOne(child.getId(), "BAIDU");
        if ("SUCCEEDED".equals(after.getStatus())) return complete(state, true);
        if ("FAILED".equals(after.getStatus()) && !state.currentFailureCounted) { state.failed++; state.currentFailureCounted = true; }
        state.blockedReason = childBlock(after.getStatus());
        if (state.blockedReason == null) state.blockedReason = "FAILED".equals(after.getStatus()) ? "CHILD_FAILED" : "CHILD_RUNNING";
        return store.checkpoint(state, null);
    }

    private BaiduBackfillTaskStore.State processSource(BaiduBackfillTaskStore.State state) {
        if (state.currentChildId != null) return processTransfer(state);
        if (state.currentDiscoveryTaskId == null) {
            state = store.checkpoint(state, saved -> {
                ResourceDiscoveryRequest request = new ResourceDiscoveryRequest(); request.setMovieId(saved.currentMovieId);
                request.setSource("PANSOU"); request.setProviders(List.of("BAIDU")); request.setMaxResults(10); request.setDeferTransfer(true);
                ResourceHubTask child = discovery.enqueue(request);
                if (child == null || child.getId() == null) throw new IllegalStateException("BACKFILL_DISCOVERY_NOT_CREATED");
                saved.currentDiscoveryTaskId = child.getId();
            });
        }
        ResourceHubTask search = tasks.getById(state.currentDiscoveryTaskId);
        if (search == null) throw new IllegalStateException("BACKFILL_DISCOVERY_MISSING");
        if ("RUNNING".equals(search.getStatus()) && (search.getStartedAt() == null || search.getStartedAt().isAfter(LocalDateTime.now().minusMinutes(30)))) {
            state.blockedReason = "CHILD_RUNNING"; return store.checkpoint(state, null);
        }
        if (!"SUCCEEDED".equals(search.getStatus())) {
            // This exact child performs read-only search with deferTransfer=true; stale searches may resume.
            if (!Set.of("PENDING", "FAILED", "RUNNING").contains(search.getStatus())
                    || (search.getAttempts() != null && search.getMaxAttempts() != null && search.getAttempts() >= search.getMaxAttempts())) {
                state.blockedReason = "CHILD_FAILED"; return store.checkpoint(state, null);
            }
            discovery.runTask(search.getId());
            search = tasks.getById(search.getId());
            if (search == null || !"SUCCEEDED".equals(search.getStatus())) { state.blockedReason = "CHILD_FAILED"; return store.checkpoint(state, null); }
        }
        List<ResourceDiscoveryResult> found = discoveries.list(new QueryWrapper<ResourceDiscoveryResult>().eq("task_id", search.getId())
                .eq("provider", "BAIDU").in("status", List.of("DISCOVERED", "FAILED", "SAVED")).gt("id", state.sourceDiscoveryCursor).orderByAsc("id").last("LIMIT 1"));
        if (found == null || found.isEmpty()) return complete(state, false);
        ResourceDiscoveryResult item = found.get(0);
        state = store.checkpoint(state, saved -> {
            if (!discovery.ensureTransferTask(item.getId())) throw new IllegalStateException("BACKFILL_CHILD_NOT_CREATED");
            BaiduTransferTask child = transfers.getOne(new QueryWrapper<BaiduTransferTask>().eq("movie_id", item.getMovieId())
                    .eq("original_url_hash", originalHash(item)).last("LIMIT 1"), false);
            if (child == null) throw new IllegalStateException("BACKFILL_CHILD_MISSING");
            saved.currentChildId = child.getId(); saved.sourceDiscoveryCursor = item.getId();
        });
        return processTransfer(state);
    }

    private BaiduBackfillTaskStore.State complete(BaiduBackfillTaskStore.State state, boolean success) {
        state.processed++; if (success) state.succeeded++; else state.skipped++;
        switch (state.currentKind) {
            case "BAIDU_TASK" -> state.taskCursor = state.currentId;
            case "DISCOVERY" -> state.discoveryCursor = state.currentId;
            case "RESOURCE_LINK" -> state.linkCursor = state.currentId;
            case "P2P_REFERENCE" -> state.p2pCursor = state.currentId;
            case "SOURCE_MOVIE" -> {
                if (!success && state.currentChildId != null) {
                    // One rejected candidate consumes this interval; the next resumes the same search.
                    state.currentChildId = null; state.blockedReason = null; state.currentFailureCounted = false; state.currentResourceLinkId = null; state.currentGyingSubmitting = false;
                    return store.checkpoint(state, null);
                }
                state.movieCursor = state.currentMovieId;
            }
            default -> throw new IllegalStateException("BACKFILL_ITEM_INVALID");
        }
        state.currentKind = null; state.currentId = null; state.currentChildId = null; state.currentMovieId = null;
        state.currentDiscoveryTaskId = null; state.sourceDiscoveryCursor = 0; state.blockedReason = null; state.currentFailureCounted = false; state.currentResourceLinkId = null; state.currentGyingSubmitting = false;
        return store.checkpoint(state, null);
    }
    private String blocked(BaiduBackfillTaskStore.State state, boolean manual) {
        if (!properties.isEnabled()) return "HUB_DISABLED";
        if (!manual && !properties.getWorker().isEnabled()) return "WORKER_DISABLED";
        if (!properties.isDriveTransferEnabled("BAIDU")) return "TRANSFER_DISABLED";
        if (!properties.isDriveShareEnabled("BAIDU")) return "SHARE_DISABLED";
        if (!properties.isDriveVideoEnabled("BAIDU") && !properties.isDriveTorrentEnabled("BAIDU")) return "FILE_TYPES_DISABLED";
        if (!client.isConfigured()) return "SESSION_REQUIRED";
        if ("SOURCE_SEARCH".equals(state.phase) && !properties.isDriveShareCollectionEnabled("BAIDU")) return "DISCOVERY_DISABLED";
        return null;
    }
    private Status view(BaiduBackfillTaskStore.State state) {
        String status = state.status, reason = state.blockedReason;
        if (!"RUNNING".equals(status) && !"COMPLETE".equals(state.phase) && state.enabled) {
            String currentGate = blocked(state, false);
            if (currentGate != null) { status = "BLOCKED"; reason = currentGate; }
        }
        return new Status(state.taskId, state.enabled, status, state.phase, state.intervalMinutes, state.batchLimit,
                state.processed, state.succeeded, state.skipped, state.failed, state.nextRunAt, reason, state.currentMovieId);
    }
    private boolean activeMovie(String id) {
        MovieMetadata movie = id == null ? null : movies.getById(id);
        return movie != null && movie.getDeletedAt() == null && "ACTIVE".equals(movie.getStatus());
    }
    private Long nextLong(String sql, Object... args) { List<Long> result = jdbc.queryForList(sql, Long.class, args); return result.isEmpty() ? null : result.get(0); }
    private static String childBlock(String status) {
        if (status == null) return "CHILD_REVIEW_REQUIRED";
        return switch (status) { case "UNKNOWN" -> "CHILD_UNKNOWN"; case "ACTION_REQUIRED" -> "CHILD_ACTION_REQUIRED";
            case "REVIEW_REQUIRED" -> "CHILD_REVIEW_REQUIRED"; default -> null; };
    }
    private static String originalHash(ResourceDiscoveryResult item) { return item.getOriginalUrlHash() == null || item.getOriginalUrlHash().isBlank() ? ResourceHubHashUtils.sha256(item.getOriginalUrl()) : item.getOriginalUrlHash(); }
    private static boolean baiduUrl(String url) { try { return url != null && BaiduWebClient.normalizedShareUrl(url, null) != null; } catch (IllegalArgumentException invalid) { return false; } }
}
