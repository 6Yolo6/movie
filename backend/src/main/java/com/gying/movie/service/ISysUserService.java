package com.gying.movie.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.SysUser;

public interface ISysUserService extends IService<SysUser> {
    String login(String username, String password);

    String login(String username, String password, String ipAddress, String userAgent);

    SysUser register(String username, String password, String email, Long invitedByUserId);

    void resetPassword(Long userId, String newPassword);
}
