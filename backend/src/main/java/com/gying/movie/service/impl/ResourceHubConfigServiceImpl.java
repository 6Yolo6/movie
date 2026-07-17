package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ResourceHubConfigRequest;
import com.gying.movie.dto.ResourceHubConfigResponse;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.service.ISysConfigService;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String KEY_WORKER_ENABLED = "resource.hub.worker.enabled";
    private static final String KEY_WORKER_TASK_LIMIT = "resource.hub.worker.task_limit";
    private static final String KEY_WORKER_QUARK_LIMIT = "resource.hub.worker.quark_limit";
    private static final String KEY_WORKER_PUBLISH_LIMIT = "resource.hub.worker.publish_limit";

    private final ResourceHubProperties properties;
    private final ISysConfigService sysConfigService;

    public ResourceHubConfigServiceImpl(ResourceHubProperties properties, ISysConfigService sysConfigService) {
        this.properties = properties;
        this.sysConfigService = sysConfigService;
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
        worker.setEnabled(readBoolean(KEY_WORKER_ENABLED, worker.isEnabled()));
        worker.setTaskLimit(readInt(KEY_WORKER_TASK_LIMIT, worker.getTaskLimit(), 1, 20));
        worker.setQuarkLimit(readInt(KEY_WORKER_QUARK_LIMIT, worker.getQuarkLimit(), 1, 20));
        worker.setPublishLimit(readInt(KEY_WORKER_PUBLISH_LIMIT, worker.getPublishLimit(), 1, 100));

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
        ResourceHubProperties.Worker worker = properties.getWorker();

        if (request.getEnabled() != null) {
            properties.setEnabled(request.getEnabled());
            upsert(KEY_ENABLED, Boolean.toString(request.getEnabled()), "Enable Resource Hub automation");
        }
        if (request.getAutoApprove() != null) {
            properties.setAutoApprove(request.getAutoApprove());
            upsert(KEY_AUTO_APPROVE, Boolean.toString(request.getAutoApprove()), "Auto approve Resource Hub resources");
        }
        if (request.getTmdbAutoSyncEnabled() != null) {
            tmdb.setAutoSyncEnabled(request.getTmdbAutoSyncEnabled());
            upsert(KEY_TMDB_AUTO_SYNC_ENABLED, Boolean.toString(request.getTmdbAutoSyncEnabled()), "Enable TMDB scheduled metadata sync");
        }
        if (request.getTmdbAutoSyncSources() != null) {
            String value = normalizeSources(request.getTmdbAutoSyncSources());
            tmdb.setAutoSyncSources(value);
            upsert(KEY_TMDB_AUTO_SYNC_SOURCES, value, "TMDB scheduled sync sources");
        }
        if (request.getTmdbAutoSyncPage() != null) {
            int value = clamp(request.getTmdbAutoSyncPage(), 1, 20);
            tmdb.setAutoSyncPage(value);
            upsert(KEY_TMDB_AUTO_SYNC_PAGE, Integer.toString(value), "TMDB scheduled sync page");
        }
        if (request.getTmdbAutoSyncMaxItems() != null) {
            int value = clamp(request.getTmdbAutoSyncMaxItems(), 1, 100);
            tmdb.setAutoSyncMaxItems(value);
            upsert(KEY_TMDB_AUTO_SYNC_MAX_ITEMS, Integer.toString(value), "TMDB scheduled sync item limit");
        }
        if (request.getTmdbAutoSyncIntervalHours() != null) {
            int value = clamp(request.getTmdbAutoSyncIntervalHours(), 1, 720);
            tmdb.setAutoSyncIntervalHours(value);
            upsert(KEY_TMDB_AUTO_SYNC_INTERVAL_HOURS, Integer.toString(value), "TMDB scheduled sync interval in hours");
        }
        if (request.getTmdbAutoDiscoveryEnabled() != null) {
            tmdb.setAutoDiscoveryEnabled(request.getTmdbAutoDiscoveryEnabled());
            upsert(KEY_TMDB_AUTO_DISCOVERY_ENABLED, Boolean.toString(request.getTmdbAutoDiscoveryEnabled()), "Create discovery tasks after TMDB sync");
        }
        if (request.getTmdbDiscoveryMaxResults() != null) {
            int value = clamp(request.getTmdbDiscoveryMaxResults(), 1, 50);
            tmdb.setDiscoveryMaxResults(value);
            upsert(KEY_TMDB_DISCOVERY_MAX_RESULTS, Integer.toString(value), "PanSou discovery result limit");
        }
        if (request.getTmdbDiscoveryCooldownHours() != null) {
            int value = clamp(request.getTmdbDiscoveryCooldownHours(), 1, 720);
            tmdb.setDiscoveryCooldownHours(value);
            upsert(KEY_TMDB_DISCOVERY_COOLDOWN_HOURS, Integer.toString(value), "Discovery retry cooldown in hours");
        }
        if (request.getWorkerEnabled() != null) {
            worker.setEnabled(request.getWorkerEnabled());
            upsert(KEY_WORKER_ENABLED, Boolean.toString(request.getWorkerEnabled()), "Enable Resource Hub worker");
        }
        if (request.getWorkerTaskLimit() != null) {
            int value = clamp(request.getWorkerTaskLimit(), 1, 20);
            worker.setTaskLimit(value);
            upsert(KEY_WORKER_TASK_LIMIT, Integer.toString(value), "Tasks processed per worker run");
        }
        if (request.getWorkerQuarkLimit() != null) {
            int value = clamp(request.getWorkerQuarkLimit(), 1, 20);
            worker.setQuarkLimit(value);
            upsert(KEY_WORKER_QUARK_LIMIT, Integer.toString(value), "Quark transfers submitted per worker run");
        }
        if (request.getWorkerPublishLimit() != null) {
            int value = clamp(request.getWorkerPublishLimit(), 1, 100);
            worker.setPublishLimit(value);
            upsert(KEY_WORKER_PUBLISH_LIMIT, Integer.toString(value), "Discoveries published per worker run");
        }

        return fromProperties();
    }

    private ResourceHubConfigResponse fromProperties() {
        ResourceHubProperties.Tmdb tmdb = properties.getTmdb();
        ResourceHubProperties.Worker worker = properties.getWorker();
        ResourceHubConfigResponse response = new ResourceHubConfigResponse();
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
        response.setWorkerEnabled(worker.isEnabled());
        response.setWorkerFixedDelayMs(worker.getFixedDelayMs());
        response.setWorkerTaskLimit(worker.getTaskLimit());
        response.setWorkerQuarkLimit(worker.getQuarkLimit());
        response.setWorkerPublishLimit(worker.getPublishLimit());
        return response;
    }

    private void ensureDefaults() {
        Map<String, String[]> defaults = new LinkedHashMap<>();
        defaults.put(KEY_ENABLED, values(Boolean.toString(properties.isEnabled()), "Enable Resource Hub automation"));
        defaults.put(KEY_AUTO_APPROVE, values(Boolean.toString(properties.isAutoApprove()), "Auto approve Resource Hub resources"));
        defaults.put(KEY_TMDB_AUTO_SYNC_ENABLED, values(Boolean.toString(properties.getTmdb().isAutoSyncEnabled()), "Enable TMDB scheduled metadata sync"));
        defaults.put(KEY_TMDB_AUTO_SYNC_SOURCES, values(properties.getTmdb().getAutoSyncSources(), "TMDB scheduled sync sources"));
        defaults.put(KEY_TMDB_AUTO_SYNC_PAGE, values(Integer.toString(properties.getTmdb().getAutoSyncPage()), "TMDB scheduled sync page"));
        defaults.put(KEY_TMDB_AUTO_SYNC_MAX_ITEMS, values(Integer.toString(properties.getTmdb().getAutoSyncMaxItems()), "TMDB scheduled sync item limit"));
        defaults.put(KEY_TMDB_AUTO_SYNC_INTERVAL_HOURS, values(Integer.toString(properties.getTmdb().getAutoSyncIntervalHours()), "TMDB scheduled sync interval in hours"));
        defaults.put(KEY_TMDB_AUTO_DISCOVERY_ENABLED, values(Boolean.toString(properties.getTmdb().isAutoDiscoveryEnabled()), "Create discovery tasks after TMDB sync"));
        defaults.put(KEY_TMDB_DISCOVERY_MAX_RESULTS, values(Integer.toString(properties.getTmdb().getDiscoveryMaxResults()), "PanSou discovery result limit"));
        defaults.put(KEY_TMDB_DISCOVERY_COOLDOWN_HOURS, values(Integer.toString(properties.getTmdb().getDiscoveryCooldownHours()), "Discovery retry cooldown in hours"));
        defaults.put(KEY_WORKER_ENABLED, values(Boolean.toString(properties.getWorker().isEnabled()), "Enable Resource Hub worker"));
        defaults.put(KEY_WORKER_TASK_LIMIT, values(Integer.toString(properties.getWorker().getTaskLimit()), "Tasks processed per worker run"));
        defaults.put(KEY_WORKER_QUARK_LIMIT, values(Integer.toString(properties.getWorker().getQuarkLimit()), "Quark transfers submitted per worker run"));
        defaults.put(KEY_WORKER_PUBLISH_LIMIT, values(Integer.toString(properties.getWorker().getPublishLimit()), "Discoveries published per worker run"));
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

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
