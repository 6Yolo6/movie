package com.gying.movie.controller;

import com.gying.movie.dto.AuthRequest;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.utils.AuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REGISTER_RATE_LIMIT_KEY = "register:rate:";
    private static final int MAX_REGISTRATIONS_PER_HOUR = 3;

    private final ISysUserService sysUserService;
    private final AuthHelper authHelper;
    private final StringRedisTemplate stringRedisTemplate;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody AuthRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }
        String token = sysUserService.login(request.getUsername(), request.getPassword());
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("message", "Login successful");
        return result;
    }

    @PostMapping("/register")
    public Map<String, Object> register(
            HttpServletRequest request,
            @RequestBody AuthRequest authRequest,
            @RequestParam(required = false) String captchaId,
            @RequestParam(required = false) String captchaCode) {
        if (authRequest == null || authRequest.getUsername() == null || authRequest.getPassword() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }

        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification code is required");
        }
        String redisKey = "captcha:" + captchaId;
        String storedCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (storedCode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Captcha expired, please request a new one");
        }
        if (!storedCode.equalsIgnoreCase(captchaCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification code");
        }
        stringRedisTemplate.delete(redisKey);

        String rateKey = REGISTER_RATE_LIMIT_KEY + getClientIp(request);
        Long count = stringRedisTemplate.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            stringRedisTemplate.expire(rateKey, 1, TimeUnit.HOURS);
        }
        if (count != null && count > MAX_REGISTRATIONS_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Registration limit exceeded. Try again later.");
        }

        sysUserService.register(authRequest.getUsername(), authRequest.getPassword());
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Registration successful");
        return result;
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, String> body) {
        AuthUser user = authHelper.requireUser(token);
        String newPassword = body == null ? null : body.get("password");
        sysUserService.resetPassword(user.getId(), newPassword);
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Password reset successful");
        return result;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader("Authorization") String token) {
        AuthUser user = authHelper.requireUser(token);
        Map<String, Object> result = new HashMap<>();
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        result.put("id", user.getId());
        return result;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }
}
