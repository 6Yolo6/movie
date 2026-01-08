package com.gying.movie.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.SysUser;
import com.gying.movie.mapper.SysUserMapper;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public String login(String username, String password) {
        SysUser user = this.getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null || !BCrypt.checkpw(password, user.getPassword())) {
            throw new RuntimeException("Invalid username or password");
        }
        return jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());
    }

    @Override
    public void register(String username, String password) {
        if (this.count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0) {
            throw new RuntimeException("Username already exists");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setRole("PUBLISHER"); // Default role for new users
        user.setScore(0);
        this.save(user);
    }

    @Override
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = this.getById(userId);
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        user.setPassword(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        this.updateById(user);
    }
}
