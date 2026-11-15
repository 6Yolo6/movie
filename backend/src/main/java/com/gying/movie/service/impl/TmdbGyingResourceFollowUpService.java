package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IMovieSourceIdentityService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable TMDB update -> strict GYING match -> existing metadata-file archive queue. */
@Service
public class TmdbGyingResourceFollowUpService {
    static final String TYPE = "TMDB_GYING_RESOURCE";
    static final String BUDGET_TYPE = "TMDB_GYING_RESOURCE_BUDGET";
    static final String MODE = "TMDB_UPDATES_RESOURCE_V1";
    static final String BUDGET_MODE = "TMDB_UPDATES_RESOURCE_BUDGET_V1";
    private static final int DAILY_LIMIT = 10;
    private final ResourceHubProperties properties;
    private final IResourceHubTaskService tasks;
    private final IMovieMetadataService movies;
    private final IMovieSourceIdentityService identities;
    private final GyingSourceWorkflowService workflow;
    private final P2pArchiveTaskService archives;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IResourceHubConfigService config;
    private final AtomicBoolean running = new AtomicBoolean();

    public TmdbGyingResourceFollowUpService(ResourceHubProperties properties, IResourceHubTaskService tasks,
            IMovieMetadataService movies, IMovieSourceIdentityService identities, GyingSourceWorkflowService workflow,
            P2pArchiveTaskService archives, ObjectMapper mapper, JdbcTemplate jdbc, TransactionTemplate transactions, IResourceHubConfigService config) {
        this.properties = properties; this.tasks = tasks; this.movies = movies; this.identities = identities;
        this.workflow = workflow; this.archives = archives; this.mapper = mapper; this.jdbc = jdbc; this.transactions = transactions; this.config = config;
    }

    /** Caller is not transactional. The metadata write and acknowledged intent commit together. */
    public <T> T persistMetadataAndEnqueue(Long metadataTaskId, Supplier<T> save, Function<T, MovieMetadata> movieOf) {
        return withDatabaseLock("gying:tmdb-resource-producer:v1", () -> transactions.execute(tx -> {
            T result = save.get();
            MovieMetadata requested = Objects.requireNonNull(movieOf.apply(result));
            MovieMetadata canonical = movies.getOne(new QueryWrapper<MovieMetadata>().eq("id", requested.getId()).last("FOR UPDATE"), false);
            if (canonical == null || !requested.getId().equals(canonical.getId())
                    || !Objects.equals(requested.getTmdbId(), canonical.getTmdbId())
                    || !Objects.equals(requested.getTmdbType(), canonical.getTmdbType())
                    || !Objects.equals(requested.getSeason(), canonical.getSeason()))
                throw new IllegalStateException("TMDB_RESOURCE_CANONICAL_ACK_INVALID");
            requireMetadata(canonical, false);
            enqueueLocked(metadataTaskId, canonical);
            return result;
        }));
    }

