package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.UserNotification;
import com.gying.movie.mapper.UserNotificationMapper;
import com.gying.movie.service.IUserNotificationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserNotificationServiceImpl extends ServiceImpl<UserNotificationMapper, UserNotification>
        implements IUserNotificationService {

    @Override
    public void notifyUser(Long userId, String type, String title, String content, String targetType, String targetId) {
        if (userId == null) {
            return;
        }
        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setTargetType(targetType);
        notification.setTargetId(targetId);
        notification.setReadFlag(false);
        notification.setCreatedAt(LocalDateTime.now());
        save(notification);
    }
}
