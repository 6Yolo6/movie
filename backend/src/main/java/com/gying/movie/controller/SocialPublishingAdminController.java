package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.client.SocialPublisherClient;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.entity.SocialPostLog;
import com.gying.movie.entity.SocialPublishTarget;
import com.gying.movie.service.ISocialPostLogService;
import com.gying.movie.service.ISocialPublishTargetService;
import com.gying.movie.service.impl.SocialPublishingService;
import com.gying.movie.utils.AuthHelper;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/admin/social-publishing")
public class SocialPublishingAdminController {
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of("QQ_CHANNEL", "WEIBO");

    private final AuthHelper authHelper;
    private final ISocialPublishTargetService targetService;
    private final ISocialPostLogService logService;
    private final SocialPublishingService publishingService;
    private final SocialPublisherClient publisherClient;

    public SocialPublishingAdminController(
            AuthHelper authHelper,
            ISocialPublishTargetService targetService,
            ISocialPostLogService logService,
            SocialPublishingService publishingService,
            SocialPublisherClient publisherClient) {
        this.authHelper = authHelper;
        this.targetService = targetService;
        this.logService = logService;
        this.publishingService = publishingService;
        this.publisherClient = publisherClient;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targets", targetService.list(new QueryWrapper<SocialPublishTarget>().orderByAsc("id")));
        result.put("posted", logService.count(new QueryWrapper<SocialPostLog>().eq("status", "POSTED")));
        result.put("failed", logService.count(new QueryWrapper<SocialPostLog>().eq("status", "FAILED")));
        result.put("pending", logService.count(new QueryWrapper<SocialPostLog>().eq("status", "PENDING")));
        result.put("postedLast24Hours", logService.count(new QueryWrapper<SocialPostLog>()
                .eq("status", "POSTED")
                .ge("posted_at", LocalDateTime.now().minusHours(24))));
        try {
            result.put("publisher", publisherClient.health());
        } catch (Exception error) {
            result.put("publisher", Map.of("ok", false, "error", safeMessage(error)));
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/qq-accounts")
    public ApiResponse<Map<String, Object>> qqAccounts(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(publisherClient.qqAccounts());
    }

    @PostMapping("/qq-accounts/login")
    public ApiResponse<Map<String, Object>> startQqLogin(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        String accountKey = accountKey(request.get("accountKey"));
        return ApiResponse.ok(publisherClient.startQqLogin(Map.of(
                "accountKey", accountKey,
                "force", request.containsKey("force") && bool(request.get("force")))));
    }

    @GetMapping("/qq-accounts/{accountKey}/login-status")
    public ApiResponse<Map<String, Object>> qqLoginStatus(
            @PathVariable String accountKey,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(publisherClient.qqLoginStatus(accountKey(accountKey)));
    }

    @DeleteMapping("/qq-accounts/{accountKey}")
    public ApiResponse<Map<String, Object>> removeQqAccount(
            @PathVariable String accountKey,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        String key = accountKey(accountKey);
        List<SocialPublishTarget> targets = targetService.list(new QueryWrapper<SocialPublishTarget>()
                .eq("platform", "QQ_CHANNEL")
                .eq("account_key", key));
        for (SocialPublishTarget target : targets) {
            target.setEnabled(false);
            target.setAutoPostEnabled(false);
            target.setUpdatedAt(LocalDateTime.now());
        }
        if (!targets.isEmpty()) targetService.updateBatchById(targets);
        Map<String, Object> result = new LinkedHashMap<>(publisherClient.removeQqAccount(key));
        result.put("disabledTargets", targets.size());
        return ApiResponse.ok(result);
    }

    @PostMapping("/targets")
    public ApiResponse<SocialPublishTarget> createTarget(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        SocialPublishTarget target = new SocialPublishTarget();
        target.setPlatform(requiredPlatform(request.get("platform")));
        target.setAccountKey(requiredText(request.get("accountKey"), "Account key"));
        target.setName(requiredText(request.get("name"), "Target name"));
        target.setTargetRef(text(request.get("targetRef"), null));
        target.setChannelRef(text(request.get("channelRef"), null));
        target.setEnabled(request.containsKey("enabled") ? bool(request.get("enabled")) : true);
        target.setAutoPostEnabled(request.containsKey("autoPostEnabled")
                && bool(request.get("autoPostEnabled")));
        target.setScheduleTime(scheduleTime(request.get("scheduleTime"), "10:00"));
        target.setPostsPerRun(integer(request.get("postsPerRun"), 1, 20, 1));
        target.setPostIntervalSeconds(integer(request.get("postIntervalSeconds"), 0, 86400, 60));
        target.setTemplate(text(request.get("template"), defaultTemplate(target.getPlatform())));
        normalizeAndValidateTarget(target);
        ensureTargetUnique(target, null);
        LocalDateTime now = LocalDateTime.now();
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        try {
            targetService.save(target);
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("Publishing account target already exists");
        }
        return ApiResponse.ok(target);
    }

    @PutMapping("/targets/{id}")
    public ApiResponse<SocialPublishTarget> updateTarget(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        SocialPublishTarget target = targetService.getById(id);
        if (target == null) {
            throw new IllegalArgumentException("Social publish target not found");
        }
        if (request.containsKey("name")) target.setName(text(request.get("name"), target.getName()));
        if (request.containsKey("accountKey")) {
            target.setAccountKey(text(request.get("accountKey"), target.getAccountKey()));
        }
        if (request.containsKey("targetRef")) target.setTargetRef(text(request.get("targetRef"), null));
        if (request.containsKey("channelRef")) target.setChannelRef(text(request.get("channelRef"), null));
        if (request.containsKey("enabled")) target.setEnabled(bool(request.get("enabled")));
        if (request.containsKey("autoPostEnabled")) target.setAutoPostEnabled(bool(request.get("autoPostEnabled")));
        if (request.containsKey("scheduleTime")) {
            target.setScheduleTime(scheduleTime(request.get("scheduleTime"), "10:00"));
        }
        if (request.containsKey("postsPerRun")) target.setPostsPerRun(integer(request.get("postsPerRun"), 1, 20, 1));
        if (request.containsKey("postIntervalSeconds")) {
            target.setPostIntervalSeconds(integer(request.get("postIntervalSeconds"), 0, 86400, 60));
        }
        if (request.containsKey("template")) target.setTemplate(text(request.get("template"), ""));
        normalizeAndValidateTarget(target);
        ensureTargetUnique(target, id);
        target.setUpdatedAt(LocalDateTime.now());
        try {
            targetService.updateById(target);
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("Publishing account target already exists");
        }
        return ApiResponse.ok(target);
    }

    @DeleteMapping("/targets/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteTarget(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        SocialPublishTarget target = targetService.getById(id);
        if (target == null) {
            throw new IllegalArgumentException("Social publish target not found");
        }

        logService.update(new UpdateWrapper<SocialPostLog>()
                .eq("target_id", id)
                .eq("status", "PENDING")
                .set("status", "FAILED")
                .set("error_message", "Publishing target deleted")
                .set("updated_at", LocalDateTime.now()));
        if (!targetService.removeById(id)) {
            throw new IllegalStateException("Failed to delete social publish target");
        }

        return ApiResponse.ok(Map.of(
                "deleted", true,
                "targetId", id,
                "historyRetained", true));
    }

    @PostMapping("/targets/{id}/publish-next")
    public ApiResponse<Map<String, Object>> publishNext(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean runNow,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(publishingService.publishNext(id, runNow));
    }

    @PostMapping("/publish-next")
    public ApiResponse<Map<String, Object>> publishNextBatch(
            @RequestBody(required = false) List<Long> targetIds,
            @RequestParam(defaultValue = "true") boolean runNow,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(publishingService.publishNext(targetIds, runNow));
    }

    @PostMapping("/logs/{id}/retry")
    public ApiResponse<Map<String, Object>> retry(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(publishingService.retry(id));
    }

    @GetMapping("/logs")
    public ApiResponse<Page<SocialPostLog>> logs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platform,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        QueryWrapper<SocialPostLog> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) query.eq("status", status.trim().toUpperCase());
        if (platform != null && !platform.isBlank()) query.eq("platform", platform.trim().toUpperCase());
        query.orderByDesc("created_at");
        return ApiResponse.ok(logService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query));
    }

    private String text(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private String requiredText(Object value, String label) {
        String result = text(value, null);
        if (result == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return result;
    }

    private String accountKey(Object value) {
        String key = requiredText(value, "Account key");
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,31}")) {
            throw new IllegalArgumentException(
                    "QQ account key must be 2-32 characters using letters, numbers, _ or -");
        }
        return key;
    }

    private String requiredPlatform(Object value) {
        String platform = requiredText(value, "Platform").toUpperCase();
        if (!SUPPORTED_PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("Unsupported social publishing platform: " + platform);
        }
        return platform;
    }

    private void normalizeAndValidateTarget(SocialPublishTarget target) {
        target.setPlatform(requiredPlatform(target.getPlatform()));
        target.setAccountKey(requiredText(target.getAccountKey(), "Account key"));
        target.setName(requiredText(target.getName(), "Target name"));
        target.setScheduleTime(scheduleTime(target.getScheduleTime(), "10:00"));
        target.setPostsPerRun(integer(target.getPostsPerRun(), 1, 20, 1));
        target.setPostIntervalSeconds(integer(target.getPostIntervalSeconds(), 0, 86400, 60));
        if ("QQ_CHANNEL".equals(target.getPlatform())) {
            target.setAccountKey(accountKey(target.getAccountKey()));
            target.setTargetRef(requiredText(target.getTargetRef(), "QQ channel number"));
        } else {
            if (!"default".equals(target.getAccountKey())) {
                throw new IllegalArgumentException("Weibo publisher currently supports account key: default");
            }
            target.setTargetRef(text(target.getTargetRef(), "default"));
            target.setChannelRef(null);
        }
        target.setEnabled(Boolean.TRUE.equals(target.getEnabled()));
        target.setAutoPostEnabled(Boolean.TRUE.equals(target.getAutoPostEnabled()));
        target.setTemplate(text(target.getTemplate(), defaultTemplate(target.getPlatform())));
    }

    private void ensureTargetUnique(SocialPublishTarget target, Long excludedId) {
        QueryWrapper<SocialPublishTarget> query = new QueryWrapper<SocialPublishTarget>()
                .eq("platform", target.getPlatform())
                .eq("account_key", target.getAccountKey())
                .eq("target_ref", target.getTargetRef());
        if (excludedId != null) {
            query.ne("id", excludedId);
        }
        if (targetService.count(query) > 0) {
            throw new IllegalArgumentException("Publishing account target already exists");
        }
    }

    private String scheduleTime(Object value, String fallback) {
        String result = text(value, fallback);
        try {
            return LocalTime.parse(result).toString();
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Schedule time must use HH:mm");
        }
    }

    private String defaultTemplate(String platform) {
        if ("WEIBO".equals(platform)) {
            return "{{title}}（{{year}}）\n{{type}}\n{{intro}}\n{{link}}";
        }
        return "标题：{{title}}\n年份：{{year}}\n类型：{{type}}\n链接：{{link}}\n简介：{{intro}}";
    }

    private boolean bool(Object value) {
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int integer(Object value, int min, int max, int fallback) {
        try {
            return Math.min(Math.max(Integer.parseInt(String.valueOf(value)), min), max);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
