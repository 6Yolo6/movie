package com.gying.movie.controller;

import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/config")
public class SysConfigController {

    @Autowired
    private ISysConfigService sysConfigService;

    @Autowired
    private JwtUtils jwtUtils;

    // Get all configurations
    @GetMapping
    public ResponseEntity<?> getAllConfigs(@RequestHeader(value = "Authorization", required = false) String token) {
        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        List<SysConfig> configs = sysConfigService.list();
        return ResponseEntity.ok(configs);
    }

    // Get specific configuration
    @GetMapping("/{key}")
    public ResponseEntity<?> getConfig(
            @PathVariable String key,
            @RequestHeader(value = "Authorization", required = false) String token) {
        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        String value = sysConfigService.getConfigValue(key, null);
        if (value == null) {
            return ResponseEntity.status(404).body("Configuration not found");
        }
        return ResponseEntity.ok(value);
    }

    // Update configuration
    @PutMapping("/{key}")
    public ResponseEntity<?> updateConfig(
            @PathVariable String key,
            @RequestBody String value,
            @RequestHeader(value = "Authorization", required = false) String token) {
        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        boolean updated = sysConfigService.updateConfig(key, value);
        if (updated) {
            return ResponseEntity.ok("Configuration updated");
        }
        return ResponseEntity.status(404).body("Configuration not found");
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
