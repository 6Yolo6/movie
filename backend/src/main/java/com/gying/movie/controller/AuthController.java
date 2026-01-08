package com.gying.movie.controller;

import com.gying.movie.dto.AuthRequest;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private ISysUserService sysUserService;

    @Autowired
    private JwtUtils jwtUtils;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody AuthRequest request) {
        String token = sysUserService.login(request.getUsername(), request.getPassword());
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("message", "Login successful");
        return result;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody AuthRequest request) {
        sysUserService.register(request.getUsername(), request.getPassword());
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Registration successful");
        return result;
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestHeader("Authorization") String token,
            @RequestBody Map<String, String> body) {
        // Validate token logic usually in Interceptor, but simplistic here for now
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            var claims = jwtUtils.validateToken(token);
            if (claims != null) {
                Long userId = ((Number) claims.get("id")).longValue();
                String newPassword = body.get("password");
                sysUserService.resetPassword(userId, newPassword);
                Map<String, Object> result = new HashMap<>();
                result.put("message", "Password reset successful");
                return result;
            }
        }
        throw new RuntimeException("Unauthorized");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader("Authorization") String token) {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            var claims = jwtUtils.validateToken(token);
            if (claims != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("username", claims.getSubject());
                result.put("role", claims.get("role"));
                result.put("id", claims.get("id"));
                return result;
            }
        }
        throw new RuntimeException("Unauthorized");
    }
}
