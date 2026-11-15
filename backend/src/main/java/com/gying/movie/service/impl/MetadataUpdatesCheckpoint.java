package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Separate, durable daily head checks; RANGE checkpoints and cloud queues are untouched. */
public final class MetadataUpdatesCheckpoint {
    public static final String MODE = "UPDATES";
    public static final int HEAD_PAGES = 2;
    private static final int MAX_TRACKED = 10000;
    private static final int MAX_ATTEMPTS = 3;
    private final IResourceHubTaskService tasks;
    private final ObjectMapper mapper;

    public record Candidate(String source, String key, ObjectNode data) {
        public Candidate {
            if (source == null || key == null || data == null || source.isBlank() || key.isBlank())
                throw new IllegalArgumentException("Invalid update candidate");
            data = data.deepCopy();
        }
        public String fingerprint() {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            data.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            return ResourceHubHashUtils.sha256(sorted.toString());
        }
    }
    public record Result(int processed, int failed, int seeded, int skipped, int pending, int exhausted) {}
    public static final class CheckpointConflict extends IllegalStateException {
        public CheckpointConflict() { super("Update task ownership changed; checkpoint retained"); }
    }

    public MetadataUpdatesCheckpoint(IResourceHubTaskService tasks, ObjectMapper mapper) {
        this.tasks = tasks; this.mapper = mapper;
    }

    public ResourceHubTask enqueue(String provider, List<String> sources, int maximum) {
        if (sources.isEmpty()) throw new IllegalArgumentException("Update sources required");
        ResourceHubTask latest = tasks.getOne(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC").eq("source", provider).eq("keyword", MODE)
                .apply("JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(payload), payload, '{}'), '$.crawl.mode')) = 'UPDATES'")
                .orderByDesc("id").last("LIMIT 1"), false);
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode state = latest == null ? mapper.createObjectNode() : state(latest).deepCopy();
        state.put("mode", MODE); state.put("headPages", HEAD_PAGES);
        state.put("roundAttempts", 0); state.put("roundBudget", Math.min(Math.max(maximum, 1), 10));
        state.set("sources", mapper.valueToTree(sources));
        if (!state.has("completed")) state.putObject("completed");
        if (!state.has("pending")) state.putObject("pending");
        validate(state);
        payload.put("source", MODE); payload.put("maxItems", Math.min(Math.max(maximum, 1), 10));
        payload.set("crawl", state);
        ResourceHubTask task = new ResourceHubTask();
        task.setTaskType("METADATA_SYNC"); task.setSource(provider); task.setKeyword(MODE);
        task.setPriority("TMDB".equals(provider) ? 10 : 9); task.setPayload(payload.toString());
        ResourceHubTask saved = tasks.enqueue(task);
        if (saved == null || saved.getId() == null) throw new IllegalStateException("Update checkpoint was not enqueued");
        return saved;
    }

