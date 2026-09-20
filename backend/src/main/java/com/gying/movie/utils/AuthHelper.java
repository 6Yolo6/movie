package com.gying.movie.utils;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.gying.movie.service.impl.LoginDeviceService;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthHelper {

    private final JwtUtils jwtUtils;
    private final SysUserMapper sysUserMapper;
    private final LoginDeviceService loginDeviceService;

    public AuthHelper(JwtUtils jwtUtils, SysUserMapper sysUserMapper, LoginDeviceService loginDeviceService) {
        this.jwtUtils = jwtUtils;
        this.sysUserMapper = sysUserMapper;
        this.loginDeviceService = loginDeviceService;
    }

    public AuthUser requireUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        Claims claims = jwtUtils.validateToken(authorization.substring(7));
        if (claims == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        Long userId = ((Number) claims.get("id")).longValue();
        SysUser user = sysUserMapper.selectById(userId);
        String jti = claims.getId();
        if (jti != null && !loginDeviceService.isActive(userId, jti)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login device authorization revoked or expired");
        }
        if (jti != null) loginDeviceService.touch(userId, jti);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return new AuthUser(user.getId(), user.getUsername(), user.getRole());
    }

    public AuthUser requireAdmin(String authorization) {
        AuthUser user = requireUser(authorization);
        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
        return user;
    }

    public AuthUser requireResourcePublisher(String authorization) {
        return requirePublisher(authorization);
    }

    public AuthUser requirePublisher(String authorization) {
        AuthUser user = requireUser(authorization);
        if (!"ADMIN".equalsIgnoreCase(user.getRole()) && !"PUBLISHER".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Publisher permission required");
        }
        return user;
    }
}
