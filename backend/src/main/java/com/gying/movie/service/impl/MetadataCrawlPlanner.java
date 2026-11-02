package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.dto.MetadataCrawlProgress;
import com.gying.movie.dto.TmdbListPage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;

/** The task payload is the checkpoint: position and completion are saved with task status. */
public final class MetadataCrawlPlanner {
    private final IResourceHubTaskService tasks;
    private final ObjectMapper mapper;
    private final Clock clock;
    private static final Duration EMPTY_RANGE_RECHECK = Duration.ofHours(24);

    public MetadataCrawlPlanner(IResourceHubTaskService tasks, ObjectMapper mapper) {
        this(tasks, mapper, Clock.systemUTC());
    }

    MetadataCrawlPlanner(IResourceHubTaskService tasks, ObjectMapper mapper, Clock clock) {
        this.tasks = tasks;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record Position(int startPage, int endPage, int page, int offset, String status, Long taskId,
            Integer observedTotalPages, String availability, Instant nextCheckAt) {
        public Position(int startPage, int endPage, int page, int offset, String status, Long taskId) {
            this(startPage, endPage, page, offset, status, taskId, null, null, null);
        }
    }

    public boolean canEnqueue(Position position) {
        return position.nextCheckAt() == null || !clock.instant().isBefore(position.nextCheckAt());
    }

    public Position next(String provider, String source, int start, int end) {
        ResourceHubTask latest = tasks.getOne(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC").eq("source", provider).eq("keyword", source)
                // JSON may be stored as text or normalized by MySQL; do not depend on whitespace.
                .apply("JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(payload), payload, '{}'), '$.crawl.mode')) = 'RANGE'")
                .orderByDesc("id").last("LIMIT 1"), false);
        if (latest == null) {
            return new Position(start, end, start, 0, "NOT_STARTED", null);
        }
        JsonNode payload = read(latest);
        JsonNode crawl = payload.path("crawl");
        boolean complete = "SUCCEEDED".equals(latest.getStatus()) && crawl.has("nextPage");
        int page = complete ? crawl.path("nextPage").asInt(start) : payload.path("page").asInt(start);
        int offset = complete ? crawl.path("nextOffset").asInt() : crawl.path("offset").asInt();
        // Extending the end after a completed pass continues beyond the old end.
        // An empty upstream page still ends the pass; failed/in-flight batches keep their position.
        int previousEnd = crawl.path("endPage").asInt();
        if (complete && end > previousEnd && payload.path("page").asInt() == previousEnd
                && page == crawl.path("startPage").asInt() && crawl.path("pageSize").asInt() > 0
                && (!crawl.has("observedTotalPages") || previousEnd < crawl.path("observedTotalPages").asInt())) {
            page = previousEnd + 1;
            offset = 0;
        }
        if (page < start || page > end || offset < 0 || offset > 10000) {
            return new Position(start, end, start, 0, "NOT_STARTED", null);
        }
        String status = "SUCCEEDED".equals(latest.getStatus()) && !complete ? "RETRY" : latest.getStatus();
        boolean tmdb = "TMDB".equals(provider);
        Integer observedTotalPages = tmdb && crawl.path("observedTotalPages").canConvertToInt()
                && crawl.path("observedTotalPages").asInt() >= 0 ? crawl.path("observedTotalPages").asInt() : null;
        boolean sameRange = start == crawl.path("startPage").asInt() && end == previousEnd;
        String availability = tmdb && complete && sameRange ? crawl.path("availability").asText(null) : null;
        Instant nextCheckAt = "EMPTY_RANGE".equals(availability) ? recheckAt(crawl) : null;
        return new Position(start, end, page, offset, status, latest.getId(),
                observedTotalPages, availability, nextCheckAt);
    }

    public MetadataCrawlProgress progress(String provider, String source, int start, int end) {
        Position p = next(provider, source, start, end);
        return new MetadataCrawlProgress(provider, source, start, end, p.page(), p.offset() + 1,
                p.status(), p.taskId(), p.observedTotalPages(), p.availability(),
                p.nextCheckAt() == null ? null : p.nextCheckAt().toString());
    }

    public String attach(String payload, Position position) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(payload);
            ObjectNode crawl = node.putObject("crawl");
            crawl.put("mode", "RANGE");
            crawl.put("startPage", position.startPage());
            crawl.put("endPage", position.endPage());
            crawl.put("offset", position.offset());
            if (position.observedTotalPages() != null) crawl.put("observedTotalPages", position.observedTotalPages());
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid metadata crawl payload", e);
        }
    }

    public boolean automatic(ResourceHubTask task) {
        return "RANGE".equals(read(task).path("crawl").path("mode").asText());
    }

    public int offset(ResourceHubTask task) {
        return Math.max(0, read(task).path("crawl").path("offset").asInt());
    }

    /** Mutate only; the caller persists payload and final status together with finishTask. */
    public void completed(ResourceHubTask task, int pageSize, int attempted, int failed) {
        completed(task, pageSize, attempted, failed, 0);
    }

    /** Advance only after every failed item has a durable, isolated retry checkpoint. */
    public void completed(ResourceHubTask task, int pageSize, int attempted, int failed, int deferred) {
        if (deferred < 0 || deferred > failed) throw new IllegalArgumentException("Invalid deferred failure count");
        ObjectNode payload = read(task);
        if (!"RANGE".equals(payload.path("crawl").path("mode").asText())) {
            return;
        }
        ObjectNode crawl = (ObjectNode) payload.path("crawl");
        crawl.remove(java.util.List.of("nextPage", "nextOffset"));
        crawl.put("pageSize", pageSize);
        crawl.put("attempted", attempted);
        crawl.put("failed", failed);
        crawl.put("deferred", deferred);
        if (failed == deferred) {
            int page = payload.path("page").asInt();
            int nextOffset = crawl.path("offset").asInt() + attempted;
            int nextPage = page;
            if (nextOffset >= pageSize) {
                // An empty upstream page ends this pass, even if the configured end is larger.
                int effectiveEnd = crawl.path("endPage").asInt();
                if ("TMDB".equals(task.getSource()) && crawl.has("observedTotalPages")) {
                    effectiveEnd = Math.min(effectiveEnd, crawl.path("observedTotalPages").asInt());
                }
                nextPage = pageSize == 0 || page >= effectiveEnd ? crawl.path("startPage").asInt() : page + 1;
                nextOffset = 0;
            }
            crawl.put("nextPage", nextPage);
            crawl.put("nextOffset", nextOffset);
        }
        task.setPayload(payload.toString());
    }

    /** Only a validated TMDB response may establish a durable empty-range cooldown. */
    public void completedTmdb(ResourceHubTask task, TmdbListPage page, int attempted, int failed) {
        ObjectNode payload = read(task);
        if (!"RANGE".equals(payload.path("crawl").path("mode").asText())) return;
        ObjectNode crawl = (ObjectNode) payload.path("crawl");
        crawl.put("observedTotalPages", page.totalPages());
        crawl.put("observedTotalResults", page.totalResults());
        crawl.put("availability", "AVAILABLE");
        crawl.remove("nextCheckAt");
        task.setPayload(payload.toString());
        completed(task, page.items().size(), attempted, failed);
        if (page.items().isEmpty() && failed == 0) {
            payload = read(task);
            crawl = (ObjectNode) payload.path("crawl");
            boolean emptyRange = crawl.path("startPage").asInt() > page.totalPages();
            crawl.put("availability", emptyRange ? "EMPTY_RANGE" : "RANGE_EXHAUSTED");
            if (emptyRange) crawl.put("nextCheckAt", clock.instant().plus(EMPTY_RANGE_RECHECK).toString());
            task.setPayload(payload.toString());
        }
    }

    private Instant recheckAt(JsonNode crawl) {
        String value = crawl.path("nextCheckAt").asText(null);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid metadata crawl recheck time", e);
        }
    }

    private ObjectNode read(ResourceHubTask task) {
        try {
            JsonNode payload = mapper.readTree(task.getPayload() == null ? "{}" : task.getPayload());
            if (payload instanceof ObjectNode node) {
                return node;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid metadata crawl checkpoint", e);
        }
        throw new IllegalArgumentException("Invalid metadata crawl checkpoint");
    }
}
