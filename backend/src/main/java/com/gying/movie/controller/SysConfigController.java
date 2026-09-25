package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.utils.AuthHelper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/config")
public class SysConfigController {

    static final String REDACTED = "[REDACTED]";
    private static final Pattern CONFIG_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)(password|passwd|secret|token|cookie|authorization|credential|passcode|"
                    + "api[_-]?key|access[_-]?key|private[_-]?key)");

    private final ISysConfigService sysConfigService;
    private final AuthHelper authHelper;

    public SysConfigController(ISysConfigService sysConfigService, AuthHelper authHelper) {
        this.sysConfigService = sysConfigService;
        this.authHelper = authHelper;
    }

    @GetMapping
    public ResponseEntity<?> getAllConfigs(@RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        List<Map<String, Object>> configs = sysConfigService
                .list(new QueryWrapper<SysConfig>().orderByAsc("config_key"))
                .stream()
                .map(this::view)
                .collect(Collectors.toList());
        if (configs.stream().noneMatch(item -> "resource.search.rate_limit_per_minute".equals(item.get("configKey")))) {
            SysConfig defaultLimit = new SysConfig();
            defaultLimit.setConfigKey("resource.search.rate_limit_per_minute");
            defaultLimit.setConfigValue("5");
            defaultLimit.setDescription("网页资源搜索：每用户每分钟最多搜索次数（1-60），保存后立即生效；资源序号选择与翻页不计入。");
            configs.add(view(defaultLimit));
        }
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/{key}")
    public ResponseEntity<?> getConfig(
            @PathVariable String key,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        if (!validKey(key)) {
            return ResponseEntity.badRequest().body("Invalid configuration key");
        }
        String value = sysConfigService.getConfigValue(key, null);
        if (value == null) {
            return ResponseEntity.status(404).body("Configuration not found");
        }
        return ResponseEntity.ok(isSensitiveKey(key) ? REDACTED : value);
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> updateConfig(
            @PathVariable String key,
            @RequestBody(required = false) String value,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        if (!validKey(key)) {
            return ResponseEntity.badRequest().body("Invalid configuration key");
        }
        if (isSensitiveKey(key)) {
            return ResponseEntity.badRequest().body("Sensitive configuration must be managed through protected secret storage");
        }
        if (value != null && value.length() > 500) {
            return ResponseEntity.badRequest().body("Configuration value is too long");
        }
        if ("resource.search.rate_limit_per_minute".equals(key)) {
            try {
                int limit = Integer.parseInt(value == null ? "" : value.trim());
                if (limit < 1 || limit > 60) throw new NumberFormatException();
                value = String.valueOf(limit);
            } catch (NumberFormatException error) {
                return ResponseEntity.badRequest().body("网页资源搜索频率必须是 1-60 之间的整数");
            }
        }
        if ("auth.register.max_users".equals(key)) {
            try {
                int limit = Integer.parseInt(value == null ? "" : value.trim());
                if (limit < 0 || limit > 100000) throw new NumberFormatException();
                value = String.valueOf(limit);
            } catch (NumberFormatException error) {
                return ResponseEntity.badRequest().body("注册人数上限必须是 0-100000 之间的整数，0 表示不限制");
            }
        }
        boolean updated = sysConfigService.updateConfig(key, value == null ? "" : value);
        if (updated) {
            return ResponseEntity.ok("Configuration updated");
        }
        return ResponseEntity.status(404).body("Configuration not found");
    }

    static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).find();
    }

    private boolean validKey(String key) {
        return key != null && CONFIG_KEY.matcher(key).matches();
    }

    private Map<String, Object> view(SysConfig config) {
        boolean sensitive = isSensitiveKey(config.getConfigKey());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", config.getId());
        result.put("configKey", config.getConfigKey());
        result.put("configValue", sensitive ? REDACTED : config.getConfigValue());
        result.put("description", config.getDescription());
        result.put("createdAt", config.getCreatedAt());
        result.put("updatedAt", config.getUpdatedAt());
        result.put("sensitive", sensitive);
        return result;
    }
}
