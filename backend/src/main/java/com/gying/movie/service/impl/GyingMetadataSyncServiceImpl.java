package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IGyingMetadataSyncService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.IResourceLinkService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GyingMetadataSyncServiceImpl implements IGyingMetadataSyncService {

    private static final Set<String> SOURCES = Set.of("HITS_MOVIE", "HITS_TV", "HITS_ANIME");

    private final ResourceHubProperties properties;
    private final IResourceHubTaskService taskService;
    private final GyingSourceWorkflowService workflowService;
    private final IResourceDiscoveryService resourceDiscoveryService;
    private final IResourceLinkService resourceLinkService;
    private final ObjectMapper objectMapper;

    public GyingMetadataSyncServiceImpl(
            ResourceHubProperties properties,
            IResourceHubTaskService taskService,
            GyingSourceWorkflowService workflowService,
            IResourceDiscoveryService resourceDiscoveryService,
            IResourceLinkService resourceLinkService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.taskService = taskService;
        this.workflowService = workflowService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.resourceLinkService = resourceLinkService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResourceHubTask enqueue(String source, int page, int maxItems) {
        ensureEnabled();
        SyncPayload payload = normalize(source, page, maxItems);
        ResourceHubTask task = new ResourceHubTask();
        task.setTaskType("METADATA_SYNC");
        task.setSource("GYING");
        task.setKeyword(payload.source());
        task.setPayload(writePayload(payload));
        task.setPriority(9);
        return taskService.enqueue(task);
    }

    @Override
    public TmdbSyncResult runTask(Long taskId) {
        ensureEnabled();
        ResourceHubTask task = taskService.getById(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource Hub task not found");
        }
        if (!"METADATA_SYNC".equalsIgnoreCase(task.getTaskType())
                || !"GYING".equalsIgnoreCase(task.getSource())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task is not a GYING metadata sync task");
        }

        SyncPayload payload = readPayload(task);
        TmdbSyncResult result = new TmdbSyncResult();
        result.setTaskId(task.getId());
        result.setSource("GYING_" + payload.source());
        result.setPage(payload.page());
        result.setRequested(payload.maxItems());
        markRunning(task);
        try {
            Map<String, Object> synced = workflowService.syncCatalogMetadata(
                    payload.source(), payload.page(), payload.maxItems());
            result.setProcessed(number(synced.get("processed")));
            result.setInserted(number(synced.get("inserted")));
            result.setUpdated(number(synced.get("linked")));
            result.setFailed(number(synced.get("failed")));
            addErrors(result, synced.get("errors"));
            enqueueDiscoveryTasks(result, synced.get("movieIds"));
            String status = result.getProcessed() == 0 && result.getFailed() > 0 ? "FAILED" : "SUCCEEDED";
            finishTask(task, status, result.getFailed() > 0
                    ? result.getFailed() + " GYING item(s) failed during sync"
                    : null);
        } catch (Exception error) {
            finishTask(task, "FAILED", error.getMessage());
            result.setFailed(Math.max(result.getFailed(), 1));
            result.getErrors().add(trim(error.getMessage(), 500));
        }
        result.setStatus(task.getStatus());
        return result;
    }

    private SyncPayload normalize(String source, int page, int maxItems) {
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported GYING catalog source: " + source);
        }
        return new SyncPayload(normalized, clamp(page, 1, 500), clamp(maxItems, 1, 20));
    }

    private SyncPayload readPayload(ResourceHubTask task) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    task.getPayload(), new TypeReference<Map<String, Object>>() { });
            return normalize(
                    (String) payload.get("source"),
                    number(payload.get("page")),
                    number(payload.get("maxItems")));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid GYING metadata sync task payload");
        }
    }

    private String writePayload(SyncPayload payload) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source", payload.source());
            value.put("page", payload.page());
            value.put("maxItems", payload.maxItems());
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize GYING sync payload", error);
        }
    }

    private void markRunning(ResourceHubTask task) {
        LocalDateTime now = LocalDateTime.now();
        task.setStatus("RUNNING");
        task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
        task.setStartedAt(now);
        task.setFinishedAt(null);
        task.setLastError(null);
        task.setUpdatedAt(now);
        taskService.updateById(task);
    }

    private void finishTask(ResourceHubTask task, String status, String error) {
        task.setStatus(status);
        task.setLastError(trim(error, 1000));
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskService.updateById(task);
    }

    private void addErrors(TmdbSyncResult result, Object value) {
        if (!(value instanceof List<?> values)) {
            return;
        }
        values.stream().map(String::valueOf).limit(10).forEach(result.getErrors()::add);
    }

    private void enqueueDiscoveryTasks(TmdbSyncResult result, Object value) {
        if (!properties.getGying().isDiscoveryEnabled() || !(value instanceof List<?> movieIds)) {
            return;
        }
        int cooldownHours = Math.max(properties.getTmdb().getDiscoveryCooldownHours(), 1);
        for (Object rawMovieId : movieIds) {
            String movieId = rawMovieId == null ? "" : String.valueOf(rawMovieId).trim();
            if (movieId.isEmpty() || hasPublishableResource(movieId)
                    || hasRecentDiscoveryTask(movieId, cooldownHours)) {
                result.setDiscoveryTasksSkipped(result.getDiscoveryTasksSkipped() + 1);
                continue;
            }
            try {
                ResourceDiscoveryRequest request = new ResourceDiscoveryRequest();
                request.setMovieId(movieId);
                request.setSource("AUTO");
                request.setMaxResults(Math.min(Math.max(
                        properties.getTmdb().getDiscoveryMaxResults(), 1), 50));
                resourceDiscoveryService.enqueue(request);
                result.setDiscoveryTasksCreated(result.getDiscoveryTasksCreated() + 1);
            } catch (Exception error) {
                result.setDiscoveryTasksSkipped(result.getDiscoveryTasksSkipped() + 1);
                result.getErrors().add(trim("discovery task " + movieId + ": " + error.getMessage(), 500));
            }
        }
    }

    private boolean hasPublishableResource(String movieId) {
        return resourceLinkService.count(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("type", "DISK")
                .eq("status", "ACTIVE")
                .isNull("deleted_at")
                .and(query -> query.isNull("link_status").or().ne("link_status", "INVALID"))) > 0;
    }

    private boolean hasRecentDiscoveryTask(String movieId, int cooldownHours) {
        return taskService.count(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "RESOURCE_DISCOVERY")
                .eq("movie_id", movieId)
                .ge("created_at", LocalDateTime.now().minusHours(cooldownHours))) > 0;
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Hub is disabled");
        }
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private record SyncPayload(String source, int page, int maxItems) {
    }
}
