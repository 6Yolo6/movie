package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single backend instance. Never consumes generic discovery/transfer queues. */
@Service
public class GyingWeeklyTransferService {
    private static final Logger log = LoggerFactory.getLogger(GyingWeeklyTransferService.class);
    private final ResourceHubProperties properties;
    private final GyingWeeklyTransferSettings settings;
    private final GyingSourceWorkflowService workflow;
    private final IResourceHubTaskService tasks;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();

    public GyingWeeklyTransferService(ResourceHubProperties properties, GyingWeeklyTransferSettings settings,
            GyingSourceWorkflowService workflow, IResourceHubTaskService tasks, ObjectMapper mapper) {
        this.properties = properties; this.settings = settings; this.workflow = workflow;
        this.tasks = tasks; this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${resource-hub.worker.fixed-delay-ms:60000}")
    public void runDue() {
        if (!properties.isEnabled() || !properties.getWorker().isEnabled() || !running.compareAndSet(false, true)) return;
        try { runOne(); }
        catch (Exception e) { log.warn("Weekly GYING workflow failed: {}", e.getClass().getSimpleName()); }
        finally { running.set(false); }
    }

    private void runOne() throws Exception {
        var schedule = settings.claimDue();
        if (schedule == null) return;
        var task = new ResourceHubTask();
        task.setTaskType("WEEKLY_TRANSFER"); task.setSource("GYING_WEEKLY"); task.setKeyword(schedule.getTypeCode());
        task.setStatus("RUNNING"); task.setStartedAt(LocalDateTime.now());
        task.setPayload(mapper.writeValueAsString(Map.of("typeCode", schedule.getTypeCode(),
                "period", "week", "maxItems", schedule.getMaxItems())));
        var items = new ArrayList<Map<String, Object>>();
        String status = "SUCCEEDED";
        try {
            ResourceHubTask persisted = tasks.enqueue(task);
            if (persisted == null || persisted.getId() == null) {
                throw new IllegalStateException("Weekly audit task was not persisted");
            }
            task.setId(persisted.getId());
            var candidates = workflow.weeklyPopularCandidates(schedule.getTypeCode(), schedule.getMaxItems());
            if (candidates == null || candidates.isEmpty()) throw new IllegalStateException("Weekly chart is empty");
            var seen = new HashSet<String>();
            for (var candidate : candidates) {
                if (seen.size() >= schedule.getMaxItems()) break;
                String mid = candidate.get("mid") instanceof String text ? text : "";
                if (!schedule.getTypeCode().equals(candidate.get("typeCode")) || !mid.matches("[A-Za-z0-9]+")) {
                    throw new IllegalStateException("Weekly chart identity mismatch");
                }
                if (!seen.add(mid)) continue;
                try {
                    var outcome = workflow.ensureWeeklyMovieResource(schedule.getTypeCode(), mid);
                    String itemStatus = String.valueOf(outcome.getOrDefault("status", "UNKNOWN"));
                    items.add(Map.of("mid", mid, "status", itemStatus));
                    if (!Set.of("PUBLISHED", "ALREADY_PUBLISHED").contains(itemStatus)) status = "FAILED";
                } catch (Exception e) {
                    items.add(Map.of("mid", mid, "status", "FAILED", "errorType", e.getClass().getSimpleName()));
                    status = "FAILED";
                }
            }
        } catch (Exception e) {
            status = "FAILED"; task.setLastError("Weekly workflow: " + e.getClass().getSimpleName());
        } finally {
            task.setStatus(status); task.setFinishedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now());
            task.setPayload(mapper.writeValueAsString(Map.of("typeCode", schedule.getTypeCode(), "period", "week",
                    "maxItems", schedule.getMaxItems(), "items", items)));
            if (task.getId() != null) tasks.updateById(task);
            settings.finished(schedule.getTypeCode(), status, task.getId());
        }
    }
}
