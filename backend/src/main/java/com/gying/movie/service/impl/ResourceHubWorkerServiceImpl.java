package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceHubMetadataSyncRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.dto.ResourceHubWorkerResult;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IGyingMetadataSyncService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.IResourceHubWorkerService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ResourceHubWorkerServiceImpl implements IResourceHubWorkerService {

    private static final int MAX_TASK_LIMIT = 20;
    private static final int MAX_QUARK_LIMIT = 20;
    private static final int MAX_PUBLISH_LIMIT = 100;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ResourceHubProperties resourceHubProperties;
    private final IResourceHubTaskService taskService;
    private final ITmdbMetadataSyncService tmdbMetadataSyncService;
    private final IGyingMetadataSyncService gyingMetadataSyncService;
    private final IResourceDiscoveryService resourceDiscoveryService;
    private final IQuarkTransferRunnerService quarkTransferRunnerService;
    private final IXunleiTransferRunnerService xunleiTransferRunnerService;
    private final IResourceHubPublishService resourceHubPublishService;
    private final IResourceHubConfigService resourceHubConfigService;

    @Autowired
    public ResourceHubWorkerServiceImpl(
            ResourceHubProperties resourceHubProperties,
            IResourceHubTaskService taskService,
            ITmdbMetadataSyncService tmdbMetadataSyncService,
            IGyingMetadataSyncService gyingMetadataSyncService,
            IResourceDiscoveryService resourceDiscoveryService,
            IQuarkTransferRunnerService quarkTransferRunnerService,
            IXunleiTransferRunnerService xunleiTransferRunnerService,
            IResourceHubPublishService resourceHubPublishService,
            IResourceHubConfigService resourceHubConfigService) {
        this.resourceHubProperties = resourceHubProperties;
        this.taskService = taskService;
        this.tmdbMetadataSyncService = tmdbMetadataSyncService;
        this.gyingMetadataSyncService = gyingMetadataSyncService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.quarkTransferRunnerService = quarkTransferRunnerService;
        this.xunleiTransferRunnerService = xunleiTransferRunnerService;
        this.resourceHubPublishService = resourceHubPublishService;
        this.resourceHubConfigService = resourceHubConfigService;
    }

    @Override
    public ResourceHubWorkerResult runOnce() {
        return runOnce(false);
    }

    @Override
    public ResourceHubWorkerResult runOnce(boolean force) {
        resourceHubConfigService.reload();
        ResourceHubWorkerResult result = baseResult(force);
        if (!resourceHubProperties.isEnabled()) {
            return skip(result, "Resource Hub is disabled");
        }
        if (!force && !resourceHubProperties.getWorker().isEnabled()) {
            return skip(result, "Resource Hub worker is disabled");
        }
        if (!running.compareAndSet(false, true)) {
            return skip(result, "Resource Hub worker is already running");
        }
        result.setStartedAt(LocalDateTime.now());
        try {
            enqueueTmdbAutoSyncTasks(result);
            enqueueGyingAutoSyncTasks(result);
            runDueTasks(result);
            runQuarkTransfers(result);
            runXunleiTransfers(result);
            publishResources(result);
        } finally {
            result.setFinishedAt(LocalDateTime.now());
            running.set(false);
        }
        return result;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void enqueueTmdbAutoSyncTasks(ResourceHubWorkerResult result) {
        ResourceHubProperties.Tmdb tmdb = resourceHubProperties.getTmdb();
        if (!tmdb.isAutoSyncEnabled() || !hasText(tmdb.getApiKey())) {
            return;
        }
        List<String> sources = autoSyncSources(tmdb.getAutoSyncSources());
        if (sources.isEmpty() || hasActiveMetadataSyncTask(sources)) {
            return;
        }
        ResourceHubTask latestTask = latestMetadataSyncTask(sources);
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(tmdb.getAutoSyncIntervalHours(), 1));
        if (latestTask != null && latestTask.getCreatedAt() != null && !latestTask.getCreatedAt().isBefore(since)) {
            return;
        }
        String source = nextAutoSyncSource(sources, latestTask == null ? null : latestTask.getKeyword());
        try {
            ResourceHubMetadataSyncRequest request = new ResourceHubMetadataSyncRequest();
            request.setSource(source);
            request.setPage(tmdb.getAutoSyncPage());
            request.setMaxItems(tmdb.getAutoSyncMaxItems());
            tmdbMetadataSyncService.enqueue(request);
            result.setMetadataSyncTasksCreated(result.getMetadataSyncTasksCreated() + 1);
        } catch (Exception e) {
            addError(result, "tmdb auto sync " + source + ": " + e.getMessage());
        }
    }

    public ResourceHubWorkerServiceImpl(ResourceHubProperties p, IResourceHubTaskService t,
            ITmdbMetadataSyncService tm, IGyingMetadataSyncService gm, IResourceDiscoveryService d,
            IQuarkTransferRunnerService q, IResourceHubPublishService pub, IResourceHubConfigService c) {
        this(p, t, tm, gm, d, q, null, pub, c);
    }

    private boolean hasActiveMetadataSyncTask(List<String> sources) {
        return taskService.count(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC")
                .eq("source", "TMDB")
                .in("keyword", sources)
                .in("status", List.of("PENDING", "RUNNING"))) > 0;
    }

    private void enqueueGyingAutoSyncTasks(ResourceHubWorkerResult result) {
        ResourceHubProperties.Gying gying = resourceHubProperties.getGying();
        if (!gying.isAutoSyncEnabled()) {
            return;
        }
        List<String> sources = gyingAutoSyncSources(gying.getAutoSyncSources());
        if (sources.isEmpty() || hasActiveGyingMetadataSyncTask(sources)) {
            return;
        }
        ResourceHubTask latestTask = latestGyingMetadataSyncTask(sources);
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(gying.getAutoSyncIntervalHours(), 1));
        if (latestTask != null && latestTask.getCreatedAt() != null && !latestTask.getCreatedAt().isBefore(since)) {
            return;
        }
        String source = nextAutoSyncSource(sources, latestTask == null ? null : latestTask.getKeyword());
        try {
            gyingMetadataSyncService.enqueue(source, gying.getAutoSyncPage(), gying.getAutoSyncMaxItems());
            result.setMetadataSyncTasksCreated(result.getMetadataSyncTasksCreated() + 1);
        } catch (Exception error) {
            addError(result, "gying auto sync " + source + ": " + error.getMessage());
        }
    }

    private boolean hasActiveGyingMetadataSyncTask(List<String> sources) {
        return taskService.count(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC")
                .eq("source", "GYING")
                .in("keyword", sources)
                .in("status", List.of("PENDING", "RUNNING"))) > 0;
    }

    private ResourceHubTask latestGyingMetadataSyncTask(List<String> sources) {
        return taskService.getOne(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC")
                .eq("source", "GYING")
                .in("keyword", sources)
                .orderByDesc("created_at")
                .orderByDesc("id")
                .last("LIMIT 1"), false);
    }

    private ResourceHubTask latestMetadataSyncTask(List<String> sources) {
        return taskService.getOne(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC")
                .eq("source", "TMDB")
                .in("keyword", sources)
                .orderByDesc("created_at")
                .orderByDesc("id")
                .last("LIMIT 1"), false);
    }

    static String nextAutoSyncSource(List<String> sources, String latestSource) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("At least one TMDB source is required");
        }
        if (latestSource == null || latestSource.isBlank()) {
            return sources.get(0);
        }
        int currentIndex = sources.indexOf(latestSource.trim().toUpperCase());
        return sources.get(currentIndex < 0 ? 0 : (currentIndex + 1) % sources.size());
    }

    private List<String> autoSyncSources(String raw) {
        if (!hasText(raw)) {
            return List.of("TRENDING_MOVIE_DAY", "TRENDING_TV_DAY", "POPULAR_MOVIE", "POPULAR_TV");
        }
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(this::hasText)
                .map(String::toUpperCase)
                .distinct()
                .toList();
    }

    private List<String> gyingAutoSyncSources(String raw) {
        if (!hasText(raw)) {
            return List.of("HITS_MOVIE", "HITS_TV", "HITS_ANIME");
        }
        List<String> supported = List.of("HITS_MOVIE", "HITS_TV", "HITS_ANIME");
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(this::hasText)
                .map(String::toUpperCase)
                .filter(supported::contains)
                .distinct()
                .toList();
    }

    private void runDueTasks(ResourceHubWorkerResult result) {
        List<ResourceHubTask> tasks = taskService.list(new QueryWrapper<ResourceHubTask>()
                .eq("status", "PENDING")
                .le("scheduled_at", LocalDateTime.now())
                .orderByDesc("priority")
                .orderByAsc("scheduled_at")
                .orderByAsc("created_at")
                .last("LIMIT " + result.getTaskLimit()));
        for (ResourceHubTask task : tasks) {
            runTask(task, result);
        }
    }

    private void runTask(ResourceHubTask task, ResourceHubWorkerResult result) {
        ResourceHubWorkerResult.TaskResult taskResult = new ResourceHubWorkerResult.TaskResult();
        taskResult.setTaskId(task.getId());
        taskResult.setTaskType(task.getTaskType());
        result.getTasks().add(taskResult);
        result.setTasksProcessed(result.getTasksProcessed() + 1);
        try {
            if ("METADATA_SYNC".equalsIgnoreCase(task.getTaskType())) {
                TmdbSyncResult syncResult = "GYING".equalsIgnoreCase(task.getSource())
                        ? gyingMetadataSyncService.runTask(task.getId())
                        : tmdbMetadataSyncService.runTask(task.getId());
                taskResult.setStatus(syncResult.getStatus());
                if ("SUCCEEDED".equalsIgnoreCase(syncResult.getStatus())) {
                    result.setTasksSucceeded(result.getTasksSucceeded() + 1);
                } else {
                    result.setTasksFailed(result.getTasksFailed() + 1);
                    taskResult.setError(firstError(syncResult.getErrors()));
                }
                return;
            }
            if ("RESOURCE_DISCOVERY".equalsIgnoreCase(task.getTaskType())) {
                ResourceDiscoveryRunResult discoveryResult = resourceDiscoveryService.runTask(task.getId());
                taskResult.setStatus(discoveryResult.getStatus());
                if ("SUCCEEDED".equalsIgnoreCase(discoveryResult.getStatus())) {
                    result.setTasksSucceeded(result.getTasksSucceeded() + 1);
                } else {
                    result.setTasksFailed(result.getTasksFailed() + 1);
                    taskResult.setError(firstError(discoveryResult.getErrors()));
                }
                return;
            }
            failUnknownTask(task, taskResult);
            result.setTasksFailed(result.getTasksFailed() + 1);
            addError(result, taskResult.getError());
        } catch (Exception e) {
            failTask(task, e.getMessage());
            taskResult.setStatus("FAILED");
            taskResult.setError(trim(e.getMessage(), 500));
            result.setTasksFailed(result.getTasksFailed() + 1);
            addError(result, "task " + task.getId() + ": " + e.getMessage());
        }
    }

    private void runQuarkTransfers(ResourceHubWorkerResult result) {
        try {
            QuarkTransferRunResult quarkResult = quarkTransferRunnerService.submitPending(result.getQuarkLimit());
            result.setQuarkTransfers(quarkResult);
            for (String error : quarkResult.getErrors()) {
                addError(result, error);
            }
        } catch (Exception e) {
            QuarkTransferRunResult failed = new QuarkTransferRunResult();
            failed.setFailed(1);
            addError(failed, e.getMessage());
            result.setQuarkTransfers(failed);
            addError(result, "quark transfers: " + e.getMessage());
        }
    }

    private void runXunleiTransfers(ResourceHubWorkerResult result) {
        try {
            if (xunleiTransferRunnerService == null) return;
            QuarkTransferRunResult xunlei = xunleiTransferRunnerService.submitPending(resourceHubProperties.getWorker().getXunleiLimit());
            for (String error : xunlei.getErrors()) addError(result, "xunlei transfers: " + error);
        } catch (Exception e) { addError(result, "xunlei transfers: " + e.getMessage()); }
    }

    private void publishResources(ResourceHubWorkerResult result) {
        try {
            ResourceHubPublishResult publishResult = resourceHubPublishService.publishPending(result.getPublishLimit());
            result.setPublishedResources(publishResult);
            for (String error : publishResult.getErrors()) {
                addError(result, error);
            }
        } catch (Exception e) {
            ResourceHubPublishResult failed = new ResourceHubPublishResult();
            failed.setFailed(1);
            addError(failed, e.getMessage());
            result.setPublishedResources(failed);
            addError(result, "publish resources: " + e.getMessage());
        }
    }

    private void failUnknownTask(ResourceHubTask task, ResourceHubWorkerResult.TaskResult taskResult) {
        String message = "Unsupported Resource Hub task type: " + task.getTaskType();
        failTask(task, message);
        taskResult.setStatus("FAILED");
        taskResult.setError(message);
    }

    private void failTask(ResourceHubTask task, String message) {
        LocalDateTime now = LocalDateTime.now();
        task.setStatus("FAILED");
        task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
        task.setLastError(trim(message, 1000));
        task.setFinishedAt(now);
        task.setUpdatedAt(now);
        taskService.updateById(task);
    }

    private ResourceHubWorkerResult baseResult(boolean force) {
        ResourceHubWorkerResult result = new ResourceHubWorkerResult();
        ResourceHubProperties.Worker worker = resourceHubProperties.getWorker();
        result.setEnabled(resourceHubProperties.isEnabled() && (force || worker.isEnabled()));
        result.setTaskLimit(clamp(worker.getTaskLimit(), 1, MAX_TASK_LIMIT));
        result.setQuarkLimit(clamp(worker.getQuarkLimit(), 1, MAX_QUARK_LIMIT));
        result.setPublishLimit(clamp(worker.getPublishLimit(), 1, MAX_PUBLISH_LIMIT));
        return result;
    }

    private ResourceHubWorkerResult skip(ResourceHubWorkerResult result, String reason) {
        result.setSkipped(true);
        result.setReason(reason);
        result.setStartedAt(LocalDateTime.now());
        result.setFinishedAt(result.getStartedAt());
        return result;
    }

    private String firstError(List<String> errors) {
        return errors == null || errors.isEmpty() ? null : trim(errors.get(0), 500);
    }

    private void addError(ResourceHubWorkerResult result, String message) {
        if (result.getErrors().size() < 10 && message != null && !message.isBlank()) {
            result.getErrors().add(trim(message, 500));
        }
    }

    private void addError(QuarkTransferRunResult result, String message) {
        if (result.getErrors().size() < 10 && message != null && !message.isBlank()) {
            result.getErrors().add(trim(message, 500));
        }
    }

    private void addError(ResourceHubPublishResult result, String message) {
        if (result.getErrors().size() < 10 && message != null && !message.isBlank()) {
            result.getErrors().add(trim(message, 500));
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
