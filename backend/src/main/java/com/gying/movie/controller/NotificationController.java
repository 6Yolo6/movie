package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.UserNotification;
import com.gying.movie.service.IUserNotificationService;
import com.gying.movie.utils.AuthHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final IUserNotificationService notificationService;
    private final AuthHelper authHelper;

    public NotificationController(IUserNotificationService notificationService, AuthHelper authHelper) {
        this.notificationService = notificationService;
        this.authHelper = authHelper;
    }

    @GetMapping
    public Page<UserNotification> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser user = authHelper.requireUser(token);
        QueryWrapper<UserNotification> query = new QueryWrapper<>();
        query.eq("user_id", user.getId());
        if (Boolean.TRUE.equals(unreadOnly)) {
            query.eq("read_flag", false);
        }
        query.orderByDesc("created_at");
        return notificationService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                query);
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser user = authHelper.requireUser(token);
        long count = notificationService.count(new QueryWrapper<UserNotification>()
                .eq("user_id", user.getId())
                .eq("read_flag", false));
        return Map.of("count", count);
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<?> markRead(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser user = authHelper.requireUser(token);
        UserNotification notification = notificationService.getById(id);
        if (notification == null || !user.getId().equals(notification.getUserId())) {
            return ResponseEntity.status(404).body("Notification not found");
        }
        notification.setReadFlag(true);
        notificationService.updateById(notification);
        return ResponseEntity.ok(Map.of("read", true));
    }

    @PutMapping("/read-all")
    public Map<String, Object> markAllRead(
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser user = authHelper.requireUser(token);
        var unread = notificationService.list(new QueryWrapper<UserNotification>()
                .eq("user_id", user.getId())
                .eq("read_flag", false));
        unread.forEach(notification -> notification.setReadFlag(true));
        if (!unread.isEmpty()) {
            notificationService.updateBatchById(unread);
        }
        return Map.of("updated", unread.size());
    }
}
