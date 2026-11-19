package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.dto.TvmazeSyncProgress;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** TVmaze's upstream timestamps and bounded discoveries live independently of list/RANGE cursors. */
public final class TvmazeUpdatesCheckpoint {
    public static final String PROVIDER = "TVMAZE";
    public static final int DISCOVERY_LIMIT = 100;
    public static final long DISCOVERY_TTL = 7 * 86400L;
    private static final int KNOWN_LIMIT = 10000;
    private final IResourceHubTaskService tasks;
    private final ObjectMapper mapper;
    private final MetadataUpdatesCheckpoint ownership;

    public TvmazeUpdatesCheckpoint(IResourceHubTaskService tasks, ObjectMapper mapper) {
        this.tasks = tasks; this.mapper = mapper;
        this.ownership = new MetadataUpdatesCheckpoint(tasks, mapper);
    }

    public QueryWrapper<ResourceHubTask> query() {
        return new QueryWrapper<ResourceHubTask>().eq("task_type", "METADATA_SYNC").eq("source", PROVIDER)
                .eq("keyword", "UPDATES")
                .apply("JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(payload), payload, '{}'), '$.crawl.provider')) = 'TVMAZE'");
    }
    public ResourceHubTask latest() {
        return tasks.getOne(query().orderByDesc("id").last("LIMIT 1"), false);
    }
    public ResourceHubTask enqueue(ResourceHubTask previous, int budget) {
        ObjectNode payload = mapper.createObjectNode(), state;
        if (previous == null) {
            state = mapper.createObjectNode();
            state.put("mode", "UPDATES"); state.put("provider", PROVIDER); state.put("version", 1);
            state.put("lastSuccessfulScanAt", 0L); state.put("nextRequestAt", 0L);
            state.put("budgetStartedAt", 0L); state.put("dailyAttempts", 0);
            state.putObject("pending"); state.putObject("deferred");
        } else state = (ObjectNode) read(previous).path("crawl").deepCopy();
        state.put("roundBudget", Math.min(Math.max(budget, 1), 10)); state.put("roundAttempts", 0);
        state.put("scanDone", false); state.remove("lastRound");
        payload.put("source", "UPDATES"); payload.set("crawl", state);
        ResourceHubTask task = new ResourceHubTask();
        task.setTaskType("METADATA_SYNC"); task.setSource(PROVIDER); task.setKeyword("UPDATES");
        task.setPriority(8); task.setPayload(payload.toString());
        ResourceHubTask saved = tasks.enqueue(task);
        if (saved == null || saved.getId() == null) throw new IllegalStateException("TVmaze checkpoint was not enqueued");
        return saved;
    }
    public ObjectNode read(ResourceHubTask task) {
        try {
            JsonNode payload = mapper.readTree(task.getPayload()), state = payload.path("crawl");
            if (!PROVIDER.equals(task.getSource()) || !"METADATA_SYNC".equals(task.getTaskType())
                    || !"UPDATES".equals(task.getKeyword()) || !payload.isObject() || !state.isObject()
                    || !"UPDATES".equals(state.path("mode").asText()) || !PROVIDER.equals(state.path("provider").asText())
                    || state.path("version").asInt() != 1
                    || !state.path("roundAttempts").isIntegralNumber() || state.path("roundAttempts").asInt() < 0
                    || state.path("roundAttempts").asInt() > 10 || !state.path("roundBudget").isIntegralNumber()
                    || state.path("roundBudget").asInt() < 1 || state.path("roundBudget").asInt() > 10
                    || !state.path("lastSuccessfulScanAt").isIntegralNumber() || state.path("lastSuccessfulScanAt").asLong() < 0
                    || !state.path("nextRequestAt").isIntegralNumber() || state.path("nextRequestAt").asLong() < 0
                    || !state.path("budgetStartedAt").isIntegralNumber() || state.path("budgetStartedAt").asLong() < 0
                    || !state.path("dailyAttempts").isIntegralNumber() || state.path("dailyAttempts").asInt() < 0
                    || state.path("dailyAttempts").asInt() > 10
                    || !state.path("scanDone").isBoolean() || !state.path("pending").isObject() || !state.path("deferred").isObject()) {
                throw new IllegalArgumentException("Invalid TVmaze checkpoint");
            }
            int unknown = state.path("deferred").size(), known = 0;
            for (var entries = state.path("pending").fields(); entries.hasNext();) {
                var entry = entries.next(); JsonNode item = entry.getValue();
                validateItem(entry.getKey(), item);
                if (!item.path("known").isBoolean() || !item.path("attempts").isIntegralNumber()
                        || item.path("attempts").asInt() < 0 || item.path("attempts").asInt() > 3) {
                    throw new IllegalArgumentException("Invalid TVmaze pending item");
                }
                if (item.path("known").asBoolean()) known++; else unknown++;
            }
            for (var entries = state.path("deferred").fields(); entries.hasNext();) {
                var entry = entries.next(); validateItem(entry.getKey(), entry.getValue());
                if (!entry.getValue().path("reason").isTextual()) throw new IllegalArgumentException("Invalid TVmaze discovery");
            }
            if (unknown > DISCOVERY_LIMIT || known > KNOWN_LIMIT) throw new IllegalArgumentException("TVmaze checkpoint capacity exceeded");
            return (ObjectNode) payload;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Invalid TVmaze checkpoint"); }
    }
    private void validateItem(String key, JsonNode item) {
        if (!key.matches("[1-9][0-9]{0,11}") || !item.isObject()
                || !item.path("updated").isIntegralNumber() || !item.path("updated").canConvertToLong()
                || item.path("updated").asLong() <= 0 || !item.path("firstSeen").isIntegralNumber()
                || item.path("firstSeen").asLong() <= 0) throw new IllegalArgumentException("Invalid TVmaze discovery identity/timestamp");
    }

    public static String window(long lastScan, long now) {
        long elapsed = Math.max(0, now - lastScan);
        return lastScan == 0 || elapsed <= 86400 ? "day" : elapsed <= 7 * 86400 ? "week" : "month";
    }

    /** Known IDs always enter the backlog; global discoveries share one expiring hundred-item pool. */
    public int merge(ObjectNode state, Map<Long, Long> feed, Map<Long, Long> completed, long now) {
        ObjectNode pending = (ObjectNode) state.path("pending"), deferred = (ObjectNode) state.path("deferred");
        List<String> expired = new ArrayList<>();
        pending.fields().forEachRemaining(e -> {
            if (!e.getValue().path("known").asBoolean() && e.getValue().path("firstSeen").asLong() < now - DISCOVERY_TTL) expired.add(e.getKey());
        });
        expired.forEach(pending::remove); expired.clear();
        deferred.fields().forEachRemaining(e -> {
            if (e.getValue().path("firstSeen").asLong() < now - DISCOVERY_TTL || completed.containsKey(Long.parseLong(e.getKey()))) expired.add(e.getKey());
        });
        expired.forEach(deferred::remove);
        int unknown = deferred.size();
        for (JsonNode item : pending) if (!item.path("known").asBoolean()) unknown++;
        int skipped = 0;
        List<Map.Entry<Long, Long>> sorted = feed.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, Long>, Boolean>comparing(e -> !completed.containsKey(e.getKey()))
                        .thenComparing(Map.Entry.<Long, Long>comparingByValue().reversed())
                        .thenComparing(Map.Entry.comparingByKey())).toList();
        for (var entry : sorted) {
            String key = entry.getKey().toString();
            boolean known = completed.containsKey(entry.getKey());
            long updated = entry.getValue();
            if (known && completed.get(entry.getKey()) >= updated) {
                if (pending.has(key) && !pending.path(key).path("known").asBoolean()) unknown--;
                pending.remove(key); skipped++; continue;
            }
            if (!known && deferred.path(key).path("updated").asLong() >= updated) { skipped++; continue; }
            if (deferred.has(key)) { deferred.remove(key); unknown--; }
            JsonNode previous = pending.path(key);
            if (previous.isMissingNode() && !known && unknown >= DISCOVERY_LIMIT) { skipped++; continue; }
            if (!previous.isMissingNode() && known && !previous.path("known").asBoolean()) unknown--;
            if (previous.isMissingNode() && !known) unknown++;
            ObjectNode item = previous.isMissingNode() ? pending.putObject(key) : (ObjectNode) previous;
            if (previous.isMissingNode()) item.put("firstSeen", now);
            if (updated > item.path("updated").asLong()) {
                item.put("updated", updated); item.put("attempts", 0); item.remove("lastErrorCategory");
            }
            item.put("known", known);
        }
        long knownSize = java.util.stream.StreamSupport.stream(pending.spliterator(), false).filter(n -> n.path("known").asBoolean()).count();
        if (knownSize > KNOWN_LIMIT) throw new IllegalStateException("TVmaze known backlog requires capacity review");
        state.put("lastSuccessfulScanAt", now); state.put("checkedAt", java.time.Instant.ofEpochSecond(now).toString());
        state.put("scanDone", true);
        return skipped;
    }

