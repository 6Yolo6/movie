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
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IQuarkTransferTaskService;
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
    private final IMovieMetadataService movieService;
    private final ObjectMapper objectMapper;

    public QuarkTransferRunnerServiceImpl(
            ResourceHubProperties resourceHubProperties,
            QuarkAutoSaveClient quarkAutoSaveClient,
            IQuarkShareService quarkShareService,
            IQuarkTransferTaskService quarkTransferTaskService,
            IMovieMetadataService movieService,
            ObjectMapper objectMapper) {
        this.resourceHubProperties = resourceHubProperties;
        this.quarkAutoSaveClient = quarkAutoSaveClient;
        this.quarkShareService = quarkShareService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.movieService = movieService;
        this.objectMapper = objectMapper;
    }

    @Override
    public QuarkTransferRunResult submitPending(int limit) {
        ensureEnabled();
        int safeLimit = Math.min(Math.max(limit <= 0 ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        QueryWrapper<QuarkTransferTask> query = new QueryWrapper<QuarkTransferTask>()
                .orderByAsc("created_at")
                .last("LIMIT " + safeLimit);
        if (resourceHubProperties.getQuark().isShareEnabled()) {
            query.in("status", List.of("PENDING", "SUBMITTED"))
                    .and(wrapper -> wrapper.eq("status", "PENDING").or().isNull("share_url"));
        } else {
            query.eq("status", "PENDING");
        }
        List<QuarkTransferTask> tasks = quarkTransferTaskService.list(query);
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
            String taskName = buildTaskName(movie, task);
            String savePath = buildSavePath(movie, task);
            Map<String, Object> requestPayload = resolveRequestPayload(task,
                    taskName,
                    task.getOriginalUrl(),
                    savePath);
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
            result.setFailed(result.getFailed() + 1);
            addError(result, "task " + task.getId() + ": " + e.getMessage());
        }
    }

    private void ensureOwnShareUrl(QuarkTransferTask task, QuarkTransferRunResult result) {
        try {
            quarkShareService.ensureShareUrl(task);
        } catch (Exception e) {
            task.setLastError(trim("share creation failed: " + e.getMessage(), 1000));
            task.setUpdatedAt(LocalDateTime.now());
            quarkTransferTaskService.updateById(task);
            addError(result, "task " + task.getId() + " share: " + e.getMessage());
        }
    }

    private Map<String, Object> resolveRequestPayload(
            QuarkTransferTask task,
            String taskName,
            String shareUrl,
            String savePath) {
        if (task.getRequestPayload() != null && !task.getRequestPayload().isBlank()) {
            try {
                Map<String, Object> payload = objectMapper.readValue(task.getRequestPayload(),
                        new TypeReference<Map<String, Object>>() {
                        });
                if (hasPayloadText(payload, "taskname")
                        && hasPayloadText(payload, "shareurl")
                        && hasPayloadText(payload, "savepath")) {
                    removeEmptyRunWeek(payload);
                    return payload;
                }
            } catch (Exception ignored) {
            }
        }
        return quarkAutoSaveClient.buildTaskPayload(taskName, shareUrl, savePath);
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
        String title = sanitizePathSegment(movie == null ? task.getMovieId()
                : firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getId()));
        return trimTrailingSlash(basePath) + "/" + category + "/" + title;
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
