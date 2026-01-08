package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class UserManagementController {

    @Autowired
    private ISysUserService sysUserService;

    @Autowired
    private JwtUtils jwtUtils;

    // Get all users with pagination
    @GetMapping
    public ResponseEntity<?> getUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestHeader(value = "Authorization", required = false) String token) {

        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        Page<SysUser> userPage = new Page<>(page, size);
        QueryWrapper<SysUser> query = new QueryWrapper<>();

        if (keyword != null && !keyword.isEmpty()) {
            query.like("username", keyword).or().like("email", keyword);
        }

        Page<SysUser> result = sysUserService.page(userPage, query);

        // Hide password in response
        result.getRecords().forEach(user -> user.setPassword(null));

        return ResponseEntity.ok(result);
    }

    // Update user role
    @PutMapping("/{id}/role")
    public ResponseEntity<?> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String token) {

        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        String role = request.get("role");
        if (!"ADMIN".equals(role) && !"PUBLISHER".equals(role) && !"USER".equals(role)) {
            return ResponseEntity.badRequest().body("Invalid role. Must be ADMIN, PUBLISHER, or USER");
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

    // Helper method to check admin role
    private boolean isAdmin(String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            return false;
        }

        Claims claims = jwtUtils.validateToken(token.substring(7));
        if (claims == null) {
            return false;
        }

        String role = claims.get("role", String.class);
        return "ADMIN".equals(role);
    }
}
