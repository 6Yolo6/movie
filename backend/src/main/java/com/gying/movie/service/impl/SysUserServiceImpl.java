package com.gying.movie.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.SysUser;
import com.gying.movie.mapper.SysUserMapper;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private LoginDeviceService loginDeviceService;

    @Override
    public String login(String username, String password) {
        return login(username, password, null, null);
    }

    @Override
    public String login(String username, String password, String ipAddress, String userAgent) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }
        SysUser user = this.getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null || !BCrypt.checkpw(password, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account disabled");
        }
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());
        loginDeviceService.record(token, user.getId(), ipAddress, userAgent);
        return token;
    }

    @Override
    public SysUser register(String username, String password, String email, Long invitedByUserId) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        if (username.length() < 3 || username.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be 3-50 characters");
        }
        if (password == null || password.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        }
        if (password.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 100 characters");
        }
        if (this.count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (this.count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setEmail(email);
        user.setInvitedByUserId(invitedByUserId);
        user.setRole("USER");
        user.setScore(0);
        user.setEnabled(true);
        try {
            this.save(user);
        } catch (org.springframework.dao.DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or email already exists");
        }
        return user;
    }

    @Override
    public void resetPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        }
        if (newPassword.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 100 characters");
        }
        SysUser user = this.getById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        user.setPassword(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        this.updateById(user);
    }
}
