package com.gying.movie.controller;

import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.utils.AuthHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/config")
public class SysConfigController {

    private final ISysConfigService sysConfigService;
    private final AuthHelper authHelper;

    public SysConfigController(ISysConfigService sysConfigService, AuthHelper authHelper) {
        this.sysConfigService = sysConfigService;
        this.authHelper = authHelper;
    }

    @GetMapping
    public ResponseEntity<?> getAllConfigs(@RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        List<SysConfig> configs = sysConfigService.list();
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/{key}")
    public ResponseEntity<?> getConfig(
            @PathVariable String key,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        String value = sysConfigService.getConfigValue(key, null);
        if (value == null) {
            return ResponseEntity.status(404).body("Configuration not found");
        }
        return ResponseEntity.ok(value);
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> updateConfig(
            @PathVariable String key,
            @RequestBody String value,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        boolean updated = sysConfigService.updateConfig(key, value);
        if (updated) {
            return ResponseEntity.ok("Configuration updated");
        }
        return ResponseEntity.status(404).body("Configuration not found");
    }
}
