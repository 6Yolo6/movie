package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQqBotService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.ISysConfigService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Sends a small daily digest to the original QQ group bot (not QQ Channel). */
@Service
public class QqDailyRecommendationScheduler {

    private static final Logger log = LoggerFactory.getLogger(QqDailyRecommendationScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String ENABLED = "qq.bot.daily_recommendation.enabled";
    private static final String DAILY_TIME = "qq.bot.daily_recommendation.time";
    private static final String COUNT = "qq.bot.daily_recommendation.count";
    private static final String GROUPS = "qq.bot.daily_recommendation.group_ids";
    private static final String LAST_RUN_PREFIX = "qq.bot.daily_recommendation.last_run.";

    private final ISysConfigService config;
    private final IMovieMetadataService movieService;
    private final IResourceLinkService resourceService;
    private final IQqBotService qqBotService;
    private final QqBotProperties properties;
    private final String publicBaseUrl;
    private final Map<Long, LocalDate> lastRuns = new ConcurrentHashMap<>();

    public QqDailyRecommendationScheduler(ISysConfigService config,
            IMovieMetadataService movieService,
            IResourceLinkService resourceService,
            IQqBotService qqBotService,
            QqBotProperties properties,
            @Value("${app.public-base-url:${app.cors.allowed-origin:http://localhost}}") String publicBaseUrl) {
        this.config = config;
        this.movieService = movieService;
        this.resourceService = resourceService;
        this.qqBotService = qqBotService;
        this.properties = properties;
        this.publicBaseUrl = publicBaseUrl == null ? "http://localhost" : publicBaseUrl.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelayString = "${qq-bot.daily-recommendation-check-ms:60000}")
    public void runIfDue() {
        if (!properties.isEnabled() || !Boolean.parseBoolean(value(ENABLED, "true"))) return;
        LocalTime target = parseTime(value(DAILY_TIME, "09:00"));
        LocalTime now = LocalTime.now(ZONE);
        if (now.getHour() != target.getHour() || now.getMinute() != target.getMinute()) return;
        LocalDate today = LocalDate.now(ZONE);
        int count = clamp(parseInt(value(COUNT, "3"), 3), 1, 10);
        for (Long groupId : groupIds(value(GROUPS, properties.getAllowedGroups()))) {
            if (alreadyRan(groupId, today)) continue;
            try {
                sendDigest(groupId, count);
                lastRuns.put(groupId, today);
                config.updateConfig(LAST_RUN_PREFIX + groupId, today.toString());
            } catch (Exception error) {
                // A failed group must be retried on the next minute tick.
                log.warn("QQ daily recommendation failed for group {}", groupId, error);
            }
        }
    }

    void sendDigest(Long groupId, int count) {
        Page<MovieMetadata> page = movieService.lambdaQuery()
                .eq(MovieMetadata::getStatus, "ACTIVE")
                .eq(MovieMetadata::getResourceStatus, "AVAILABLE")
                .last("ORDER BY CASE WHEN COALESCE((SELECT MAX(rl.updated_at) FROM resource_link rl "
                        + "WHERE rl.movie_id = movie_metadata.id AND rl.status = 'ACTIVE' AND rl.deleted_at IS NULL "
                        + "AND COALESCE(rl.link_status, 'NORMAL') <> 'INVALID'), updated_at, created_at) "
                        + ">= DATE_SUB(NOW(), INTERVAL 30 DAY) THEN 0 ELSE 1 END, "
                        + "COALESCE(popularity, 0) DESC, COALESCE(tmdb_popularity, 0) DESC, "
                        + "GREATEST(COALESCE(douban_score, 0), COALESCE(imdb_score, 0), COALESCE(tmdb_vote_average, 0)) DESC, "
                        + "COALESCE((SELECT MAX(rl.updated_at) FROM resource_link rl WHERE rl.movie_id = movie_metadata.id "
                        + "AND rl.status = 'ACTIVE' AND rl.deleted_at IS NULL AND COALESCE(rl.link_status, 'NORMAL') <> 'INVALID'), "
                        + "updated_at, created_at) DESC, created_at DESC")
                .page(new Page<>(1, Math.min(count * 3L, 30L)));
        AtomicInteger sent = new AtomicInteger();
        for (MovieMetadata movie : page.getRecords()) {
            if (sent.get() >= count) break;
            List<ResourceLink> links = resourceService.getResourcesByMovieId(movie.getId());
            if (links == null || links.isEmpty()) continue;
            qqBotService.sendGroupMessage(groupId, format(movie, links));
            sent.incrementAndGet();
        }
        if (sent.get() == 0) throw new IllegalStateException("No recent movies with valid resources");
    }

    private String format(MovieMetadata movie, List<ResourceLink> links) {
        StringBuilder out = new StringBuilder("【最近更新】").append(first(movie.getTitleCn(), movie.getTitleEn(), movie.getId()));
        if (movie.getYear() != null) out.append(" (").append(movie.getYear()).append(")");
        if (movie.getGenres() != null && !movie.getGenres().isEmpty()) out.append("\n类型：").append(String.join(" / ", movie.getGenres()));
        String rating = rating(movie);
        if (!rating.isBlank()) out.append("\n评分：").append(rating);
        if (movie.getSummary() != null && !movie.getSummary().isBlank()) out.append("\n简介：").append(trim(movie.getSummary(), 180));
        out.append("\n详情：").append(publicBaseUrl).append("/movie/").append(movie.getId());
        links.stream().limit(3).forEach(link -> out.append("\n资源：").append(first(link.getName(), link.getProvider())).append(" ").append(link.getUrl()));
        return trim(out.toString(), 1800);
    }

    private String rating(MovieMetadata movie) {
        List<String> values = new ArrayList<>();
        if (positive(movie.getDoubanScore())) values.add("豆瓣 " + movie.getDoubanScore());
        if (positive(movie.getImdbScore())) values.add("IMDb " + movie.getImdbScore());
        if (positive(movie.getTmdbVoteAverage())) values.add("TMDB " + movie.getTmdbVoteAverage());
        return String.join(" / ", values);
    }

    private boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private String value(String key, String fallback) { String v = config.getConfigValue(key, fallback); return v == null || v.isBlank() ? fallback : v.trim(); }
    private LocalTime parseTime(String value) { try { return LocalTime.parse(value, TIME); } catch (DateTimeParseException e) { return LocalTime.of(9, 0); } }
    private int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; } }
    private int clamp(int value, int min, int max) { return Math.min(Math.max(value, min), max); }
    private List<Long> groupIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null) return ids;
        for (String item : raw.split("[,;]")) { try { if (!item.isBlank()) ids.add(Long.parseLong(item.trim())); } catch (NumberFormatException ignored) {} }
        return ids;
    }
    private boolean alreadyRan(Long groupId, LocalDate today) {
        if (today.equals(lastRuns.get(groupId))) return true;
        return today.toString().equals(config.getConfigValue(LAST_RUN_PREFIX + groupId, ""));
    }
    private String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return ""; }
    private String trim(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
