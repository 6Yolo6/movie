package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.dto.MetadataCrawlProgress;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;

/** The task payload is the checkpoint: position and completion are saved with task status. */
public final class MetadataCrawlPlanner {
    private final IResourceHubTaskService tasks;
    private final ObjectMapper mapper;

    public MetadataCrawlPlanner(IResourceHubTaskService tasks, ObjectMapper mapper) {
        this.tasks = tasks;
        this.mapper = mapper;
    }

    public record Position(int startPage, int endPage, int page, int offset, String status, Long taskId) {}

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
        if (crawl.path("startPage").asInt() != start || crawl.path("endPage").asInt() != end) {
            return new Position(start, end, start, 0, "NOT_STARTED", null);
        }
        boolean complete = "SUCCEEDED".equals(latest.getStatus()) && crawl.has("nextPage");
        int page = complete ? crawl.path("nextPage").asInt(start) : payload.path("page").asInt(start);
        int offset = complete ? crawl.path("nextOffset").asInt() : crawl.path("offset").asInt();
        if (page < start || page > end || offset < 0 || offset > 10000) {
            return new Position(start, end, start, 0, "NOT_STARTED", null);
        }
        String status = "SUCCEEDED".equals(latest.getStatus()) && !complete ? "RETRY" : latest.getStatus();
        return new Position(start, end, page, offset, status, latest.getId());
    }

    public MetadataCrawlProgress progress(String provider, String source, int start, int end) {
        Position p = next(provider, source, start, end);
        return new MetadataCrawlProgress(provider, source, start, end, p.page(), p.offset() + 1,
                p.status(), p.taskId());
    }

    public String attach(String payload, Position position) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(payload);
            ObjectNode crawl = node.putObject("crawl");
            crawl.put("mode", "RANGE");
            crawl.put("startPage", position.startPage());
            crawl.put("endPage", position.endPage());
            crawl.put("offset", position.offset());
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
        ObjectNode payload = read(task);
        if (!"RANGE".equals(payload.path("crawl").path("mode").asText())) {
            return;
        }
        ObjectNode crawl = (ObjectNode) payload.path("crawl");
        crawl.remove(java.util.List.of("nextPage", "nextOffset"));
        crawl.put("pageSize", pageSize);
        crawl.put("attempted", attempted);
        crawl.put("failed", failed);
        if (failed == 0) {
            int page = payload.path("page").asInt();
            int nextOffset = crawl.path("offset").asInt() + attempted;
            int nextPage = page;
            if (nextOffset >= pageSize) {
                // An empty upstream page ends this pass, even if the configured end is larger.
                nextPage = pageSize == 0 || page >= crawl.path("endPage").asInt()
                        ? crawl.path("startPage").asInt() : page + 1;
                nextOffset = 0;
            }
            crawl.put("nextPage", nextPage);
            crawl.put("nextOffset", nextOffset);
        }
        task.setPayload(payload.toString());
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
