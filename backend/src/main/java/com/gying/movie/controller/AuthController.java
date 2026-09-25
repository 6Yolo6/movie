package com.gying.movie.controller;

import com.gying.movie.dto.AuthRequest;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.service.impl.EmailVerificationService;
import com.gying.movie.service.impl.RegistrationService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.JwtUtils;
import com.gying.movie.service.impl.LoginDeviceService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.util.Locale;
import com.gying.movie.security.ClientIpResolver;
import com.gying.movie.security.RedisRateLimiter;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final int MAX_REGISTRATIONS_PER_HOUR = 3;

    private final ISysUserService sysUserService;
    private final AuthHelper authHelper;
    private final RedisRateLimiter rateLimiter;
    private final RegistrationService registrationService;
    private final EmailVerificationService emailVerificationService;
    private final JwtUtils jwtUtils;
    private final LoginDeviceService loginDeviceService;

    @PostMapping("/login")
    public Map<String, Object> login(HttpServletRequest httpRequest, @Valid @RequestBody AuthRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }
        rateLimiter.require("login-account", request.getUsername().trim().toLowerCase(Locale.ROOT), 10, Duration.ofMinutes(5));
        String token = sysUserService.login(request.getUsername(), request.getPassword(), getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("message", "Login successful");
        return result;
    }

    @GetMapping("/devices")
    public Map<String, Object> devices(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthUser user = authHelper.requireUser(authorization);
        Claims claims = jwtUtils.validateToken(authorization.substring(7));
        return Map.of("devices", loginDeviceService.list(user.getId(), claims == null ? null : claims.getId()));
    }

    @DeleteMapping("/devices/{id}")
    public Map<String, Object> revokeDevice(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthUser user = authHelper.requireUser(authorization);
        return Map.of("revoked", loginDeviceService.revoke(user.getId(), id));
    }

    @GetMapping("/registration-policy")
    public Map<String, Object> registrationPolicy(@RequestParam(required = false) String invite) {
        return registrationService.policy(invite);
    }

    @PostMapping("/email-code")
    public Map<String, Object> sendEmailCode(HttpServletRequest request, @RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String inviteCode = body == null ? null : body.get("inviteCode");
        RegistrationService.normalizeEmail(email);
        Map<String, Object> policy = registrationService.policy(inviteCode);
        if (!Boolean.TRUE.equals(policy.get("registrationAllowed"))) {
            boolean limitReached = Boolean.TRUE.equals(policy.get("registrationLimitReached"));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    limitReached ? "Registration limit reached" : "Registration is invite-only");
        }
        try {
            emailVerificationService.send(email, getClientIp(request));
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        return Map.of("message", "Verification code sent");
    }

    @PostMapping("/register")
    public Map<String, Object> register(HttpServletRequest request, @Valid @RequestBody AuthRequest authRequest) {
        if (authRequest == null || authRequest.getUsername() == null || authRequest.getPassword() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }
        rateLimiter.require("register", getClientIp(request), MAX_REGISTRATIONS_PER_HOUR, Duration.ofHours(1));
        registrationService.register(authRequest.getUsername(), authRequest.getPassword(), authRequest.getEmail(), authRequest.getEmailCode(), authRequest.getInviteCode());
        return Map.of("message", "Registration successful");
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
        return ClientIpResolver.resolved(request);
    }
}
