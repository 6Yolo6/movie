package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysUserService;
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
}
