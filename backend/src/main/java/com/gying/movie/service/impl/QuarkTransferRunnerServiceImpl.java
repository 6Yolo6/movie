package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.client.QuarkAutoSaveClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.utils.SeasonSearchUtils;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuarkTransferRunnerServiceImpl implements IQuarkTransferRunnerService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final ResourceHubProperties resourceHubProperties;
    private final QuarkAutoSaveClient quarkAutoSaveClient;
    private final IQuarkShareService quarkShareService;
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IMovieMetadataService movieService;
    private final ObjectMapper objectMapper;

    public QuarkTransferRunnerServiceImpl(
            ResourceHubProperties resourceHubProperties,
            QuarkAutoSaveClient quarkAutoSaveClient,
            IQuarkShareService quarkShareService,
            IQuarkTransferTaskService quarkTransferTaskService,
            IResourceDiscoveryResultService discoveryResultService,
            IMovieMetadataService movieService,
            ObjectMapper objectMapper) {
        this.resourceHubProperties = resourceHubProperties;
        this.quarkAutoSaveClient = quarkAutoSaveClient;
        this.quarkShareService = quarkShareService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.discoveryResultService = discoveryResultService;
        this.movieService = movieService;
        this.objectMapper = objectMapper;
    }

    @Override
    public QuarkTransferRunResult submitPending(int limit) {
        ensureEnabled();
        int safeLimit = Math.min(Math.max(limit <= 0 ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        List<QuarkTransferTask> tasks = quarkTransferTaskService.list(new QueryWrapper<QuarkTransferTask>()
                .eq("status", "PENDING")
                .orderByAsc("created_at")
                .last("LIMIT " + safeLimit));
        if (resourceHubProperties.getQuark().isShareEnabled() && tasks.size() < safeLimit) {
            int remaining = safeLimit - tasks.size();
            tasks.addAll(quarkTransferTaskService.list(new QueryWrapper<QuarkTransferTask>()
                    .eq("status", "SUBMITTED")
                    .isNull("share_url")
                    .orderByAsc("updated_at")
                    .last("LIMIT " + remaining)));
        }
        QuarkTransferRunResult result = new QuarkTransferRunResult();
        for (QuarkTransferTask task : tasks) {
            submit(task, result);
        }
        return result;
    }

    @Override
    public QuarkTransferRunResult submitOne(Long taskId) {
        ensureEnabled();
        QuarkTransferTask task = quarkTransferTaskService.getById(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quark transfer task not found");
        }
        QuarkTransferRunResult result = new QuarkTransferRunResult();
        result.setTaskId(taskId);
        submit(task, result);
        return result;
    }

    private void submit(QuarkTransferTask task, QuarkTransferRunResult result) {
        boolean alreadySubmitted = "SUBMITTED".equalsIgnoreCase(task.getStatus());
        if (!"PENDING".equalsIgnoreCase(task.getStatus())
                && !"FAILED".equalsIgnoreCase(task.getStatus())
                && !alreadySubmitted) {
            result.setSkipped(result.getSkipped() + 1);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            task.setStatus("RUNNING");
            task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
            task.setStartedAt(now);
            task.setLastError(null);
            task.setUpdatedAt(now);
            quarkTransferTaskService.updateById(task);

            MovieMetadata movie = movieService.getById(task.getMovieId());
            ResourceDiscoveryResult discovery = task.getDiscoveryResultId() == null
                    ? null
                    : discoveryResultService.getById(task.getDiscoveryResultId());
            String transferShareUrl = task.getOriginalUrl();
            boolean seasonTransfer = isSeasonTransfer(movie);
            boolean recursiveTransfer = seasonTransfer;
            if (seasonTransfer) {
                transferShareUrl = quarkAutoSaveClient.resolveSeasonShareUrl(
                        transferShareUrl,
                        movie.getSeason(),
                        discovery == null ? null : discovery.getTitle());
            } else if (movie != null) {
                QuarkAutoSaveClient.MovieShareSelection selection = quarkAutoSaveClient.resolveMovieShareUrl(
                        transferShareUrl,
                        movie.getTitleCn(),
                        movie.getTitleEn(),
                        movie.getAliases(),
                        movie.getYear());
                transferShareUrl = selection.shareUrl();
                recursiveTransfer = selection.recursive();
            }
            String taskName = buildTaskName(movie, task);
            String savePath = buildSavePath(movie, task);
            String updateSubdir = recursiveTransfer ? ".*" : null;

            Map<String, Object> requestPayload = resolveRequestPayload(task,
                    taskName,
                    transferShareUrl,
                    savePath,
                    updateSubdir);
            task.setRequestPayload(writePayload(requestPayload));
            if (resourceHubProperties.getQuark().isRunImmediately()) {
                quarkAutoSaveClient.requireAccountReady();
            }

            task.setSavedPath(savePath);
            JsonNode response = null;
            if (!alreadySubmitted) {
                response = quarkAutoSaveClient.addTask(requestPayload);
                task.setResponsePayload(writeResponsePayload(response, null));
            }
            if (resourceHubProperties.getQuark().isRunImmediately()) {
                String runOutput = quarkAutoSaveClient.runTaskNow(requestPayload);
                task.setResponsePayload(writeResponsePayload(response, runOutput));
                String runError = detectRunError(runOutput);
                if (runError != null) {
                    throw new IllegalStateException(runError);
                }
            }
            task.setStatus("SUBMITTED");
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            quarkTransferTaskService.updateById(task);
            ensureOwnShareUrl(task, result);
            result.setSubmitted(result.getSubmitted() + 1);
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setLastError(trim(e.getMessage(), 1000));
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            quarkTransferTaskService.updateById(task);
            markDiscoveryFailed(task, task.getLastError(), task.getUpdatedAt());
            result.setFailed(result.getFailed() + 1);
            addError(result, "task " + task.getId() + ": " + e.getMessage());
        }
    }

    private void ensureOwnShareUrl(QuarkTransferTask task, QuarkTransferRunResult result) {
        try {
            quarkShareService.ensureShareUrl(task);
        } catch (Exception e) {
            LocalDateTime now = LocalDateTime.now();
            String error = trim("share creation failed: " + e.getMessage(), 1000);
            task.setStatus("FAILED");
            task.setLastError(error);
            task.setFinishedAt(now);
            task.setUpdatedAt(now);
            quarkTransferTaskService.updateById(task);
            markDiscoveryFailed(task, error, now);
            result.setFailed(result.getFailed() + 1);
            addError(result, "task " + task.getId() + " share: " + e.getMessage());
        }
    }

    private void markDiscoveryFailed(QuarkTransferTask task, String error, LocalDateTime now) {
        if (task.getDiscoveryResultId() == null) {
            return;
        }
        ResourceDiscoveryResult discovery = discoveryResultService.getById(task.getDiscoveryResultId());
        if (discovery == null) {
            return;
        }
        discovery.setStatus("FAILED");
        discovery.setFailureReason(error);
        discovery.setUpdatedAt(now);
        discoveryResultService.updateById(discovery);
    }

    private Map<String, Object> resolveRequestPayload(
            QuarkTransferTask task,
            String taskName,
            String shareUrl,
            String savePath,
            String updateSubdir) {
        if (task.getRequestPayload() != null && !task.getRequestPayload().isBlank()) {
            try {
                Map<String, Object> payload = objectMapper.readValue(task.getRequestPayload(),
                        new TypeReference<Map<String, Object>>() {
                        });
                if (hasPayloadText(payload, "taskname")
                        && hasPayloadText(payload, "shareurl")
                        && hasPayloadText(payload, "savepath")) {
                    payload.put("taskname", taskName);
                    payload.put("shareurl", shareUrl);
                    payload.put("savepath", savePath);
                    payload.put("update_subdir",
                            updateSubdir == null ? "" : updateSubdir.trim());
                    removeEmptyRunWeek(payload);
                    return payload;
                }
            } catch (Exception ignored) {
            }
        }
        return quarkAutoSaveClient.buildTaskPayload(taskName, shareUrl, savePath, updateSubdir);
    }

    private void removeEmptyRunWeek(Map<String, Object> payload) {
        Object runWeek = payload.get("runweek");
        if (runWeek instanceof Collection<?> days && days.isEmpty()) {
            payload.remove("runweek");
        }
    }

    private boolean hasPayloadText(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof String text && !text.isBlank();
    }

    private String buildTaskName(MovieMetadata movie, QuarkTransferTask task) {
        if (isSeasonTransfer(movie)) {
            String title = SeasonSearchUtils.seasonQualifiedTitle(
                    firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), movie.getId()),
                    movie.getSeason());
            return movie.getYear() == null ? title : title + " (" + movie.getYear() + ")";
        }
        ResourceDiscoveryResult discovery = task.getDiscoveryResultId() == null
                ? null
                : discoveryResultService.getById(task.getDiscoveryResultId());
        if (discovery != null && discovery.getTitle() != null && !discovery.getTitle().isBlank()) {
            return discovery.getTitle().trim();
        }
        if (movie == null) {
            return "GYing-" + task.getMovieId();
        }
        String title = firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), movie.getId());
        if (movie.getYear() != null) {
            return title + " (" + movie.getYear() + ")";
        }
        return title;
    }

    private String buildSavePath(MovieMetadata movie, QuarkTransferTask task) {
        String basePath = resourceHubProperties.getQuark().getSavePath();
        if (basePath == null || basePath.isBlank()) {
            basePath = "/GYing Resource Hub";
        }
        String category = movie == null ? "unknown" : categoryDir(movie.getCategory());
        String rawTitle = movie == null ? task.getMovieId()
                : firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getId());
        String path = trimTrailingSlash(basePath) + "/" + category + "/"
                + sanitizePathSegment(SeasonSearchUtils.baseTitle(rawTitle));
        if (isSeasonTransfer(movie)) {
            return path + "/" + sanitizePathSegment(SeasonSearchUtils.seasonLabel(movie.getSeason()));
        }
        return path;
    }

    private boolean isSeasonTransfer(MovieMetadata movie) {
        if (movie == null || movie.getSeason() == null || movie.getSeason() <= 0) {
            return false;
        }
        return "tv".equalsIgnoreCase(movie.getCategory())
                || "ac".equalsIgnoreCase(movie.getCategory());
    }

    private String categoryDir(String category) {
        if ("tv".equalsIgnoreCase(category)) {
            return "tv";
        }
        if ("ac".equalsIgnoreCase(category)) {
            return "anime";
        }
        return "movie";
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String writeResponsePayload(JsonNode addTaskResponse, String runOutput) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            if (addTaskResponse != null) {
                payload.set("addTask", addTaskResponse);
            }
            if (runOutput != null) {
                payload.put("runNowOutput", trim(runOutput, 4000));
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return addTaskResponse == null ? "{}" : addTaskResponse.toString();
        }
    }

    private String detectRunError(String runOutput) {
        if (runOutput == null || runOutput.isBlank()) {
            return null;
        }
        String[] lines = runOutput.split("\\R");
        for (String line : lines) {
            String text = line.replaceFirst("^data:\\s*", "").trim();
            if (text.contains("❌")
                    || text.contains("好友已取消")
                    || text.contains("分享为空")
                    || text.contains("转存失败")
                    || text.contains("登录失败")
                    || text.contains("cookie无效")
                    || text.contains("不存在cookie必要参数")) {
                return trim("quark-auto-save run failed: " + text, 1000);
            }
        }
        return null;
    }

    private void ensureEnabled() {
        if (!resourceHubProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Hub is disabled");
        }
    }

    private String sanitizePathSegment(String value) {
        String text = firstText(value, "unknown");
        return text.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String firstText(String... values) {
        if (values == null) {
            return "unknown";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "unknown";
    }

    private void addError(QuarkTransferRunResult result, String message) {
        if (result.getErrors().size() < 10 && message != null && !message.isBlank()) {
            result.getErrors().add(trim(message, 500));
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
