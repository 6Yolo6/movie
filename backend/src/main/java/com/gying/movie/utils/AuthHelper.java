package com.gying.movie.utils;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthHelper {

    private final JwtUtils jwtUtils;
    private final SysUserMapper sysUserMapper;

    public AuthHelper(JwtUtils jwtUtils, SysUserMapper sysUserMapper) {
        this.jwtUtils = jwtUtils;
        this.sysUserMapper = sysUserMapper;
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

    public AuthUser requirePublisher(String authorization) {
        AuthUser user = requireUser(authorization);
        if (!"ADMIN".equalsIgnoreCase(user.getRole()) && !"PUBLISHER".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Publisher permission required");
        }
        return user;
    }
}
