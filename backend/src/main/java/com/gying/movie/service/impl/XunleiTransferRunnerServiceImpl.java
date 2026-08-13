package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.springframework.stereotype.Service;

@Service
public class XunleiTransferRunnerServiceImpl implements IXunleiTransferRunnerService {
    private final ResourceHubProperties properties;
    private final XunleiClient client;
    private final IXunleiTransferTaskService taskService;
    private final IResourceDiscoveryResultService discoveryService;
    private final IResourceLinkService linkService;

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
        tasks.forEach(task -> submit(task, result));
        return result;
    }

    @Override public QuarkTransferRunResult submitOne(Long taskId) {
        XunleiTransferTask task = taskService.getById(taskId);
        if (task == null) throw new IllegalArgumentException("Xunlei transfer task not found");
        QuarkTransferRunResult result = new QuarkTransferRunResult(); result.setTaskId(taskId); submit(task, result); return result;
    }

    private void submit(XunleiTransferTask task, QuarkTransferRunResult result) {
        try {
            if ("WAITING_SHARE".equalsIgnoreCase(task.getStatus()) && task.getSavedPath() != null) {
                String share = client.createShare(task.getSavedPath());
                if (share == null) { result.setSkipped(result.getSkipped() + 1); return; }
                task.setShareUrl(share); task.setShareUrlHash(ResourceHubHashUtils.sha256(share));
                task.setStatus("SUCCEEDED"); task.setLastError(null); task.setFinishedAt(LocalDateTime.now());
                task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task); updatePublishedLink(task);
                result.setSubmitted(result.getSubmitted() + 1); return;
            }
            task.setStatus("RUNNING"); task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1); task.setStartedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task);
            XunleiClient.RestoreResult restore = client.restore(task.getOriginalUrl(), properties.getXunlei().getSavePath());
            task.setResponsePayload(restore.response()); task.setSavedPath(restore.parentId()); task.setStatus("SUBMITTED"); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task);
            XunleiClient.RestoreStatus status = client.await(restore.taskId());
            if (!status.success()) { task.setStatus("FAILED"); task.setLastError("Xunlei restore " + status.status()); result.setFailed(result.getFailed() + 1); }
            else { task.setStatus("WAITING_SHARE"); String share = client.createShare(restore.parentId()); if (share != null) { task.setShareUrl(share); task.setShareUrlHash(ResourceHubHashUtils.sha256(share)); task.setStatus("SUCCEEDED"); updatePublishedLink(task); result.setSubmitted(result.getSubmitted() + 1); } else { task.setLastError("Xunlei restore succeeded but share API did not return a URL"); result.setSubmitted(result.getSubmitted() + 1); } }
            task.setFinishedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task);
        } catch (Exception e) { task.setStatus("FAILED"); task.setLastError(e.getMessage()); task.setFinishedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task); result.setFailed(result.getFailed() + 1); if (result.getErrors().size() < 10) result.getErrors().add(e.getMessage()); }
    }

    private void updatePublishedLink(XunleiTransferTask task) {
        if (task.getDiscoveryResultId() == null || task.getShareUrl() == null) return;
        ResourceDiscoveryResult discovery = discoveryService.getById(task.getDiscoveryResultId()); if (discovery == null) return;
        discovery.setShareUrl(task.getShareUrl()); discovery.setShareUrlHash(task.getShareUrlHash()); discovery.setStatus("SAVED"); discovery.setUpdatedAt(LocalDateTime.now()); discoveryService.updateById(discovery);
        if (discovery.getResourceLinkId() != null) { ResourceLink link = linkService.getById(discovery.getResourceLinkId()); if (link != null) { link.setUrl(task.getShareUrl()); link.setUrlHash(task.getShareUrlHash()); link.setProvider("XUNLEI"); link.setStatus("ACTIVE"); link.setLinkStatus("NORMAL"); link.setUpdatedAt(LocalDateTime.now()); linkService.updateById(link); } }
    }
}
