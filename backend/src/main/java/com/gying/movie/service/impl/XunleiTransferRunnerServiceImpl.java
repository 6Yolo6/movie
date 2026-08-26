package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gying.movie.client.XunleiClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IXunleiTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class XunleiTransferRunnerServiceImpl implements IXunleiTransferRunnerService {
    private static final int MAX_TRANSFER_ATTEMPTS = 3;
    private final ResourceHubProperties properties;
    private final XunleiClient client;
    private final IXunleiTransferTaskService taskService;
    private final IResourceDiscoveryResultService discoveryService;
    private final IResourceLinkService linkService;
    private final Set<Long> runningTaskIds = ConcurrentHashMap.newKeySet();

    public XunleiTransferRunnerServiceImpl(ResourceHubProperties properties, XunleiClient client,
            IXunleiTransferTaskService taskService, IResourceDiscoveryResultService discoveryService,
            IResourceLinkService linkService) {
        this.properties = properties; this.client = client; this.taskService = taskService;
        this.discoveryService = discoveryService; this.linkService = linkService;
    }

    @Override public QuarkTransferRunResult submitPending(int limit) {
        QuarkTransferRunResult result = new QuarkTransferRunResult();
        if (!client.isConfigured()) return result;
        List<String> statuses = properties.getXunlei().isShareEnabled()
                ? List.of("PENDING", "FAILED", "WAITING_SHARE")
                : List.of("PENDING", "FAILED");
        List<XunleiTransferTask> tasks = taskService.list(new QueryWrapper<XunleiTransferTask>()
                .in("status", statuses)
                .orderByAsc("created_at")
                .last("LIMIT " + Math.min(Math.max(limit, 1), 20)));
        tasks.stream()
                .filter(task -> !hasReachedRetryLimit(task))
                .forEach(task -> submitGuarded(task, result, false));
        return result;
    }

    @Override public QuarkTransferRunResult submitOne(Long taskId) {
        XunleiTransferTask task = taskService.getById(taskId);
        if (task == null) throw new IllegalArgumentException("Xunlei transfer task not found");
        QuarkTransferRunResult result = new QuarkTransferRunResult(); result.setTaskId(taskId);
        submitGuarded(task, result, true);
        return result;
    }

    private void submitGuarded(XunleiTransferTask task, QuarkTransferRunResult result, boolean manualRetry) {
        if (task.getId() == null || !runningTaskIds.add(task.getId())) {
            result.setSkipped(result.getSkipped() + 1);
            return;
        }
        try {
            submit(task, result, manualRetry);
        } finally {
            runningTaskIds.remove(task.getId());
        }
    }

    private void submit(XunleiTransferTask task, QuarkTransferRunResult result, boolean manualRetry) {
        try {
            if (!manualRetry && hasReachedRetryLimit(task)) {
                result.setSkipped(result.getSkipped() + 1);
                return;
            }
            if ("SUCCEEDED".equalsIgnoreCase(task.getStatus()) && task.getShareUrl() != null) {
                clearLastError(task.getId());
                updatePublishedLink(task);
                result.setSkipped(result.getSkipped() + 1);
                return;
            }
            if ("CANCELED".equalsIgnoreCase(task.getStatus())
                    || "RUNNING".equalsIgnoreCase(task.getStatus())) {
                result.setSkipped(result.getSkipped() + 1);
                return;
            }
            if ("WAITING_SHARE".equalsIgnoreCase(task.getStatus()) && task.getSavedPath() != null) {
                client.awaitContent(task.getSavedPath());
                String share = client.createShare(task.getSavedPath());
                if (share == null) {
                    task.setLastError("Xunlei transfer succeeded but share API did not return a URL");
                    task.setUpdatedAt(LocalDateTime.now());
                    taskService.updateById(task);
                    result.setFailed(result.getFailed() + 1);
                    if (result.getErrors().size() < 10) {
                        result.getErrors().add(task.getLastError());
                    }
                    return;
                }
                task.setShareUrl(share); task.setShareUrlHash(ResourceHubHashUtils.sha256(share));
                task.setStatus("SUCCEEDED"); task.setLastError(null); task.setFinishedAt(LocalDateTime.now());
                task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task); clearLastError(task.getId()); updatePublishedLink(task);
                result.setSubmitted(result.getSubmitted() + 1); return;
            }
            task.setStatus("RUNNING"); task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1); task.setStartedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task);
            String sourceUrl = task.getOriginalUrl();
            ResourceDiscoveryResult discovery = task.getDiscoveryResultId() == null
                    ? null : discoveryService.getById(task.getDiscoveryResultId());
            if (discovery != null) {
                sourceUrl = XunleiClient.normalizeShareUrl(sourceUrl, discovery.getCode());
                if (!sourceUrl.equals(task.getOriginalUrl())) {
                    task.setOriginalUrl(sourceUrl);
                    task.setOriginalUrlHash(ResourceHubHashUtils.sha256(sourceUrl));
                }
            }
            XunleiClient.RestoreResult restore = client.restore(sourceUrl, transferPath(task));
            task.setResponsePayload(restore.response()); task.setStatus("SUBMITTED"); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task);
            XunleiClient.RestoreStatus status = client.await(restore.taskId());
            if (!status.success()) { task.setStatus("FAILED"); task.setLastError("Xunlei restore " + status.status()); result.setFailed(result.getFailed() + 1); }
            else { task.setStatus("WAITING_SHARE"); XunleiClient.RestoredSelection restored = client.awaitRestoredFiles(restore.restoredFileId(), restore.expectedNames(), restore.startedAt()); task.setSavedPath(restored.fileIds().get(0)); String share = client.createShare(restored.fileIds()); if (share != null) { task.setShareUrl(share); task.setShareUrlHash(ResourceHubHashUtils.sha256(share)); task.setStatus("SUCCEEDED"); task.setLastError(null); updatePublishedLink(task); result.setSubmitted(result.getSubmitted() + 1); } else { task.setLastError("Xunlei restore succeeded but share API did not return a URL"); result.setFailed(result.getFailed() + 1); if (result.getErrors().size() < 10) result.getErrors().add(task.getLastError()); } }
            task.setFinishedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task);
            if ("SUCCEEDED".equalsIgnoreCase(task.getStatus())) clearLastError(task.getId());
        } catch (Exception e) { task.setStatus("FAILED"); task.setLastError(e.getMessage()); task.setFinishedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task); result.setFailed(result.getFailed() + 1); if (result.getErrors().size() < 10) result.getErrors().add(e.getMessage()); }
    }

    private boolean hasReachedRetryLimit(XunleiTransferTask task) {
        return task != null
                && task.getAttempts() != null
                && task.getAttempts() >= MAX_TRANSFER_ATTEMPTS
                && !"SUCCEEDED".equalsIgnoreCase(task.getStatus());
    }

    private void clearLastError(Long taskId) {
        if (taskId != null) taskService.update(new UpdateWrapper<XunleiTransferTask>()
                .eq("id", taskId)
                .set("last_error", null));
    }

    private void updatePublishedLink(XunleiTransferTask task) {
        if (task.getDiscoveryResultId() == null || task.getShareUrl() == null) return;
        ResourceDiscoveryResult discovery = discoveryService.getById(task.getDiscoveryResultId()); if (discovery == null) return;
        String finalCode = XunleiClient.extractShareCode(task.getShareUrl());
        discovery.setShareUrl(task.getShareUrl()); discovery.setShareUrlHash(task.getShareUrlHash());
        discovery.setCode(finalCode); discovery.setStatus("DISCOVERED"); discovery.setFailureReason(null); discovery.setUpdatedAt(LocalDateTime.now());
        if (discovery.getResourceLinkId() != null) { ResourceLink link = linkService.getById(discovery.getResourceLinkId()); if (link != null) { link.setUrl(task.getShareUrl()); link.setUrlHash(task.getShareUrlHash()); link.setCode(finalCode); link.setProvider("XUNLEI"); link.setStatus("ACTIVE"); link.setLinkStatus("NORMAL"); link.setValidatedAt(LocalDateTime.now()); link.setUpdatedAt(LocalDateTime.now()); linkService.updateById(link); linkService.update(new UpdateWrapper<ResourceLink>().eq("id", link.getId()).set("last_check_error", null)); discovery.setStatus("SAVED"); } }
        discoveryService.updateById(discovery);
        discoveryService.update(new UpdateWrapper<ResourceDiscoveryResult>()
                .eq("id", discovery.getId())
                .set("failure_reason", null));
    }

    static String transferPath(ResourceHubProperties properties, XunleiTransferTask task) {
        String base = properties.getXunlei().getSavePath() == null
                ? "/GYing Resource Hub"
                : properties.getXunlei().getSavePath().replaceAll("/+$", "");
        return base;
    }

    private String transferPath(XunleiTransferTask task) {
        return transferPath(properties, task);
    }
}
