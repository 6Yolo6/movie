package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.dto.ResourceHubMetadataSyncRequest;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import com.gying.movie.utils.AuthHelper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public ResourceHubAdminController(
            AuthHelper authHelper,
            ResourceHubProperties resourceHubProperties,
            IResourceHubTaskService taskService,
            IResourceDiscoveryResultService discoveryResultService,
            IQuarkTransferTaskService quarkTransferTaskService,
            ITmdbMetadataSyncService tmdbMetadataSyncService,
            IResourceDiscoveryService resourceDiscoveryService,
            IQuarkTransferRunnerService quarkTransferRunnerService) {
        this.authHelper = authHelper;
        this.resourceHubProperties = resourceHubProperties;
        this.taskService = taskService;
        this.discoveryResultService = discoveryResultService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.tmdbMetadataSyncService = tmdbMetadataSyncService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.quarkTransferRunnerService = quarkTransferRunnerService;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", resourceHubProperties.isEnabled());
        result.put("autoApprove", resourceHubProperties.isAutoApprove());
        result.put("tmdbConfigured", hasText(resourceHubProperties.getTmdb().getApiKey()));
        result.put("pansouBaseUrl", nullToEmpty(resourceHubProperties.getPansou().getBaseUrl()));
        result.put("quarkBaseUrl", nullToEmpty(resourceHubProperties.getQuark().getBaseUrl()));
        result.put("taskStatusCounts", taskService.countByStatus());
        result.put("discoveredCount", discoveryResultService.count(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("status", "DISCOVERED")));
        result.put("savedDiscoveryCount", discoveryResultService.count(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("status", "SAVED")));
        result.put("pendingQuarkTransfers", quarkTransferTaskService.count(new QueryWrapper<QuarkTransferTask>()
                .eq("status", "PENDING")));
        return ApiResponse.ok(result);
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
            ResourceDiscoveryRunResult result = resourceDiscoveryService.runTask(task.getId());
            return ApiResponse.ok(result);
        }
        return ApiResponse.ok(task);
    }

    @PostMapping("/discover/{taskId}/run")
    public ApiResponse<ResourceDiscoveryRunResult> runResourceDiscovery(
            @PathVariable Long taskId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(resourceDiscoveryService.runTask(taskId));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