    private void enqueueLocked(Long parentId, MovieMetadata movie) {
        if (parentId == null || parentId <= 0) throw new IllegalArgumentException("TMDB_RESOURCE_PARENT_REQUIRED");
        String key = key(movie);
        ResourceHubTask existing = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TYPE)
                .eq("source", "GYING").eq("keyword", key).orderByAsc("id").last("LIMIT 1"), false);
        if (existing != null) { validateTask(existing, movie, false); return; }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("mode", MODE); payload.put("movieId", movie.getId()); payload.put("metadataTaskId", parentId);
        payload.put("tmdbId", movie.getTmdbId()); payload.put("tmdbType", movie.getTmdbType());
        payload.put("season", season(movie)); payload.put("year", movie.getYear());
        payload.put("phase", "STRICT_MATCH"); payload.put("policy", "GYING_SEARCH_ONLY_P2P_METADATA_FILES");
        ResourceHubTask task = new ResourceHubTask(); task.setTaskType(TYPE); task.setSource("GYING");
        task.setMovieId(movie.getId()); task.setTmdbId(movie.getTmdbId()); task.setTmdbType(movie.getTmdbType());
        task.setKeyword(key); task.setPriority(3); task.setMaxAttempts(3); task.setPayload(payload.toString());
        // UPCOMING movies keep their resource intent until release instead of exhausting retries before release.
        try {
            LocalDate release = LocalDate.parse(Objects.toString(movie.getReleaseDates(), ""));
            if (release.isAfter(LocalDate.now())) task.setScheduledAt(release.atStartOfDay());
        } catch (java.time.format.DateTimeParseException ignored) { /* Unknown dates use bounded next-day retries. */ }
        ResourceHubTask saved = tasks.enqueue(task);
        if (saved == null || saved.getId() == null) throw new IllegalStateException("TMDB_RESOURCE_INTENT_NOT_PERSISTED");
        ResourceHubTask acknowledged = tasks.getById(saved.getId());
        validateTask(acknowledged, movie, false);
    }

    private String key(MovieMetadata movie) {
        return ResourceHubHashUtils.sha256(movie.getTmdbType() + "/" + movie.getTmdbId() + "/" + season(movie) + "/" + movie.getId());
    }
    private int season(MovieMetadata movie) { return "movie".equals(movie.getTmdbType()) ? 0 : Objects.requireNonNull(movie.getSeason()); }
    private void requireMetadata(MovieMetadata movie) { requireMetadata(movie, true); }
    private void requireMetadata(MovieMetadata movie, boolean resource) {
        if (movie == null || movie.getId() == null || movie.getDeletedAt() != null || !"ACTIVE".equals(movie.getStatus())
                || movie.getTmdbId() == null || movie.getTmdbId() <= 0 || movie.getTmdbLastSyncAt() == null
                || !Set.of("movie", "tv").contains(Objects.toString(movie.getTmdbType(), ""))
                || "tv".equals(movie.getTmdbType()) && (movie.getSeason() == null || movie.getSeason() < 1 || movie.getSeason() > 99)
                || resource && (movie.getYear() == null || movie.getYear() < 1801 || movie.getYear() > LocalDate.now().getYear() + 5
                || !("movie".equals(movie.getTmdbType()) && "mv".equals(movie.getCategory())
                    || "tv".equals(movie.getTmdbType()) && Set.of("tv", "ac").contains(Objects.toString(movie.getCategory(), ""))
                        && movie.getSeason() != null && movie.getSeason() >= 1 && movie.getSeason() <= 99)))
            throw new IllegalStateException("TMDB_RESOURCE_LOCAL_METADATA_REVIEW_REQUIRED");
        MovieSourceIdentity identity = identities.getOne(new QueryWrapper<MovieSourceIdentity>().eq("source", "TMDB")
                .eq("source_type", movie.getTmdbType()).eq("external_id", String.valueOf(movie.getTmdbId()))
                .eq("season", season(movie)).eq("match_status", "CONFIRMED").last("LIMIT 1"), false);
        if (identity == null || identity.getId() == null || !movie.getId().equals(identity.getMovieId())
                || !"TMDB".equals(identity.getSource()) || !movie.getTmdbType().equals(identity.getSourceType())
                || !String.valueOf(movie.getTmdbId()).equals(identity.getExternalId())
                || !Objects.equals(season(movie), identity.getSeason()) || !"CONFIRMED".equals(identity.getMatchStatus()))
            throw new IllegalStateException("TMDB_RESOURCE_SOURCE_IDENTITY_REVIEW_REQUIRED");
    }
    private void validateTask(ResourceHubTask task, MovieMetadata movie) { validateTask(task, movie, true); }
    private void validateTask(ResourceHubTask task, MovieMetadata movie, boolean exactYear) {
        ObjectNode payload = payload(task);
        if (!movie.getId().equals(task.getMovieId()) || !movie.getId().equals(payload.path("movieId").asText())
                || !Objects.equals(movie.getTmdbId(), task.getTmdbId()) || !movie.getTmdbType().equals(task.getTmdbType())
                || !key(movie).equals(task.getKeyword()) || !payload.path("tmdbId").isIntegralNumber() || !payload.path("tmdbId").canConvertToLong()
                || payload.path("tmdbId").asLong() != movie.getTmdbId() || !movie.getTmdbType().equals(payload.path("tmdbType").asText())
                || !payload.path("season").isIntegralNumber() || !payload.path("season").canConvertToInt() || payload.path("season").asInt() != season(movie)
                || !(payload.path("year").isNull() || payload.path("year").isIntegralNumber() && payload.path("year").canConvertToInt())
                || exactYear && (!payload.path("year").isIntegralNumber() || !Objects.equals(payload.path("year").asInt(), movie.getYear())))
            throw new IllegalStateException("TMDB_RESOURCE_TASK_IDENTITY_CHANGED");
    }
    private ObjectNode payload(ResourceHubTask task) {
        if (task == null || !TYPE.equals(task.getTaskType()) || !"GYING".equals(task.getSource()))
            throw new IllegalArgumentException("TMDB_RESOURCE_TASK_REQUIRED");
        try {
            JsonNode parsed = mapper.readTree(task.getPayload());
            if (!(parsed instanceof ObjectNode value) || !MODE.equals(value.path("mode").asText())) throw new IllegalArgumentException();
            return value.deepCopy();
        } catch (Exception invalid) { throw new IllegalArgumentException("TMDB_RESOURCE_CHECKPOINT_INVALID"); }
    }
    private boolean enabled() {
        return properties.isEnabled() && properties.getWorker().isEnabled() && properties.getTmdb().isAutoSyncEnabled() && properties.getTmdb().isAutoSyncUpdatesOnly()
                && properties.getGying().isAutoSyncEnabled() && properties.getGying().isAutoSyncUpdatesOnly() && properties.getGying().isDiscoveryEnabled()
                && List.of("QUARK", "XUNLEI", "BAIDU").stream().anyMatch(p -> properties.isDriveDiscoveryEnabled(p) && properties.isDriveTorrentEnabled(p));
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 20000)
    public void runDue() {
        config.reload();
        if (!enabled() || !properties.getWorker().isEnabled() || !running.compareAndSet(false, true)) return;
        try {
            List<ResourceHubTask> due = tasks.list(new QueryWrapper<ResourceHubTask>().eq("task_type", TYPE).eq("source", "GYING")
                    .in("status", List.of("PENDING", "FAILED")).apply("attempts < max_attempts")
                    .apply("JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode'))={0}", MODE)
                    .le("scheduled_at", LocalDateTime.now()).orderByAsc("scheduled_at").orderByAsc("id").last("LIMIT 10"));
            for (ResourceHubTask task : due) runOne(task);
        } finally { running.set(false); }
    }

    /** Exact new-task verification entry; never drains historical or another movie's work. */
    public ResourceHubTask submitOne(Long id) {
        config.reload();
        ResourceHubTask task = tasks.getById(id); payload(task);
        if (!enabled() || !running.compareAndSet(false, true)) return task;
        try { runOne(task); return tasks.getById(id); } finally { running.set(false); }
    }

    private boolean protectedHistory(String movieId) {
        // Collection can replace resolution-slot source_refs; protect even an idle unfinished archive selection.
        if (tasks.count(new QueryWrapper<ResourceHubTask>().eq("movie_id", movieId).eq("task_type", P2pArchiveTaskService.TYPE)
                .notIn("status", List.of("SUCCEEDED", "CANCELED"))) > 0) return true;
        return tasks.count(new QueryWrapper<ResourceHubTask>().eq("task_type", BaiduBackfillTaskStore.TYPE).eq("source", "BAIDU")
                .notIn("status", List.of("SUCCEEDED", "CANCELED"))
                .apply("JSON_UNQUOTE(JSON_EXTRACT(payload, '$.currentMovieId'))={0}", movieId)) > 0;
    }

    private void runOne(ResourceHubTask task) {
        config.reload();
        if (!enabled()) return;
        if (!Set.of("PENDING", "FAILED").contains(Objects.toString(task.getStatus(), "")) || task.getAttempts() == null
                || task.getMaxAttempts() == null || task.getAttempts() >= Math.min(task.getMaxAttempts(), 3)
                || task.getScheduledAt() == null || task.getScheduledAt().isAfter(LocalDateTime.now())) return;
        try {
            ObjectNode checkpoint = payload(task);
            MovieMetadata movie = movies.getById(task.getMovieId()); requireMetadata(movie); validateTask(task, movie);
            if (protectedHistory(movie.getId())) { defer(task, checkpoint, "PROTECTED_ARCHIVE_RECONCILIATION_REQUIRED"); return; }
            boolean claimed = withDatabaseLock("gying:tmdb-resource-budget:v1", () -> transactions.execute(tx -> reserveAndClaim(task)));
            if (!claimed) return;
            if (!enabled()) throw new IllegalStateException("GYING_RESOURCE_COLLECTION_PAUSED");
            MovieSourceIdentity identity = workflow.resolveTmdbUpdateGyingIdentity(movie.getId());
            config.reload();
            if (!enabled()) throw new IllegalStateException("GYING_RESOURCE_COLLECTION_PAUSED");
            MovieMetadata latest = movies.getById(task.getMovieId()); requireMetadata(latest); validateTask(task, latest);
            if (protectedHistory(movie.getId())) throw new IllegalStateException("PROTECTED_ARCHIVE_RECONCILIATION_REQUIRED");
            if (identity == null || identity.getId() == null || !movie.getId().equals(identity.getMovieId())
                    || !"GYING".equals(identity.getSource()) || !Set.of("AUTO", "CONFIRMED").contains(Objects.toString(identity.getMatchStatus(), ""))
                    || !("movie".equals(movie.getTmdbType()) ? "mv".equals(identity.getSourceType()) : Set.of("tv", "ac").contains(Objects.toString(identity.getSourceType(), "")))
                    || !Objects.equals(season(movie), identity.getSeason()) || identity.getExternalId() == null
                    || !identity.getExternalId().matches("[A-Za-z0-9]{1,100}")) throw new IllegalStateException("GYING_STRICT_IDENTITY_CHANGED");
            GyingSourceWorkflowService.P2pCollection collection = workflow.collectMovieP2pResources(movie.getId(), identity.getId());
            if (collection == null || !movie.getId().equals(collection.movieId()) || !identity.getId().equals(collection.identityId())
                    || !identity.getSourceType().equals(collection.typeCode()) || !identity.getExternalId().equals(collection.mid()))
                throw new IllegalStateException("GYING_RESOURCE_COLLECTION_IDENTITY_CHANGED");
            if (collection.selected() == null || collection.selected().isEmpty()) throw new IllegalStateException("GYING_NO_ELIGIBLE_P2P_RESOURCES");
            ObjectNode completed = payload(task); completed.put("phase", "ARCHIVE_QUEUED"); completed.put("gyingIdentityId", identity.getId());
            completed.put("gyingType", identity.getSourceType()); completed.put("gyingMid", identity.getExternalId());
            completed.put("selectedCount", collection.selected().size()); completed.remove("blockedReason");
            transactions.executeWithoutResult(tx -> {
                archives.enqueue(collection.movieId(), collection.typeCode(), collection.mid(), collection.selected());
                acknowledgeArchiveTasks(collection);
                finish(task, "SUCCEEDED", completed, null);
            });
        } catch (CheckpointConflict lost) { /* Another owner wins; never overwrite its checkpoint. */ }
        catch (Exception error) {
            if ("RUNNING".equals(task.getStatus())) {
                try { finish(task, "FAILED", payload(task), safeReason(error)); } catch (CheckpointConflict lost) { /* Preserve owner. */ }
            } else {
                try { defer(task, payload(task), safeReason(error)); } catch (CheckpointConflict lost) { /* Preserve owner. */ }
            }
        }
    }
    private void acknowledgeArchiveTasks(GyingSourceWorkflowService.P2pCollection collection) {
        String fingerprint = P2pArchiveTaskService.fingerprint(collection.selected());
        List<String> providers = List.of("QUARK", "XUNLEI", "BAIDU").stream()
                .filter(provider -> properties.isDriveDiscoveryEnabled(provider) && properties.isDriveTorrentEnabled(provider)).toList();
        if (providers.isEmpty()) throw new IllegalStateException("GYING_RESOURCE_ARCHIVE_PROVIDERS_PAUSED");
        for (String provider : providers) {
            ResourceHubTask child = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", P2pArchiveTaskService.TYPE)
                    .eq("movie_id", collection.movieId()).eq("source", provider).eq("keyword", fingerprint).last("LIMIT 1"), false);
            if (child == null || child.getId() == null) throw new IllegalStateException("GYING_RESOURCE_ARCHIVE_ACK_MISSING");
            ResourceHubTask persisted = tasks.getById(child.getId());
            try {
                JsonNode state = mapper.readTree(persisted.getPayload());
                if (!P2pArchiveTaskService.TYPE.equals(persisted.getTaskType()) || !provider.equals(persisted.getSource())
                        || !collection.movieId().equals(persisted.getMovieId()) || !fingerprint.equals(persisted.getKeyword())
                        || !Set.of("PENDING", "FAILED", "SUCCEEDED").contains(Objects.toString(persisted.getStatus(), ""))
                        || !fingerprint.equals(state.path("fingerprint").asText()) || !collection.typeCode().equals(state.path("typeCode").asText())
                        || !collection.mid().equals(state.path("mid").asText()) || !archiveResourceIdsMatch(state.path("resourceIds"),
                            collection.selected().stream().map(com.gying.movie.entity.ResourceLink::getId).filter(Objects::nonNull).toList()))
                    throw new IllegalStateException();
            } catch (Exception invalid) { throw new IllegalStateException("GYING_RESOURCE_ARCHIVE_ACK_INVALID"); }
        }
    }

    private boolean archiveResourceIdsMatch(JsonNode values, List<Long> expected) {
        if (!values.isArray() || values.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            JsonNode value = values.get(index);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() != expected.get(index)) return false;
        }
        return true;
    }

    private String safeReason(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[A-Z0-9_]{1,100}") ? message : "GYING_RESOURCE_" + error.getClass().getSimpleName();
    }

    /** Budget reservation and task lease are one transaction; CAS failure rolls both back before networking. */
    boolean reserveAndClaim(ResourceHubTask task) {
        ResourceHubTask budget = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", BUDGET_TYPE)
                .eq("source", "GYING").eq("keyword", BUDGET_MODE).last("LIMIT 1 FOR UPDATE"), false);
        LocalDateTime now = LocalDateTime.now();
        if (budget == null) {
            budget = new ResourceHubTask(); budget.setTaskType(BUDGET_TYPE); budget.setSource("GYING"); budget.setKeyword(BUDGET_MODE);
            budget.setStatus("SUCCEEDED"); budget.setPriority(0); budget.setMaxAttempts(0);
            ObjectNode initial = mapper.createObjectNode(); initial.put("mode", BUDGET_MODE); initial.put("windowStartedAt", now.toString()); initial.put("used", 0);
            budget.setPayload(initial.toString()); ResourceHubTask saved = tasks.enqueue(budget);
            if (saved == null || saved.getId() == null) throw new IllegalStateException("GYING_RESOURCE_BUDGET_NOT_PERSISTED");
            budget = tasks.getById(saved.getId());
        }
        if (budget == null || budget.getId() == null || !BUDGET_TYPE.equals(budget.getTaskType()) || !"GYING".equals(budget.getSource())
                || !BUDGET_MODE.equals(budget.getKeyword()) || !"SUCCEEDED".equals(budget.getStatus())
                || budget.getMaxAttempts() == null || budget.getMaxAttempts() != 0)
            throw new IllegalStateException("GYING_RESOURCE_BUDGET_IDENTITY_INVALID");
        ObjectNode state;
        try {
            state = (ObjectNode) mapper.readTree(budget.getPayload());
            if (!BUDGET_MODE.equals(state.path("mode").asText()) || (!state.path("used").isIntegralNumber() || !state.path("used").canConvertToInt())
                    || state.path("used").asInt() < 0 || state.path("used").asInt() > DAILY_LIMIT) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new IllegalStateException("GYING_RESOURCE_BUDGET_INVALID"); }
        LocalDateTime started = LocalDateTime.parse(state.path("windowStartedAt").asText());
        if (started.isAfter(now)) throw new IllegalStateException("GYING_RESOURCE_BUDGET_CLOCK_INVALID");
        if (!started.plusHours(24).isAfter(now)) { state.put("windowStartedAt", now.toString()); state.put("used", 0); started = now; }
        if (state.path("used").asInt() >= DAILY_LIMIT) {
            ObjectNode waiting = payload(task); waiting.put("blockedReason", "GYING_RESOURCE_DAILY_BUDGET_EXHAUSTED");
            updateTask(task, task.getStatus(), waiting, started.plusHours(24), null); return false;
        }
        state.put("used", state.path("used").asInt() + 1);
        if (!tasks.update(new UpdateWrapper<ResourceHubTask>().eq("id", budget.getId()).eq("task_type", BUDGET_TYPE).eq("source", "GYING")
                .eq("keyword", BUDGET_MODE).eq("status", "SUCCEEDED").eq("max_attempts", 0)
                .apply("BINARY task_type=BINARY {0} AND BINARY source=BINARY {1} AND BINARY keyword=BINARY {2}", BUDGET_TYPE, "GYING", BUDGET_MODE)
                .apply("BINARY CAST(payload AS CHAR)=BINARY CAST(CAST({0} AS JSON) AS CHAR)", budget.getPayload())
                .set("payload", state.toString()).set("updated_at", now))) throw new CheckpointConflict();
        ObjectNode claimed = payload(task); claimed.put("attemptedAt", now.toString()); claimed.remove("blockedReason");
        int attempts = task.getAttempts() + 1;
        UpdateWrapper<ResourceHubTask> update = owned(task).apply("attempts >= 0 AND attempts < LEAST(max_attempts, 3)")
                .le("scheduled_at", now).set("status", "RUNNING").set("attempts", attempts)
                .set("payload", claimed.toString()).set("started_at", now).set("finished_at", null).set("last_error", null).set("updated_at", now);
        if (!tasks.update(update)) throw new CheckpointConflict();
        task.setStatus("RUNNING"); task.setAttempts(attempts); task.setPayload(claimed.toString()); return true;
    }
    private UpdateWrapper<ResourceHubTask> owned(ResourceHubTask task) {
        return new UpdateWrapper<ResourceHubTask>().eq("id", task.getId()).eq("task_type", TYPE).eq("source", "GYING")
                .eq("status", task.getStatus()).eq("attempts", task.getAttempts()).eq("max_attempts", task.getMaxAttempts())
                .eq("movie_id", task.getMovieId()).eq("tmdb_id", task.getTmdbId()).eq("tmdb_type", task.getTmdbType()).eq("keyword", task.getKeyword())
                .eq("scheduled_at", task.getScheduledAt())
                .apply("BINARY task_type=BINARY {0} AND BINARY source=BINARY {1} AND BINARY movie_id=BINARY {2} AND BINARY tmdb_type=BINARY {3} AND BINARY keyword=BINARY {4}",
                        TYPE, "GYING", task.getMovieId(), task.getTmdbType(), task.getKeyword())
                .apply("BINARY CAST(payload AS CHAR)=BINARY CAST(CAST({0} AS JSON) AS CHAR)", task.getPayload());
    }
    private void defer(ResourceHubTask task, ObjectNode checkpoint, String reason) {
        checkpoint.put("blockedReason", reason); updateTask(task, task.getStatus(), checkpoint, LocalDateTime.now().plusHours(24), reason);
    }
    private void finish(ResourceHubTask task, String status, ObjectNode checkpoint, String reason) {
        checkpoint.put("lastResult", reason == null ? "ARCHIVE_QUEUED" : reason);
        updateTask(task, status, checkpoint, LocalDateTime.now().plusHours(24), reason);
    }
    private void updateTask(ResourceHubTask task, String status, ObjectNode checkpoint, LocalDateTime next, String reason) {
        if (!tasks.update(owned(task).set("status", status).set("payload", checkpoint.toString()).set("scheduled_at", next)
                .set("last_error", reason).set("finished_at", "RUNNING".equals(task.getStatus()) ? LocalDateTime.now() : task.getFinishedAt())
                .set("updated_at", LocalDateTime.now()))) throw new CheckpointConflict();
        task.setStatus(status); task.setPayload(checkpoint.toString()); task.setScheduledAt(next); task.setLastError(reason);
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedFollowUps() {
        tasks.update(new UpdateWrapper<ResourceHubTask>().eq("task_type", TYPE).eq("source", "GYING").eq("status", "RUNNING")
                .apply("JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode'))={0}", MODE)
                .set("status", "FAILED").set("last_error", "Interrupted GYING resource attempt; metadata remains completed")
                .set("scheduled_at", LocalDateTime.now().plusHours(24)).set("updated_at", LocalDateTime.now()));
    }

    /** Hold a dedicated connection lock until the separate metadata/budget transaction has completed. */
    <T> T withDatabaseLock(String name, Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("GYING_RESOURCE_NESTED_TRANSACTION_FORBIDDEN");
        Connection connection = null; boolean acquired = false;
        try {
            connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) { acquired = result.next() && result.getInt(1) == 1 && !result.wasNull(); }
            }
            if (!acquired) throw new IllegalStateException("GYING_RESOURCE_DATABASE_LOCK_BUSY");
            return action.get();
        } catch (java.sql.SQLException unavailable) { throw new IllegalStateException("GYING_RESOURCE_DATABASE_LOCK_UNAVAILABLE"); }
        finally {
            if (connection != null) {
                boolean released = !acquired;
                if (acquired) try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, name);
                    try (ResultSet result = release.executeQuery()) { released = result.next() && result.getInt(1) == 1 && !result.wasNull(); }
                } catch (java.sql.SQLException ignored) { /* A pooled close alone does not release a session lock. */ }
                if (!released) {
                    if (jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource pool) {
                        try { pool.evictConnection(connection); } catch (RuntimeException unavailable) {
                            try { connection.abort(Runnable::run); } catch (java.sql.SQLException ignored) { /* Best effort physical retirement. */ }
                        }
                    }
                    else try { connection.abort(Runnable::run); } catch (java.sql.SQLException ignored) { /* Physical-session retirement. */ }
                }
                try { connection.close(); } catch (java.sql.SQLException ignored) {
                    try { connection.abort(Runnable::run); } catch (java.sql.SQLException unavailable) { /* Never replay a committed ACK. */ }
                }
            }
        }
    }
    static final class CheckpointConflict extends IllegalStateException {
        CheckpointConflict() { super("GYING_RESOURCE_CHECKPOINT_CONFLICT"); }
    }
}
