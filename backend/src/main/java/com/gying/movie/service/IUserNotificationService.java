package com.gying.movie.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.UserNotification;

public interface IUserNotificationService extends IService<UserNotification> {
    void notifyUser(Long userId, String type, String title, String content, String targetType, String targetId);
}
