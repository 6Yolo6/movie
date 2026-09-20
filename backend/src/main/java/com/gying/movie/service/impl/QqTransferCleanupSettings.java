package com.gying.movie.service.impl;

import com.gying.movie.service.ISysConfigService;
import org.springframework.stereotype.Component;

@Component
public class QqTransferCleanupSettings {
    public static final String KEY_ENABLED = "qq.bot.transfer_cleanup.enabled";
    public static final String KEY_DELAY_MINUTES = "qq.bot.transfer_cleanup.delay_minutes";
    public static final String KEY_QUARK_ROOT = "qq.bot.transfer_cleanup.quark_root";
    public static final String KEY_XUNLEI_ROOT = "qq.bot.transfer_cleanup.xunlei_root";

    private final ISysConfigService config;

    public QqTransferCleanupSettings(ISysConfigService config) {
        this.config = config;
    }

    public boolean enabled() {
        return Boolean.parseBoolean(config.getConfigValue(KEY_ENABLED, "true"));
    }

    public int delayMinutes() {
        try {
            return Math.min(Math.max(Integer.parseInt(config.getConfigValue(KEY_DELAY_MINUTES, "10")), 1), 10080);
        } catch (NumberFormatException ignored) {
            return 10;
        }
    }

    public String quarkRoot() {
        return normalizeRoot(config.getConfigValue(KEY_QUARK_ROOT, "/GYing QQ Temp"), "/GYing QQ Temp");
    }

    public String xunleiRoot() {
        return normalizeRoot(config.getConfigValue(KEY_XUNLEI_ROOT,
                "/影视剧资源分享(先转存后再查看)/GYing QQ Temp"),
                "/影视剧资源分享(先转存后再查看)/GYing QQ Temp");
    }

    public String quarkTarget(Long taskId) {
        return quarkRoot() + "/quark-" + safeId(taskId);
    }

    public String xunleiTarget(Long taskId) {
        return xunleiRoot() + "/xunlei-" + safeId(taskId);
    }

    public boolean isUnderQuarkRoot(String path) {
        return isUnder(path, quarkRoot());
    }

    public boolean isUnderXunleiRoot(String path) {
        return isUnder(path, xunleiRoot());
    }

    private boolean isUnder(String path, String root) {
        if (path == null || path.isBlank()) return false;
        String normalized = path.trim().replace('\\', '/').replaceAll("/+$", "");
        return normalized.startsWith(root + "/") && normalized.length() > root.length() + 1;
    }

    private String normalizeRoot(String value, String fallback) {
        String root = value == null || value.isBlank() ? fallback : value.trim();
        root = root.replace('\\', '/').replaceAll("/+", "/").replaceAll("/+$", "");
        return root.startsWith("/") ? root : "/" + root;
    }

    private String safeId(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("Transfer task must be saved before assigning QQ temporary path");
        }
        return taskId.toString();
    }
}
