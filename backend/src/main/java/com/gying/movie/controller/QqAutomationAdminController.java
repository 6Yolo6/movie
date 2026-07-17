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
        result.put("botStatusCounts", countByStatus("bot"));
        result.put("channelStatusCounts", countByStatus("channel"));
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

    private Map<String, Long> countByStatus(String type) {
        Map<String, Long> result = new LinkedHashMap<>();
        if ("bot".equals(type)) {
            for (String status : new String[] {"SUCCEEDED", "NO_RESOURCE", "NO_METADATA", "TRAILER", "BLOCKED", "RATE_LIMITED", "REJECTED"}) {
                result.put(status, qqBotSearchLogService.count(new QueryWrapper<QqBotSearchLog>().eq("status", status)));
            }
        } else {
            for (String status : new String[] {"POSTED", "FAILED", "SKIPPED"}) {
                result.put(status, qqChannelPostLogService.count(new QueryWrapper<QqChannelPostLog>().eq("status", status)));
            }
        }
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