    public List<String> ordered(ObjectNode state) {
        ObjectNode pending = (ObjectNode) state.path("pending");
        List<String> keys = new ArrayList<>(); pending.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.<String, Boolean>comparing(k -> !pending.path(k).path("known").asBoolean())
                .thenComparingLong(k -> pending.path(k).path("firstSeen").asLong()).thenComparingLong(Long::parseLong));
        return keys;
    }

    public boolean start(ResourceHubTask task) { return ownership.start(task); }
    public void finish(ResourceHubTask task, String status, String error) { ownership.finish(task, status, error); }
    public void persist(ResourceHubTask task, ObjectNode payload) {
        LocalDateTime now = LocalDateTime.now();
        var update = new UpdateWrapper<ResourceHubTask>().eq("id", task.getId()).eq("status", "RUNNING")
                .eq("attempts", task.getAttempts())
                .apply("BINARY CAST(payload AS CHAR) = BINARY CAST(CAST({0} AS JSON) AS CHAR)", task.getPayload())
                .set("payload", payload.toString()).set("updated_at", now);
        if (!tasks.update(update)) throw new MetadataUpdatesCheckpoint.CheckpointConflict();
        task.setPayload(payload.toString()); task.setUpdatedAt(now);
    }
    public TvmazeSyncProgress progress() {
        ResourceHubTask task = latest();
        if (task == null) return null;
        try {
            JsonNode state = read(task).path("crawl"), summary = state.path("lastRound");
            int exhausted = 0;
            for (JsonNode item : state.path("pending")) if (item.path("attempts").asInt() >= 3) exhausted++;
            return new TvmazeSyncProgress(task.getId(), task.getStatus(), state.path("checkedAt").asText(null),
                    state.path("window").asText(null), state.path("roundAttempts").asInt(),
                    summary.path("processed").asInt(), summary.path("skipped").asInt(), state.path("pending").size(),
                    state.path("deferred").size(), exhausted, state.path("coverageGap").asBoolean());
        } catch (IllegalArgumentException error) {
            return new TvmazeSyncProgress(task.getId(), "REVIEW_REQUIRED", null, null, 0, 0, 0, 0, 0, 0, false);
        }
    }
}
