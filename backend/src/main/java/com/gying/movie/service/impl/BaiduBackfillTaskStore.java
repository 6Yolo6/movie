package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** One parent pointer, durable progress and a database lease; no network call runs in a transaction. */
@Service
public class BaiduBackfillTaskStore {
    static final String TYPE = "CLOUD_DRIVE_BACKFILL";
    static final String KEY = "resource.hub.baidu.backfill.task-id";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public BaiduBackfillTaskStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc = jdbc; this.transactions = transactions; this.mapper = mapper;
    }

    public static class State {
        public int version = 1;
        public Long taskId;
        public long revision;
        public long settingsRevision;
        public boolean enabled;
        public int intervalMinutes = 10;
        public int batchLimit = 1;
        public String status = "PAUSED";
        public String phase = "EXISTING_TASKS";
        public long taskMaxId, discoveryMaxId, linkMaxId, p2pMaxId;
        public long taskCursor, discoveryCursor, linkCursor, p2pCursor;
        public String movieCursor = "";
        public String movieCutoff;
        public String currentKind, currentMovieId;
        public Long currentId, currentChildId, currentDiscoveryTaskId;
        public long sourceDiscoveryCursor;
        public long processed, succeeded, skipped, failed;
        public boolean currentFailureCounted;
        public Long currentResourceLinkId;
        public boolean currentGyingSubmitting;
        public String blockedReason, nextRunAt, leaseOwner, leaseUntil;
    }

    public State read() {
        List<String> pointers = jdbc.queryForList("SELECT config_value FROM sys_config WHERE config_key=?", String.class, KEY);
        if (pointers.isEmpty()) return new State();
        return readTask(pointer(pointers.get(0)), false);
    }

    public State settings(boolean enabled, int intervalMinutes, int batchLimit) {
        if (intervalMinutes < 1 || intervalMinutes > 1440 || batchLimit < 1 || batchLimit > 5) {
            throw new IllegalArgumentException("INVALID_BACKFILL_SETTINGS");
        }
        return transactions.execute(tx -> {
            State state = ensureLocked();
            state.enabled = enabled; state.intervalMinutes = intervalMinutes; state.batchLimit = batchLimit;
            state.settingsRevision++;
            if (state.leaseOwner == null && !"COMPLETE".equals(state.phase)) {
                state.status = enabled ? "PENDING" : "PAUSED";
                state.blockedReason = null;
                state.nextRunAt = enabled ? now().toString() : null;
            }
            write(state);
            return state;
        });
    }

    public State claim(boolean manual) {
        return transactions.execute(tx -> {
            State state = manual ? ensureLocked() : readLockedIfPresent();
            if (state == null) return null;
            LocalDateTime now = now();
            if ("COMPLETE".equals(state.phase)) {
                // A crash after the final phase checkpoint may leave only the parent lease unfinished.
                if (state.leaseOwner != null && !LocalDateTime.parse(state.leaseUntil).isAfter(now)) {
                    state.leaseOwner = null; state.leaseUntil = null; state.status = "COMPLETE"; state.nextRunAt = null;
                    state.revision++; write(state);
                }
                return null;
            }
            if (!manual && (!state.enabled || (state.nextRunAt != null && LocalDateTime.parse(state.nextRunAt).isAfter(now)))) return null;
            if (state.leaseOwner != null && state.leaseUntil != null && LocalDateTime.parse(state.leaseUntil).isAfter(now)) return null;
            state.leaseOwner = UUID.randomUUID().toString(); state.leaseUntil = now.plusMinutes(30).toString();
            state.revision++; state.status = "RUNNING"; state.blockedReason = null;
            write(state);
            return state;
        });
    }

    /** Child allocation may join this short transaction, so parent and child commit together. */
    public State checkpoint(State progress, Consumer<State> localMutation) {
        return transactions.execute(tx -> {
            State latest = readTask(progress.taskId, true);
            assertOwned(latest, progress);
            State saved = copy(progress);
            // A pause or settings edit made while a remote call was running is never overwritten.
            saved.enabled = latest.enabled; saved.intervalMinutes = latest.intervalMinutes;
            saved.batchLimit = latest.batchLimit; saved.settingsRevision = latest.settingsRevision;
            if (localMutation != null) localMutation.accept(saved);
            saved.revision++; saved.leaseUntil = now().plusMinutes(30).toString();
            write(saved);
            return saved;
        });
    }

    public State finish(State progress) {
        return transactions.execute(tx -> {
            State latest = readTask(progress.taskId, true);
            assertOwned(latest, progress);
            State saved = copy(progress);
            saved.enabled = latest.enabled; saved.intervalMinutes = latest.intervalMinutes;
            saved.batchLimit = latest.batchLimit; saved.settingsRevision = latest.settingsRevision;
            saved.revision++; saved.leaseOwner = null; saved.leaseUntil = null;
            saved.status = "COMPLETE".equals(saved.phase) ? "COMPLETE" : saved.blockedReason != null ? "BLOCKED" : saved.enabled ? "PENDING" : "PAUSED";
            saved.nextRunAt = !"COMPLETE".equals(saved.phase) && saved.enabled ? now().plusMinutes(saved.intervalMinutes).toString() : null;
            write(saved);
            return saved;
        });
    }

    private State ensureLocked() {
        jdbc.update("INSERT INTO sys_config(config_key,config_value,description) VALUES(?,'0','百度慢速补全父任务指针') ON DUPLICATE KEY UPDATE config_key=config_key", KEY);
        String value = jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=? FOR UPDATE", String.class, KEY);
        long id = pointer(value);
        if (id > 0) return readTask(id, true);
        State state = new State();
        state.taskMaxId = maximum("baidu_transfer_task", "");
        state.discoveryMaxId = maximum("resource_discovery_result", " WHERE provider='BAIDU'");
        state.linkMaxId = maximum("resource_link", " WHERE provider='BAIDU'");
        state.p2pMaxId = maximum("resource_hub_task", " WHERE task_type='P2P_ARCHIVE'");
        state.movieCutoff = now().toString();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO resource_hub_task(task_type,source,status,priority,attempts,max_attempts,payload,scheduled_at) VALUES(?,'BAIDU','PAUSED',0,0,0,?,NULL)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, TYPE); statement.setString(2, json(state)); return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("BACKFILL_PARENT_NOT_PERSISTED");
        state.taskId = key.getKey().longValue();
        jdbc.update("UPDATE sys_config SET config_value=? WHERE config_key=?", state.taskId.toString(), KEY);
        write(state);
        return state;
    }

    private State readLockedIfPresent() {
        List<String> pointers = jdbc.queryForList("SELECT config_value FROM sys_config WHERE config_key=? FOR UPDATE", String.class, KEY);
        if (pointers.isEmpty()) return null;
        return readTask(pointer(pointers.get(0)), true);
    }
    private State readTask(long id, boolean locked) {
        List<String> values = jdbc.queryForList("SELECT payload FROM resource_hub_task WHERE id=? AND task_type=? AND source='BAIDU'" + (locked ? " FOR UPDATE" : ""), String.class, id, TYPE);
        if (values.size() != 1) throw new IllegalStateException("BACKFILL_PARENT_MISSING");
        try {
            JsonNode raw = mapper.readTree(values.get(0));
            validateShape(raw);
            State state = mapper.treeToValue(raw, State.class);
            if (state.taskId == null || state.taskId != id) throw new IllegalStateException();
            validate(state);
            return state;
        } catch (Exception invalid) { throw new IllegalStateException("BACKFILL_STATE_INVALID"); }
    }
    private void assertOwned(State latest, State expected) {
        if (expected.leaseOwner == null || !expected.leaseOwner.equals(latest.leaseOwner) || latest.revision != expected.revision
                || latest.leaseUntil == null || !LocalDateTime.parse(latest.leaseUntil).isAfter(now())) {
            throw new IllegalStateException("BACKFILL_LEASE_LOST");
        }
    }
    private void write(State state) {
        validate(state);
        if (jdbc.update("UPDATE resource_hub_task SET payload=?,status=?,last_error=?,scheduled_at=?,started_at=CASE WHEN ?='RUNNING' THEN COALESCE(started_at,CURRENT_TIMESTAMP) ELSE started_at END,finished_at=CASE WHEN ?='COMPLETE' THEN CURRENT_TIMESTAMP ELSE NULL END,updated_at=CURRENT_TIMESTAMP WHERE id=? AND task_type=? AND source='BAIDU'",
                json(state), state.status, state.blockedReason, state.nextRunAt, state.status, state.status, state.taskId, TYPE) != 1) {
            throw new IllegalStateException("BACKFILL_CHECKPOINT_NOT_PERSISTED");
        }
    }
    private static final Set<String> PHASES = Set.of("EXISTING_TASKS", "EXISTING_DISCOVERIES", "EXISTING_P2P", "SOURCE_SEARCH", "COMPLETE");
    private static final Set<String> STATUSES = Set.of("PAUSED", "PENDING", "RUNNING", "BLOCKED", "COMPLETE");
    private static final Set<String> KINDS = Set.of("BAIDU_TASK", "DISCOVERY", "RESOURCE_LINK", "P2P_REFERENCE", "SOURCE_MOVIE");
    private static void validateShape(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalStateException("BACKFILL_STATE_INVALID");
        for (String field : List.of("version", "taskId", "revision", "settingsRevision", "intervalMinutes", "batchLimit", "taskMaxId", "discoveryMaxId", "linkMaxId", "p2pMaxId", "taskCursor", "discoveryCursor", "linkCursor", "p2pCursor", "sourceDiscoveryCursor", "processed", "succeeded", "skipped", "failed")) {
            JsonNode value = raw.get(field);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalStateException("BACKFILL_STATE_INVALID");
        }
        for (String field : List.of("enabled", "currentFailureCounted", "currentGyingSubmitting")) {
            if (!raw.has(field) || !raw.get(field).isBoolean()) throw new IllegalStateException("BACKFILL_STATE_INVALID");
        }
        for (String field : List.of("status", "phase", "movieCursor", "movieCutoff")) {
            if (!raw.has(field) || !raw.get(field).isTextual()) throw new IllegalStateException("BACKFILL_STATE_INVALID");
        }
        for (String field : List.of("currentKind", "currentMovieId", "blockedReason", "nextRunAt", "leaseOwner", "leaseUntil")) {
            if (!raw.has(field) || !(raw.get(field).isNull() || raw.get(field).isTextual())) throw new IllegalStateException("BACKFILL_STATE_INVALID");
        }
        for (String field : List.of("currentId", "currentChildId", "currentDiscoveryTaskId", "currentResourceLinkId")) {
            if (!raw.has(field) || !(raw.get(field).isNull() || (raw.get(field).isIntegralNumber() && raw.get(field).canConvertToLong()))) throw new IllegalStateException("BACKFILL_STATE_INVALID");
        }
    }
    private static void validate(State state) {
        if (state.version != 1 || state.taskId == null || state.taskId <= 0 || state.revision < 0 || state.settingsRevision < 0
                || state.intervalMinutes < 1 || state.intervalMinutes > 1440 || state.batchLimit < 1 || state.batchLimit > 5
                || !PHASES.contains(state.phase) || !STATUSES.contains(state.status)
                || state.taskMaxId < 0 || state.discoveryMaxId < 0 || state.linkMaxId < 0 || state.p2pMaxId < 0
                || state.taskCursor < 0 || state.taskCursor > state.taskMaxId || state.discoveryCursor < 0 || state.discoveryCursor > state.discoveryMaxId
                || state.linkCursor < 0 || state.linkCursor > state.linkMaxId || state.p2pCursor < 0 || state.p2pCursor > state.p2pMaxId
                || state.sourceDiscoveryCursor < 0 || state.processed < 0 || state.succeeded < 0 || state.skipped < 0 || state.failed < 0
                || state.succeeded > state.processed || state.skipped != state.processed - state.succeeded
                || state.movieCursor == null || state.movieCursor.length() > 64) invalid();
        iso(state.movieCutoff, false); iso(state.nextRunAt, true); iso(state.leaseUntil, true);
        if ((state.leaseOwner == null) != (state.leaseUntil == null)
                || (state.leaseOwner != null && !state.leaseOwner.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                || ("RUNNING".equals(state.status) && state.leaseOwner == null)) invalid();
        if (state.currentKind != null && (!KINDS.contains(state.currentKind) || state.currentMovieId == null || state.currentMovieId.isBlank() || state.currentMovieId.length() > 64)) invalid();
        for (Long id : new Long[] { state.currentId, state.currentChildId, state.currentDiscoveryTaskId, state.currentResourceLinkId }) if (id != null && id <= 0) invalid();
        if (state.currentKind == null && (state.currentId != null || state.currentChildId != null || state.currentMovieId != null
                || state.currentDiscoveryTaskId != null || state.currentResourceLinkId != null || state.currentGyingSubmitting)) invalid();
        if (state.currentKind != null) {
            switch (state.currentKind) {
                case "BAIDU_TASK" -> { if (!"EXISTING_TASKS".equals(state.phase) || state.currentId == null || state.currentId > state.taskMaxId) invalid(); }
                case "DISCOVERY" -> { if (!"EXISTING_DISCOVERIES".equals(state.phase) || state.currentId == null || state.currentId > state.discoveryMaxId) invalid(); }
                case "RESOURCE_LINK" -> { if (!"EXISTING_DISCOVERIES".equals(state.phase) || state.currentId == null || state.currentId > state.linkMaxId) invalid(); }
                case "P2P_REFERENCE" -> { if (!"EXISTING_P2P".equals(state.phase) || state.currentId == null || state.currentId > state.p2pMaxId) invalid(); }
                case "SOURCE_MOVIE" -> { if (!"SOURCE_SEARCH".equals(state.phase) || state.currentId != null) invalid(); }
                default -> invalid();
            }
        }
        if (state.currentGyingSubmitting && (state.currentChildId == null || state.currentResourceLinkId == null)) invalid();
    }
    private static void iso(String value, boolean nullable) {
        if (value == null) { if (!nullable) invalid(); return; }
        try { if (value.length() > 40) invalid(); LocalDateTime.parse(value); } catch (Exception invalid) { invalid(); }
    }
    private static void invalid() { throw new IllegalStateException("BACKFILL_STATE_INVALID"); }

    private long maximum(String table, String filter) { return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM " + table + filter, Long.class); }
    private LocalDateTime now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class); }
    private static long pointer(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) throw new IllegalStateException("BACKFILL_POINTER_INVALID");
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { throw new IllegalStateException("BACKFILL_POINTER_INVALID"); }
    }
    private State copy(State state) { try { return mapper.readValue(json(state), State.class); } catch (Exception error) { throw new IllegalStateException("BACKFILL_STATE_INVALID"); } }
    private String json(State state) { try { return mapper.writeValueAsString(state); } catch (Exception error) { throw new IllegalStateException("BACKFILL_STATE_INVALID"); } }
}
