package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.IQqAutomationConfigService;
import com.gying.movie.service.ISysConfigService;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QqAutomationConfigServiceImpl implements IQqAutomationConfigService {

    private static final String KEY_BOT_MIN_KEYWORD_LENGTH = "qq.bot.min_keyword_length";
    private static final String KEY_BOT_RATE_LIMIT_PER_MINUTE = "qq.bot.rate_limit_per_minute";
    private static final String KEY_BOT_MAX_RESULTS = "qq.bot.max_results";
    private static final String KEY_BOT_BLOCKED_KEYWORDS = "qq.bot.blocked_keywords";
    private static final String KEY_CHANNEL_AUTO_POST_ENABLED = "qq.channel.auto_post.enabled";
    private static final String KEY_CHANNEL_INTERVAL_MINUTES = "qq.channel.auto_post.interval_minutes";
    private static final String KEY_CHANNEL_MAX_POSTS_PER_RUN = "qq.channel.auto_post.max_posts_per_run";
    private static final String KEY_CHANNEL_DAILY_TIME = "qq.channel.auto_post.daily_time";
    private static final String KEY_CHANNEL_POST_TOTAL = "qq.channel.auto_post.post_total";
    private static final String KEY_CHANNEL_POST_INTERVAL_SECONDS = "qq.channel.auto_post.post_interval_seconds";
    private static final String KEY_CHANNEL_POST_TEMPLATE = "qq.channel.auto_post.template";
    private static final String KEY_CHANNEL_CANDIDATE_LIMIT = "qq.channel.auto_post.candidate_limit";
    private static final String KEY_CHANNEL_GUILD_ID = "qq.channel.guild_id";
    private static final String KEY_CHANNEL_MOVIE_ID = "qq.channel.movie_channel_id";
    private static final String KEY_CHANNEL_TV_ID = "qq.channel.tv_channel_id";
    private static final String LEGACY_CHANNEL_POST_TEMPLATE = "\u6807\u9898\uff1a{{title}}\n"
            + "\u94fe\u63a5\uff1a{{link}}\n"
            + "\u7b80\u4ecb\uff1a{{intro}}";
    private static final String DEFAULT_CHANNEL_POST_TEMPLATE = "\u6807\u9898\uff1a{{title}}\n"
            + "\u5e74\u4efd\uff1a{{year}}\n"
            + "\u7c7b\u578b\uff1a{{type}}\n"
            + "\u94fe\u63a5\uff1a{{link}}\n"
            + "\u7b80\u4ecb\uff1a{{intro}}";

    private final QqBotProperties qqBotProperties;
    private final ISysConfigService sysConfigService;
    private final String defaultGuildId;
    private final String defaultMovieChannelId;
    private final String defaultTvChannelId;

    public QqAutomationConfigServiceImpl(QqBotProperties qqBotProperties,
            ISysConfigService sysConfigService,
            @Value("${qq.channel.guild-id:${QQ_CHANNEL_GUILD_ID:736090076}}") String defaultGuildId,
            @Value("${qq.channel.movie-id:${QQ_CHANNEL_MOVIE_ID:736142774}}") String defaultMovieChannelId,
            @Value("${qq.channel.tv-id:${QQ_CHANNEL_TV_ID:736142775}}") String defaultTvChannelId) {
        this.qqBotProperties = qqBotProperties;
        this.sysConfigService = sysConfigService;
        this.defaultGuildId = defaultText(defaultGuildId);
        this.defaultMovieChannelId = defaultText(defaultMovieChannelId);
        this.defaultTvChannelId = defaultText(defaultTvChannelId);
    }

    @PostConstruct
    public void init() {
        reload();
    }

    @Override
    public synchronized Map<String, Object> reload() {
        ensureDefaults();
        qqBotProperties.setMinKeywordLength(readInt(KEY_BOT_MIN_KEYWORD_LENGTH, qqBotProperties.getMinKeywordLength(), 1, 20));
        qqBotProperties.setRateLimitPerMinute(readInt(KEY_BOT_RATE_LIMIT_PER_MINUTE, qqBotProperties.getRateLimitPerMinute(), 0, 100));
        qqBotProperties.setMaxResults(readInt(KEY_BOT_MAX_RESULTS, qqBotProperties.getMaxResults(), 1, 5));
        qqBotProperties.setBlockedKeywords(readString(KEY_BOT_BLOCKED_KEYWORDS, qqBotProperties.getBlockedKeywords()));
        return getConfig();
    }

    @Override
    public synchronized Map<String, Object> getConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("botMinKeywordLength", readInt(KEY_BOT_MIN_KEYWORD_LENGTH, qqBotProperties.getMinKeywordLength(), 1, 20));
        result.put("botRateLimitPerMinute", readInt(KEY_BOT_RATE_LIMIT_PER_MINUTE, qqBotProperties.getRateLimitPerMinute(), 0, 100));
        result.put("botMaxResults", readInt(KEY_BOT_MAX_RESULTS, qqBotProperties.getMaxResults(), 1, 5));
        result.put("botBlockedKeywords", readString(KEY_BOT_BLOCKED_KEYWORDS, qqBotProperties.getBlockedKeywords()));
        result.put("channelAutoPostEnabled", readBoolean(KEY_CHANNEL_AUTO_POST_ENABLED, false));
        result.put("channelIntervalMinutes", readInt(KEY_CHANNEL_INTERVAL_MINUTES, 60, 1, 10080));
        result.put("channelMaxPostsPerRun", readInt(KEY_CHANNEL_MAX_POSTS_PER_RUN, 1, 1, 20));
        result.put("channelDailyTime", readString(KEY_CHANNEL_DAILY_TIME, "09:00"));
        result.put("channelPostTotal", readInt(KEY_CHANNEL_POST_TOTAL, readInt(KEY_CHANNEL_MAX_POSTS_PER_RUN, 1, 1, 20), 1, 100));
        result.put("channelPostIntervalSeconds", readInt(KEY_CHANNEL_POST_INTERVAL_SECONDS, 60, 0, 86400));
        result.put("channelPostTemplate", readString(KEY_CHANNEL_POST_TEMPLATE, DEFAULT_CHANNEL_POST_TEMPLATE));
        result.put("channelCandidateLimit", readInt(KEY_CHANNEL_CANDIDATE_LIMIT, 10, 1, 100));
        result.put("channelGuildId", readString(KEY_CHANNEL_GUILD_ID, defaultGuildId));
        result.put("channelMovieId", readString(KEY_CHANNEL_MOVIE_ID, defaultMovieChannelId));
        result.put("channelTvId", readString(KEY_CHANNEL_TV_ID, defaultTvChannelId));
        return result;
    }

    @Override
    public synchronized Map<String, Object> updateConfig(Map<String, Object> request) {
        if (request == null) {
            return getConfig();
        }
        putInt(request, "botMinKeywordLength", KEY_BOT_MIN_KEYWORD_LENGTH, 1, 20, "QQ bot minimum search keyword length");
        putInt(request, "botRateLimitPerMinute", KEY_BOT_RATE_LIMIT_PER_MINUTE, 0, 100, "QQ bot per-user search rate limit");
        putInt(request, "botMaxResults", KEY_BOT_MAX_RESULTS, 1, 5, "QQ bot maximum reply resources");
        putString(request, "botBlockedKeywords", KEY_BOT_BLOCKED_KEYWORDS, "QQ bot blocked search keywords");
        putBoolean(request, "channelAutoPostEnabled", KEY_CHANNEL_AUTO_POST_ENABLED, "Enable QQ channel auto posting");
        putInt(request, "channelIntervalMinutes", KEY_CHANNEL_INTERVAL_MINUTES, 1, 10080, "QQ channel auto post interval in minutes");
        putInt(request, "channelMaxPostsPerRun", KEY_CHANNEL_MAX_POSTS_PER_RUN, 1, 20, "QQ channel posts per run");
        putString(request, "channelDailyTime", KEY_CHANNEL_DAILY_TIME, "QQ channel daily post time HH:mm");
        putInt(request, "channelPostTotal", KEY_CHANNEL_POST_TOTAL, 1, 100, "QQ channel total posts per day");
        putInt(request, "channelPostIntervalSeconds", KEY_CHANNEL_POST_INTERVAL_SECONDS, 0, 86400, "QQ channel interval seconds between posts");
        putString(request, "channelPostTemplate", KEY_CHANNEL_POST_TEMPLATE, "QQ channel post template");
        putInt(request, "channelCandidateLimit", KEY_CHANNEL_CANDIDATE_LIMIT, 1, 100, "QQ channel candidate resource limit per run");
        putString(request, "channelGuildId", KEY_CHANNEL_GUILD_ID, "QQ channel guild ID");
        putString(request, "channelMovieId", KEY_CHANNEL_MOVIE_ID, "QQ channel movie board/channel ID");
        putString(request, "channelTvId", KEY_CHANNEL_TV_ID, "QQ channel TV board/channel ID");
        return reload();
    }

    private void ensureDefaults() {
        upsertMissing(KEY_BOT_MIN_KEYWORD_LENGTH, Integer.toString(qqBotProperties.getMinKeywordLength()), "QQ bot minimum search keyword length");
        upsertMissing(KEY_BOT_RATE_LIMIT_PER_MINUTE, Integer.toString(qqBotProperties.getRateLimitPerMinute()), "QQ bot per-user search rate limit");
        upsertMissing(KEY_BOT_MAX_RESULTS, Integer.toString(qqBotProperties.getMaxResults()), "QQ bot maximum reply resources");
        upsertMissing(KEY_BOT_BLOCKED_KEYWORDS, defaultText(qqBotProperties.getBlockedKeywords()), "QQ bot blocked search keywords");
        upsertMissing(KEY_CHANNEL_AUTO_POST_ENABLED, "false", "Enable QQ channel auto posting");
        upsertMissing(KEY_CHANNEL_INTERVAL_MINUTES, "60", "QQ channel auto post interval in minutes");
        upsertMissing(KEY_CHANNEL_MAX_POSTS_PER_RUN, "1", "QQ channel posts per run");
        upsertMissing(KEY_CHANNEL_DAILY_TIME, "09:00", "QQ channel daily post time HH:mm");
        upsertMissing(KEY_CHANNEL_POST_TOTAL, "1", "QQ channel total posts per day");
        upsertMissing(KEY_CHANNEL_POST_INTERVAL_SECONDS, "60", "QQ channel interval seconds between posts");
        upsertMissing(KEY_CHANNEL_POST_TEMPLATE, DEFAULT_CHANNEL_POST_TEMPLATE, "QQ channel post template");
        upsertMissing(KEY_CHANNEL_CANDIDATE_LIMIT, "10", "QQ channel candidate resource limit per run");
        upsertMissing(KEY_CHANNEL_GUILD_ID, defaultGuildId, "QQ channel guild ID");
        upsertMissing(KEY_CHANNEL_MOVIE_ID, defaultMovieChannelId, "QQ channel movie board/channel ID");
        upsertMissing(KEY_CHANNEL_TV_ID, defaultTvChannelId, "QQ channel TV board/channel ID");
    }

    private void putBoolean(Map<String, Object> request, String field, String key, String description) {
        if (request.containsKey(field)) {
            upsert(key, Boolean.toString(Boolean.parseBoolean(String.valueOf(request.get(field)))), description);
        }
    }

    private void putInt(Map<String, Object> request, String field, String key, int min, int max, String description) {
        if (!request.containsKey(field)) {
            return;
        }
        int value;
        try {
            value = Integer.parseInt(String.valueOf(request.get(field)));
        } catch (NumberFormatException e) {
            value = min;
        }
        upsert(key, Integer.toString(clamp(value, min, max)), description);
    }

    private void putString(Map<String, Object> request, String field, String key, String description) {
        if (request.containsKey(field)) {
            upsert(key, defaultText(request.get(field) == null ? "" : String.valueOf(request.get(field))), description);
        }
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
        String value = sysConfigService.getConfigValue(key, defaultText(defaultValue));
        if (value == null || value.isBlank()) {
            return defaultText(defaultValue);
        }
        if (KEY_CHANNEL_POST_TEMPLATE.equals(key)
                && (looksLikeMojibake(value) || LEGACY_CHANNEL_POST_TEMPLATE.equals(value))) {
            upsert(key, DEFAULT_CHANNEL_POST_TEMPLATE, "QQ channel post template");
            return DEFAULT_CHANNEL_POST_TEMPLATE;
        }
        return value;
    }

    private void upsertMissing(String key, String value, String description) {
        if (sysConfigService.count(new QueryWrapper<SysConfig>().eq("config_key", key)) == 0) {
            insert(key, value, description);
        }
    }

    private void upsert(String key, String value, String description) {
        SysConfig config = sysConfigService.getOne(new QueryWrapper<SysConfig>().eq("config_key", key), false);
        if (config == null) {
            insert(key, value, description);
            return;
        }
        config.setConfigValue(value);
        config.setDescription(description);
        config.setUpdatedAt(LocalDateTime.now());
        sysConfigService.updateById(config);
    }

    private void insert(String key, String value, String description) {
        SysConfig config = new SysConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setDescription(description);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        sysConfigService.save(config);
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }

    private boolean looksLikeMojibake(String value) {
        return value.contains("æ") || value.contains("é") || value.contains("ç")
                || value.contains("鏍") || value.contains("閾") || value.contains("绠");
    }
}
