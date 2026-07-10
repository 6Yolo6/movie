package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.gying.movie.client.NapCatClient;
import com.gying.movie.client.QqOfficialBotClient;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQqBotService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QqBotServiceImpl implements IQqBotService {

    private static final Logger log = LoggerFactory.getLogger(QqBotServiceImpl.class);
    private static final int MAX_REPLY_RESOURCES = 5;

    private final QqBotProperties qqBotProperties;
    private final ResourceHubProperties resourceHubProperties;
    private final NapCatClient napCatClient;
    private final QqOfficialBotClient qqOfficialBotClient;
    private final IMovieMetadataService movieService;
    private final IResourceLinkService resourceLinkService;
    private final IResourceDiscoveryService resourceDiscoveryService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IQuarkShareService quarkShareService;
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final IQuarkTransferRunnerService quarkTransferRunnerService;
    private final IResourceHubPublishService resourceHubPublishService;
    private final ITmdbMetadataSyncService tmdbMetadataSyncService;
    private final Map<String, Deque<Instant>> searchRateLimits = new ConcurrentHashMap<>();

    public QqBotServiceImpl(
            QqBotProperties qqBotProperties,
            ResourceHubProperties resourceHubProperties,
            NapCatClient napCatClient,
            QqOfficialBotClient qqOfficialBotClient,
            IMovieMetadataService movieService,
            IResourceLinkService resourceLinkService,
            IResourceDiscoveryService resourceDiscoveryService,
            IResourceDiscoveryResultService discoveryResultService,
            IQuarkShareService quarkShareService,
            IQuarkTransferTaskService quarkTransferTaskService,
            IQuarkTransferRunnerService quarkTransferRunnerService,
            IResourceHubPublishService resourceHubPublishService,
            ITmdbMetadataSyncService tmdbMetadataSyncService) {
        this.qqBotProperties = qqBotProperties;
        this.resourceHubProperties = resourceHubProperties;
        this.napCatClient = napCatClient;
        this.qqOfficialBotClient = qqOfficialBotClient;
        this.movieService = movieService;
        this.resourceLinkService = resourceLinkService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.discoveryResultService = discoveryResultService;
        this.quarkShareService = quarkShareService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.quarkTransferRunnerService = quarkTransferRunnerService;
        this.resourceHubPublishService = resourceHubPublishService;
        this.tmdbMetadataSyncService = tmdbMetadataSyncService;
    }

    @Override
    public boolean handleOneBotEvent(JsonNode event) {
        if (!qqBotProperties.isEnabled() || event == null || !isGroupMessage(event)) {
            return false;
        }
        Long groupId = number(event.path("group_id"));
        Long userId = number(event.path("user_id"));
        Long selfId = number(event.path("self_id"));
        if (groupId == null || isOwnMessage(userId, selfId) || !isAllowedGroup(groupId)) {
            return false;
        }

        String text = extractMessageText(event.path("message"));
        String keyword = extractKeyword(text);
        if (!hasText(keyword)) {
            return false;
        }

        String safeKeyword = trim(keyword, 80);
        trySend(groupId, "正在搜索：" + safeKeyword);
        CompletableFuture.runAsync(() -> searchAndReply(groupId, safeKeyword, String.valueOf(userId)));
        return true;
    }

    private void searchAndReply(Long groupId, String keyword, String userKey) {
        try {
            trySend(groupId, buildSearchReply(keyword, userKey));
        } catch (Exception e) {
            log.warn("QQ bot resource search failed for keyword {}", keyword, e);
            trySend(groupId, "搜索失败：" + safeError(e.getMessage()));
        }
    }

    @Override
    public String buildSearchReply(String keyword) {
        return buildSearchReply(keyword, null);
    }

    @Override
    public String buildSearchReply(String keyword, String userKey) {
        String safeKeyword = trim(keyword, 80);
        if (!hasText(safeKeyword)) {
            return "请输入要搜索的影片名称。";
        }
        if (safeKeyword.length() < Math.max(qqBotProperties.getMinKeywordLength(), 1)) {
            return "搜索词太短，请至少输入 " + Math.max(qqBotProperties.getMinKeywordLength(), 1) + " 个字。";
        }
        if (!allowSearch(userKey)) {
            return "搜索太频繁，请稍后再试。";
        }
        MovieMetadata movie = findBestMovie(safeKeyword);
        if (movie == null || needsMetadataSync(movie)) {
            MovieMetadata syncedMovie = syncMovieMetadata(safeKeyword);
            if (syncedMovie != null) {
                movie = syncedMovie;
            } else if (movie == null || needsMetadataSync(movie)) {
                return "没有找到可信影片元数据：" + safeKeyword + "\n已跳过外部资源搜索，避免误转存无关资源。";
            }
        }
        if (isUpcoming(movie)) {
            markTrailer(movie);
            return buildUpcomingReply(movie);
        }

        List<ResourceLink> links = loadResources(movie.getId());
        List<String> transferNotes = new ArrayList<>();
        if (links.isEmpty()) {
            runDiscoveryPipeline(movie, safeKeyword, transferNotes);
            links = loadResources(movie.getId());
        }
        return buildReply(movie, links);
    }

    private void runDiscoveryPipeline(MovieMetadata movie, String keyword, List<String> transferNotes) {
        if (!resourceHubProperties.isEnabled()) {
            transferNotes.add("Resource Hub 未启用，无法自动搜索资源");
            return;
        }
        ResourceDiscoveryRequest request = new ResourceDiscoveryRequest();
        request.setMovieId(movie.getId());
        request.setKeyword(buildSearchKeyword(movie, keyword));
        request.setSource("PANSOU");
        request.setMaxResults(safeMaxResults());

        ResourceHubTask task = resourceDiscoveryService.enqueue(request);
        ResourceDiscoveryRunResult discoveryRun = resourceDiscoveryService.runTask(task.getId());
        if (discoveryRun.getDiscovered() == 0 && discoveryRun.getDuplicate() == 0) {
            transferNotes.add("没有搜索到可用资源");
            if (!discoveryRun.getErrors().isEmpty()) {
                transferNotes.add(safeError(discoveryRun.getErrors().get(0)));
            }
            return;
        }

        submitTransferTasks(movie.getId(), transferNotes);
        publishMovieDiscoveries(movie.getId(), transferNotes);
    }

    private void submitTransferTasks(String movieId, List<String> transferNotes) {
        if (!qqBotProperties.isAutoTransfer()) {
            return;
        }
        List<QuarkTransferTask> tasks = quarkTransferTaskService.list(new QueryWrapper<QuarkTransferTask>()
                .eq("movie_id", movieId)
                .in("status", List.of("PENDING", "FAILED", "SUBMITTED"))
                .orderByDesc("created_at")
                .last("LIMIT " + safeMaxResults()));
        for (QuarkTransferTask task : tasks) {
            submitTransferTask(task, transferNotes);
        }
    }

    private void submitTransferTask(QuarkTransferTask task, List<String> transferNotes) {
        if ("SUBMITTED".equalsIgnoreCase(task.getStatus())) {
            ensureShareUrl(task, transferNotes);
            addSavedPathNote(task, transferNotes);
            return;
        }
        try {
            QuarkTransferRunResult result = quarkTransferRunnerService.submitOne(task.getId());
            QuarkTransferTask refreshed = quarkTransferTaskService.getById(task.getId());
            ensureShareUrl(refreshed, transferNotes);
            addSavedPathNote(refreshed, transferNotes);
            if (result.getFailed() > 0 && !result.getErrors().isEmpty()) {
                transferNotes.add(safeError(result.getErrors().get(0)));
            }
        } catch (Exception e) {
            transferNotes.add("转存失败：" + safeError(e.getMessage()));
        }
    }

    private void ensureShareUrl(QuarkTransferTask task, List<String> transferNotes) {
        if (task == null || !resourceHubProperties.getQuark().isShareEnabled()) {
            return;
        }
        try {
            String shareUrl = quarkShareService.ensureShareUrl(task);
            if (hasText(shareUrl)) {
                transferNotes.add("已创建我的夸克分享");
            }
        } catch (Exception e) {
            transferNotes.add("创建分享失败：" + safeError(e.getMessage()));
        }
    }

    private void publishMovieDiscoveries(String movieId, List<String> transferNotes) {
        List<ResourceDiscoveryResult> discoveries = discoveryResultService.list(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("movie_id", movieId)
                .eq("status", "DISCOVERED")
                .orderByDesc("confidence")
                .orderByAsc("created_at")
                .last("LIMIT " + safeMaxResults()));
        for (ResourceDiscoveryResult discovery : discoveries) {
            try {
                ResourceHubPublishResult publishResult = resourceHubPublishService.publishDiscovery(discovery.getId());
                if (publishResult.getFailed() > 0 && !publishResult.getErrors().isEmpty()) {
                    transferNotes.add(safeError(publishResult.getErrors().get(0)));
                }
            } catch (Exception e) {
                transferNotes.add("发布资源失败：" + safeError(e.getMessage()));
            }
        }
    }

    private MovieMetadata findBestMovie(String keyword) {
        List<MovieMetadata> candidates = movieService.list(new QueryWrapper<MovieMetadata>()
                .eq("status", "ACTIVE")
                .and(query -> query.like("title_cn", keyword)
                        .or().like("title_en", keyword)
                        .or().like("series_name", keyword)
                        .or().like("aliases", keyword))
                .orderByDesc("popularity")
                .orderByDesc("tmdb_popularity")
                .orderByDesc("created_at")
                .last("LIMIT 8"));
        return candidates.stream()
                .max(Comparator.comparingInt(movie -> scoreMovie(movie, keyword)))
                .orElse(null);
    }

    private MovieMetadata syncMovieMetadata(String keyword) {
        try {
            return tmdbMetadataSyncService.syncBestByKeyword(keyword);
        } catch (Exception e) {
            log.warn("TMDB metadata sync failed for QQ keyword {}", keyword, e);
            return null;
        }
    }

    private boolean needsMetadataSync(MovieMetadata movie) {
        if (movie == null) {
            return true;
        }
        if (hasText(movie.getId()) && movie.getId().startsWith("qq_")) {
            return true;
        }
        return movie.getTmdbId() == null
                && !hasText(movie.getSummary())
                && (movie.getGenres() == null || movie.getGenres().isEmpty())
                && (movie.getRegions() == null || movie.getRegions().isEmpty());
    }

    private boolean isUpcoming(MovieMetadata movie) {
        if (movie == null) {
            return false;
        }
        LocalDate releaseDate = firstReleaseDate(movie.getReleaseDates());
        if (releaseDate != null) {
            return releaseDate.isAfter(LocalDate.now());
        }
        return movie.getYear() != null && movie.getYear() > LocalDate.now().getYear();
    }

    private LocalDate firstReleaseDate(String releaseDates) {
        if (!hasText(releaseDates)) {
            return null;
        }
        for (String part : releaseDates.split("[,/;|\\s]+")) {
            String value = part.trim();
            if (value.length() >= 10) {
                try {
                    return LocalDate.parse(value.substring(0, 10));
                } catch (DateTimeParseException ignored) {
                    // Try the next date token.
                }
            }
        }
        return null;
    }

    private void markTrailer(MovieMetadata movie) {
        if (movie != null && !"TRAILER".equalsIgnoreCase(movie.getResourceStatus())) {
            movie.setResourceStatus("TRAILER");
            movie.setUpdatedAt(LocalDateTime.now());
            movieService.updateById(movie);
        }
    }

    private String buildUpcomingReply(MovieMetadata movie) {
        StringBuilder reply = new StringBuilder();
        reply.append("片名：").append(title(movie));
        if (movie.getYear() != null) {
            reply.append(" (").append(movie.getYear()).append(")");
        }
        appendLine(reply, "类型", join(movie.getGenres()));
        appendLine(reply, "地区", join(movie.getRegions()));
        appendLine(reply, "评分", rating(movie));
        appendLine(reply, "简介", trim(movie.getSummary(), 180));
        reply.append("\n\n影片尚未上映或流媒体资源未发布，暂不进行网盘搜索。");
        return reply.toString();
    }

    private boolean allowSearch(String userKey) {
        int limit = qqBotProperties.getRateLimitPerMinute();
        if (limit <= 0) {
            return true;
        }
        String key = hasText(userKey) ? userKey.trim() : "anonymous";
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(60);
        Deque<Instant> hits = searchRateLimits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst().isBefore(windowStart)) {
                hits.removeFirst();
            }
            if (hits.size() >= limit) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }

    private int scoreMovie(MovieMetadata movie, String keyword) {
        String normalized = keyword.toLowerCase();
        int score = 0;
        score += exactScore(movie.getTitleCn(), normalized, 100);
        score += exactScore(movie.getTitleEn(), normalized, 90);
        score += exactScore(movie.getSeriesName(), normalized, 80);
        score += containsScore(movie.getAliases(), normalized, 40);
        score += movie.getPopularity() == null ? 0 : Math.min(movie.getPopularity(), 20);
        return score;
    }

    private int exactScore(String value, String keyword, int exactScore) {
        if (!hasText(value)) {
            return 0;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.equals(keyword)) {
            return exactScore;
        }
        return normalized.contains(keyword) ? exactScore / 2 : 0;
    }

    private int containsScore(String value, String keyword, int score) {
        return hasText(value) && value.toLowerCase().contains(keyword) ? score : 0;
    }

    private List<ResourceLink> loadResources(String movieId) {
        return resourceLinkService.getResourcesByMovieId(movieId).stream()
                .filter(link -> !"INVALID".equalsIgnoreCase(link.getLinkStatus()))
                .limit(MAX_REPLY_RESOURCES)
                .toList();
    }

    private String buildReply(MovieMetadata movie, List<ResourceLink> links) {
        StringBuilder reply = new StringBuilder();
        reply.append("片名：").append(title(movie));
        if (movie.getYear() != null) {
            reply.append(" (").append(movie.getYear()).append(")");
        }
        appendLine(reply, "类型", join(movie.getGenres()));
        appendLine(reply, "地区", join(movie.getRegions()));
        appendLine(reply, "评分", rating(movie));
        appendLine(reply, "简介", trim(movie.getSummary(), 180));
        if (links.isEmpty()) {
            reply.append("\n\n暂时没有可用资源链接。");
            return reply.toString();
        }
        reply.append("\n\n资源链接：");
        int index = 1;
        for (ResourceLink link : links) {
            reply.append("\n").append(index++).append(". ")
                    .append(firstText(link.getProvider(), link.getType(), "RESOURCE"))
                    .append(" - ").append(trim(firstText(link.getName(), title(movie)), 60))
                    .append("\n").append(link.getUrl());
            if (hasText(link.getCode())) {
                reply.append("\n提取码：").append(link.getCode());
            }
        }
        return reply.toString();
    }

    private void addSavedPathNote(QuarkTransferTask task, List<String> transferNotes) {
        if (task != null && hasText(task.getSavedPath())) {
            transferNotes.add("已转存到 " + task.getSavedPath());
        }
    }

    private String buildSearchKeyword(MovieMetadata movie, String fallback) {
        String title = firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), fallback);
        return movie.getYear() == null ? title : title + " " + movie.getYear();
    }

    private String extractKeyword(String text) {
        if (!hasText(text)) {
            return null;
        }
        String normalized = text.replace('\u3000', ' ').trim();
        for (String prefix : commandPrefixes()) {
            if (!hasText(prefix)) {
                continue;
            }
            String command = prefix.trim();
            if (normalized.equals(command)) {
                return null;
            }
            if (normalized.startsWith(command)) {
                return normalized.substring(command.length()).trim();
            }
        }
        return null;
    }

    private String extractMessageText(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) {
            return "";
        }
        if (message.isTextual()) {
            return message.asText("").replaceAll("\\[CQ:[^\\]]+]", " ").trim();
        }
        if (message.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode segment : message) {
                if ("text".equalsIgnoreCase(segment.path("type").asText())) {
                    text.append(segment.path("data").path("text").asText("")).append(' ');
                }
            }
            return text.toString().trim();
        }
        return message.asText("");
    }

    private boolean isGroupMessage(JsonNode event) {
        return "message".equalsIgnoreCase(event.path("post_type").asText())
                && "group".equalsIgnoreCase(event.path("message_type").asText());
    }

    private boolean isOwnMessage(Long userId, Long selfId) {
        return userId != null && selfId != null && userId.equals(selfId);
    }

    private boolean isAllowedGroup(Long groupId) {
        List<Long> allowedGroups = allowedGroups();
        return allowedGroups.isEmpty() || allowedGroups.contains(groupId);
    }

    private Long number(JsonNode node) {
        return node != null && node.isNumber() ? node.asLong() : null;
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (hasText(value)) {
            builder.append("\n").append(label).append("：").append(value);
        }
    }

    private String rating(MovieMetadata movie) {
        List<String> values = new ArrayList<>();
        if (movie.getDoubanScore() != null) {
            values.add("豆瓣 " + movie.getDoubanScore());
        }
        if (movie.getTmdbVoteAverage() != null) {
            values.add("TMDB " + movie.getTmdbVoteAverage());
        }
        return String.join(" / ", values);
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return trim(String.join(" / ", values), 80);
    }

    private List<String> distinct(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (hasText(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private List<Long> allowedGroups() {
        List<Long> result = new ArrayList<>();
        String raw = qqBotProperties.getAllowedGroups();
        if (!hasText(raw)) {
            return result;
        }
        for (String item : raw.split(",")) {
            String value = item.trim();
            if (!value.isEmpty()) {
                try {
                    result.add(Long.parseLong(value));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private List<String> commandPrefixes() {
        List<String> result = new ArrayList<>();
        String raw = qqBotProperties.getCommandPrefixes();
        if (!hasText(raw)) {
            return List.of("找", "搜", "/movie", "/search");
        }
        for (String item : raw.split(",")) {
            String value = item.trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private String title(MovieMetadata movie) {
        return firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), movie.getId());
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private int safeMaxResults() {
        return Math.min(Math.max(qqBotProperties.getMaxResults(), 1), MAX_REPLY_RESOURCES);
    }

    private String safeError(String message) {
        return firstText(trim(message, 120), "未知错误");
    }

    private void trySend(Long groupId, String message) {
        try {
            if ("qqbot".equalsIgnoreCase(qqBotProperties.getReplyProvider())) {
                qqOfficialBotClient.sendGroupMessage(groupId, trim(message, 1800));
            } else {
                napCatClient.sendGroupMessage(groupId, trim(message, 1800));
            }
        } catch (Exception e) {
            log.warn("Failed to send QQ group message to {}", groupId, e);
        }
    }

    private String trim(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
