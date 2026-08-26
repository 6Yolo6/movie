package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.entity.QqBotSearchLog;
import com.gying.movie.entity.QqChannelPostLog;
import com.gying.movie.service.IQqAutomationConfigService;
import com.gying.movie.service.IQqBotSearchLogService;
import com.gying.movie.service.IQqChannelPostLogService;
import com.gying.movie.utils.AuthHelper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/qq-automation")
public class QqAutomationAdminController {

    private final AuthHelper authHelper;
    private final IQqAutomationConfigService configService;
    private final IQqBotSearchLogService qqBotSearchLogService;
    private final IQqChannelPostLogService qqChannelPostLogService;

    public QqAutomationAdminController(
            AuthHelper authHelper,
            IQqAutomationConfigService configService,
            IQqBotSearchLogService qqBotSearchLogService,
            IQqChannelPostLogService qqChannelPostLogService) {
        this.authHelper = authHelper;
        this.configService = configService;
        this.qqBotSearchLogService = qqBotSearchLogService;
        this.qqChannelPostLogService = qqChannelPostLogService;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", configService.getConfig());
        Map<String, Long> botStatusCounts = countByStatus("bot", null);
        Map<String, Long> channelStatusCounts = countByStatus("channel", null);
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        result.put("botStatusCounts", botStatusCounts);
        result.put("botRecentStatusCounts", countByStatus("bot", since));
        result.put("botSummary", botSummary(botStatusCounts, since));
        result.put("channelStatusCounts", channelStatusCounts);
        result.put("channelRecentStatusCounts", countByStatus("channel", since));
        return ApiResponse.ok(result);
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(configService.getConfig());
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> updateConfig(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ApiResponse.ok(configService.updateConfig(request));
    }

    @GetMapping("/bot-searches")
    public ApiResponse<Page<QqBotSearchLog>> botSearches(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        QueryWrapper<QqBotSearchLog> query = new QueryWrapper<>();
        if (hasText(status)) {
            query.eq("status", status.trim().toUpperCase());
        }
        if (hasText(keyword)) {
            String text = keyword.trim();
            query.and(w -> w.like("keyword", text).or().like("movie_id", text).or().like("user_key", text));
        }
        query.orderByDesc("created_at");
        return ApiResponse.ok(qqBotSearchLogService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query));
    }

    @GetMapping("/channel-posts")
    public ApiResponse<Page<QqChannelPostLog>> channelPosts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channelType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        QueryWrapper<QqChannelPostLog> query = new QueryWrapper<>();
        if (hasText(status)) {
            query.eq("status", status.trim().toUpperCase());
        }
        if (hasText(channelType)) {
            query.eq("channel_type", channelType.trim().toLowerCase());
        }
        if (hasText(keyword)) {
            String text = keyword.trim();
            query.and(w -> w.like("title", text).or().like("movie_id", text).or().like("link_url", text));
        }
        query.orderByDesc("created_at");
        return ApiResponse.ok(qqChannelPostLogService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query));
    }

    private Map<String, Long> countByStatus(String type, LocalDateTime since) {
        Map<String, Long> result = new LinkedHashMap<>();
        if ("bot".equals(type)) {
            for (String status : new String[] {
                    "SUCCEEDED", "NO_RESOURCE", "NO_METADATA", "TRAILER", "AMBIGUOUS",
                    "BLOCKED", "RATE_LIMITED", "REJECTED", "FAILED"
            }) {
                QueryWrapper<QqBotSearchLog> query = new QueryWrapper<QqBotSearchLog>().eq("status", status);
                if (since != null) {
                    query.ge("created_at", since);
                }
                result.put(status, qqBotSearchLogService.count(query));
            }
        } else {
            for (String status : new String[] {"POSTED", "FAILED", "SKIPPED"}) {
                QueryWrapper<QqChannelPostLog> query = new QueryWrapper<QqChannelPostLog>().eq("status", status);
                if (since != null) {
                    query.ge("created_at", since);
                }
                result.put(status, qqChannelPostLogService.count(query));
            }
        }
        return result;
    }

    private Map<String, Object> botSummary(Map<String, Long> statusCounts, LocalDateTime since) {
        long total = qqBotSearchLogService.count();
        long recent = qqBotSearchLogService.count(new QueryWrapper<QqBotSearchLog>().ge("created_at", since));
        long succeeded = count(statusCounts, "SUCCEEDED");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("last24Hours", recent);
        result.put("succeeded", succeeded);
        result.put("successRate", total == 0 ? 0D : Math.round(succeeded * 1000D / total) / 10D);
        result.put("noResult", count(statusCounts, "NO_RESOURCE")
                + count(statusCounts, "NO_METADATA")
                + count(statusCounts, "TRAILER"));
        result.put("ambiguous", count(statusCounts, "AMBIGUOUS"));
        result.put("blocked", count(statusCounts, "BLOCKED") + count(statusCounts, "RATE_LIMITED"));
        result.put("failed", count(statusCounts, "FAILED") + count(statusCounts, "REJECTED"));
        return result;
    }

    private long count(Map<String, Long> counts, String status) {
        return counts.getOrDefault(status, 0L);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
