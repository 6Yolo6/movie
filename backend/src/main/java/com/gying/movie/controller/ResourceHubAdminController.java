package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.gying.movie.utils.AuthHelper;
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
            ICommentService commentService) {
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
        result.put("quarkBaseUrl", nullToEmpty(resourceHubProperties.getQuark().getBaseUrl()));
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
        query.orderByDesc("created_at");
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

    @PostMapping("/discoveries/{discoveryResultId}/qq-channel-post")
    public ApiResponse<Map<String, Object>> queueDiscoveryForQqChannel(
            @PathVariable Long discoveryResultId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
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
        return ApiResponse.ok(result);
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
                .isNull("deleted_at"));
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
}
