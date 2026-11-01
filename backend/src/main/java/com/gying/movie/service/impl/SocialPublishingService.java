package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.client.SocialPublisherClient;
import com.gying.movie.entity.SocialPostLog;
import com.gying.movie.entity.SocialPublishTarget;
import com.gying.movie.service.ISocialPostLogService;
import com.gying.movie.service.ISocialPublishTargetService;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

@Service
public class SocialPublishingService {
    private static final Set<String> RETRYABLE_STATES = Set.of("PENDING", "PREPARE_FAILED");
    private static final String WEIBO_AUTH_REQUIRED = "[WEIBO_AUTH_REQUIRED]";
    private static final String PROTECTED_MOVIE_LOG = """
            SELECT 1 FROM social_post_log protected_log
            WHERE protected_log.target_id = social_post_log.target_id
              AND protected_log.movie_id = social_post_log.movie_id
              AND protected_log.id <> social_post_log.id
              AND protected_log.status NOT IN ('PENDING', 'PREPARE_FAILED')
            """;
    private final Set<Long> activeTargets = ConcurrentHashMap.newKeySet();
    private final ISocialPublishTargetService targetService;
    private final ISocialPostLogService logService;
    private final SocialPublisherClient publisherClient;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "social-publishing");
        thread.setDaemon(true);
        return thread;
    });

    public SocialPublishingService(
            ISocialPublishTargetService targetService,
            ISocialPostLogService logService,
            SocialPublisherClient publisherClient,
            JdbcTemplate jdbcTemplate) {
        this.targetService = targetService;
        this.logService = logService;
        this.publisherClient = publisherClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public Map<String, Object> publishNext(Long targetId, boolean runNow) {
        SocialPublishTarget target = requireTarget(targetId);
        // Resume the existing queue first, including logs whose resource version changed.
        List<SocialPostLog> pending = logService.list(new QueryWrapper<SocialPostLog>()
                .eq("target_id", targetId).in("status", RETRYABLE_STATES)
                .notExists(PROTECTED_MOVIE_LOG)
                .orderByAsc("created_at").orderByAsc("id").last("LIMIT 1"));
        SocialPostLog log;
        if (pending.isEmpty()) {
            Map<String, Object> candidate = nextCandidate(targetId);
            if (candidate == null) {
                return Map.of("targetId", targetId, "status", "SKIPPED", "message", "No unposted resource candidate");
            }
            log = queue(target, candidate);
        } else {
            log = pending.get(0);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetId", targetId);
        result.put("logId", log.getId());
        result.put("title", log.getTitle());
        result.put("status", log.getStatus());
        if (runNow && isRetryable(log.getStatus())) {
            Map<String, Object> published = publisherClient.publish(log.getId());
            result.put("publisher", published);
            if (published.get("status") instanceof String status && !status.isBlank()) {
                result.put("status", status);
            } else {
                SocialPostLog refreshed = logService.getById(log.getId());
                result.put("status", refreshed == null ? log.getStatus() : refreshed.getStatus());
            }
        }
        String status = String.valueOf(result.get("status"));
        result.put("retryable", isRetryable(status));
        result.put("blocked", !isRetryable(status) && !"POSTED".equals(status));
        return result;
    }

    public Map<String, Object> publishNext(List<Long> targetIds, boolean runNow) {
        List<SocialPublishTarget> targets = targetIds == null || targetIds.isEmpty()
                ? targetService.list(new QueryWrapper<SocialPublishTarget>().eq("enabled", true).orderByAsc("id"))
                : targetService.listByIds(targetIds);
        List<Map<String, Object>> items = new ArrayList<>();
        int posted = 0;
        int pending = 0;
        int skipped = 0;
        int failed = 0;
        int processing = 0;
        int unknown = 0;
        for (SocialPublishTarget target : targets) {
            try {
                Map<String, Object> item = publishNext(target.getId(), runNow);
                items.add(item);
                switch (String.valueOf(item.get("status")).toUpperCase()) {
                    case "POSTED" -> posted++;
                    case "PENDING" -> pending++;
                    case "SKIPPED" -> skipped++;
                    case "PREPARE_FAILED" -> failed++;
                    case "PREPARING", "PUBLISHING" -> processing++;
                    default -> unknown++;
                }
            } catch (Exception error) {
                // An HTTP failure does not prove that the publisher did not submit content.
                unknown++;
                items.add(Map.of(
                        "targetId", target.getId(),
                        "status", "UNKNOWN",
                        "error", safeMessage(error)));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selected", targets.size());
        result.put("posted", posted);
        result.put("pending", pending);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("processing", processing);
        result.put("unknown", unknown);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> retry(Long logId) {
        SocialPostLog log = logService.getById(logId);
        if (log == null) {
            throw new IllegalArgumentException("Social post log not found");
        }
        if (!isRetryable(log.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Publication cannot be retried automatically; verify in-flight, unknown or historical outcomes first");
        }
        requireTarget(log.getTargetId());
        // The publisher owns the atomic state transition. Never reset a possibly active attempt here.
        Map<String, Object> result = new LinkedHashMap<>(publisherClient.publish(logId));
        result.put("logId", logId);
        return result;
    }

    public void runDueTargets() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        List<SocialPublishTarget> targets = targetService.list(new QueryWrapper<SocialPublishTarget>()
                .eq("enabled", true)
                .eq("auto_post_enabled", true)
                .orderByAsc("schedule_time")
                .orderByAsc("id"));
        recoverWeiboAuthenticationFailures(targets);
        for (SocialPublishTarget target : targets) {
            LocalTime due = parseTime(target.getScheduleTime());
            if (activeTargets.contains(target.getId()) || now.toLocalTime().isBefore(due)
                    || (target.getLastAutoRunAt() != null
                    && target.getLastAutoRunAt().toLocalDate().equals(today))) {
                continue;
            }
            target.setLastAutoRunAt(now);
            target.setUpdatedAt(now);
            targetService.updateById(target);
            submitTarget(target, () -> runTarget(target));
        }
    }

    private void recoverWeiboAuthenticationFailures(List<SocialPublishTarget> targets) {
        LocalDateTime newSessionAt = null;
        boolean sessionChecked = false;
        for (SocialPublishTarget target : targets) {
            if (!"WEIBO".equals(target.getPlatform()) || !Boolean.TRUE.equals(target.getEnabled())
                    || !Boolean.TRUE.equals(target.getAutoPostEnabled()) || activeTargets.contains(target.getId())) continue;
            List<SocialPostLog> failures = logService.list(new QueryWrapper<SocialPostLog>()
                    .eq("target_id", target.getId()).eq("platform", "WEIBO")
                    .eq("status", "PREPARE_FAILED").likeRight("error_message", WEIBO_AUTH_REQUIRED)
                    .notExists(PROTECTED_MOVIE_LOG)
                    .isNull("external_url").orderByAsc("created_at").orderByAsc("id"));
            if (failures.isEmpty()) continue;
            if (!sessionChecked) {
                sessionChecked = true;
                try {
                    Map<String, Object> weibo = publisherClient.weiboSessionStatus();
                    if (Boolean.TRUE.equals(weibo.get("ready"))
                            && weibo.get("sessionUpdatedAt") instanceof String updatedAt) {
                        newSessionAt = OffsetDateTime.parse(updatedAt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                    }
                } catch (Exception ignored) {
                    // No verified new session means no automatic recovery.
                }
            }
            if (newSessionAt == null) continue;
            LocalDateTime sessionAt = newSessionAt;
            List<SocialPostLog> recoverable = failures.stream()
                    .filter(log -> log.getUpdatedAt() != null && sessionAt.isAfter(log.getUpdatedAt()))
                    .limit(Math.min(Math.max(value(target.getPostsPerRun(), 1), 1), 20)).toList();
            if (!recoverable.isEmpty()) submitTarget(target, () -> runRecoveredLogs(target, recoverable));
        }
    }

    private void submitTarget(SocialPublishTarget target, Runnable work) {
        if (!activeTargets.add(target.getId())) return;
        executor.submit(() -> {
            try { work.run(); } finally { activeTargets.remove(target.getId()); }
        });
    }

    private void runRecoveredLogs(SocialPublishTarget target, List<SocialPostLog> logs) {
        for (int i = 0; i < logs.size(); i++) {
            try {
                Map<String, Object> published = retry(logs.get(i).getId());
                if (Boolean.FALSE.equals(published.get("ok")) || authenticationRequired(published)) return;
            } catch (Exception ignored) {
                return; // Recheck ownership and session at the next scheduler tick.
            }
            if (i + 1 < logs.size() && !waitInterval(target)) return;
        }
    }

    private void runTarget(SocialPublishTarget target) {
        int count = Math.min(Math.max(value(target.getPostsPerRun(), 1), 1), 20);
        int delay = Math.min(Math.max(value(target.getPostIntervalSeconds(), 60), 0), 86400);
        for (int i = 0; i < count; i++) {
            try {
                Map<String, Object> result = publishNext(target.getId(), true);
                if (result.get("publisher") instanceof Map<?, ?> published
                        && (Boolean.FALSE.equals(published.get("ok")) || authenticationRequired(published))) return;
            } catch (Exception ignored) {
                // A lost publisher response may already have crossed the send boundary.
                // Stop this batch instead of creating more uncertain publications.
                return;
            }
            if (delay > 0 && i + 1 < count) {
                try {
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean authenticationRequired(Map<?, ?> published) {
        return Boolean.TRUE.equals(published.get("authenticationRequired"));
    }

    private boolean waitInterval(SocialPublishTarget target) {
        int delay = Math.min(Math.max(value(target.getPostIntervalSeconds(), 60), 0), 86400);
        try {
            if (delay > 0) Thread.sleep(delay * 1000L);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private SocialPublishTarget requireTarget(Long targetId) {
        SocialPublishTarget target = targetService.getById(targetId);
        if (target == null) {
            throw new IllegalArgumentException("Social publish target not found");
        }
        if (!Boolean.TRUE.equals(target.getEnabled())) {
            throw new IllegalStateException("Social publish target is disabled");
        }
        return target;
    }

    private Map<String, Object> nextCandidate(Long targetId) {
        String sql = """
                WITH ranked_resources AS (
                  SELECT rl.id AS resource_link_id, rl.movie_id, rl.url, rl.created_at,
                         COALESCE(NULLIF(m.title_cn, ''), NULLIF(m.title_en, ''), m.id) AS title,
                         COALESCE(m.year, '') AS release_year,
                         LOWER(COALESCE(NULLIF(m.tmdb_type, ''), NULLIF(m.category, ''), 'movie')) AS media_type,
                         COALESCE(m.summary, '') AS summary,
                         COALESCE(m.poster_url, '') AS poster_url,
                         COALESCE(m.popularity, 0) AS site_popularity,
                         COALESCE(m.tmdb_popularity, 0) AS tmdb_popularity,
                         ROW_NUMBER() OVER (PARTITION BY rl.movie_id ORDER BY rl.created_at DESC, rl.id DESC) AS resource_rank
                  FROM resource_link rl
                  JOIN movie_metadata m ON m.id = rl.movie_id
                  WHERE rl.status = 'ACTIVE'
                    AND rl.deleted_at IS NULL
                    AND COALESCE(rl.link_status, 'NORMAL') = 'NORMAL'
                    AND rl.source = 'RESOURCE_HUB'
                    AND COALESCE(rl.url, '') <> ''
                    AND m.deleted_at IS NULL
                    AND COALESCE(m.status, 'ACTIVE') = 'ACTIVE'
                    AND NOT EXISTS (
                      SELECT 1 FROM social_post_log existing
                      WHERE existing.target_id = ? AND existing.movie_id = rl.movie_id
                        AND existing.status NOT IN ('PENDING', 'PREPARE_FAILED')
                    )
                )
                SELECT * FROM ranked_resources
                WHERE resource_rank = 1
                ORDER BY site_popularity DESC, tmdb_popularity DESC, created_at DESC, resource_link_id DESC
                LIMIT 1
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, targetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SocialPostLog queue(SocialPublishTarget target, Map<String, Object> candidate) {
        Long resourceId = ((Number) candidate.get("resource_link_id")).longValue();
        SocialPostLog log = logService.getOne(new QueryWrapper<SocialPostLog>()
                .eq("target_id", target.getId())
                .eq("resource_link_id", resourceId)
                .last("LIMIT 1"), false);
        if (log != null) {
            return log; // Keep ownership, final outcomes and historical failures intact.
        }
        log = new SocialPostLog();
        log.setTargetId(target.getId());
        log.setPlatform(target.getPlatform());
        log.setResourceLinkId(resourceId);
        log.setMovieId(String.valueOf(candidate.get("movie_id")));
        log.setTitle(String.valueOf(candidate.get("title")));
        log.setCreatedAt(LocalDateTime.now());
        log.setStatus("PENDING");
        log.setUpdatedAt(LocalDateTime.now());
        try {
            if (!logService.save(log)) throw new IllegalStateException("Social post could not be queued");
        } catch (DuplicateKeyException error) {
            SocialPostLog existing = logService.getOne(new QueryWrapper<SocialPostLog>()
                    .eq("target_id", target.getId())
                    .eq("resource_link_id", resourceId)
                    .last("LIMIT 1"), false);
            if (existing == null) throw error;
            return existing;
        }
        return log;
    }

    private boolean isRetryable(String status) {
        return status != null && RETRYABLE_STATES.contains(status);
    }

    private LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value == null ? "10:00" : value.trim());
        } catch (DateTimeParseException error) {
            return LocalTime.of(10, 0);
        }
    }

    private int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
