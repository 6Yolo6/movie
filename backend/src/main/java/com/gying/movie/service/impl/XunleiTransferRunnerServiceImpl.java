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
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.ResourceHubHashUtils;
import com.gying.movie.entity.MovieMetadata;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class XunleiTransferRunnerServiceImpl implements IXunleiTransferRunnerService {
    private static final int MAX_TRANSFER_ATTEMPTS = 3;
    private final ResourceHubProperties properties;
    private final XunleiClient client;
    private final IXunleiTransferTaskService taskService;
    private final IResourceDiscoveryResultService discoveryService;
    private final IResourceLinkService linkService;
    @Autowired(required = false)
    private IMovieMetadataService movieService;
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
                .last("LIMIT 200"));
        tasks.stream()
                .filter(task -> !hasReachedRetryLimit(task))
                .limit(Math.min(Math.max(limit, 1), 20))
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
                List<String> restoredIds = client.extractRestoredFileIds(task.getResponsePayload());
                if (client.contentSummary(task.getSavedPath()).videoCount() == 0 && !restoredIds.isEmpty()) {
                    client.moveFiles(restoredIds, task.getSavedPath());
                }
                client.awaitContent(task.getSavedPath());
                String share = existingMovieFolderShare(task);
                if (share == null) share = client.createShare(task.getSavedPath());
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
            XunleiClient.RestoreResult restore = client.restore(sourceUrl, transferPath(task, discovery));
            task.setResponsePayload(restore.response()); task.setStatus("SUBMITTED"); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task);
            XunleiClient.RestoreStatus status = restore.reused()
                    ? new XunleiClient.RestoreStatus(true, "REUSED", null)
                    : client.await(restore.taskId());
            if (!status.success()) { task.setStatus("FAILED"); task.setLastError("Xunlei restore " + status.status()); result.setFailed(result.getFailed() + 1); }
            else {
                task.setStatus("WAITING_SHARE");
                task.setSavedPath(restore.parentId());
                List<String> restoredIds = new java.util.ArrayList<>();
                if (!restore.reused() && client.contentSummary(restore.parentId()).videoCount() == 0) {
                    restoredIds.addAll(client.extractRestoredFileIds(restore.response()));
                    client.extractRestoredFileIds(status.response()).stream()
                            .filter(id -> !restoredIds.contains(id))
                            .forEach(restoredIds::add);
                    if (restoredIds.isEmpty()) {
                        XunleiClient.RestoredSelection selected = client.awaitRestoredFiles(
                                restore.restoredFileId(), restore.expectedNames(), restore.startedAt());
                        restoredIds.addAll(selected.fileIds());
                    }
                    task.setResponsePayload(client.restoredFileIdsPayload(restoredIds));
                }
                task.setUpdatedAt(LocalDateTime.now());
                taskService.updateById(task);
                if (!restoredIds.isEmpty()) {
                    client.moveFiles(restoredIds, restore.parentId());
                }
                client.awaitContent(restore.parentId());
                String share = existingMovieFolderShare(task);
                if (share == null) share = client.createShare(restore.parentId());
                if (share != null) { task.setShareUrl(share); task.setShareUrlHash(ResourceHubHashUtils.sha256(share)); task.setStatus("SUCCEEDED"); task.setLastError(null); updatePublishedLink(task); result.setSubmitted(result.getSubmitted() + 1); } else { task.setLastError("Xunlei restore succeeded but share API did not return a URL"); result.setFailed(result.getFailed() + 1); if (result.getErrors().size() < 10) result.getErrors().add(task.getLastError()); }
            }
            task.setFinishedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task);
            if ("SUCCEEDED".equalsIgnoreCase(task.getStatus())) clearLastError(task.getId());
        } catch (Exception e) { if (!("WAITING_SHARE".equalsIgnoreCase(task.getStatus()) && task.getSavedPath() != null)) task.setStatus("FAILED"); task.setLastError(e.getMessage()); task.setFinishedAt(LocalDateTime.now()); task.setUpdatedAt(LocalDateTime.now()); taskService.updateById(task); result.setFailed(result.getFailed() + 1); if (result.getErrors().size() < 10) result.getErrors().add(e.getMessage()); }
    }

    private boolean hasReachedRetryLimit(XunleiTransferTask task) {
        return task != null
                && "FAILED".equalsIgnoreCase(task.getStatus())
                && task.getAttempts() != null
                && task.getAttempts() >= MAX_TRANSFER_ATTEMPTS;
    }

    private void clearLastError(Long taskId) {
        if (taskId != null) taskService.update(new UpdateWrapper<XunleiTransferTask>()
                .eq("id", taskId)
                .set("last_error", null));
    }

    private String existingMovieFolderShare(XunleiTransferTask task) {
        if (task == null || task.getMovieId() == null || task.getSavedPath() == null) {
            return null;
        }
        XunleiTransferTask existing = taskService.getOne(new QueryWrapper<XunleiTransferTask>()
                .eq("movie_id", task.getMovieId())
                .eq("saved_path", task.getSavedPath())
                .eq("status", "SUCCEEDED")
                .isNotNull("share_url")
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
        return existing == null ? null : existing.getShareUrl();
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
                ? "/影视剧资源分享(先转存后再查看)/GYing Resource Hub"
                : properties.getXunlei().getSavePath().replaceAll("/+$", "");
        String movieId = task == null || task.getMovieId() == null || task.getMovieId().isBlank()
                ? "unknown" : task.getMovieId().trim();
        return base + "/" + movieId.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String transferPath(XunleiTransferTask task, ResourceDiscoveryResult discovery) {
        String title = null;
        if (movieService != null && task != null && task.getMovieId() != null) {
            MovieMetadata movie = movieService.getById(task.getMovieId());
            if (movie != null) {
                title = firstText(movie.getTitleCn(), movie.getSeriesName(), movie.getTitleEn());
            }
        }
        if (!hasText(title) && discovery != null) {
            title = discovery.getTitle();
        }
        return transferPath(properties, task, title);
    }

    static String transferPath(ResourceHubProperties properties, XunleiTransferTask task, String title) {
        String base = properties.getXunlei().getSavePath() == null
                ? "/影视剧资源分享(先转存后再查看)/GYing Resource Hub"
                : properties.getXunlei().getSavePath().replaceAll("/+$", "");
        String movieId = task == null || task.getMovieId() == null || task.getMovieId().isBlank()
                ? "unknown" : task.getMovieId().trim();
        String safeId = movieId.replaceAll("[\\\\/:*?\"<>|]", "_");
        String safeTitle = hasText(title) ? title.trim().replaceAll("[\\\\/:*?\"<>|]", "_") : safeId;
        return base + "/" + safeTitle + "（" + safeId + "）";
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) return value.trim();
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