    public boolean automatic(ResourceHubTask task) {
        try { return task != null && MODE.equals(read(task).path("crawl").path("mode").asText()); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** Scan first, then persist its complete backlog before issuing any detail requests. */
    public Result run(ResourceHubTask task, List<Candidate> head, Set<String> allowedSources,
            int maximum, Predicate<Candidate> trustedCompletion, Consumer<Candidate> collect) {
        ObjectNode payload = read(task); ObjectNode state = state(task);
        ObjectNode completed = (ObjectNode) state.path("completed"), pending = (ObjectNode) state.path("pending");
        // Use the same parsed tree for mutations and persistence.
        payload.set("crawl", state);
        int seeded = 0, skipped = 0, processed = 0, failed = 0;
        TreeMap<String, String> scan = new TreeMap<>();
        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : head) {
            if (!allowedSources.contains(candidate.source())) throw new IllegalArgumentException("Unexpected update source");
            Candidate prior = unique.putIfAbsent(candidate.key(), candidate);
            if (prior != null && !prior.fingerprint().equals(candidate.fingerprint()))
                throw new IllegalStateException("Conflicting update identities in one scan");
        }
        for (Candidate candidate : unique.values()) {
            String key = candidate.key(), fingerprint = candidate.fingerprint(); scan.put(key, fingerprint);
            if (fingerprint.equals(completed.path(key).asText())) { pending.remove(key); skipped++; continue; }
            if (!completed.has(key) && !pending.has(key) && trustedCompletion.test(candidate)) {
                completed.put(key, fingerprint); seeded++; continue;
            }
            JsonNode previous = pending.path(key);
            if (!fingerprint.equals(previous.path("fingerprint").asText())) {
                ObjectNode item = pending.putObject(key);
                item.put("source", candidate.source()); item.put("fingerprint", fingerprint);
                item.set("data", candidate.data()); item.put("attempts", 0);
            } else {
                // One identity may move between selected lists; retain its bounded retry count.
                ((ObjectNode) previous).put("source", candidate.source());
            }
        }
        if (completed.size() + pending.size() > MAX_TRACKED)
            throw new IllegalStateException("Update checkpoint capacity requires review; backlog retained");
        state.set("sources", mapper.valueToTree(allowedSources.stream().sorted().toList()));
        state.put("checkedAt", LocalDateTime.now().toString());
        state.put("headFingerprint", ResourceHubHashUtils.sha256(scan.toString()));
        persist(task, payload);
        List<String> keys = new ArrayList<>(); pending.fieldNames().forEachRemaining(keys::add);
        int attempted = state.path("roundAttempts").asInt();
        int budget = Math.min(Math.min(Math.max(maximum, 1), 10), state.path("roundBudget").asInt());
        for (String key : keys) {
            ObjectNode item = (ObjectNode) pending.get(key);
            if (attempted >= budget) break;
            if (!allowedSources.contains(item.path("source").asText()) || item.path("attempts").asInt() >= MAX_ATTEMPTS) continue;
            Candidate candidate = new Candidate(item.path("source").asText(), key, (ObjectNode) item.path("data"));
            attempted++; state.put("roundAttempts", attempted); item.put("attempts", item.path("attempts").asInt() + 1);
            // Persist the attempt before remote work; interrupted work retains its retry budget.
            persist(task, payload);
            try {
                collect.accept(candidate);
                completed.put(key, item.path("fingerprint").asText()); pending.remove(key); processed++;
            } catch (Exception error) {
                if (error instanceof CheckpointConflict conflict) throw conflict;
                item.put("lastErrorCategory", error.getClass().getSimpleName()); failed++;
            }
            persist(task, payload);
        }
        int exhausted = 0;
        for (JsonNode item : pending) if (item.path("attempts").asInt() >= MAX_ATTEMPTS) exhausted++;
        ObjectNode summary = state.putObject("lastRound");
        summary.put("processed", processed); summary.put("failed", failed); summary.put("seeded", seeded);
        summary.put("skipped", skipped); summary.put("pending", pending.size()); summary.put("exhausted", exhausted);
        summary.put("detailBudget", budget); summary.put("attempted", attempted);
        persist(task, payload);
        return new Result(processed, failed, seeded, skipped, pending.size(), exhausted);
    }

    private ObjectNode state(ResourceHubTask task) {
        JsonNode state = read(task).path("crawl");
        if (!(state instanceof ObjectNode object) || !MODE.equals(object.path("mode").asText()))
            throw new IllegalArgumentException("Invalid update checkpoint");
        validate(object); return object;
    }
    private ObjectNode read(ResourceHubTask task) {
        try {
            JsonNode node = mapper.readTree(task.getPayload());
            if (node instanceof ObjectNode object) return object;
        } catch (Exception error) { throw new IllegalArgumentException("Invalid update checkpoint", error); }
        throw new IllegalArgumentException("Invalid update checkpoint");
    }
    private void validate(ObjectNode state) {
        if (!state.path("roundAttempts").isIntegralNumber() || state.path("roundAttempts").asInt(-1) < 0
                || state.path("roundAttempts").asInt() > 10 || !state.path("roundBudget").isIntegralNumber()
                || state.path("roundBudget").asInt() < 1 || state.path("roundBudget").asInt() > 10)
            throw new IllegalArgumentException("Invalid update round budget");
        if (!state.path("completed").isObject() || !state.path("pending").isObject())
            throw new IllegalArgumentException("Invalid update checkpoint maps");
        for (JsonNode value : state.path("completed"))
            if (!value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid completed fingerprint");
        for (var entries = state.path("pending").fields(); entries.hasNext();) {
            var entry = entries.next(); JsonNode item = entry.getValue();
            if (!item.isObject() || !item.path("data").isObject() || !item.path("source").isTextual()
                    || !item.path("fingerprint").asText().matches("[0-9a-f]{64}") || !item.path("attempts").isIntegralNumber()
                    || item.path("attempts").asInt(-1) < 0 || item.path("attempts").asInt() > MAX_ATTEMPTS)
                throw new IllegalArgumentException("Invalid pending update checkpoint");
            Candidate candidate = new Candidate(item.path("source").asText(), entry.getKey(), (ObjectNode) item.path("data"));
            if (!candidate.fingerprint().equals(item.path("fingerprint").asText()))
                throw new IllegalArgumentException("Pending update fingerprint does not match data");
        }
    }
    public boolean start(ResourceHubTask task) {
        if (!Set.of("PENDING", "FAILED").contains(task.getStatus())) return false;
        int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
        LocalDateTime now = LocalDateTime.now();
        var update = new UpdateWrapper<ResourceHubTask>().eq("id", task.getId()).eq("status", task.getStatus());
        if (task.getAttempts() == null) update.isNull("attempts"); else update.eq("attempts", attempts);
        update.apply("BINARY CAST(payload AS CHAR) = BINARY CAST(CAST({0} AS JSON) AS CHAR)", task.getPayload())
                .set("status", "RUNNING").set("attempts", attempts + 1).set("started_at", now)
                .set("finished_at", null).set("last_error", null).set("updated_at", now);
        if (!tasks.update(update)) return false;
        task.setStatus("RUNNING"); task.setAttempts(attempts + 1); task.setStartedAt(now);
        task.setFinishedAt(null); task.setLastError(null); task.setUpdatedAt(now); return true;
    }

    public void finish(ResourceHubTask task, String status, String error) {
        LocalDateTime now = LocalDateTime.now();
        if (!tasks.update(owned(task).set("status", status).set("last_error", error)
                .set("finished_at", now).set("updated_at", now))) throw new CheckpointConflict();
        task.setStatus(status); task.setLastError(error); task.setFinishedAt(now); task.setUpdatedAt(now);
    }

    private UpdateWrapper<ResourceHubTask> owned(ResourceHubTask task) {
        if (task.getId() == null || !"RUNNING".equals(task.getStatus()) || task.getAttempts() == null)
            throw new CheckpointConflict();
        return new UpdateWrapper<ResourceHubTask>().eq("id", task.getId()).eq("status", "RUNNING")
                .eq("attempts", task.getAttempts()).apply("BINARY CAST(payload AS CHAR) = BINARY CAST(CAST({0} AS JSON) AS CHAR)", task.getPayload());
    }

    private void persist(ResourceHubTask task, ObjectNode payload) {
        String next = payload.toString(); LocalDateTime now = LocalDateTime.now();
        if (!tasks.update(owned(task).set("payload", next).set("updated_at", now))) throw new CheckpointConflict();
        task.setPayload(next); task.setUpdatedAt(now);
    }
}
