package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable per-item metadata/P2P retries, isolated from video transfers and publishing. */
@Service
public class GyingMetadataItemRetryService {
    static final String TYPE = "METADATA_ITEM_RETRY";
    private final ResourceHubProperties properties;
    private final IResourceHubTaskService tasks;
    private final GyingSourceWorkflowService workflow;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();

    public GyingMetadataItemRetryService(ResourceHubProperties properties, IResourceHubTaskService tasks,
            GyingSourceWorkflowService workflow, ObjectMapper mapper) {
        this.properties = properties; this.tasks = tasks; this.workflow = workflow; this.mapper = mapper;
    }

    public synchronized List<Long> defer(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty() || items.size() > 20) throw new IllegalArgumentException("Bounded failed items required");
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> input : items) {
            Map<String, Object> item = safeItem(input);
            // Hash the exact opaque ID; the queue keyword column itself is case-insensitive.
            String key = ResourceHubHashUtils.sha256(item.get("typeCode") + ":" + item.get("mid"));
            ResourceHubTask existing = tasks.getOne(new QueryWrapper<ResourceHubTask>()
                    .eq("task_type", TYPE).eq("source", "GYING").eq("keyword", key)
                    .in("status", List.of("PENDING", "RUNNING", "FAILED"))
                    .orderByDesc("id").last("LIMIT 1"), false);
            if (existing != null) {
                // Exhausted items remain reviewable; never reset their retry budget on every catalog pass.
                if (existing.getId() == null) throw new IllegalStateException("Retry checkpoint has no ID");
                ids.add(existing.getId()); continue;
            }
            ResourceHubTask task = new ResourceHubTask();
            task.setTaskType(TYPE); task.setSource("GYING"); task.setKeyword(key);
            task.setPriority(1); task.setMaxAttempts(3);
            task.setScheduledAt(LocalDateTime.now().plusMinutes(30));
            task.setPayload(json(item));
            ResourceHubTask saved = tasks.enqueue(task);
            if (saved == null || saved.getId() == null) throw new IllegalStateException("Failed item was not persisted");
            ids.add(saved.getId());
        }
        return List.copyOf(ids);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedItems() {
        tasks.update(new UpdateWrapper<ResourceHubTask>().eq("task_type", TYPE).eq("source", "GYING")
                .eq("status", "RUNNING").set("status", "FAILED")
                .set("last_error", "Interrupted metadata item; idempotent retry pending")
                .set("scheduled_at", LocalDateTime.now().plusMinutes(1)).set("updated_at", LocalDateTime.now()));
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 45000)
    public void runDue() {
        if (!properties.isEnabled() || !properties.getWorker().isEnabled()
                || !properties.getGying().isAutoSyncEnabled() || !running.compareAndSet(false, true)) return;
        try {
            List<ResourceHubTask> due = tasks.list(new QueryWrapper<ResourceHubTask>()
                    .eq("task_type", TYPE).eq("source", "GYING").in("status", List.of("PENDING", "FAILED"))
                    .apply("attempts < max_attempts").le("scheduled_at", LocalDateTime.now())
                    .orderByAsc("scheduled_at", "id").last("LIMIT 1"));
            for (ResourceHubTask task : due) runTask(task);
        } finally { running.set(false); }
    }

    void runTask(ResourceHubTask task) {
        if (!TYPE.equals(task.getTaskType()) || !"GYING".equals(task.getSource())) throw new IllegalArgumentException("Not a metadata item retry");
        int attempt = (task.getAttempts() == null ? 0 : task.getAttempts()) + 1;
        if (!tasks.update(new UpdateWrapper<ResourceHubTask>().eq("id", task.getId()).eq("task_type", TYPE)
                .in("status", List.of("PENDING", "FAILED")).apply("attempts < max_attempts")
                .set("status", "RUNNING").set("attempts", attempt)
                .set("started_at", LocalDateTime.now()).set("updated_at", LocalDateTime.now()))) return;
        task.setAttempts(attempt);
        try {
            Map<String, Object> item = safeItem(mapper.readValue(task.getPayload(), new TypeReference<Map<String, Object>>() {}));
            workflow.retryCatalogItem(item);
            task.setStatus("SUCCEEDED"); task.setLastError(null);
        } catch (Exception error) {
            task.setStatus("FAILED");
            task.setLastError("Isolated metadata/P2P retry failed (" + error.getClass().getSimpleName() + ")");
            task.setScheduledAt(LocalDateTime.now().plusMinutes(30));
        }
        task.setFinishedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now());
        if (!tasks.update(task, new UpdateWrapper<ResourceHubTask>().eq("id", task.getId())
                .eq("task_type", TYPE).set("last_error", task.getLastError()))) {
            throw new IllegalStateException("Metadata retry result was not persisted");
        }
    }

    static Map<String, Object> safeItem(Map<String, Object> input) {
        String type = Objects.toString(input.get("typeCode"), "");
        String mid = Objects.toString(input.get("mid"), "");
        String title = Objects.toString(input.get("title"), "").trim();
        if (!Set.of("mv", "tv", "ac").contains(type) || !mid.matches("[A-Za-z0-9]{1,100}")
                || title.isEmpty() || title.length() > 255) throw new IllegalArgumentException("Invalid failed metadata item");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("typeCode", type); item.put("mid", mid); item.put("title", title);
        for (String field : List.of("year", "season")) {
            if (input.get(field) instanceof Number number) item.put(field, number.intValue());
        }
        String stage = Objects.toString(input.get("stage"), "METADATA");
        item.put("stage", "P2P".equals(stage) ? "P2P" : "METADATA");
        String category = Objects.toString(input.get("errorCategory"), "Unknown");
        item.put("errorCategory", category.matches("[A-Za-z0-9_$]{1,100}") ? category : "Unknown");
        item.put("version", 1);
        return item;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid retry payload"); }
    }
}
