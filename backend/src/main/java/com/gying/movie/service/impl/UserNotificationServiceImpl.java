package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
    public int markAllRead(Long userId) {
        if (userId == null) {
            return 0;
        }
        return baseMapper.update(null, new UpdateWrapper<UserNotification>()
                .set("read_flag", true)
                .eq("user_id", userId)
                .eq("read_flag", false));
    }

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
