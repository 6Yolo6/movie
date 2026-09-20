package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.client.XunleiClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ResourceHubConfigRequest;
import com.gying.movie.dto.ResourceHubConfigResponse;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.service.ISysConfigService;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ResourceHubConfigServiceImpl implements IResourceHubConfigService {

    private static final Logger log = LoggerFactory.getLogger(ResourceHubConfigServiceImpl.class);

    private static final String KEY_ENABLED = "resource.hub.enabled";
    private static final String KEY_AUTO_APPROVE = "resource.hub.auto_approve";
    private static final String KEY_TMDB_AUTO_SYNC_ENABLED = "resource.hub.tmdb.auto_sync_enabled";
    private static final String KEY_TMDB_AUTO_SYNC_SOURCES = "resource.hub.tmdb.auto_sync_sources";
    private static final String KEY_TMDB_AUTO_SYNC_PAGE = "resource.hub.tmdb.auto_sync_page";
    private static final String KEY_TMDB_AUTO_SYNC_MAX_ITEMS = "resource.hub.tmdb.auto_sync_max_items";
    private static final String KEY_TMDB_AUTO_SYNC_INTERVAL_HOURS = "resource.hub.tmdb.auto_sync_interval_hours";
    private static final String KEY_TMDB_AUTO_DISCOVERY_ENABLED = "resource.hub.tmdb.auto_discovery_enabled";
    private static final String KEY_TMDB_DISCOVERY_MAX_RESULTS = "resource.hub.tmdb.discovery_max_results";
    private static final String KEY_TMDB_DISCOVERY_COOLDOWN_HOURS = "resource.hub.tmdb.discovery_cooldown_hours";
    private static final String KEY_GYING_DISCOVERY_ENABLED = "resource.hub.gying.discovery_enabled";
    private static final String KEY_GYING_AUTO_SYNC_ENABLED = "resource.hub.gying.auto_sync_enabled";
    private static final String KEY_GYING_AUTO_SYNC_SOURCES = "resource.hub.gying.auto_sync_sources";
    private static final String KEY_GYING_AUTO_SYNC_PAGE = "resource.hub.gying.auto_sync_page";
    private static final String KEY_GYING_AUTO_SYNC_MAX_ITEMS = "resource.hub.gying.auto_sync_max_items";
    private static final String KEY_GYING_AUTO_SYNC_INTERVAL_HOURS = "resource.hub.gying.auto_sync_interval_hours";
    private static final String KEY_WORKER_ENABLED = "resource.hub.worker.enabled";
    private static final String KEY_WORKER_TASK_LIMIT = "resource.hub.worker.task_limit";
    private static final String KEY_WORKER_QUARK_LIMIT = "resource.hub.worker.quark_limit";
    private static final String KEY_WORKER_PUBLISH_LIMIT = "resource.hub.worker.publish_limit";
    private static final String KEY_DISCOVERED_RETRY_ENABLED = "resource.hub.worker.discovered_retry_enabled";
    private static final String KEY_DISCOVERED_RETRY_LIMIT = "resource.hub.worker.discovered_retry_limit";
    private static final String KEY_DISCOVERED_RETRY_DELAY_MS = "resource.hub.worker.discovered_retry_delay_ms";
    private static final String KEY_DISCOVERED_RETRY_CRON = "resource.hub.worker.discovered_retry_cron";

    private final ResourceHubProperties properties;
    private final ISysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private XunleiClient xunleiClient;

    public ResourceHubConfigServiceImpl(ResourceHubProperties properties, ISysConfigService sysConfigService) {
        this(properties, sysConfigService, new ObjectMapper());
    }

    @Autowired
    public ResourceHubConfigServiceImpl(ResourceHubProperties properties, ISysConfigService sysConfigService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("Resource Hub database config reload skipped", e);
        }
    }

    @Override
    public synchronized ResourceHubConfigResponse reload() {
        ResourceHubProperties.Tmdb tmdb = properties.getTmdb();
        ResourceHubProperties.Gying gying = properties.getGying();
        ResourceHubProperties.Worker worker = properties.getWorker();

        properties.setEnabled(readBoolean(KEY_ENABLED, properties.isEnabled()));
        properties.setAutoApprove(readBoolean(KEY_AUTO_APPROVE, properties.isAutoApprove()));
        tmdb.setAutoSyncEnabled(readBoolean(KEY_TMDB_AUTO_SYNC_ENABLED, tmdb.isAutoSyncEnabled()));
        tmdb.setAutoSyncSources(readString(KEY_TMDB_AUTO_SYNC_SOURCES, tmdb.getAutoSyncSources()));
        tmdb.setAutoSyncPage(readInt(KEY_TMDB_AUTO_SYNC_PAGE, tmdb.getAutoSyncPage(), 1, 20));
        tmdb.setAutoSyncMaxItems(readInt(KEY_TMDB_AUTO_SYNC_MAX_ITEMS, tmdb.getAutoSyncMaxItems(), 1, 100));
        tmdb.setAutoSyncIntervalHours(readInt(KEY_TMDB_AUTO_SYNC_INTERVAL_HOURS, tmdb.getAutoSyncIntervalHours(), 1, 720));
        tmdb.setAutoDiscoveryEnabled(readBoolean(KEY_TMDB_AUTO_DISCOVERY_ENABLED, tmdb.isAutoDiscoveryEnabled()));
        tmdb.setDiscoveryMaxResults(readInt(KEY_TMDB_DISCOVERY_MAX_RESULTS, tmdb.getDiscoveryMaxResults(), 1, 50));
        tmdb.setDiscoveryCooldownHours(readInt(KEY_TMDB_DISCOVERY_COOLDOWN_HOURS, tmdb.getDiscoveryCooldownHours(), 1, 720));
        gying.setDiscoveryEnabled(readBoolean(KEY_GYING_DISCOVERY_ENABLED, gying.isDiscoveryEnabled()));
        gying.setAutoSyncEnabled(readBoolean(KEY_GYING_AUTO_SYNC_ENABLED, gying.isAutoSyncEnabled()));
        gying.setAutoSyncSources(readString(KEY_GYING_AUTO_SYNC_SOURCES, gying.getAutoSyncSources()));
        gying.setAutoSyncPage(readInt(KEY_GYING_AUTO_SYNC_PAGE, gying.getAutoSyncPage(), 1, 500));
        gying.setAutoSyncMaxItems(readInt(KEY_GYING_AUTO_SYNC_MAX_ITEMS, gying.getAutoSyncMaxItems(), 1, 20));
        gying.setAutoSyncIntervalHours(readInt(
                KEY_GYING_AUTO_SYNC_INTERVAL_HOURS, gying.getAutoSyncIntervalHours(), 1, 720));
        worker.setEnabled(readBoolean(KEY_WORKER_ENABLED, worker.isEnabled()));
        worker.setTaskLimit(readInt(KEY_WORKER_TASK_LIMIT, worker.getTaskLimit(), 1, 20));
        worker.setQuarkLimit(readInt(KEY_WORKER_QUARK_LIMIT, worker.getQuarkLimit(), 1, 20));
        worker.setPublishLimit(readInt(KEY_WORKER_PUBLISH_LIMIT, worker.getPublishLimit(), 1, 100));
        worker.setDiscoveredRetryEnabled(readBoolean(KEY_DISCOVERED_RETRY_ENABLED, worker.isDiscoveredRetryEnabled()));
        worker.setDiscoveredRetryLimit(readInt(KEY_DISCOVERED_RETRY_LIMIT, worker.getDiscoveredRetryLimit(), 1, 100));
        worker.setDiscoveredRetryDelayMs(readLong(KEY_DISCOVERED_RETRY_DELAY_MS, worker.getDiscoveredRetryDelayMs(), 0, 3600000));
        worker.setDiscoveredRetryCron(readString(KEY_DISCOVERED_RETRY_CRON, worker.getDiscoveredRetryCron()));

        ensureDefaults();
        return fromProperties();
    }

    @Override
    public synchronized ResourceHubConfigResponse getConfig() {
        return reload();
    }

    @Override
    public synchronized ResourceHubConfigResponse updateConfig(ResourceHubConfigRequest request) {
        if (request == null) {
            return getConfig();
        }

        ResourceHubProperties.Tmdb tmdb = properties.getTmdb();
        ResourceHubProperties.Gying gying = properties.getGying();
        ResourceHubProperties.Worker worker = properties.getWorker();
        ResourceHubProperties.Xunlei xunlei = properties.getXunlei();

        // Credentials are intentionally runtime-only. They are never written to sys_config.
        if (xunlei != null) {
            if (hasText(request.getXunleiAuthorization())) {
                xunlei.setAuthorization(request.getXunleiAuthorization().trim());
            }
            if (hasText(request.getXunleiCaptchaToken())) {
                xunlei.setCaptchaToken(request.getXunleiCaptchaToken().trim());
            }
        }

        if (request.getEnabled() != null) {
            properties.setEnabled(request.getEnabled());
            upsert(KEY_ENABLED, Boolean.toString(request.getEnabled()), "影视资源中心总开关");
        }
        if (request.getAutoApprove() != null) {
            properties.setAutoApprove(request.getAutoApprove());
            upsert(KEY_AUTO_APPROVE, Boolean.toString(request.getAutoApprove()), "影视资源中心自动入库资源是否直接通过审核");
        }
        if (request.getTmdbAutoSyncEnabled() != null) {
            tmdb.setAutoSyncEnabled(request.getTmdbAutoSyncEnabled());
            upsert(KEY_TMDB_AUTO_SYNC_ENABLED, Boolean.toString(request.getTmdbAutoSyncEnabled()), "是否按计划从 TMDB 自动同步影片元数据");
        }
        if (request.getTmdbAutoSyncSources() != null) {
            String value = normalizeSources(request.getTmdbAutoSyncSources());
            tmdb.setAutoSyncSources(value);
            upsert(KEY_TMDB_AUTO_SYNC_SOURCES, value, "TMDB 自动同步的数据源类型列表");
        }
        if (request.getTmdbAutoSyncPage() != null) {
            int value = clamp(request.getTmdbAutoSyncPage(), 1, 20);
            tmdb.setAutoSyncPage(value);
            upsert(KEY_TMDB_AUTO_SYNC_PAGE, Integer.toString(value), "TMDB 自动同步读取的目录页码");
        }
        if (request.getTmdbAutoSyncMaxItems() != null) {
            int value = clamp(request.getTmdbAutoSyncMaxItems(), 1, 100);
            tmdb.setAutoSyncMaxItems(value);
            upsert(KEY_TMDB_AUTO_SYNC_MAX_ITEMS, Integer.toString(value), "每轮 TMDB 自动同步最多处理的影片数");
        }
        if (request.getTmdbAutoSyncIntervalHours() != null) {
            int value = clamp(request.getTmdbAutoSyncIntervalHours(), 1, 720);
            tmdb.setAutoSyncIntervalHours(value);
            upsert(KEY_TMDB_AUTO_SYNC_INTERVAL_HOURS, Integer.toString(value), "TMDB 自动同步任务之间的最小间隔（小时）");
        }
        if (request.getTmdbAutoDiscoveryEnabled() != null) {
            tmdb.setAutoDiscoveryEnabled(request.getTmdbAutoDiscoveryEnabled());
            upsert(KEY_TMDB_AUTO_DISCOVERY_ENABLED, Boolean.toString(request.getTmdbAutoDiscoveryEnabled()), "TMDB 同步完成后是否自动创建资源发现任务");
        }
        if (request.getTmdbDiscoveryMaxResults() != null) {
            int value = clamp(request.getTmdbDiscoveryMaxResults(), 1, 50);
            tmdb.setDiscoveryMaxResults(value);
            upsert(KEY_TMDB_DISCOVERY_MAX_RESULTS, Integer.toString(value), "单次 PanSou 资源发现返回的最大候选数");
        }
        if (request.getTmdbDiscoveryCooldownHours() != null) {
            int value = clamp(request.getTmdbDiscoveryCooldownHours(), 1, 720);
            tmdb.setDiscoveryCooldownHours(value);
            upsert(KEY_TMDB_DISCOVERY_COOLDOWN_HOURS, Integer.toString(value), "同一影片再次自动发现资源前的冷却时间（小时）");
        }
        if (request.getGyingDiscoveryEnabled() != null) {
            gying.setDiscoveryEnabled(request.getGyingDiscoveryEnabled());
            upsert(KEY_GYING_DISCOVERY_ENABLED, Boolean.toString(request.getGyingDiscoveryEnabled()),
                    "自动发现资源时是否优先从 GYING 获取候选");
        }
        if (request.getGyingAutoSyncEnabled() != null) {
            gying.setAutoSyncEnabled(request.getGyingAutoSyncEnabled());
            upsert(KEY_GYING_AUTO_SYNC_ENABLED, Boolean.toString(request.getGyingAutoSyncEnabled()),
                    "是否按计划从 GYING 自动同步影片元数据");
        }
        if (request.getGyingAutoSyncSources() != null) {
            String value = normalizeGyingSources(request.getGyingAutoSyncSources());
            gying.setAutoSyncSources(value);
            upsert(KEY_GYING_AUTO_SYNC_SOURCES, value, "GYING 自动同步的数据源类型列表");
        }
        if (request.getGyingAutoSyncPage() != null) {
            int value = clamp(request.getGyingAutoSyncPage(), 1, 500);
            gying.setAutoSyncPage(value);
            upsert(KEY_GYING_AUTO_SYNC_PAGE, Integer.toString(value), "GYING 自动同步读取的目录页码");
        }
        if (request.getGyingAutoSyncMaxItems() != null) {
            int value = clamp(request.getGyingAutoSyncMaxItems(), 1, 20);
            gying.setAutoSyncMaxItems(value);
            upsert(KEY_GYING_AUTO_SYNC_MAX_ITEMS, Integer.toString(value), "每轮 GYING 自动同步最多处理的影片数");
        }
        if (request.getGyingAutoSyncIntervalHours() != null) {
            int value = clamp(request.getGyingAutoSyncIntervalHours(), 1, 720);
            gying.setAutoSyncIntervalHours(value);
            upsert(KEY_GYING_AUTO_SYNC_INTERVAL_HOURS, Integer.toString(value),
                    "GYING 自动同步任务之间的最小间隔（小时）");
        }
        if (request.getWorkerEnabled() != null) {
            worker.setEnabled(request.getWorkerEnabled());
            upsert(KEY_WORKER_ENABLED, Boolean.toString(request.getWorkerEnabled()), "是否启用影视资源中心后台 Worker");
        }
        if (request.getWorkerTaskLimit() != null) {
            int value = clamp(request.getWorkerTaskLimit(), 1, 20);
            worker.setTaskLimit(value);
            upsert(KEY_WORKER_TASK_LIMIT, Integer.toString(value), "Worker 每轮最多执行的资源发现任务数");
        }
        if (request.getWorkerQuarkLimit() != null) {
            int value = clamp(request.getWorkerQuarkLimit(), 1, 20);
            worker.setQuarkLimit(value);
            upsert(KEY_WORKER_QUARK_LIMIT, Integer.toString(value), "Worker 每轮最多提交的夸克转存任务数");
        }
        if (request.getWorkerPublishLimit() != null) {
            int value = clamp(request.getWorkerPublishLimit(), 1, 100);
            worker.setPublishLimit(value);
            upsert(KEY_WORKER_PUBLISH_LIMIT, Integer.toString(value), "Worker 每轮最多发布到正式资源库的发现结果数");
        }
        if (request.getDiscoveredRetryEnabled() != null) {
            worker.setDiscoveredRetryEnabled(request.getDiscoveredRetryEnabled());
            upsert(KEY_DISCOVERED_RETRY_ENABLED, Boolean.toString(request.getDiscoveredRetryEnabled()), "是否启用已发现但未完成转存资源的定时重试");
        }
        if (request.getDiscoveredRetryLimit() != null) {
            int value = clamp(request.getDiscoveredRetryLimit(), 1, 100);
            worker.setDiscoveredRetryLimit(value);
            upsert(KEY_DISCOVERED_RETRY_LIMIT, Integer.toString(value), "每轮定时重试最多处理的发现结果数");
        }
        if (request.getDiscoveredRetryDelayMs() != null) {
            long value = clamp(request.getDiscoveredRetryDelayMs(), 0L, 3600000L);
            worker.setDiscoveredRetryDelayMs(value);
            upsert(KEY_DISCOVERED_RETRY_DELAY_MS, Long.toString(value), "批量重试每条资源之间的等待时间（毫秒）");
        }
        if (request.getDiscoveredRetryCron() != null && hasText(request.getDiscoveredRetryCron())) {
            String value = request.getDiscoveredRetryCron().trim();
            worker.setDiscoveredRetryCron(value);
            upsert(KEY_DISCOVERED_RETRY_CRON, value, "已发现资源定时重试的 Cron 表达式");
        }

        return fromProperties();
    }

    private ResourceHubConfigResponse fromProperties() {
        ResourceHubProperties.Tmdb tmdb = properties.getTmdb();
        ResourceHubProperties.Gying gying = properties.getGying();
        ResourceHubProperties.Worker worker = properties.getWorker();
        ResourceHubConfigResponse response = new ResourceHubConfigResponse();
        ResourceHubProperties.Xunlei xunlei = properties.getXunlei();
        XunleiClient.AuthorizationStatus tokenState = xunleiClient == null
                ? null : xunleiClient.authorizationStatus();
        boolean configured = tokenState != null
                ? tokenState.configured()
                : xunlei != null && hasText(xunlei.getAuthorization());
        String expiresAt = tokenState != null && tokenState.expiresAt() > 0L
                ? DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(tokenState.expiresAt()))
                : xunleiAuthorizationExpiresAt(xunlei == null ? null : xunlei.getAuthorization());
        boolean expired = tokenState != null
                ? tokenState.expired()
                : expiresAt != null && Instant.parse(expiresAt).isBefore(Instant.now());
        response.setXunleiAuthorizationConfigured(configured);
        response.setXunleiAuthorizationExpiresAt(expiresAt);
        response.setXunleiAuthorizationExpired(expired);
        response.setXunleiCaptchaConfigured(xunlei != null && hasText(xunlei.getCaptchaToken()));
        response.setEnabled(properties.isEnabled());
        response.setAutoApprove(properties.isAutoApprove());
        response.setTmdbConfigured(hasText(tmdb.getApiKey()));
        response.setTmdbAutoSyncEnabled(tmdb.isAutoSyncEnabled());
        response.setTmdbAutoSyncSources(tmdb.getAutoSyncSources());
        response.setTmdbAutoSyncPage(tmdb.getAutoSyncPage());
        response.setTmdbAutoSyncMaxItems(tmdb.getAutoSyncMaxItems());
        response.setTmdbAutoSyncIntervalHours(tmdb.getAutoSyncIntervalHours());
        response.setTmdbAutoDiscoveryEnabled(tmdb.isAutoDiscoveryEnabled());
        response.setTmdbDiscoveryMaxResults(tmdb.getDiscoveryMaxResults());
        response.setTmdbDiscoveryCooldownHours(tmdb.getDiscoveryCooldownHours());
        response.setGyingDiscoveryEnabled(gying.isDiscoveryEnabled());
        response.setGyingAutoSyncEnabled(gying.isAutoSyncEnabled());
        response.setGyingAutoSyncSources(gying.getAutoSyncSources());
        response.setGyingAutoSyncPage(gying.getAutoSyncPage());
        response.setGyingAutoSyncMaxItems(gying.getAutoSyncMaxItems());
        response.setGyingAutoSyncIntervalHours(gying.getAutoSyncIntervalHours());
        response.setWorkerEnabled(worker.isEnabled());
        response.setWorkerFixedDelayMs(worker.getFixedDelayMs());
        response.setWorkerTaskLimit(worker.getTaskLimit());
        response.setWorkerQuarkLimit(worker.getQuarkLimit());
        response.setWorkerPublishLimit(worker.getPublishLimit());
        response.setDiscoveredRetryEnabled(worker.isDiscoveredRetryEnabled());
        response.setDiscoveredRetryLimit(worker.getDiscoveredRetryLimit());
        response.setDiscoveredRetryDelayMs(worker.getDiscoveredRetryDelayMs());
        response.setDiscoveredRetryCron(worker.getDiscoveredRetryCron());
        return response;
    }

    private String xunleiAuthorizationExpiresAt(String authorization) {
        if (!hasText(authorization)) {
            return null;
        }
        try {
            String token = authorization.trim().replaceFirst("(?i)^Bearer\\s+", "");
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            JsonNode payload = objectMapper.readTree(new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            long exp = payload.path("exp").asLong(0);
            return exp <= 0 ? null : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(exp));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void ensureDefaults() {
        Map<String, String[]> defaults = new LinkedHashMap<>();
        defaults.put(KEY_ENABLED, values(Boolean.toString(properties.isEnabled()), "影视资源中心总开关"));
        defaults.put(KEY_AUTO_APPROVE, values(Boolean.toString(properties.isAutoApprove()), "影视资源中心自动入库资源是否直接通过审核"));
        defaults.put(KEY_TMDB_AUTO_SYNC_ENABLED, values(Boolean.toString(properties.getTmdb().isAutoSyncEnabled()), "是否按计划从 TMDB 自动同步影片元数据"));
        defaults.put(KEY_TMDB_AUTO_SYNC_SOURCES, values(properties.getTmdb().getAutoSyncSources(), "TMDB 自动同步的数据源类型列表"));
        defaults.put(KEY_TMDB_AUTO_SYNC_PAGE, values(Integer.toString(properties.getTmdb().getAutoSyncPage()), "TMDB 自动同步读取的目录页码"));
        defaults.put(KEY_TMDB_AUTO_SYNC_MAX_ITEMS, values(Integer.toString(properties.getTmdb().getAutoSyncMaxItems()), "每轮 TMDB 自动同步最多处理的影片数"));
        defaults.put(KEY_TMDB_AUTO_SYNC_INTERVAL_HOURS, values(Integer.toString(properties.getTmdb().getAutoSyncIntervalHours()), "TMDB 自动同步任务之间的最小间隔（小时）"));
        defaults.put(KEY_TMDB_AUTO_DISCOVERY_ENABLED, values(Boolean.toString(properties.getTmdb().isAutoDiscoveryEnabled()), "TMDB 同步完成后是否自动创建资源发现任务"));
        defaults.put(KEY_TMDB_DISCOVERY_MAX_RESULTS, values(Integer.toString(properties.getTmdb().getDiscoveryMaxResults()), "单次 PanSou 资源发现返回的最大候选数"));
        defaults.put(KEY_TMDB_DISCOVERY_COOLDOWN_HOURS, values(Integer.toString(properties.getTmdb().getDiscoveryCooldownHours()), "同一影片再次自动发现资源前的冷却时间（小时）"));
        defaults.put(KEY_GYING_DISCOVERY_ENABLED, values(
                Boolean.toString(properties.getGying().isDiscoveryEnabled()),
                "自动发现资源时是否优先从 GYING 获取候选"));
        defaults.put(KEY_GYING_AUTO_SYNC_ENABLED, values(
                Boolean.toString(properties.getGying().isAutoSyncEnabled()),
                "是否按计划从 GYING 自动同步影片元数据"));
        defaults.put(KEY_GYING_AUTO_SYNC_SOURCES, values(
                properties.getGying().getAutoSyncSources(), "GYING 自动同步的数据源类型列表"));
        defaults.put(KEY_GYING_AUTO_SYNC_PAGE, values(
                Integer.toString(properties.getGying().getAutoSyncPage()), "GYING 自动同步读取的目录页码"));
        defaults.put(KEY_GYING_AUTO_SYNC_MAX_ITEMS, values(
                Integer.toString(properties.getGying().getAutoSyncMaxItems()), "每轮 GYING 自动同步最多处理的影片数"));
        defaults.put(KEY_GYING_AUTO_SYNC_INTERVAL_HOURS, values(
                Integer.toString(properties.getGying().getAutoSyncIntervalHours()),
                "GYING 自动同步任务之间的最小间隔（小时）"));
        defaults.put(KEY_WORKER_ENABLED, values(Boolean.toString(properties.getWorker().isEnabled()), "是否启用影视资源中心后台 Worker"));
        defaults.put(KEY_WORKER_TASK_LIMIT, values(Integer.toString(properties.getWorker().getTaskLimit()), "Worker 每轮最多执行的资源发现任务数"));
        defaults.put(KEY_WORKER_QUARK_LIMIT, values(Integer.toString(properties.getWorker().getQuarkLimit()), "Worker 每轮最多提交的夸克转存任务数"));
        defaults.put(KEY_WORKER_PUBLISH_LIMIT, values(Integer.toString(properties.getWorker().getPublishLimit()), "Worker 每轮最多发布到正式资源库的发现结果数"));
        defaults.put(KEY_DISCOVERED_RETRY_ENABLED, values(Boolean.toString(properties.getWorker().isDiscoveredRetryEnabled()), "是否启用已发现但未完成转存资源的定时重试"));
        defaults.put(KEY_DISCOVERED_RETRY_LIMIT, values(Integer.toString(properties.getWorker().getDiscoveredRetryLimit()), "每轮定时重试最多处理的发现结果数"));
        defaults.put(KEY_DISCOVERED_RETRY_DELAY_MS, values(Long.toString(properties.getWorker().getDiscoveredRetryDelayMs()), "批量重试每条资源之间的等待时间（毫秒）"));
        defaults.put(KEY_DISCOVERED_RETRY_CRON, values(properties.getWorker().getDiscoveredRetryCron(), "已发现资源定时重试的 Cron 表达式"));
        defaults.forEach((key, value) -> upsertMissing(key, value[0], value[1]));
    }

    private String[] values(String value, String description) {
        return new String[] {value, description};
    }

    private boolean readBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(sysConfigService.getConfigValue(key, Boolean.toString(defaultValue)));
    }

    private int readInt(String key, int defaultValue, int min, int max) {
        String value = sysConfigService.getConfigValue(key, Integer.toString(defaultValue));
        try {
            return clamp(Integer.parseInt(value), min, max);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long readLong(String key, long defaultValue, long min, long max) {
        String value = sysConfigService.getConfigValue(key, Long.toString(defaultValue));
        try {
            return Math.min(Math.max(Long.parseLong(value), min), max);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String readString(String key, String defaultValue) {
        return sysConfigService.getConfigValue(key, defaultValue);
    }

    private void upsertMissing(String key, String value, String description) {
        if (sysConfigService.count(new QueryWrapper<SysConfig>().eq("config_key", key)) > 0) {
            return;
        }
        insert(key, value, description);
    }

    private void upsert(String key, String value, String description) {
        SysConfig config = sysConfigService.getOne(new QueryWrapper<SysConfig>().eq("config_key", key));
        if (config == null) {
            insert(key, value, description);
            return;
        }
        config.setConfigValue(value);
        config.setDescription(description);
        sysConfigService.updateById(config);
    }

    private void insert(String key, String value, String description) {
        SysConfig config = new SysConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setDescription(description);
        sysConfigService.save(config);
    }

    private String normalizeSources(String raw) {
        if (!hasText(raw)) {
            return "TRENDING_MOVIE_DAY,TRENDING_TV_DAY,POPULAR_MOVIE,POPULAR_TV";
        }
        String normalized = String.join(",", java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(this::hasText)
                .map(String::toUpperCase)
                .distinct()
                .toList());
        return hasText(normalized) ? normalized : "TRENDING_MOVIE_DAY,TRENDING_TV_DAY,POPULAR_MOVIE,POPULAR_TV";
    }

    private String normalizeGyingSources(String raw) {
        if (!hasText(raw)) {
            return "HITS_MOVIE,HITS_TV,HITS_ANIME,CSCORE_MOVIE,CSCORE_TV,CSCORE_ANIME";
        }
        java.util.Set<String> supported = java.util.Set.of("HITS_MOVIE", "HITS_TV", "HITS_ANIME", "CSCORE_MOVIE", "CSCORE_TV", "CSCORE_ANIME");
        String normalized = String.join(",", java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(this::hasText)
                .map(String::toUpperCase)
                .filter(supported::contains)
                .distinct()
                .toList());
        return hasText(normalized) ? normalized : "HITS_MOVIE,HITS_TV,HITS_ANIME,CSCORE_MOVIE,CSCORE_TV,CSCORE_ANIME";
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
