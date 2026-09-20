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
    private static final String KEY_BOT_TRANSFER_CLEANUP_ENABLED = "qq.bot.transfer_cleanup.enabled";
    private static final String KEY_BOT_TRANSFER_CLEANUP_DELAY_MINUTES = "qq.bot.transfer_cleanup.delay_minutes";
    private static final String KEY_BOT_DAILY_RECOMMENDATION_ENABLED = "qq.bot.daily_recommendation.enabled";
    private static final String KEY_BOT_DAILY_RECOMMENDATION_TIME = "qq.bot.daily_recommendation.time";
    private static final String KEY_BOT_DAILY_RECOMMENDATION_COUNT = "qq.bot.daily_recommendation.count";
    private static final String KEY_BOT_DAILY_RECOMMENDATION_GROUP_IDS = "qq.bot.daily_recommendation.group_ids";
    private static final String KEY_BOT_DAILY_RECOMMENDATION_TEMPLATE = "qq.bot.daily_recommendation.template";
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
    static final String DEFAULT_BOT_DAILY_RECOMMENDATION_TEMPLATE = "\u3010\u6700\u8fd1\u66f4\u65b0\u3011{{title}} ({{year}})\n"
            + "\u7c7b\u578b\uff1a{{genres}}\n"
            + "\u8bc4\u5206\uff1a{{rating}}\n"
            + "\u7b80\u4ecb\uff1a{{summary}}\n"
            + "\u8d44\u6e90\uff1a{{resources}}";

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
        result.put("botTransferCleanupEnabled", readBoolean(KEY_BOT_TRANSFER_CLEANUP_ENABLED, true));
        result.put("botTransferCleanupDelayMinutes", readInt(KEY_BOT_TRANSFER_CLEANUP_DELAY_MINUTES, 10, 1, 10080));
        result.put("botDailyRecommendationEnabled", readBoolean(KEY_BOT_DAILY_RECOMMENDATION_ENABLED, true));
        result.put("botDailyRecommendationTime", readString(KEY_BOT_DAILY_RECOMMENDATION_TIME, "09:00"));
        result.put("botDailyRecommendationCount", readInt(KEY_BOT_DAILY_RECOMMENDATION_COUNT, 3, 1, 10));
        result.put("botDailyRecommendationGroupIds", readString(KEY_BOT_DAILY_RECOMMENDATION_GROUP_IDS, qqBotProperties.getAllowedGroups()));
        result.put("botDailyRecommendationTemplate", readString(KEY_BOT_DAILY_RECOMMENDATION_TEMPLATE,
                DEFAULT_BOT_DAILY_RECOMMENDATION_TEMPLATE));
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
        putInt(request, "botMinKeywordLength", KEY_BOT_MIN_KEYWORD_LENGTH, 1, 20, "QQ群机器人接受的最短搜索关键词字数");
        putInt(request, "botRateLimitPerMinute", KEY_BOT_RATE_LIMIT_PER_MINUTE, 0, 100, "每个群成员每分钟最多可发起的搜索次数");
        putInt(request, "botMaxResults", KEY_BOT_MAX_RESULTS, 1, 5, "QQ群机器人单次回复展示的资源候选数量");
        putString(request, "botBlockedKeywords", KEY_BOT_BLOCKED_KEYWORDS, "QQ群机器人拒绝搜索的关键词");
        putBoolean(request, "botTransferCleanupEnabled", KEY_BOT_TRANSFER_CLEANUP_ENABLED, "自动清理QQ群搜索产生的临时转存文件");
        putInt(request, "botTransferCleanupDelayMinutes", KEY_BOT_TRANSFER_CLEANUP_DELAY_MINUTES, 1, 10080, "QQ群临时转存成功后的保留时间（分钟）");
        putBoolean(request, "botDailyRecommendationEnabled", KEY_BOT_DAILY_RECOMMENDATION_ENABLED, "是否开启QQ群每日影片推荐");
        putString(request, "botDailyRecommendationTime", KEY_BOT_DAILY_RECOMMENDATION_TIME, "QQ群每日推荐执行时间（HH:mm）");
        putInt(request, "botDailyRecommendationCount", KEY_BOT_DAILY_RECOMMENDATION_COUNT, 1, 10, "每个群每天推荐的影片数量");
        putString(request, "botDailyRecommendationGroupIds", KEY_BOT_DAILY_RECOMMENDATION_GROUP_IDS, "接收每日推荐的 QQ 群号");
        putString(request, "botDailyRecommendationTemplate", KEY_BOT_DAILY_RECOMMENDATION_TEMPLATE, "QQ群每日推荐消息模板");
        putBoolean(request, "channelAutoPostEnabled", KEY_CHANNEL_AUTO_POST_ENABLED, "是否开启 QQ 频道自动发布");
        putInt(request, "channelIntervalMinutes", KEY_CHANNEL_INTERVAL_MINUTES, 1, 10080, "QQ 频道自动发布批次间隔（分钟）");
        putInt(request, "channelMaxPostsPerRun", KEY_CHANNEL_MAX_POSTS_PER_RUN, 1, 20, "QQ 频道每轮最多发布的帖子数");
        putString(request, "channelDailyTime", KEY_CHANNEL_DAILY_TIME, "QQ 频道每日自动发布开始时间（HH:mm）");
        putInt(request, "channelPostTotal", KEY_CHANNEL_POST_TOTAL, 1, 100, "QQ 频道每天计划发布的帖子总数");
        putInt(request, "channelPostIntervalSeconds", KEY_CHANNEL_POST_INTERVAL_SECONDS, 0, 86400, "QQ 频道连续两篇帖子之间的等待时间（秒）");
        putString(request, "channelPostTemplate", KEY_CHANNEL_POST_TEMPLATE, "QQ 频道帖子正文模板");
        putInt(request, "channelCandidateLimit", KEY_CHANNEL_CANDIDATE_LIMIT, 1, 100, "QQ 频道每轮选取的候选资源数量上限");
        putString(request, "channelGuildId", KEY_CHANNEL_GUILD_ID, "用于自动发布的 QQ 频道 ID");
        putString(request, "channelMovieId", KEY_CHANNEL_MOVIE_ID, "电影内容发布到的 QQ 子频道 ID");
        putString(request, "channelTvId", KEY_CHANNEL_TV_ID, "剧集和动漫内容发布到的 QQ 子频道 ID");
        return reload();
    }

    private void ensureDefaults() {
        upsertMissing(KEY_BOT_MIN_KEYWORD_LENGTH, Integer.toString(qqBotProperties.getMinKeywordLength()), "QQ群机器人接受的最短搜索关键词字数");
        upsertMissing(KEY_BOT_RATE_LIMIT_PER_MINUTE, Integer.toString(qqBotProperties.getRateLimitPerMinute()), "每个群成员每分钟最多可发起的搜索次数");
        upsertMissing(KEY_BOT_MAX_RESULTS, Integer.toString(qqBotProperties.getMaxResults()), "QQ群机器人单次回复展示的资源候选数量");
        upsertMissing(KEY_BOT_BLOCKED_KEYWORDS, defaultText(qqBotProperties.getBlockedKeywords()), "QQ群机器人拒绝搜索的关键词");
        upsertMissing(KEY_BOT_TRANSFER_CLEANUP_ENABLED, "true", "自动清理QQ群搜索产生的临时转存文件");
        upsertMissing(KEY_BOT_TRANSFER_CLEANUP_DELAY_MINUTES, "10", "QQ群临时转存成功后的保留时间（分钟）");
        upsertMissing(KEY_BOT_DAILY_RECOMMENDATION_ENABLED, "true", "是否开启QQ群每日影片推荐");
        upsertMissing(KEY_BOT_DAILY_RECOMMENDATION_TIME, "09:00", "QQ群每日推荐执行时间（HH:mm）");
        upsertMissing(KEY_BOT_DAILY_RECOMMENDATION_COUNT, "3", "每个群每天推荐的影片数量");
        upsertMissing(KEY_BOT_DAILY_RECOMMENDATION_GROUP_IDS, defaultText(qqBotProperties.getAllowedGroups()), "接收每日推荐的 QQ 群号");
        upsertMissing(KEY_BOT_DAILY_RECOMMENDATION_TEMPLATE, DEFAULT_BOT_DAILY_RECOMMENDATION_TEMPLATE,
                "QQ群每日推荐消息模板");
        upsertMissing(KEY_CHANNEL_AUTO_POST_ENABLED, "false", "是否开启 QQ 频道自动发布");
        upsertMissing(KEY_CHANNEL_INTERVAL_MINUTES, "60", "QQ 频道自动发布批次间隔（分钟）");
        upsertMissing(KEY_CHANNEL_MAX_POSTS_PER_RUN, "1", "QQ 频道每轮最多发布的帖子数");
        upsertMissing(KEY_CHANNEL_DAILY_TIME, "09:00", "QQ 频道每日自动发布开始时间（HH:mm）");
        upsertMissing(KEY_CHANNEL_POST_TOTAL, "1", "QQ 频道每天计划发布的帖子总数");
        upsertMissing(KEY_CHANNEL_POST_INTERVAL_SECONDS, "60", "QQ 频道连续两篇帖子之间的等待时间（秒）");
        upsertMissing(KEY_CHANNEL_POST_TEMPLATE, DEFAULT_CHANNEL_POST_TEMPLATE, "QQ 频道帖子正文模板");
        upsertMissing(KEY_CHANNEL_CANDIDATE_LIMIT, "10", "QQ 频道每轮选取的候选资源数量上限");
        upsertMissing(KEY_CHANNEL_GUILD_ID, defaultGuildId, "用于自动发布的 QQ 频道 ID");
        upsertMissing(KEY_CHANNEL_MOVIE_ID, defaultMovieChannelId, "电影内容发布到的 QQ 子频道 ID");
        upsertMissing(KEY_CHANNEL_TV_ID, defaultTvChannelId, "剧集和动漫内容发布到的 QQ 子频道 ID");
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
            upsert(key, DEFAULT_CHANNEL_POST_TEMPLATE, "QQ 频道帖子正文模板");
            return DEFAULT_CHANNEL_POST_TEMPLATE;
        }
        if (KEY_BOT_DAILY_RECOMMENDATION_TEMPLATE.equals(key) && looksLikeMojibake(value)) {
            upsert(key, DEFAULT_BOT_DAILY_RECOMMENDATION_TEMPLATE, "QQ群每日推荐消息模板");
            return DEFAULT_BOT_DAILY_RECOMMENDATION_TEMPLATE;
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
