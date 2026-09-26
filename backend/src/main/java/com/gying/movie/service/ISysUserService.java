package com.gying.movie.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.SysUser;

public interface ISysUserService extends IService<SysUser> {
    String login(String username, String password);

    String login(String username, String password, String ipAddress, String userAgent);

    SysUser register(String username, String password, String email, Long invitedByUserId);

    void resetPassword(Long userId, String newPassword);

    /** Cooldown between two self-service email changes, in days. */
    long EMAIL_CHANGE_INTERVAL_DAYS = 90;

    SysUser changeEmail(Long userId, String newEmail);

    /** 修改站内昵称；登录用户名不可改。 */
    SysUser updateNickname(Long userId, String nickname);
}
