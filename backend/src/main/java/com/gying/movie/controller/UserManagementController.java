package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.service.impl.RegistrationService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class UserManagementController {

    private final ISysUserService sysUserService;
    private final AuthHelper authHelper;
    private final JwtUtils jwtUtils;

    public UserManagementController(ISysUserService sysUserService, AuthHelper authHelper, JwtUtils jwtUtils) {
        this.sysUserService = sysUserService;
        this.authHelper = authHelper;
        this.jwtUtils = jwtUtils;
    }

    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> createUser(
            @jakarta.validation.Valid @RequestBody com.gying.movie.dto.AdminCreateUserRequest request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        String role = request.getRole();
        if (!"USER".equals(role) && !"PUBLISHER".equals(role)) {
            return ResponseEntity.badRequest().body("新用户角色只能为 USER 或 PUBLISHER");
        }
        String email = com.gying.movie.service.impl.RegistrationService.normalizeEmail(request.getEmail());
        // Explicit admin-only path: no public-registration toggle, invitation consumption or email-code bypass for visitors.
        SysUser user = sysUserService.register(request.getUsername().trim(), request.getPassword(), email, null);
        if (!role.equals(user.getRole())) {
            user.setRole(role);
            if (!sysUserService.updateById(user)) throw new IllegalStateException("Could not assign user role");
        }
        return ResponseEntity.status(201).body(Map.of("id", user.getId(), "username", user.getUsername(),
                "email", user.getEmail(), "role", role, "enabled", true));
    }

    @GetMapping
    public ResponseEntity<?> getUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);

        Page<SysUser> userPage = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        QueryWrapper<SysUser> query = new QueryWrapper<>();

        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like("username", keyword).or().like("email", keyword));
        }
        if (role != null && !role.isBlank()) {
            query.eq("role", role);
        }
        if (enabled != null) {
            query.eq("enabled", enabled);
        }
        query.orderByDesc("created_at");

        Page<SysUser> result = sysUserService.page(userPage, query);
        result.getRecords().forEach(user -> user.setPassword(null));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<?> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser admin = authHelper.requireAdmin(token);

        String role = request.get("role");
        if (!"PUBLISHER".equals(role) && !"USER".equals(role)) {
            return ResponseEntity.badRequest().body("Invalid role. Must be PUBLISHER or USER");
        }
        if (admin.getId().equals(id)) {
            return ResponseEntity.badRequest().body("Cannot change your own role");
        }

        SysUser user = sysUserService.getById(id);
        if (user == null) {
            return ResponseEntity.status(404).body("User not found");
        }

        user.setRole(role);
        sysUserService.updateById(user);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "User role updated successfully");
        response.put("userId", id);
        response.put("newRole", role);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/impersonate")
    public ResponseEntity<?> impersonateUser(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        SysUser target = sysUserService.getById(id);
        if (target == null) {
            return ResponseEntity.status(404).body("User not found");
        }
        if (Boolean.FALSE.equals(target.getEnabled())) {
            return ResponseEntity.status(403).body("Cannot switch to a disabled account");
        }

        String targetToken = jwtUtils.generateToken(target.getId(), target.getUsername(), target.getRole());
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", target.getId());
        userInfo.put("username", target.getUsername());
        userInfo.put("role", target.getRole());

        Map<String, Object> response = new HashMap<>();
        response.put("token", targetToken);
        response.put("user", userInfo);
        response.put("message", "Account switched successfully");
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<?> updateUserEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser admin = authHelper.requireAdmin(token);
        if (admin.getId().equals(id) && !enabled) {
            return ResponseEntity.badRequest().body("Cannot disable your own account");
        }
        SysUser user = sysUserService.getById(id);
        if (user == null) {
            return ResponseEntity.status(404).body("User not found");
        }
        user.setEnabled(enabled);
        sysUserService.updateById(user);
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }

    @PutMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> updateUser(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser admin = authHelper.requireAdmin(token);
        SysUser user = sysUserService.getById(id);
        if (user == null) {
            return ResponseEntity.status(404).body("User not found");
        }

        String username = request.get("username");
        if (username != null) {
            String value = username.trim();
            if (value.length() < 3 || value.length() > 50) {
                return ResponseEntity.badRequest().body("用户名长度必须为 3-50 个字符");
            }
            if (!value.equals(user.getUsername())) {
                boolean taken = sysUserService.count(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, value).ne(SysUser::getId, id)) > 0;
                if (taken) return ResponseEntity.status(409).body("用户名已被占用");
                user.setUsername(value);
            }
        }

        String email = request.get("email");
        if (email != null) {
            String value;
            try {
                value = RegistrationService.normalizeEmail(email);
            } catch (org.springframework.web.server.ResponseStatusException error) {
                return ResponseEntity.badRequest().body("邮箱格式不正确");
            }
            if (!value.equals(user.getEmail())) {
                boolean taken = sysUserService.count(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getEmail, value).ne(SysUser::getId, id)) > 0;
                if (taken) return ResponseEntity.status(409).body("邮箱已被注册");
                user.setEmail(value);
            }
        }

        String role = request.get("role");
        if (role != null && !role.isBlank()) {
            if (!"USER".equals(role) && !"PUBLISHER".equals(role)) {
                return ResponseEntity.badRequest().body("角色只能为 USER 或 PUBLISHER");
            }
            if (admin.getId().equals(id)) {
                return ResponseEntity.badRequest().body("Cannot change your own role");
            }
            user.setRole(role);
        }

        try {
            sysUserService.updateById(user);
        } catch (org.springframework.dao.DuplicateKeyException error) {
            return ResponseEntity.status(409).body("用户名或邮箱已被占用");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("role", user.getRole());
        response.put("enabled", user.getEnabled());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<?> resetUserPassword(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser admin = authHelper.requireAdmin(token);
        String newPassword = request == null ? null : request.get("password");
        if (newPassword == null || newPassword.length() < 12) {
            return ResponseEntity.badRequest().body("密码至少 12 个字符");
        }
        if (newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body("密码最多 72 个 UTF-8 字节");
        }
        if (sysUserService.getById(id) == null) {
            return ResponseEntity.status(404).body("User not found");
        }
        sysUserService.resetPassword(id, newPassword);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Password reset successfully");
        response.put("userId", id);
        response.put("sessionsRevoked", true);
        response.put("self", admin.getId().equals(id));
        return ResponseEntity.ok(response);
    }
}
