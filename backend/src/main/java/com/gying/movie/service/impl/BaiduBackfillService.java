package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduActionException;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.*;
import com.gying.movie.service.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Slowly collects torrent metadata from exact GYING identities already linked to this catalog. */
@Service
public class BaiduBackfillService {
    private final BaiduBackfillTaskStore store;
    private final JdbcTemplate jdbc;
    private final ResourceHubProperties properties;
    private final IResourceHubConfigService config;
    private final IResourceHubTaskService tasks;
    private final GyingSourceWorkflowService gying;
    private final IMovieMetadataService movies;
    private final IMovieSourceIdentityService identities;
    private final P2pArchiveTaskService archives;
    private final BaiduWebClient client;
    private final ObjectMapper mapper;

    public BaiduBackfillService(BaiduBackfillTaskStore store, JdbcTemplate jdbc, ResourceHubProperties properties,
            IResourceHubConfigService config, IResourceHubTaskService tasks, GyingSourceWorkflowService gying,
            IMovieMetadataService movies, IMovieSourceIdentityService identities, P2pArchiveTaskService archives,
            BaiduWebClient client, ObjectMapper mapper) {
        this.store = store; this.jdbc = jdbc; this.properties = properties; this.config = config; this.tasks = tasks;
        this.gying = gying; this.movies = movies; this.identities = identities; this.archives = archives; this.client = client; this.mapper = mapper;
    }
    public record Status(Long taskId, boolean enabled, String status, String phase, int intervalMinutes, int batchLimit,
            long processed, long succeeded, long skipped, long failed, String nextRunAt, String blockedReason, String currentMovieId,
            Long currentIdentityId, String currentGyingType, String currentGyingId, Long currentChildId,
            List<Long> currentResourceIds, String currentAction, List<String> currentSeedNames, int currentSeedCount,
            String currentChildStatus, String currentChildError, String lastResult, Long legacyTaskId) { }

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
            int maximum = state.batchLimit, completed = 0;
            while (completed < Math.min(maximum, state.batchLimit) && !"COMPLETE".equals(state.phase)) {
                String gate = blocked(manual);
                if (gate != null) { state.blockedReason = gate; break; }
                if (state.currentIdentityId == null) {
                    state = select(state);
                    if (state.currentIdentityId == null) continue;
                }
                long before = state.processed;
                state = process(state);
                if (state.blockedReason != null) break;
                if (state.processed > before) completed++;
                // A manual run may start paused; edits made during an item still take effect at its boundary.
                if (!state.enabled && (!manual || state.settingsRevision != startingSettingsRevision)) break;
            }
        } catch (BaiduActionException failure) {
            state = store.read();
            if (!runOwner.equals(state.leaseOwner)) return view(state);
            state.blockedReason = failure.actionRequired() ? "CHILD_ACTION_REQUIRED" : failure.uncertain() ? "CHILD_UNKNOWN" : "PROCESSING_ERROR";
        } catch (Exception failure) {
            BaiduBackfillTaskStore.State latest = store.read();
            if (!runOwner.equals(latest.leaseOwner)) return view(latest);
            state = latest; state.blockedReason = "PROCESSING_ERROR";
        }
        try { return view(store.finish(state)); }
        catch (IllegalStateException lostLease) { return view(store.read()); }
    }

    static final String NEXT_IDENTITY_SQL = "SELECT i.id,i.movie_id,i.source_type,i.external_id FROM movie_source_identity i "
            + "JOIN movie_metadata m ON m.id=i.movie_id WHERE i.source='GYING' AND i.match_status IN('AUTO','CONFIRMED') "
            + "AND i.source_type IN('mv','tv','ac') AND i.external_id REGEXP '^[A-Za-z0-9]{1,100}$' "
            + "AND m.deleted_at IS NULL AND m.status='ACTIVE' AND m.created_at<=? AND i.id>? AND i.id<=? "
            + "AND NOT EXISTS(SELECT 1 FROM movie_source_identity preferred WHERE preferred.movie_id=i.movie_id "
            + "AND preferred.source='GYING' AND preferred.match_status IN('AUTO','CONFIRMED') AND preferred.source_type IN('mv','tv','ac') "
            + "AND preferred.external_id REGEXP '^[A-Za-z0-9]{1,100}$' AND preferred.id<=? "
            + "AND (COALESCE(preferred.confidence,0)>COALESCE(i.confidence,0) "
            + "OR (COALESCE(preferred.confidence,0)=COALESCE(i.confidence,0) AND preferred.id<i.id))) ORDER BY i.id LIMIT 1";
    private BaiduBackfillTaskStore.State select(BaiduBackfillTaskStore.State state) {
        List<Map<String, Object>> found = jdbc.queryForList(NEXT_IDENTITY_SQL, state.movieCutoff, state.identityCursor, state.identityMaxId, state.identityMaxId);
        if (found.isEmpty()) { state.phase = "COMPLETE"; state.currentAction = "COMPLETE"; return store.checkpoint(state, null); }
        Map<String, Object> item = found.get(0);
        state.currentIdentityId = ((Number) item.get("id")).longValue(); state.currentMovieId = (String) item.get("movie_id");
        state.currentGyingType = (String) item.get("source_type"); state.currentGyingId = (String) item.get("external_id");
        state.currentAction = "COLLECT_GYING_TORRENTS";
        return store.checkpoint(state, null);
    }

    private BaiduBackfillTaskStore.State process(BaiduBackfillTaskStore.State state) {
        MovieMetadata movie = movies.getById(state.currentMovieId);
        if (movie == null || movie.getDeletedAt() != null || !"ACTIVE".equals(movie.getStatus())) return complete(state, false, "MOVIE_INACTIVE");
        if (state.currentChildId != null) return processArchive(state);
        MovieSourceIdentity identity = identities.getById(state.currentIdentityId);
        if (!matchesIdentity(state, identity)) { state.blockedReason = "GYING_IDENTITY_CHANGED"; return store.checkpoint(state, null); }
        ResourceHubTask protectedChild = archives.protectedArchive(state.currentMovieId, "BAIDU");
        if (protectedChild != null) {
            state = attach(state, protectedChild);
            return processArchive(state);
        }
        GyingSourceWorkflowService.P2pCollection collected;
        try { collected = gying.collectMovieP2pResources(state.currentMovieId, state.currentIdentityId); }
        catch (Exception unavailable) {
            if (!state.currentFailureCounted) { state.failed++; state.currentFailureCounted = true; }
            state.blockedReason = "GYING_SOURCE_UNAVAILABLE"; return store.checkpoint(state, null);
        }
        if (!Objects.equals(state.currentMovieId, collected.movieId()) || !Objects.equals(state.currentIdentityId, collected.identityId())
                || !Objects.equals(state.currentGyingType, collected.typeCode()) || !Objects.equals(state.currentGyingId, collected.mid())
                || !matchesIdentity(state, identities.getById(state.currentIdentityId))) {
            state.blockedReason = "GYING_IDENTITY_CHANGED"; return store.checkpoint(state, null);
        }
        List<ResourceLink> selected = collected.selected();
        if (selected.isEmpty() || selected.stream().noneMatch(link -> "TORRENT".equals(link.getType()))) {
            return complete(state, false, "NO_ELIGIBLE_GYING_TORRENTS");
        }
        try { P2pArchiveTaskService.validateSelection(state.currentMovieId, selected); }
        catch (IllegalArgumentException ineligible) { return complete(state, false, "NO_ELIGIBLE_GYING_TORRENTS"); }
        state = store.checkpoint(state, saved -> {
            ResourceHubTask child = archives.enqueueForProvider(saved.currentMovieId, saved.currentGyingType, saved.currentGyingId,
                    selected, "BAIDU", saved.taskId);
            saved.currentChildId = child.getId(); manifest(saved, archives.selectionForTask(child)); saved.currentAction = "ARCHIVE_METADATA_FILES";
        });
        return processArchive(state);
    }

    private BaiduBackfillTaskStore.State attach(BaiduBackfillTaskStore.State state, ResourceHubTask child) {
        state.currentChildId = child.getId(); state.currentAction = action(child);
        try { manifest(state, archives.selectionForTask(child)); }
        catch (Exception invalid) { state.currentResourceIds = List.of(); state.currentSeedNames = List.of(); state.currentSeedCount = 0; }
        return store.checkpoint(state, null);
    }
    private static void manifest(BaiduBackfillTaskStore.State state, List<ResourceLink> selected) {
        state.currentResourceIds = selected.stream().map(ResourceLink::getId).filter(Objects::nonNull).distinct().toList();
        state.currentSeedNames = selected.stream().filter(link -> "TORRENT".equals(link.getType())).map(link -> {
            String name = link.getName() == null || link.getName().isBlank() ? link.getQuality() + " " + link.getSourceRef() + ".torrent" : link.getName();
            name = name.replaceAll("[\\p{Cntrl}]", " "); return name.length() > 255 ? name.substring(0, 255) : name;
        }).toList();
        state.currentSeedCount = state.currentSeedNames.size();
    }
    private BaiduBackfillTaskStore.State processArchive(BaiduBackfillTaskStore.State state) {
        ResourceHubTask child = tasks.getById(state.currentChildId);
        if (child == null || !"BAIDU".equals(child.getSource()) || !P2pArchiveTaskService.TYPE.equals(child.getTaskType())
                || !Objects.equals(state.currentMovieId, child.getMovieId())) throw new IllegalStateException("BACKFILL_CHILD_INVALID");
        state.currentAction = action(child);
        String stop = childBlock(child.getStatus());
        if (stop != null) { state.blockedReason = stop; return store.checkpoint(state, null); }
        JsonNode payload = payload(child);
        if (payload.path("baiduShareSubmitting").asBoolean(false)) { state.blockedReason = "CHILD_UNKNOWN"; return store.checkpoint(state, null); }
        if ("CANCELED".equals(child.getStatus())) return complete(state, false, "SELECTION_SUPERSEDED");
        if ("RUNNING".equals(child.getStatus())) {
            if (!store.recoverInterruptedArchive(state)) { state.blockedReason = "CHILD_RUNNING"; return store.checkpoint(state, null); }
            child = tasks.getById(state.currentChildId); payload = payload(child);
        }
        JsonNode owner = payload.get("backfillParentId");
        if (owner != null && (!owner.isIntegralNumber() || owner.asLong() != state.taskId)) {
            state.blockedReason = "CHILD_REVIEW_REQUIRED"; return store.checkpoint(state, null);
        }
        if (!state.currentGyingType.equals(payload.path("typeCode").asText()) || !state.currentGyingId.equals(payload.path("mid").asText())
                || state.currentResourceIds.isEmpty() || state.currentSeedCount == 0) {
            state.blockedReason = "CHILD_REVIEW_REQUIRED"; return store.checkpoint(state, null);
        }
        if (child.getAttempts() != null && child.getMaxAttempts() != null && child.getAttempts() >= child.getMaxAttempts()
                && !"SUCCEEDED".equals(child.getStatus())) { state.blockedReason = "CHILD_FAILED"; return store.checkpoint(state, null); }
        if ("FAILED".equals(child.getStatus()) && child.getScheduledAt() != null && child.getScheduledAt().isAfter(LocalDateTime.now())) {
            state.blockedReason = "CHILD_RETRY_WAIT"; return store.checkpoint(state, null);
        }
        ResourceHubTask after;
        try { after = archives.submitOne(child.getId(), "BAIDU", state.taskId); }
        catch (BaiduActionException failure) {
            state.blockedReason = failure.uncertain() ? "CHILD_UNKNOWN" : failure.actionRequired() ? "CHILD_ACTION_REQUIRED" : "CHILD_REVIEW_REQUIRED";
            return store.checkpoint(state, null);
        } catch (Exception invalidCheckpoint) { state.blockedReason = "CHILD_REVIEW_REQUIRED"; return store.checkpoint(state, null); }
        state.currentAction = action(after);
        if ("SUCCEEDED".equals(after.getStatus())) return complete(state, true, "TORRENTS_ARCHIVED");
        if ("FAILED".equals(after.getStatus()) && !state.currentFailureCounted) { state.failed++; state.currentFailureCounted = true; }
        state.blockedReason = childBlock(after.getStatus());
        if (state.blockedReason == null) state.blockedReason = "FAILED".equals(after.getStatus()) ? "CHILD_FAILED" : "CHILD_RUNNING";
        return store.checkpoint(state, null);
    }
    private static boolean matchesIdentity(BaiduBackfillTaskStore.State state, MovieSourceIdentity identity) {
        return identity != null && Objects.equals(state.currentIdentityId, identity.getId()) && Objects.equals(state.currentMovieId, identity.getMovieId())
                && "GYING".equals(identity.getSource()) && Set.of("AUTO", "CONFIRMED").contains(identity.getMatchStatus())
                && Objects.equals(state.currentGyingType, identity.getSourceType()) && Objects.equals(state.currentGyingId, identity.getExternalId());
    }
    private BaiduBackfillTaskStore.State complete(BaiduBackfillTaskStore.State state, boolean success, String result) {
        state.processed++; if (success) state.succeeded++; else state.skipped++;
        state.identityCursor = state.currentIdentityId; state.currentIdentityId = null; state.currentChildId = null;
        state.currentMovieId = null; state.currentGyingType = null; state.currentGyingId = null;
        state.currentResourceIds = List.of(); state.currentSeedNames = List.of(); state.currentSeedCount = 0;
        state.currentAction = "IDLE"; state.lastResult = result; state.blockedReason = null; state.currentFailureCounted = false;
        return store.checkpoint(state, null);
    }
    private String blocked(boolean manual) {
        if (!properties.isEnabled()) return "HUB_DISABLED";
        if (!manual && !properties.getWorker().isEnabled()) return "WORKER_DISABLED";
        if (!properties.isDriveTransferEnabled("BAIDU")) return "TRANSFER_DISABLED";
        if (!properties.isDriveShareEnabled("BAIDU")) return "SHARE_DISABLED";
        if (!properties.isDriveTorrentEnabled("BAIDU")) return "TORRENT_DISABLED";
        if (!client.isConfigured()) return "SESSION_REQUIRED";
        return null;
    }
    private static final Set<String> CHILD_STATUSES = Set.of("PENDING", "RUNNING", "FAILED", "SUCCEEDED", "UNKNOWN", "ACTION_REQUIRED", "REVIEW_REQUIRED", "CANCELED");
    private static final Set<String> CHILD_ERRORS = Set.of("BaiduActionException", "IllegalStateException", "IllegalArgumentException", "ResponseStatusException");
    private Status view(BaiduBackfillTaskStore.State state) {
        String status = state.status, reason = state.blockedReason;
        if (!"RUNNING".equals(status) && !"COMPLETE".equals(state.phase) && state.enabled) {
            String currentGate = blocked(false); if (currentGate != null) { status = "BLOCKED"; reason = currentGate; }
        }
        ResourceHubTask child = state.currentChildId == null ? null : tasks.getById(state.currentChildId);
        String childStatus = child == null ? null : child.getStatus() != null && CHILD_STATUSES.contains(child.getStatus()) ? child.getStatus() : "UNKNOWN";
        String childError = child == null ? null : payload(child).path("errorCategory").asText(null);
        if (childError != null && !CHILD_ERRORS.contains(childError)) childError = "OTHER";
        return new Status(state.taskId, state.enabled, status, state.phase, state.intervalMinutes, state.batchLimit,
                state.processed, state.succeeded, state.skipped, state.failed, state.nextRunAt, reason, state.currentMovieId,
                state.currentIdentityId, state.currentGyingType, state.currentGyingId, state.currentChildId,
                state.currentResourceIds, child == null ? state.currentAction : action(child), state.currentSeedNames,
                state.currentSeedCount, childStatus, childError, state.lastResult, store.legacyTaskId());
    }
    private JsonNode payload(ResourceHubTask child) {
        try { JsonNode result = mapper.readTree(child.getPayload()); return result != null && result.isObject() ? result : mapper.createObjectNode(); }
        catch (Exception invalid) { return mapper.createObjectNode(); }
    }
    private String action(ResourceHubTask child) {
        String stage = payload(child).path("stage").asText("ARCHIVE_METADATA_FILES");
        return BaiduBackfillTaskStore.ACTIONS.contains(stage) ? stage : "ARCHIVE_METADATA_FILES";
    }
    private static String childBlock(String status) {
        if (status == null) return "CHILD_REVIEW_REQUIRED";
        return switch (status) { case "UNKNOWN" -> "CHILD_UNKNOWN"; case "ACTION_REQUIRED" -> "CHILD_ACTION_REQUIRED";
            case "REVIEW_REQUIRED" -> "CHILD_REVIEW_REQUIRED"; default -> null; };
    }
}
