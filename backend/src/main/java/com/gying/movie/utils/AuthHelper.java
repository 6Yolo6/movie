package com.gying.movie.utils;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
        var attributes = RequestContextHolder.getRequestAttributes();
        var request = attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
        if (request != null && request.getAttribute(AuthHelper.class.getName()) instanceof CachedAuth cached
                && java.util.Objects.equals(cached.authorization(), authorization)) return cached.user();
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        Claims claims = jwtUtils.validateToken(authorization.substring(7));
        if (claims == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        if (!(claims.get("id") instanceof Number) || claims.getId() == null || claims.getId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again");
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
        AuthUser result = new AuthUser(user.getId(), user.getUsername(), user.getRole());
        if (request != null) request.setAttribute(AuthHelper.class.getName(), new CachedAuth(authorization, result));
        return result;
    }

    private record CachedAuth(String authorization, AuthUser user) { }

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
