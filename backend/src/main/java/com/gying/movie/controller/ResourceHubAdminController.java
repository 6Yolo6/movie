package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.client.QqChannelPublisherClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceHubConfigRequest;
import com.gying.movie.dto.ResourceHubConfigResponse;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.dto.ResourceHubMetadataSyncRequest;
import com.gying.movie.dto.ResourceHubWorkerResult;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.QqChannelPostLog;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.UserFavorite;
import com.gying.movie.entity.Comment;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.IResourceHubWorkerService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IQqAutomationConfigService;
import com.gying.movie.service.IQqChannelPostLogService;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IUserFavoriteService;
import com.gying.movie.service.ICommentService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import com.gying.movie.service.impl.GyingSourceWorkflowService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.ResourceHubHashUtils;
import com.gying.movie.utils.SeasonSearchUtils;
import com.gying.movie.utils.ResourceTitleMatcher;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/resource-hub")
public class ResourceHubAdminController {
    private static final int MISSING_RESOURCE_BATCH_LIMIT = 20;

    private final AuthHelper authHelper;
    private final ResourceHubProperties resourceHubProperties;
    private final IResourceHubTaskService taskService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final ITmdbMetadataSyncService tmdbMetadataSyncService;
    private final IResourceDiscoveryService resourceDiscoveryService;
    private final IQuarkTransferRunnerService quarkTransferRunnerService;
    private final IResourceHubPublishService resourceHubPublishService;
    private final IResourceHubWorkerService resourceHubWorkerService;
    private final IResourceHubConfigService resourceHubConfigService;
    private final IResourceLinkService resourceLinkService;
    private final IQqAutomationConfigService qqAutomationConfigService;
    private final IQqChannelPostLogService qqChannelPostLogService;
    private final IMovieMetadataService movieService;
    private final IUserFavoriteService favoriteService;
    private final ICommentService commentService;
    private final QqChannelPublisherClient qqChannelPublisherClient;
    private final GyingSourceWorkflowService gyingSourceWorkflowService;
    private final ExecutorService discoveryPipelineExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "resource-discovery-pipeline");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, DiscoveryPipelineJob> discoveryPipelineJobs = new ConcurrentHashMap<>();

    public ResourceHubAdminController(
            AuthHelper authHelper,
            ResourceHubProperties resourceHubProperties,
            IResourceHubTaskService taskService,
            IResourceDiscoveryResultService discoveryResultService,
            IQuarkTransferTaskService quarkTransferTaskService,
            ITmdbMetadataSyncService tmdbMetadataSyncService,
            IResourceDiscoveryService resourceDiscoveryService,
            IQuarkTransferRunnerService quarkTransferRunnerService,
            IResourceHubPublishService resourceHubPublishService,
            IResourceHubWorkerService resourceHubWorkerService,
            IResourceHubConfigService resourceHubConfigService,
            IResourceLinkService resourceLinkService,
            IQqAutomationConfigService qqAutomationConfigService,
            IQqChannelPostLogService qqChannelPostLogService,
            IMovieMetadataService movieService,
            IUserFavoriteService favoriteService,
            ICommentService commentService,
            QqChannelPublisherClient qqChannelPublisherClient,
            GyingSourceWorkflowService gyingSourceWorkflowService) {
        this.authHelper = authHelper;
        this.resourceHubProperties = resourceHubProperties;
        this.taskService = taskService;
        this.discoveryResultService = discoveryResultService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.tmdbMetadataSyncService = tmdbMetadataSyncService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.quarkTransferRunnerService = quarkTransferRunnerService;
        this.resourceHubPublishService = resourceHubPublishService;
        this.resourceHubWorkerService = resourceHubWorkerService;
        this.resourceHubConfigService = resourceHubConfigService;
        this.resourceLinkService = resourceLinkService;
        this.qqAutomationConfigService = qqAutomationConfigService;
        this.qqChannelPostLogService = qqChannelPostLogService;
        this.movieService = movieService;
        this.favoriteService = favoriteService;
        this.commentService = commentService;
        this.qqChannelPublisherClient = qqChannelPublisherClient;
        this.gyingSourceWorkflowService = gyingSourceWorkflowService;
    }

    @PreDestroy
    public void shutdownDiscoveryPipelineExecutor() {
        discoveryPipelineExecutor.shutdownNow();
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        ResourceHubConfigResponse config = resourceHubConfigService.getConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled());
        result.put("autoApprove", config.isAutoApprove());
        result.put("tmdbConfigured", config.isTmdbConfigured());
        result.put("pansouBaseUrl", nullToEmpty(resourceHubProperties.getPansou().getBaseUrl()));
        result.put("pansouApiBaseUrl", nullToEmpty(resourceHubProperties.getPansou().getApiBaseUrl()));
        result.put("pansouApiConfigured", hasText(resourceHubProperties.getPansou().getApiKey()));
        result.put("quarkBaseUrl", nullToEmpty(resourceHubProperties.getQuark().getBaseUrl()));
        result.put("xunleiEnabled", resourceHubProperties.getXunlei().isEnabled());
        result.put("xunleiConfigured", resourceHubProperties.getXunlei().isEnabled()
                && hasText(resourceHubProperties.getXunlei().getAuthorization())
                && hasText(resourceHubProperties.getXunlei().getCaptchaToken()));
        result.put("config", config);
        result.put("worker", workerStatusMap());
        result.put("taskStatusCounts", taskService.countByStatus());
        result.put("discoveredCount", discoveryResultService.count(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("status", "DISCOVERED")));
        result.put("savedDiscoveryCount", discoveryResultService.count(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("status", "DISCOVERED")
                .isNotNull("share_url")
                .isNull("resource_link_id")));
        result.put("pendingQuarkTransfers", quarkTransferTaskService.count(new QueryWrapper<QuarkTransferTask>()
                .eq("status", "PENDING")));
        result.put("collectionStats", collectionStats());
        return ApiResponse.ok(result);
    }

    @GetMapping("/config")
    public ApiResponse<ResourceHubConfigResponse> config(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(resourceHubConfigService.getConfig());
    }

    @PutMapping("/config")
    public ApiResponse<ResourceHubConfigResponse> updateConfig(
            @RequestBody(required = false) ResourceHubConfigRequest request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(resourceHubConfigService.updateConfig(request));
    }

    @GetMapping("/worker/status")
    public ApiResponse<Map<String, Object>> workerStatus(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(workerStatusMap());
    }

    @PostMapping("/worker/run-once")
    public ApiResponse<ResourceHubWorkerResult> runWorkerOnce(
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(resourceHubWorkerService.runOnce(force));
    }

    @GetMapping("/tasks")
    public ApiResponse<Page<ResourceHubTask>> tasks(
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        QueryWrapper<ResourceHubTask> query = new QueryWrapper<>();
        if (hasText(taskType)) {
            query.eq("task_type", taskType.trim().toUpperCase());
        }
        if (hasText(status)) {
            query.eq("status", status.trim().toUpperCase());
        }
        query.orderByDesc("priority").orderByAsc("scheduled_at").orderByDesc("created_at");
        Page<ResourceHubTask> result = taskService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                query);
        return ApiResponse.ok(result);
    }

    @PostMapping("/tmdb/metadata-sync")
    public ApiResponse<Object> enqueueTmdbMetadataSync(
            @RequestBody(required = false) ResourceHubMetadataSyncRequest request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        ResourceHubTask task = tmdbMetadataSyncService.enqueue(request);
        if (request != null && Boolean.TRUE.equals(request.getRunNow())) {
            TmdbSyncResult result = tmdbMetadataSyncService.runTask(task.getId());
            if ("FAILED".equalsIgnoreCase(result.getStatus())) {
                throw new IllegalStateException(result.getErrors().isEmpty()
                        ? "TMDB metadata sync failed"
                        : result.getErrors().get(0));
            }
            return ApiResponse.ok(result);
        }
        return ApiResponse.ok(task);
    }

    @PostMapping("/tmdb/metadata-sync/{taskId}/run")
    public ApiResponse<TmdbSyncResult> runTmdbMetadataSync(
            @PathVariable Long taskId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(tmdbMetadataSyncService.runTask(taskId));
    }

    @PostMapping("/discover")
    public ApiResponse<Object> enqueueResourceDiscovery(
            @RequestBody ResourceDiscoveryRequest request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        ResourceHubTask task = resourceDiscoveryService.enqueue(request);
        if (request != null && Boolean.TRUE.equals(request.getRunNow())) {
            DiscoveryPipelineJob job = new DiscoveryPipelineJob(UUID.randomUUID().toString(), task.getId());
            discoveryPipelineJobs.put(job.jobId, job);
            trimDiscoveryPipelineJobs();
            discoveryPipelineExecutor.submit(() -> runDiscoveryPipelineJob(job));
            return ApiResponse.ok(job.toResponse());
        }
        return ApiResponse.ok(task);
    }

    @GetMapping("/discover/jobs/{jobId}")
    public ApiResponse<Map<String, Object>> getDiscoveryPipelineJob(
            @PathVariable String jobId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        DiscoveryPipelineJob job = discoveryPipelineJobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Discovery pipeline job not found");
        }
        return ApiResponse.ok(job.toResponse());
    }

    @PostMapping("/discover/{taskId}/run")
    public ApiResponse<ResourceDiscoveryRunResult> runResourceDiscovery(
            @PathVariable Long taskId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(resourceDiscoveryService.runTask(taskId));
    }

    @GetMapping("/discoveries")
    public ApiResponse<Page<ResourceDiscoveryResult>> discoveries(
            @RequestParam(required = false) String movieId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        QueryWrapper<ResourceDiscoveryResult> query = new QueryWrapper<>();
        if (hasText(movieId)) {
            query.eq("movie_id", movieId.trim());
        }
        if (hasText(status)) {
            query.eq("status", status.trim().toUpperCase());
        }
        if (hasText(source)) {
            query.eq("source", source.trim().toUpperCase());
        }
        if (hasText(keyword)) {
            String text = keyword.trim();
            query.and(w -> w.like("title", text)
                    .or().like("movie_id", text)
                    .or().like("original_url", text)
                    .or().like("share_url", text));
        }
        if ("asc".equalsIgnoreCase(sortOrder)) {
            query.orderByAsc("created_at");
        } else {
            query.orderByDesc("created_at");
        }
        Page<ResourceDiscoveryResult> result = discoveryResultService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                query);
        return ApiResponse.ok(result);
    }

    @PostMapping("/discoveries/publish")
    public ApiResponse<ResourceHubPublishResult> publishDiscoveries(
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(resourceHubPublishService.publishPending(limit));
    }

    @PostMapping("/discoveries/{discoveryResultId}/publish")
    public ApiResponse<ResourceHubPublishResult> publishDiscovery(
            @PathVariable Long discoveryResultId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(resourceHubPublishService.publishDiscovery(discoveryResultId));
    }

    @PostMapping("/discoveries/{discoveryResultId}/retry-share-publish")
    public ApiResponse<Map<String, Object>> retryShareAndPublish(
            @PathVariable Long discoveryResultId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(retryShareAndPublishOne(discoveryResultId));
    }

    @PostMapping("/discoveries/batch/retry-share-publish")
    public ApiResponse<Map<String, Object>> batchRetryShareAndPublish(
            @RequestBody(required = false) List<Long> discoveryResultIds,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(runDiscoveryBatch(discoveryResultIds, this::retryShareAndPublishOne));
    }

    @PostMapping("/discoveries/reconcile")
    public ApiResponse<Map<String, Object>> reconcileDiscoveries(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "2000") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(reconcileDiscoveryStates(dryRun, limit));
    }

    @PostMapping("/discoveries/batch/publish")
    public ApiResponse<Map<String, Object>> batchPublishDiscoveries(
            @RequestBody(required = false) List<Long> discoveryResultIds,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(runDiscoveryBatch(discoveryResultIds, id -> {
            ResourceHubPublishResult published = resourceHubPublishService.publishDiscovery(id);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("discoveryResultId", id);
            item.put("published", published.getPublished());
            item.put("updated", published.getUpdated());
            item.put("failed", published.getFailed());
            item.put("errors", published.getErrors());
            return item;
        }));
    }

    @PostMapping("/discoveries/{discoveryResultId}/qq-channel-post")
    public ApiResponse<Map<String, Object>> queueDiscoveryForQqChannel(
            @PathVariable Long discoveryResultId,
            @RequestParam(defaultValue = "true") boolean runNow,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(queueDiscoveryForQqChannel(discoveryResultId, runNow));
    }

    @PostMapping("/discoveries/batch/qq-channel-post")
    public ApiResponse<Map<String, Object>> batchPostDiscoveriesToQqChannel(
            @RequestBody(required = false) List<Long> discoveryResultIds,
            @RequestParam(defaultValue = "true") boolean runNow,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(runDiscoveryBatch(
                discoveryResultIds,
                id -> {
                    Map<String, Object> item = queueDiscoveryForQqChannel(id, runNow);
                    if (runNow && !Boolean.TRUE.equals(item.get("immediate"))) {
                        Object error = item.get("error");
                        throw new IllegalStateException(error == null
                                ? "QQ channel post was queued but not posted immediately"
                                : String.valueOf(error));
                    }
                    return item;
                }));
    }

    private Map<String, Object> queueDiscoveryForQqChannel(Long discoveryResultId, boolean runNow) {
        ResourceDiscoveryResult discovery = discoveryResultService.getById(discoveryResultId);
        if (discovery == null) {
            throw new IllegalArgumentException("Discovery result not found");
        }
        if (discovery.getResourceLinkId() == null) {
            resourceHubPublishService.publishDiscovery(discoveryResultId);
            discovery = discoveryResultService.getById(discoveryResultId);
        }
        if (discovery == null || discovery.getResourceLinkId() == null) {
            throw new IllegalStateException("Discovery result has not produced a publishable resource link");
        }
        ResourceLink resource = resourceLinkService.getById(discovery.getResourceLinkId());
        if (resource == null || !"ACTIVE".equalsIgnoreCase(resource.getStatus())) {
            throw new IllegalStateException("Resource link is not active");
        }
        MovieMetadata movie = movieService.getById(resource.getMovieId());
        if (movie == null) {
            throw new IllegalStateException("Movie not found: " + resource.getMovieId());
        }
        if (!ResourceTitleMatcher.isRelevant(movie, resource.getName(), null)) {
            throw new IllegalStateException("Resource title does not match movie title");
        }
        String channelType = resolveChannelType(movie);
        String channelId = resolveChannelId(channelType);
        QqChannelPostLog log = qqChannelPostLogService.getOne(new QueryWrapper<QqChannelPostLog>()
                .eq("resource_link_id", resource.getId())
                .last("LIMIT 1"), false);
        LocalDateTime now = LocalDateTime.now();
        if (log == null) {
            log = new QqChannelPostLog();
            log.setResourceLinkId(resource.getId());
            log.setCreatedAt(now);
        }
        log.setMovieId(resource.getMovieId());
        log.setTitle(firstText(movie.getTitleCn(), movie.getTitleEn(), resource.getName(), movie.getId()));
        log.setLinkUrl(resource.getUrl());
        log.setChannelType(channelType);
        log.setChannelId(channelId);
        log.setStatus("PENDING");
        log.setErrorMessage(null);
        log.setPostedAt(null);
        if (log.getId() == null) {
            qqChannelPostLogService.save(log);
        } else {
            qqChannelPostLogService.updateById(log);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postLogId", log.getId());
        result.put("resourceLinkId", resource.getId());
        result.put("status", log.getStatus());
        result.put("immediate", false);
        if (runNow) {
            try {
                result.put("publisher", qqChannelPublisherClient.publish(log.getId()));
                QqChannelPostLog refreshed = qqChannelPostLogService.getById(log.getId());
                result.put("status", refreshed == null ? log.getStatus() : refreshed.getStatus());
                result.put("immediate", refreshed != null && "POSTED".equalsIgnoreCase(refreshed.getStatus()));
                result.put("error", refreshed == null ? null : refreshed.getErrorMessage());
            } catch (Exception error) {
                result.put("error", safeText(error.getMessage()));
            }
        }
        return result;
    }

    @PostMapping("/quark/transfers/submit")
    public ApiResponse<QuarkTransferRunResult> submitPendingQuarkTransfers(
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(quarkTransferRunnerService.submitPending(limit));
    }

    @PostMapping("/quark/transfers/{taskId}/submit")
    public ApiResponse<QuarkTransferRunResult> submitQuarkTransfer(
            @PathVariable Long taskId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(quarkTransferRunnerService.submitOne(taskId));
    }

    @GetMapping("/missing-resources")
    public ApiResponse<Map<String, Object>> missingResources(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        QueryWrapper<MovieMetadata> query = new QueryWrapper<MovieMetadata>()
                .eq("status", "ACTIVE")
                .isNull("deleted_at")
                .apply("""
                        NOT EXISTS (
                          SELECT 1 FROM resource_link rl
                          WHERE rl.movie_id = movie_metadata.id
                            AND rl.status = 'ACTIVE'
                            AND rl.deleted_at IS NULL
                            AND rl.type = 'DISK'
                            AND COALESCE(rl.link_status, 'NORMAL') <> 'INVALID'
                        )
                        """);
        if (hasText(keyword)) {
            String text = keyword.trim();
            query.and(w -> w.like("title_cn", text)
                    .or().like("title_en", text)
                    .or().like("series_name", text)
                    .or().like("aliases", text)
                    .or().eq("id", text));
        }
        if ("asc".equalsIgnoreCase(sortOrder)) {
            query.orderByAsc("updated_at");
        } else {
            query.orderByDesc("updated_at");
        }
        Page<MovieMetadata> movies = movieService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                query);
        List<Map<String, Object>> records = movies.getRecords().stream().map(movie -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", movie.getId());
            item.put("title", firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), movie.getId()));
            item.put("category", movie.getCategory());
            item.put("year", movie.getYear());
            item.put("resourceStatus", movie.getResourceStatus());
            item.put("updatedAt", movie.getUpdatedAt());
            return item;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", movies.getTotal());
        result.put("current", movies.getCurrent());
        result.put("size", movies.getSize());
        return ApiResponse.ok(result);
    }

    @PostMapping("/missing-resources/{movieId}/resolve")
    public ApiResponse<Map<String, Object>> resolveMissingResource(
            @PathVariable String movieId,
            @RequestParam(defaultValue = "PANSOU") String source,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        MovieMetadata movie = movieService.getById(movieId);
        if (movie == null || "DELETED".equalsIgnoreCase(movie.getStatus())) {
            throw new IllegalArgumentException("Movie not found: " + movieId);
        }
        DiscoveryPipelineJob job = new DiscoveryPipelineJob(UUID.randomUUID().toString(), null);
        discoveryPipelineJobs.put(job.jobId, job);
        trimDiscoveryPipelineJobs();
        discoveryPipelineExecutor.submit(() -> runMissingResourceJob(job, movie, source));
        return ApiResponse.ok(job.toResponse());
    }

    @PostMapping("/missing-resources/batch/resolve")
    public ApiResponse<Map<String, Object>> resolveMissingResources(
            @RequestBody(required = false) List<String> movieIds,
            @RequestParam(defaultValue = "PANSOU") String source,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        List<String> ids = movieIds == null
                ? List.of()
                : movieIds.stream().filter(this::hasText).map(String::trim).distinct().toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Select at least one missing-resource movie");
        }
        if (ids.size() > MISSING_RESOURCE_BATCH_LIMIT) {
            throw new IllegalArgumentException(
                    "Select at most " + MISSING_RESOURCE_BATCH_LIMIT
                            + " missing-resource movies per batch");
        }
        DiscoveryPipelineJob job = new DiscoveryPipelineJob(UUID.randomUUID().toString(), null);
        discoveryPipelineJobs.put(job.jobId, job);
        trimDiscoveryPipelineJobs();
        discoveryPipelineExecutor.submit(() -> runMissingResourceBatchJob(job, ids, source));
        return ApiResponse.ok(job.toResponse());
    }

    private void runDiscoveryPipelineJob(DiscoveryPipelineJob job) {
        try {
            ResourceDiscoveryRunResult discovery = resourceDiscoveryService.runTask(job.taskId);
            job.result.put("discovery", discovery);
            QuarkTransferRunResult transfers = quarkTransferRunnerService.submitPending(
                    resourceHubConfigService.getConfig().getWorkerQuarkLimit());
            job.result.put("transfers", transfers);
            ResourceHubPublishResult published = resourceHubPublishService.publishPending(
                    resourceHubConfigService.getConfig().getWorkerPublishLimit());
            job.result.put("published", published);
            job.status = "SUCCEEDED";
        } catch (Exception e) {
            job.status = "FAILED";
            job.errors.add(safeText(e.getMessage()));
        } finally {
            job.finishedAt = LocalDateTime.now();
        }
    }

    private void runMissingResourceJob(DiscoveryPipelineJob job, MovieMetadata movie, String source) {
        try {
            String normalizedSource = hasText(source) ? source.trim().toUpperCase() : "PANSOU";
            job.result.put("source", normalizedSource);
            job.result.put("movieId", movie.getId());
            if ("GYING".equals(normalizedSource)) {
                try {
                    job.result.put("gying", gyingSourceWorkflowService.ensureLocalMovieResource(movie.getId()));
                    requirePublishedResource(movie.getId());
                } catch (Exception gyingError) {
                    job.result.put("gyingError", safeText(gyingError.getMessage()));
                    job.result.put("fallbackSource", "PANSOU");
                    runPanSouMissingResourceJob(job, movie);
                }
            } else if ("PANSOU".equals(normalizedSource)) {
                runPanSouMissingResourceJob(job, movie);
            } else {
                throw new IllegalArgumentException("Unsupported missing-resource source: " + source);
            }
            job.status = "SUCCEEDED";
        } catch (Exception error) {
            job.status = "FAILED";
            job.errors.add(safeText(error.getMessage()));
        } finally {
            job.finishedAt = LocalDateTime.now();
        }
    }

    private void runMissingResourceBatchJob(
            DiscoveryPipelineJob batchJob,
            List<String> movieIds,
            String source) {
        List<Map<String, Object>> items = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (String movieId : movieIds) {
            MovieMetadata movie = movieService.getById(movieId);
            if (movie == null || "DELETED".equalsIgnoreCase(movie.getStatus())) {
                items.add(Map.of(
                        "movieId", movieId,
                        "status", "FAILED",
                        "error", "Movie not found"));
                failed++;
                continue;
            }
            DiscoveryPipelineJob itemJob = new DiscoveryPipelineJob(UUID.randomUUID().toString(), null);
            runMissingResourceJob(itemJob, movie, source);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("movieId", movieId);
            item.put("status", itemJob.status);
            item.put("result", itemJob.result);
            item.put("errors", new ArrayList<>(itemJob.errors));
            items.add(item);
            if ("SUCCEEDED".equals(itemJob.status)) {
                succeeded++;
            } else {
                failed++;
            }
        }
        batchJob.result.put("selected", movieIds.size());
        batchJob.result.put("succeeded", succeeded);
        batchJob.result.put("failed", failed);
        batchJob.result.put("items", items);
        batchJob.status = "SUCCEEDED";
        batchJob.finishedAt = LocalDateTime.now();
    }

    private void runPanSouMissingResourceJob(DiscoveryPipelineJob job, MovieMetadata movie) {
        int transfers = 0;
        int published = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        List<ResourceDiscoveryRunResult> discoveryAttempts = new ArrayList<>();
        List<String> searchKeywords = new ArrayList<>();
        if (movie.getSeason() != null && movie.getSeason() > 0) {
            if (hasText(movie.getTitleCn())) {
                String keyword = SeasonSearchUtils.seasonQualifiedTitle(movie.getTitleCn(), movie.getSeason());
                searchKeywords.addAll(SeasonSearchUtils.searchVariants(movie.getTitleCn(), keyword));
                searchKeywords.add(movie.getTitleCn().trim());
            }
            if (hasText(movie.getTitleEn()) && !movie.getTitleEn().equalsIgnoreCase(movie.getTitleCn())) {
                searchKeywords.add(movie.getTitleEn().trim() + " Season " + movie.getSeason());
                searchKeywords.add(movie.getTitleEn().trim() + " S" + String.format("%02d", movie.getSeason()));
                searchKeywords.add(movie.getTitleEn().trim());
            }
        } else {
            searchKeywords.add(null);
            if (hasText(movie.getTitleCn())) {
                searchKeywords.add(movie.getTitleCn().trim());
            }
            if (hasText(movie.getTitleEn()) && !movie.getTitleEn().equalsIgnoreCase(movie.getTitleCn())) {
                searchKeywords.add(movie.getTitleEn().trim());
            }
        }

        for (String keyword : searchKeywords) {
            ResourceDiscoveryRequest request = new ResourceDiscoveryRequest();
            request.setMovieId(movie.getId());
            request.setKeyword(keyword);
            request.setSource("PANSOU");
            request.setMaxResults(resourceHubConfigService.getConfig().getTmdbDiscoveryMaxResults());
            ResourceHubTask task = resourceDiscoveryService.enqueue(request);
            ResourceDiscoveryRunResult discovery = resourceDiscoveryService.runTask(task.getId());
            discoveryAttempts.add(discovery);

            List<ResourceDiscoveryResult> rows = discoveryResultService.list(
                    new QueryWrapper<ResourceDiscoveryResult>()
                            .eq("task_id", task.getId())
                            .eq("status", "DISCOVERED")
                            .orderByDesc("confidence")
                            .orderByAsc("id"));
            for (ResourceDiscoveryResult row : rows) {
                QuarkTransferTask transfer = findOrCreateTransfer(row);
                QuarkTransferRunResult transferResult = quarkTransferRunnerService.submitOne(transfer.getId());
                errors.addAll(transferResult.getErrors());
                QuarkTransferTask refreshed = quarkTransferTaskService.getById(transfer.getId());
                if (refreshed == null
                        || !"SUBMITTED".equalsIgnoreCase(refreshed.getStatus())
                        || !hasText(refreshed.getShareUrl())) {
                    failed++;
                    continue;
                }
                transfers++;
                ResourceHubPublishResult publishResult = resourceHubPublishService.publishDiscovery(row.getId());
                int saved = publishResult.getPublished() + publishResult.getUpdated();
                published += saved;
                failed += publishResult.getFailed();
                errors.addAll(publishResult.getErrors());
                if (saved > 0) {
                    break;
                }
            }
            if (published > 0) {
                break;
            }
        }
        job.result.put("discovery", discoveryAttempts.isEmpty() ? null : discoveryAttempts.get(0));
        job.result.put("discoveryAttempts", discoveryAttempts);
        job.result.put("transfers", transfers);
        job.result.put("published", published);
        job.result.put("failed", failed);
        job.result.put("errors", errors.stream().limit(10).toList());
        if (published == 0) {
            markMovieWithoutResource(movie);
            throw new IllegalStateException(errors.isEmpty()
                    ? "PanSou found no candidate that could be transferred and published"
                    : errors.get(errors.size() - 1));
        }
        requirePublishedResource(movie.getId());
    }
    private QuarkTransferTask findOrCreateTransfer(ResourceDiscoveryResult discovery) {
        QuarkTransferTask transfer = quarkTransferTaskService.getOne(
                new QueryWrapper<QuarkTransferTask>()
                        .eq("discovery_result_id", discovery.getId())
                        .orderByDesc("updated_at")
                        .last("LIMIT 1"),
                false);
        if (transfer != null && !"CANCELED".equalsIgnoreCase(transfer.getStatus())) {
            return transfer;
        }
        if (!hasText(discovery.getOriginalUrl())) {
            throw new IllegalStateException("Discovery result has no source URL to transfer");
        }
        LocalDateTime now = LocalDateTime.now();
        transfer = new QuarkTransferTask();
        transfer.setDiscoveryResultId(discovery.getId());
        transfer.setMovieId(discovery.getMovieId());
        transfer.setOriginalUrl(discovery.getOriginalUrl());
        transfer.setOriginalUrlHash(firstText(
                discovery.getOriginalUrlHash(),
                ResourceHubHashUtils.sha256(discovery.getOriginalUrl())));
        transfer.setStatus("PENDING");
        transfer.setAttempts(0);
        transfer.setCreatedAt(now);
        transfer.setUpdatedAt(now);
        quarkTransferTaskService.save(transfer);
        return transfer;
    }

    private void requirePublishedResource(String movieId) {
        long count = resourceLinkService.count(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("status", "ACTIVE")
                .eq("type", "DISK")
                .isNull("deleted_at")
                .and(query -> query.isNull("link_status").or().ne("link_status", "INVALID")));
        if (count == 0) {
            throw new IllegalStateException("Missing-resource workflow did not publish an active resource");
        }
    }

    private void markMovieWithoutResource(MovieMetadata movie) {
        if (movie == null || "TRAILER".equalsIgnoreCase(movie.getResourceStatus())) {
            return;
        }
        movie.setResourceStatus("TRAILER");
        movie.setUpdatedAt(LocalDateTime.now());
        movieService.updateById(movie);
    }

    private Map<String, Object> retryShareAndPublishOne(Long discoveryResultId) {
        ResourceDiscoveryResult discovery = discoveryResultService.getById(discoveryResultId);
        if (discovery == null) {
            throw new IllegalArgumentException("Discovery result not found: " + discoveryResultId);
        }
        MovieMetadata movie = movieService.getById(discovery.getMovieId());
        if (movie == null) {
            throw new IllegalStateException("Movie not found: " + discovery.getMovieId());
        }
        if (!ResourceTitleMatcher.isRelevant(movie, discovery.getTitle(), null)) {
            throw new IllegalStateException("Resource title still does not match movie title");
        }
        if ("IGNORED".equalsIgnoreCase(discovery.getStatus())
                && "Resource title does not match movie title".equals(discovery.getFailureReason())) {
            discovery.setStatus("DISCOVERED");
            discovery.setFailureReason(null);
            discovery.setUpdatedAt(LocalDateTime.now());
            discoveryResultService.update(new UpdateWrapper<ResourceDiscoveryResult>()
                    .eq("id", discovery.getId())
                    .set("status", "DISCOVERED")
                    .set("failure_reason", null)
                    .set("updated_at", discovery.getUpdatedAt()));
        }
        boolean retryable = "DISCOVERED".equalsIgnoreCase(discovery.getStatus())
                || "FAILED".equalsIgnoreCase(discovery.getStatus());
        if (!retryable
                && discovery.getResourceLinkId() == null) {
            throw new IllegalStateException("Discovery result is not retryable: " + discovery.getStatus());
        }

        QuarkTransferTask transfer = findOrCreateTransfer(discovery);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discoveryResultId", discoveryResultId);
        if (!hasText(transfer.getShareUrl())) {
            QuarkTransferRunResult transferResult = quarkTransferRunnerService.submitOne(transfer.getId());
            result.put("transferTaskId", transfer.getId());
            result.put("transferSubmitted", transferResult.getSubmitted());
            result.put("transferFailed", transferResult.getFailed());
            result.put("transferErrors", transferResult.getErrors());
            transfer = quarkTransferTaskService.getById(transfer.getId());
            if (transferResult.getFailed() > 0 || transfer == null || !hasText(transfer.getShareUrl())) {
                if (recoverFailedDiscoveryWithPanSou(discovery, result)) {
                    return result;
                }
                throw new IllegalStateException(transferResult.getErrors().isEmpty()
                        ? "Quark transfer did not create an own share and PanSou rediscovery failed"
                        : transferResult.getErrors().get(0));
            }
        }
        ResourceHubPublishResult publishResult = resourceHubPublishService.publishDiscovery(discoveryResultId);
        result.put("published", publishResult.getPublished());
        result.put("updated", publishResult.getUpdated());
        result.put("failed", publishResult.getFailed());
        result.put("skipped", publishResult.getSkipped());
        result.put("errors", publishResult.getErrors());
        if (publishResult.getFailed() > 0) {
            throw new IllegalStateException(publishResult.getErrors().isEmpty()
                    ? "Discovery publish failed"
                    : publishResult.getErrors().get(0));
        }
        requirePublishedResource(discovery.getMovieId());
        return result;
    }

    private boolean recoverFailedDiscoveryWithPanSou(
            ResourceDiscoveryResult failedDiscovery,
            Map<String, Object> result) {
        ResourceDiscoveryRequest request = new ResourceDiscoveryRequest();
        request.setMovieId(failedDiscovery.getMovieId());
        request.setSource("PANSOU");
        request.setRefresh(true);
        request.setMaxResults(resourceHubConfigService.getConfig().getTmdbDiscoveryMaxResults());
        ResourceHubTask task = resourceDiscoveryService.enqueue(request);
        ResourceDiscoveryRunResult discoveryRun = resourceDiscoveryService.runTask(task.getId());
        result.put("rediscoveryTaskId", task.getId());
        result.put("rediscovered", discoveryRun.getDiscovered());

        List<ResourceDiscoveryResult> rediscovered = discoveryResultService.list(
                new QueryWrapper<ResourceDiscoveryResult>()
                        .eq("task_id", task.getId())
                        .eq("status", "DISCOVERED")
                        .orderByDesc("confidence"));
        for (ResourceDiscoveryResult candidate : rediscovered) {
            QuarkTransferTask candidateTransfer = findOrCreateTransfer(candidate);
            if (!hasText(candidateTransfer.getShareUrl())) {
                QuarkTransferRunResult transferResult = quarkTransferRunnerService.submitOne(candidateTransfer.getId());
                if (transferResult.getFailed() > 0) {
                    continue;
                }
                candidateTransfer = quarkTransferTaskService.getById(candidateTransfer.getId());
                if (candidateTransfer == null || !hasText(candidateTransfer.getShareUrl())) {
                    continue;
                }
            }
            ResourceHubPublishResult publishResult = resourceHubPublishService.publishDiscovery(candidate.getId());
            if (publishResult.getPublished() + publishResult.getUpdated() <= 0) {
                continue;
            }
            requirePublishedResource(candidate.getMovieId());
            failedDiscovery.setStatus("IGNORED");
            failedDiscovery.setFailureReason("Recovered by PanSou discovery " + candidate.getId());
            failedDiscovery.setUpdatedAt(LocalDateTime.now());
            discoveryResultService.updateById(failedDiscovery);
            result.put("replacementDiscoveryResultId", candidate.getId());
            result.put("published", publishResult.getPublished());
            result.put("updated", publishResult.getUpdated());
            result.put("failed", 0);
            result.put("errors", List.of());
            return true;
        }
        return false;
    }

    private Map<String, Object> reconcileDiscoveryStates(boolean dryRun, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 5000);
        List<ResourceDiscoveryResult> discoveries = discoveryResultService.list(
                new QueryWrapper<ResourceDiscoveryResult>()
                        .in("status", List.of("IGNORED", "DISCOVERED"))
                        .orderByDesc("updated_at")
                        .last("LIMIT " + safeLimit));
        int titleRestored = 0;
        int taskConflictRestored = 0;
        int failedSynced = 0;
        int staleReasonCleared = 0;
        int unchanged = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ResourceDiscoveryResult discovery : discoveries) {
            boolean titleMismatch = "Resource title does not match movie title".equals(discovery.getFailureReason());
            boolean taskConflict = "Movie already has a Quark transfer task".equals(discovery.getFailureReason());
            if ("IGNORED".equalsIgnoreCase(discovery.getStatus()) && (titleMismatch || taskConflict)) {
                MovieMetadata movie = movieService.getById(discovery.getMovieId());
                if (movie != null && ResourceTitleMatcher.isRelevant(movie, discovery.getTitle(), null)) {
                    if (titleMismatch) {
                        titleRestored++;
                    } else {
                        taskConflictRestored++;
                    }
                    if (!dryRun) {
                        discovery.setStatus("DISCOVERED");
                        discovery.setFailureReason(null);
                        discovery.setUpdatedAt(now);
                        discoveryResultService.update(new UpdateWrapper<ResourceDiscoveryResult>()
                                .eq("id", discovery.getId())
                                .set("status", "DISCOVERED")
                                .set("failure_reason", null)
                                .set("updated_at", now));
                    }
                    continue;
                }
            }
            if ("DISCOVERED".equalsIgnoreCase(discovery.getStatus())) {
                QuarkTransferTask transfer = quarkTransferTaskService.getOne(
                        new QueryWrapper<QuarkTransferTask>()
                                .eq("discovery_result_id", discovery.getId())
                                .orderByDesc("updated_at")
                                .orderByDesc("id")
                                .last("LIMIT 1"),
                        false);
                if (transfer != null && "FAILED".equalsIgnoreCase(transfer.getStatus())) {
                    failedSynced++;
                    if (!dryRun) {
                        discovery.setStatus("FAILED");
                        discovery.setFailureReason(firstText(
                                transfer.getLastError(),
                                "Quark transfer failed"));
                        discovery.setUpdatedAt(now);
                        discoveryResultService.updateById(discovery);
                    }
                    continue;
                }
                if (titleMismatch || taskConflict) {
                    staleReasonCleared++;
                    if (!dryRun) {
                        discovery.setFailureReason(null);
                        discovery.setUpdatedAt(now);
                        discoveryResultService.update(new UpdateWrapper<ResourceDiscoveryResult>()
                                .eq("id", discovery.getId())
                                .set("failure_reason", null)
                                .set("updated_at", now));
                    }
                    continue;
                }
            }
            unchanged++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("checked", discoveries.size());
        result.put("titleRestored", titleRestored);
        result.put("taskConflictRestored", taskConflictRestored);
        result.put("failedSynced", failedSynced);
        result.put("staleReasonCleared", staleReasonCleared);
        result.put("unchanged", unchanged);
        return result;
    }

    private Map<String, Object> runDiscoveryBatch(
            List<Long> discoveryResultIds,
            Function<Long, Map<String, Object>> action) {
        List<Long> ids = discoveryResultIds == null
                ? List.of()
                : discoveryResultIds.stream().filter(java.util.Objects::nonNull).distinct().limit(100).toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Select at least one discovery result");
        }
        int succeeded = 0;
        int failed = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : ids) {
            try {
                Map<String, Object> item = new LinkedHashMap<>(action.apply(id));
                item.putIfAbsent("discoveryResultId", id);
                item.put("status", "SUCCEEDED");
                items.add(item);
                succeeded++;
            } catch (Exception error) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("discoveryResultId", id);
                item.put("status", "FAILED");
                item.put("error", safeText(error.getMessage()));
                items.add(item);
                failed++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selected", ids.size());
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        result.put("items", items);
        return result;
    }

    private void trimDiscoveryPipelineJobs() {
        if (discoveryPipelineJobs.size() <= 20) {
            return;
        }
        discoveryPipelineJobs.values().stream()
                .filter(job -> !"RUNNING".equals(job.status))
                .sorted((left, right) -> right.startedAt.compareTo(left.startedAt))
                .skip(20)
                .map(job -> job.jobId)
                .toList()
                .forEach(discoveryPipelineJobs::remove);
    }

    private static class DiscoveryPipelineJob {
        private final String jobId;
        private final Long taskId;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private volatile LocalDateTime finishedAt;
        private volatile String status = "RUNNING";
        private final Map<String, Object> result = new LinkedHashMap<>();
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        private DiscoveryPipelineJob(String jobId, Long taskId) {
            this.jobId = jobId;
            this.taskId = taskId;
        }

        private Map<String, Object> toResponse() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", jobId);
            response.put("taskId", taskId);
            response.put("status", status);
            response.put("startedAt", startedAt);
            response.put("finishedAt", finishedAt);
            response.put("result", result);
            response.put("errors", new ArrayList<>(errors));
            return response;
        }
    }

    @PostMapping("/cleanup/duplicate-tmdb")
    public ApiResponse<Map<String, Object>> cleanupDuplicateTmdbMovies(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<MovieMetadata> tmdbMovies = movieService.list(new QueryWrapper<MovieMetadata>()
                .likeRight("id", "tmdb_")
                .ne("status", "DELETED")
                .orderByDesc("created_at")
                .last("LIMIT " + safeLimit));
        int candidates = 0;
        int merged = 0;
        long movedResources = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (MovieMetadata tmdbMovie : tmdbMovies) {
            MovieMetadata canonical = findCanonicalMovie(tmdbMovie);
            if (canonical == null) {
                continue;
            }
            candidates++;
            long resources = resourceLinkService.count(new QueryWrapper<ResourceLink>()
                    .eq("movie_id", tmdbMovie.getId())
                    .ne("status", "DELETED"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fromMovieId", tmdbMovie.getId());
            item.put("fromTitle", firstText(tmdbMovie.getTitleCn(), tmdbMovie.getTitleEn(), tmdbMovie.getId()));
            item.put("toMovieId", canonical.getId());
            item.put("toTitle", firstText(canonical.getTitleCn(), canonical.getTitleEn(), canonical.getId()));
            item.put("resources", resources);
            items.add(item);
            if (dryRun) {
                continue;
            }
            moveMovieReferences(tmdbMovie.getId(), canonical.getId());
            tmdbMovie.setStatus("DELETED");
            tmdbMovie.setDeletedAt(LocalDateTime.now());
            tmdbMovie.setUpdatedAt(LocalDateTime.now());
            movieService.updateById(tmdbMovie);
            merged++;
            movedResources += resources;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("candidates", candidates);
        result.put("merged", merged);
        result.put("movedResources", movedResources);
        result.put("items", items);
        return ApiResponse.ok(result);
    }

    @PostMapping("/cleanup/mismatched-resources")
    public ApiResponse<Map<String, Object>> cleanupMismatchedResources(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "200") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        List<ResourceLink> resources = resourceLinkService.list(new QueryWrapper<ResourceLink>()
                .eq("source", "RESOURCE_HUB")
                .eq("status", "ACTIVE")
                .isNull("deleted_at")
                .orderByAsc("created_at")
                .last("LIMIT " + safeLimit));
        int cleaned = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (ResourceLink resource : resources) {
            MovieMetadata movie = movieService.getById(resource.getMovieId());
            if (movie == null || ResourceTitleMatcher.isRelevant(movie, resource.getName(), null)) {
                continue;
            }
            ResourceDiscoveryResult discovery = discoveryResultService.getOne(
                    new QueryWrapper<ResourceDiscoveryResult>()
                            .eq("resource_link_id", resource.getId())
                            .orderByDesc("updated_at")
                            .last("LIMIT 1"),
                    false);
            QuarkTransferTask transfer = discovery == null ? null : quarkTransferTaskService.getOne(
                    new QueryWrapper<QuarkTransferTask>()
                            .eq("discovery_result_id", discovery.getId())
                            .orderByDesc("updated_at")
                            .last("LIMIT 1"),
                    false);
            QqChannelPostLog post = qqChannelPostLogService.getOne(new QueryWrapper<QqChannelPostLog>()
                    .eq("resource_link_id", resource.getId())
                    .last("LIMIT 1"), false);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resourceLinkId", resource.getId());
            item.put("movieId", resource.getMovieId());
            item.put("movieTitle", firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getId()));
            item.put("resourceName", resource.getName());
            item.put("discoveryResultId", discovery == null ? null : discovery.getId());
            item.put("transferTaskId", transfer == null ? null : transfer.getId());
            item.put("channelPostStatus", post == null ? null : post.getStatus());
            items.add(item);
            if (dryRun) {
                continue;
            }

            String reason = "Resource title does not match movie title";
            resource.setStatus("DELETED");
            resource.setLinkStatus("INVALID");
            resource.setLastCheckError(reason);
            resource.setDeletedAt(now);
            resource.setUpdatedAt(now);
            resourceLinkService.updateById(resource);
            if (discovery != null) {
                discovery.setStatus("IGNORED");
                discovery.setFailureReason(reason);
                discovery.setUpdatedAt(now);
                discoveryResultService.updateById(discovery);
            }
            if (transfer != null) {
                transfer.setStatus("CANCELED");
                transfer.setLastError(reason);
                transfer.setFinishedAt(now);
                transfer.setUpdatedAt(now);
                quarkTransferTaskService.updateById(transfer);
            }
            if (post != null && "PENDING".equalsIgnoreCase(post.getStatus())) {
                post.setStatus("FAILED");
                post.setErrorMessage(reason);
                qqChannelPostLogService.updateById(post);
            }
            refreshMovieResourceStatus(movie, now);
            cleaned++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("candidates", items.size());
        result.put("cleaned", cleaned);
        result.put("items", items);
        return ApiResponse.ok(result);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String resolveChannelType(MovieMetadata movie) {
        String type = firstText(movie == null ? null : movie.getTmdbType(), movie == null ? null : movie.getCategory());
        if (!hasText(type)) {
            return "movie";
        }
        String normalized = type.trim().toLowerCase();
        return List.of("tv", "series", "show", "drama").contains(normalized) ? "tv" : "movie";
    }

    private String resolveChannelId(String channelType) {
        Map<String, Object> config = qqAutomationConfigService.getConfig();
        Object value = "tv".equals(channelType) ? config.get("channelTvId") : config.get("channelMovieId");
        String channelId = value == null ? null : String.valueOf(value).trim();
        if (!hasText(channelId)) {
            throw new IllegalStateException("QQ channel board is not configured for type " + channelType);
        }
        return channelId;
    }

    private MovieMetadata findCanonicalMovie(MovieMetadata tmdbMovie) {
        if (tmdbMovie == null) {
            return null;
        }
        String title = firstText(tmdbMovie.getTitleCn(), tmdbMovie.getSeriesName(), tmdbMovie.getTitleEn());
        if (!hasText(title)) {
            return null;
        }
        QueryWrapper<MovieMetadata> query = new QueryWrapper<MovieMetadata>()
                .notLikeRight("id", "tmdb_")
                .ne("status", "DELETED")
                .and(w -> w.eq("title_cn", title)
                        .or().eq("title_en", title)
                        .or().eq("series_name", title)
                        .or().like("aliases", title));
        if (tmdbMovie.getYear() != null) {
            query.and(w -> w.eq("year", tmdbMovie.getYear()).or().isNull("year"));
        }
        query.last("LIMIT 1");
        return movieService.getOne(query, false);
    }

    private void moveMovieReferences(String fromMovieId, String toMovieId) {
        List<ResourceLink> resources = resourceLinkService.list(new QueryWrapper<ResourceLink>().eq("movie_id", fromMovieId));
        for (ResourceLink resource : resources) {
            resource.setMovieId(toMovieId);
            resourceLinkService.updateById(resource);
        }
        List<ResourceDiscoveryResult> discoveries = discoveryResultService.list(new QueryWrapper<ResourceDiscoveryResult>().eq("movie_id", fromMovieId));
        for (ResourceDiscoveryResult discovery : discoveries) {
            discovery.setMovieId(toMovieId);
            discoveryResultService.updateById(discovery);
        }
        List<QuarkTransferTask> transfers = quarkTransferTaskService.list(new QueryWrapper<QuarkTransferTask>().eq("movie_id", fromMovieId));
        for (QuarkTransferTask transfer : transfers) {
            transfer.setMovieId(toMovieId);
            quarkTransferTaskService.updateById(transfer);
        }
        List<ResourceHubTask> tasks = taskService.list(new QueryWrapper<ResourceHubTask>().eq("movie_id", fromMovieId));
        for (ResourceHubTask task : tasks) {
            task.setMovieId(toMovieId);
            taskService.updateById(task);
        }
        List<UserFavorite> favorites = favoriteService.list(new QueryWrapper<UserFavorite>().eq("movie_id", fromMovieId));
        for (UserFavorite favorite : favorites) {
            long existing = favoriteService.count(new QueryWrapper<UserFavorite>()
                    .eq("user_id", favorite.getUserId())
                    .eq("movie_id", toMovieId));
            if (existing > 0) {
                favoriteService.removeById(favorite.getId());
                continue;
            }
            favorite.setMovieId(toMovieId);
            favoriteService.updateById(favorite);
        }
        List<Comment> comments = commentService.list(new QueryWrapper<Comment>().eq("relate_id", fromMovieId));
        for (Comment comment : comments) {
            comment.setRelateId(toMovieId);
            commentService.updateById(comment);
        }
    }

    private void refreshMovieResourceStatus(MovieMetadata movie, LocalDateTime now) {
        if (movie == null) {
            return;
        }
        long activeResources = resourceLinkService.count(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movie.getId())
                .eq("status", "ACTIVE")
                .eq("type", "DISK")
                .isNull("deleted_at")
                .and(query -> query.isNull("link_status").or().ne("link_status", "INVALID")));
        movie.setResourceStatus(activeResources > 0 ? "AVAILABLE" : "TRAILER");
        movie.setUpdatedAt(now);
        movieService.updateById(movie);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String safeText(String value) {
        if (!hasText(value)) {
            return "Unknown error";
        }
        String text = value.trim();
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }

    private Map<String, Object> workerStatusMap() {
        ResourceHubConfigResponse config = resourceHubConfigService.getConfig();
        ResourceHubProperties.Worker worker = resourceHubProperties.getWorker();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled() && config.isWorkerEnabled());
        result.put("running", resourceHubWorkerService.isRunning());
        result.put("fixedDelayMs", config.getWorkerFixedDelayMs());
        result.put("taskLimit", config.getWorkerTaskLimit());
        result.put("quarkLimit", config.getWorkerQuarkLimit());
        result.put("publishLimit", config.getWorkerPublishLimit());
        return result;
    }

    private Map<String, Object> collectionStats() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        ResourceHubTask latestTmdbTask = taskService.getOne(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC")
                .eq("source", "TMDB")
                .orderByDesc("created_at")
                .orderByDesc("id")
                .last("LIMIT 1"), false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tmdbMovies", movieService.count(new QueryWrapper<MovieMetadata>()
                .isNotNull("tmdb_id")
                .ne("status", "DELETED")
                .isNull("deleted_at")));
        result.put("tmdbSyncedLast24Hours", movieService.count(new QueryWrapper<MovieMetadata>()
                .isNotNull("tmdb_id")
                .ge("tmdb_last_sync_at", since)
                .ne("status", "DELETED")
                .isNull("deleted_at")));
        result.put("tmdbCreatedLast24Hours", movieService.count(new QueryWrapper<MovieMetadata>()
                .isNotNull("tmdb_id")
                .ge("created_at", since)
                .ne("status", "DELETED")
                .isNull("deleted_at")));
        result.put("tmdbTasksLast24Hours", taskService.count(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC")
                .ge("created_at", since)));
        result.put("tmdbSucceededLast24Hours", taskService.count(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC")
                .eq("status", "SUCCEEDED")
                .ge("created_at", since)));
        result.put("tmdbFailedLast24Hours", taskService.count(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC")
                .eq("status", "FAILED")
                .ge("created_at", since)));
        result.put("discoveriesLast24Hours", discoveryResultService.count(new QueryWrapper<ResourceDiscoveryResult>()
                .ge("created_at", since)));
        result.put("savedDiscoveriesLast24Hours", discoveryResultService.count(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("status", "SAVED")
                .ge("updated_at", since)));
        result.put("resourcesSavedLast24Hours", resourceLinkService.count(new QueryWrapper<ResourceLink>()
                .eq("source", "RESOURCE_HUB")
                .eq("status", "ACTIVE")
                .isNull("deleted_at")
                .ge("created_at", since)));
        result.put("latestTmdbTaskAt", latestTmdbTask == null ? null : latestTmdbTask.getCreatedAt());
        result.put("latestTmdbTaskSource", latestTmdbTask == null ? null : latestTmdbTask.getKeyword());
        result.put("latestTmdbTaskStatus", latestTmdbTask == null ? null : latestTmdbTask.getStatus());
        result.put("nextTmdbRunAt", latestTmdbTask == null || latestTmdbTask.getCreatedAt() == null
                ? LocalDateTime.now()
                : latestTmdbTask.getCreatedAt().plusHours(
                        Math.max(resourceHubProperties.getTmdb().getAutoSyncIntervalHours(), 1)));
        return result;
    }
}
