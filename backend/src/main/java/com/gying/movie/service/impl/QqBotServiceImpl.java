package com.gying.movie.service.impl;

import jakarta.annotation.PreDestroy;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.gying.movie.client.NapCatClient;
import com.gying.movie.client.PanSouClient;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.client.QqOfficialBotClient;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.MovieSearchCandidate;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.QqBotSearchLog;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQqBotService;
import com.gying.movie.service.IQqBotSearchLogService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IXunleiTransferTaskService;
import com.gying.movie.utils.MovieSearchCandidateUtils;
import com.gying.movie.utils.MovieTitleMatcher;
import com.gying.movie.utils.QqResourcePreferenceParser;
import com.gying.movie.utils.QqResourcePreferenceParser.ResourcePreference;
import com.gying.movie.utils.ResourceTitleMatcher;
import com.gying.movie.utils.SeasonSearchUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QqBotServiceImpl implements IQqBotService {

    private static final Logger log = LoggerFactory.getLogger(QqBotServiceImpl.class);
    private static final int MAX_REPLY_RESOURCES = 5;
    private static final int MAX_SELECTABLE_RESOURCES = 10;
    private static final int MAX_CANDIDATE_SUGGESTIONS = 10;
    private static final int MAX_GYING_CANDIDATE_SUGGESTIONS = 4;
    private static final Set<String> OWNED_SHARE_PROVIDERS = Set.of("QUARK", "XUNLEI");
    private static final long CANDIDATE_TTL_SECONDS = 300;
    private static final long RESOURCE_CONTEXT_TTL_SECONDS = 300;
    private static final String PERMANENT_LINK_INVALID_REASON = "Re-shared link is still invalid; possible policy violation";

    private final QqBotProperties qqBotProperties;
    private final ResourceHubProperties resourceHubProperties;
    private final NapCatClient napCatClient;
    private final PanSouClient panSouClient;
    private final QqOfficialBotClient qqOfficialBotClient;
    private final IMovieMetadataService movieService;
    private final IResourceLinkService resourceLinkService;
    private final IResourceDiscoveryService resourceDiscoveryService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IQuarkShareService quarkShareService;
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final IQuarkTransferRunnerService quarkTransferRunnerService;
    private final IXunleiTransferTaskService xunleiTransferTaskService;
    private final IXunleiTransferRunnerService xunleiTransferRunnerService;
    private final IResourceHubPublishService resourceHubPublishService;
    private final ITmdbMetadataSyncService tmdbMetadataSyncService;
    private final IQqBotSearchLogService qqBotSearchLogService;
    private final GyingSourceWorkflowService gyingSourceWorkflowService;
    private final Map<String, Deque<Instant>> searchRateLimits = new ConcurrentHashMap<>();
    private final Map<String, SuggestedCandidates> suggestedCandidates = new ConcurrentHashMap<>();
    private final Map<String, ResourceSearchContext> resourceSearchContexts = new ConcurrentHashMap<>();
    private final Map<String, ResourceCandidates> resourceCandidates = new ConcurrentHashMap<>();
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "qq-bot-search");
        thread.setDaemon(true);
        return thread;
    });

    public QqBotServiceImpl(
            QqBotProperties qqBotProperties,
            ResourceHubProperties resourceHubProperties,
            NapCatClient napCatClient,
            PanSouClient panSouClient,
            QqOfficialBotClient qqOfficialBotClient,
            IMovieMetadataService movieService,
            IResourceLinkService resourceLinkService,
            IResourceDiscoveryService resourceDiscoveryService,
            IResourceDiscoveryResultService discoveryResultService,
            IQuarkShareService quarkShareService,
            IQuarkTransferTaskService quarkTransferTaskService,
            IQuarkTransferRunnerService quarkTransferRunnerService,
            IXunleiTransferTaskService xunleiTransferTaskService,
            IXunleiTransferRunnerService xunleiTransferRunnerService,
            IResourceHubPublishService resourceHubPublishService,
            ITmdbMetadataSyncService tmdbMetadataSyncService,
            IQqBotSearchLogService qqBotSearchLogService,
            GyingSourceWorkflowService gyingSourceWorkflowService) {
        this.qqBotProperties = qqBotProperties;
        this.resourceHubProperties = resourceHubProperties;
        this.napCatClient = napCatClient;
        this.panSouClient = panSouClient;
        this.qqOfficialBotClient = qqOfficialBotClient;
        this.movieService = movieService;
        this.resourceLinkService = resourceLinkService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.discoveryResultService = discoveryResultService;
        this.quarkShareService = quarkShareService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.quarkTransferRunnerService = quarkTransferRunnerService;
        this.xunleiTransferTaskService = xunleiTransferTaskService;
        this.xunleiTransferRunnerService = xunleiTransferRunnerService;
        this.resourceHubPublishService = resourceHubPublishService;
        this.tmdbMetadataSyncService = tmdbMetadataSyncService;
        this.qqBotSearchLogService = qqBotSearchLogService;
        this.gyingSourceWorkflowService = gyingSourceWorkflowService;
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
        if (!hasText(keyword) && isCandidateSelection(text)
                && (activeSuggestedCandidates(String.valueOf(userId)) != null
                        || activeResourceCandidates(String.valueOf(userId)) != null)) {
            keyword = text.trim();
        }
        if (!hasText(keyword)
                && activeResourceSearchContext(String.valueOf(userId)) != null
                && parseResourcePreference(text) != null) {
            keyword = text.trim();
        }
        if (!hasText(keyword)) {
            if (isBotMentioned(event, selfId)) {
                trySend(groupId, userId, defaultHelpReply());
                return true;
            }
            return false;
        }

        String safeKeyword = trim(keyword, 80);
        trySend(groupId, userId, "正在搜索资源，请稍后...");
        try {
            searchExecutor.execute(() -> {
                try {
                    searchAndReply(groupId, userId, safeKeyword, String.valueOf(userId));
                } catch (Throwable error) {
                    log.error("QQ bot async search crashed for keyword {}", safeKeyword, error);
                    trySend(groupId, userId, "\u641c\u7d22\u5931\u8d25\uff1a" + safeError(error.getMessage()));
                }
            });
        } catch (RejectedExecutionException error) {
            log.warn("QQ bot search executor is unavailable for keyword {}", safeKeyword, error);
            trySend(groupId, userId, "\u641c\u7d22\u4efb\u52a1\u8f83\u591a\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5");
        }
        return true;
    }

    @PreDestroy
    void shutdownSearchExecutor() {
        searchExecutor.shutdownNow();
    }

    Instant now() {
        return Instant.now();
    }

    private void searchAndReply(Long groupId, Long userId, String keyword, String userKey) {
        try {
            trySend(groupId, userId, buildSearchReply(keyword, userKey));
        } catch (Exception e) {
            log.warn("QQ bot resource search failed for keyword {}", keyword, e);
            trySend(groupId, userId, "搜索失败：" + safeError(e.getMessage()));
        }
    }

    @Override
    public String buildSearchReply(String keyword) {
        return buildSearchReply(keyword, null);
    }

    @Override
    public String buildSearchReply(String keyword, String userKey) {
        String requestedKeyword = trim(keyword, 80);
        if (!hasText(requestedKeyword)) {
            return finishSearch(userKey, requestedKeyword, "REJECTED", null, 0,
                    defaultHelpReply(), "empty keyword");
        }
        ResourcePreference resourcePreference = parseResourcePreference(requestedKeyword);
        if (resourcePreference != null) {
            return buildResourcePreferenceReply(requestedKeyword, userKey, resourcePreference);
        }
        boolean candidateSelection = isCandidateSelection(requestedKeyword);
        if (candidateSelection && removeExpiredResourceCandidates(userKey)) {
            return finishSearch(userKey, requestedKeyword, "EXPIRED", null, 0,
                    "资源候选已过期，请重新搜索影片并选择资源。",
                    "resource candidates expired");
        }
        ResourceCandidates activeResources = activeResourceCandidates(userKey);
        if (activeResources != null && candidateSelection) {
            int index = Integer.parseInt(requestedKeyword.trim()) - 1;
            if (index < 0 || index >= activeResources.resources().size()) {
                return finishSearch(userKey, requestedKeyword, "AMBIGUOUS", activeResources.movieId(), 0,
                        "资源序号无效，请回复 1-" + activeResources.resources().size() + " 之间的序号。",
                        "invalid resource selection");
            }
            ResourceChoice selected = activeResources.resources().get(index);
            resourceCandidates.remove(candidateUserKey(userKey));
            return completeResourceSelection(userKey, requestedKeyword, activeResources, selected);
        }
        if (!candidateSelection) {
            resourceCandidates.remove(candidateUserKey(userKey));
        }
        SuggestedCandidates activeSuggestions = activeSuggestedCandidates(userKey);
        MovieSearchCandidate selectedCandidate = resolveSuggestedCandidate(activeSuggestions, requestedKeyword);
        String selectedKeyword = candidateTitle(selectedCandidate);
        if (isCandidateSelection(requestedKeyword) && activeSuggestions != null && selectedCandidate == null) {
            return finishSearch(userKey, requestedKeyword, "AMBIGUOUS", null, 0,
                    "序号无效，请回复 1-" + activeSuggestions.candidates().size() + " 之间的序号。",
                    "invalid candidate selection");
        }
        String safeKeyword = firstText(selectedKeyword, requestedKeyword);
        if (safeKeyword.length() < Math.max(qqBotProperties.getMinKeywordLength(), 1)) {
            return finishSearch(userKey, safeKeyword, "REJECTED", null, 0,
                    "搜索词太短，请至少输入 " + Math.max(qqBotProperties.getMinKeywordLength(), 1) + " 个字。",
                    "keyword too short");
        }
        String blockedWord = firstBlockedKeyword(safeKeyword);
        if (hasText(blockedWord)) {
            log.info("QQ bot search blocked by sensitive keyword: {}", blockedWord);
            return finishSearch(userKey, safeKeyword, "BLOCKED", null, 0,
                    "搜索词包含不支持的内容，请更换关键词。", "blocked keyword: " + blockedWord);
        }
        if (!allowSearch(userKey)) {
            return finishSearch(userKey, safeKeyword, "RATE_LIMITED", null, 0,
                    "搜索太频繁，请稍后再试。", "rate limited");
        }
        MovieMetadata movie = resolveSelectedCandidateMovie(selectedCandidate);
        List<MovieMetadata> localCandidates = findMovieCandidates(safeKeyword);
        if (selectedCandidate != null && movie == null) {
            return finishSearch(userKey, safeKeyword, "NO_METADATA", null, 0,
                    "所选候选无法建立可信影片元数据：" + safeKeyword
                            + "\n已跳过外部资源搜索，请重新回复其他候选序号。",
                    "selected candidate metadata unavailable");
        }
        if (selectedCandidate == null) {
            MovieMetadata exactLocalMovie = localCandidates.stream()
                    .filter(candidate -> MovieTitleMatcher.isExactMatch(candidate, safeKeyword))
                    .max(Comparator.comparingInt(candidate -> scoreMovie(candidate, safeKeyword)))
                    .orElse(null);
            if (exactLocalMovie != null) {
                List<MovieSearchCandidate> candidates = findSearchCandidates(safeKeyword, localCandidates);
                if (hasDistinctCandidate(exactLocalMovie, candidates)) {
                    rememberSuggestedCandidates(userKey, candidates);
                    return finishSearch(userKey, safeKeyword, "AMBIGUOUS", null, 0,
                            MovieSearchCandidateUtils.formatReply(safeKeyword, candidates),
                            "candidate selection required");
                }
            }
            movie = exactLocalMovie;
        }
        if (movie == null) {
            SeasonSearchUtils.SeasonQuery seasonQuery = SeasonSearchUtils.parse(safeKeyword);
            if (seasonQuery != null) {
                MovieMetadata syncedSeasonMovie = syncExactMovieMetadata(seasonQuery.baseTitle());
                if (MovieTitleMatcher.isExactMatch(syncedSeasonMovie, safeKeyword)) {
                    movie = syncedSeasonMovie;
                }
            }
            if (movie == null) {
                movie = syncExactMovieMetadata(safeKeyword);
            }
            if (movie == null) {
                List<MovieSearchCandidate> candidates = findSearchCandidates(safeKeyword, localCandidates);
                if (!candidates.isEmpty()) {
                    rememberSuggestedCandidates(userKey, candidates);
                    return finishSearch(userKey, safeKeyword, "AMBIGUOUS", null, 0,
                            MovieSearchCandidateUtils.formatReply(safeKeyword, candidates),
                            "candidate selection required");
                }
                return finishSearch(userKey, safeKeyword, "NO_METADATA", null, 0,
                        "没有找到可信影片元数据：" + safeKeyword + "\n已跳过外部资源搜索，避免误转存无关资源。",
                        "no credible metadata");
            }
        } else if (needsMetadataSync(movie)) {
            MovieMetadata syncedMovie = syncExactMovieMetadata(safeKeyword);
            if (MovieTitleMatcher.isExactMatch(syncedMovie, safeKeyword)) {
                movie = syncedMovie;
            } else {
                return finishSearch(userKey, safeKeyword, "NO_METADATA", null, 0,
                        "没有找到可信影片元数据：" + safeKeyword + "\n已跳过外部资源搜索，避免误转存无关资源。",
                        "no credible metadata");
            }
        }
        if (hasText(selectedKeyword)) {
            suggestedCandidates.remove(candidateUserKey(userKey));
        }
        if (isUpcoming(movie)) {
            markTrailer(movie);
            return finishSearch(userKey, safeKeyword, "TRAILER", movie.getId(), 0, buildUpcomingReply(movie), null);
        }

        rememberResourceSearchContext(userKey, movie, safeKeyword);
        List<String> searchNotes = new ArrayList<>();
        List<ResourceChoice> choices = findResourceChoices(movie, safeKeyword, searchNotes);
        if (!choices.isEmpty()) {
            rememberResourceCandidates(userKey, movie, choices);
            return finishSearch(userKey, safeKeyword, "RESOURCE_SELECTION", movie.getId(), choices.size(),
                    buildResourceSelectionReply(movie, choices, searchNotes), null);
        }
        return finishSearch(userKey, safeKeyword, "NO_RESOURCE", movie.getId(), 0,
                buildNoResourceReply(movie, searchNotes), "no resource candidate");
    }

    private String finishSearch(
            String userKey,
            String keyword,
            String status,
            String movieId,
            int resourceCount,
            String reply,
            String failureReason) {
        try {
            QqBotSearchLog item = new QqBotSearchLog();
            item.setUserKey(trim(firstText(userKey, "anonymous"), 100));
            item.setKeyword(trim(keyword, 255));
            item.setStatus(trim(status, 40));
            item.setMovieId(trim(movieId, 100));
            item.setResourceCount(resourceCount);
            item.setReplyPreview(trim(reply, 1000));
            item.setFailureReason(trim(failureReason, 1000));
            item.setCreatedAt(LocalDateTime.now());
            qqBotSearchLogService.save(item);
        } catch (Exception e) {
            log.warn("Failed to save QQ bot search log for keyword {}", keyword, e);
        }
        return reply;
    }

    private String buildResourcePreferenceReply(
            String requestedCommand,
            String userKey,
            ResourcePreference preference) {
        ResourceSearchContext context = activeResourceSearchContext(userKey);
        if (context == null) {
            return finishSearch(userKey, requestedCommand, "REJECTED", null, 0,
                    "当前没有可继续选择的影片，请先发送“搜 片名”。",
                    "resource selection context missing");
        }
        if (!allowSearch(userKey)) {
            return finishSearch(userKey, requestedCommand, "RATE_LIMITED", context.movieId(), 0,
                    "搜索太频繁，请稍后再试。", "rate limited");
        }
        MovieMetadata movie = movieService.getById(context.movieId());
        if (movie == null) {
            resourceSearchContexts.remove(candidateUserKey(userKey));
            return finishSearch(userKey, requestedCommand, "NO_METADATA", context.movieId(), 0,
                    "影片信息已失效，请重新搜索片名。", "movie context missing");
        }

        boolean allProviders = QqResourcePreferenceParser.ALL.equals(preference.provider());
        if ("BAIDU".equals(preference.provider())) {
            return finishSearch(userKey, requestedCommand, "UNSUPPORTED_PROVIDER", movie.getId(), 0,
                    "暂不支持百度网盘服务，请使用夸克或迅雷。", "baidu provider is not supported");
        }
        if (!allProviders && !OWNED_SHARE_PROVIDERS.contains(preference.provider())) {
            return finishSearch(userKey, requestedCommand, "UNSUPPORTED_PROVIDER", movie.getId(), 0,
                    "目前仅支持夸克和迅雷自有分享链接。", "unsupported cloud provider");
        }
        List<String> searchNotes = new ArrayList<>();
        ResourceCandidates candidates = activeResourceCandidates(userKey);
        if (candidates == null || !context.movieId().equals(candidates.movieId())) {
            List<ResourceChoice> discovered = findResourceChoices(movie, context.keyword(), searchNotes);
            if (!discovered.isEmpty()) {
                rememberResourceCandidates(userKey, movie, discovered);
                candidates = activeResourceCandidates(userKey);
            }
        }
        if (candidates == null || candidates.resources().isEmpty()) {
            String detail = searchNotes.isEmpty()
                    ? "暂未找到可用的夸克或迅雷资源；已完成多次搜索，请稍后再试。"
                    : String.join("；", searchNotes);
            return finishSearch(userKey, requestedCommand, "NO_RESOURCE", movie.getId(), 0,
                    "没有找到可供选择的资源：" + detail, detail);
        }
        List<ResourceChoice> filtered = allProviders
                ? candidates.allResources()
                : candidates.allResources().stream()
                        .filter(choice -> preference.provider().equalsIgnoreCase(choice.provider()))
                        .toList();
        if (filtered.isEmpty()) {
            return finishSearch(userKey, requestedCommand, "NO_RESOURCE", movie.getId(), 0,
                    "当前没有" + QqResourcePreferenceParser.label(preference.provider())
                            + "资源候选，请回复“资源”查看全部候选，或重新搜索影片。",
                    "no matching provider candidate");
        }
        ResourceCandidates filteredCandidates = new ResourceCandidates(
                now().plusSeconds(RESOURCE_CONTEXT_TTL_SECONDS),
                candidates.movieId(),
                candidates.movieTitle(),
                candidates.allResources(),
                filtered);
        resourceCandidates.put(candidateUserKey(userKey), filteredCandidates);
        String note = QqResourcePreferenceParser.hasExplicitCount(requestedCommand)
                ? "已忽略数量指令，仅展示候选；请回复单个资源序号，避免重复转存。"
                : null;
        List<String> notes = note == null ? searchNotes : new ArrayList<>(searchNotes);
        if (note != null) {
            notes.add(note);
        }
        return finishSearch(userKey, requestedCommand, "RESOURCE_SELECTION", movie.getId(), filtered.size(),
                buildResourceSelectionReply(movie, filtered, notes), null);
    }

    private List<ResourceChoice> findResourceChoices(
            MovieMetadata movie,
            String keyword,
            List<String> searchNotes) {
        List<ResourceChoice> choices = new ArrayList<>();
        for (ResourceLink link : loadOwnedResourcesForReply(movie, OWNED_SHARE_PROVIDERS, MAX_SELECTABLE_RESOURCES)) {
            choices.add(ResourceChoice.owned(link));
        }
        if (resourceHubProperties.isEnabled()) {
            discoverResourceCandidates(movie, keyword, searchNotes);
            List<ResourceDiscoveryResult> discoveries = discoveryResultService.list(
                    new QueryWrapper<ResourceDiscoveryResult>()
                            .eq("movie_id", movie.getId())
                            .in("provider", OWNED_SHARE_PROVIDERS)
                            .in("status", List.of("DISCOVERED", "FAILED"))
                            .orderByDesc("confidence")
                            .orderByDesc("created_at")
                            .last("LIMIT " + MAX_SELECTABLE_RESOURCES));
            if (discoveries == null) {
                discoveries = List.of();
            }
            Set<String> identities = new LinkedHashSet<>();
            choices.forEach(choice -> identities.add(choice.identity()));
            for (ResourceDiscoveryResult discovery : discoveries) {
                ResourceChoice choice = ResourceChoice.discovery(discovery);
                if (hasText(choice.name()) && identities.add(choice.identity())) {
                    choices.add(choice);
                }
                if (choices.size() >= MAX_SELECTABLE_RESOURCES) {
                    break;
                }
            }
        }
        return List.copyOf(choices);
    }

    private void discoverResourceCandidates(MovieMetadata movie, String keyword, List<String> searchNotes) {
        List<String> errors = new ArrayList<>();
        for (String searchKeyword : buildSearchKeywords(movie, keyword)) {
            try {
                ResourceDiscoveryRequest request = new ResourceDiscoveryRequest();
                request.setMovieId(movie.getId());
                request.setKeyword(searchKeyword);
                request.setSource("AUTO");
                request.setMaxResults(MAX_SELECTABLE_RESOURCES);
                request.setDeferTransfer(true);
                ResourceHubTask task = resourceDiscoveryService.enqueue(request);
                ResourceDiscoveryRunResult result = resourceDiscoveryService.runTask(task.getId());
                if (result.getDiscovered() > 0 || result.getDuplicate() > 0) {
                    return;
                }
                if (result.getErrors() != null && !result.getErrors().isEmpty()) {
                    errors.add(safeError(result.getErrors().get(0)));
                }
            } catch (Exception error) {
                errors.add(safeError(error.getMessage()));
            }
        }
        searchNotes.add(errors.isEmpty()
                ? "多次搜索后仍未发现可转存资源"
                : "资源搜索失败：" + String.join("；", errors));
    }

    private String completeResourceSelection(
            String userKey,
            String requestedKeyword,
            ResourceCandidates candidates,
            ResourceChoice choice) {
        if (choice.resourceLink() != null) {
            return finishSearch(userKey, requestedKeyword, "SUCCEEDED", candidates.movieId(), 1,
                    buildSelectedResourceReply(candidates.movieTitle(), choice.resourceLink()), null);
        }
        List<String> transferNotes = new ArrayList<>();
        ResourceLink transferred = transferSelectedDiscovery(choice.discoveryId(), transferNotes);
        if (transferred != null) {
            return finishSearch(userKey, requestedKeyword, "SUCCEEDED", candidates.movieId(), 1,
                    buildSelectedResourceReply(candidates.movieTitle(), transferred), null);
        }
        String detail = transferNotes.isEmpty()
                ? "所选资源多次转存后仍失败，可能无资源、违规或分享内没有视频文件。"
                : String.join("；", transferNotes);
        return finishSearch(userKey, requestedKeyword, "NO_RESOURCE", candidates.movieId(), 0,
                "所选资源转存失败：" + detail + "\n请重新搜索影片并选择其他资源。", detail);
    }

    private ResourceLink transferSelectedDiscovery(Long discoveryId, List<String> transferNotes) {
        if (discoveryId == null) {
            transferNotes.add("资源记录已失效");
            return null;
        }
        ResourceDiscoveryResult discovery = discoveryResultService.getById(discoveryId);
        if (discovery == null) {
            transferNotes.add("资源记录已失效");
            return null;
        }
        try {
            if ("XUNLEI".equalsIgnoreCase(discovery.getProvider())) {
                XunleiTransferTask task = xunleiTransferTaskService.getOne(
                        new QueryWrapper<XunleiTransferTask>()
                                .eq("discovery_result_id", discoveryId)
                                .orderByDesc("created_at")
                                .last("LIMIT 1"), false);
                if (task == null) {
                    resourceDiscoveryService.ensureTransferTask(discoveryId);
                    task = xunleiTransferTaskService.getOne(
                            new QueryWrapper<XunleiTransferTask>()
                                    .eq("discovery_result_id", discoveryId)
                                    .orderByDesc("created_at")
                                    .last("LIMIT 1"), false);
                }
                if (task == null && hasText(discovery.getOriginalUrlHash())) {
                    task = xunleiTransferTaskService.getOne(
                            new QueryWrapper<XunleiTransferTask>()
                                    .eq("movie_id", discovery.getMovieId())
                                    .eq("original_url_hash", discovery.getOriginalUrlHash())
                                    .orderByDesc("updated_at")
                                    .last("LIMIT 1"), false);
                }
                if (task == null) {
                    transferNotes.add("未创建迅雷转存任务，请稍后重试");
                    return null;
                }
                submitXunleiTransferTask(task, transferNotes);
                syncTaskShareToDiscovery(discovery, xunleiTransferTaskService.getById(task.getId()));
            } else {
                QuarkTransferTask task = quarkTransferTaskService.getOne(
                        new QueryWrapper<QuarkTransferTask>()
                                .eq("discovery_result_id", discoveryId)
                                .orderByDesc("created_at")
                                .last("LIMIT 1"), false);
                if (task == null) {
                    resourceDiscoveryService.ensureTransferTask(discoveryId);
                    task = quarkTransferTaskService.getOne(
                            new QueryWrapper<QuarkTransferTask>()
                                    .eq("discovery_result_id", discoveryId)
                                    .orderByDesc("created_at")
                                    .last("LIMIT 1"), false);
                }
                if (task == null && hasText(discovery.getOriginalUrlHash())) {
                    task = quarkTransferTaskService.getOne(
                            new QueryWrapper<QuarkTransferTask>()
                                    .eq("movie_id", discovery.getMovieId())
                                    .eq("original_url_hash", discovery.getOriginalUrlHash())
                                    .orderByDesc("updated_at")
                                    .last("LIMIT 1"), false);
                }
                if (task == null) {
                    transferNotes.add("未创建夸克转存任务，请稍后重试");
                    return null;
                }
                submitTransferTask(task, transferNotes);
                syncTaskShareToDiscovery(discovery, quarkTransferTaskService.getById(task.getId()));
            }
            ResourceHubPublishResult publishResult = resourceHubPublishService.publishDiscovery(discoveryId);
            if (publishResult != null && publishResult.getFailed() > 0
                    && publishResult.getErrors() != null && !publishResult.getErrors().isEmpty()) {
                transferNotes.add(safeError(publishResult.getErrors().get(0)));
            }
            ResourceDiscoveryResult refreshed = discoveryResultService.getById(discoveryId);
            if (refreshed != null && refreshed.getResourceLinkId() != null) {
                ResourceLink link = resourceLinkService.getById(refreshed.getResourceLinkId());
                ResourceLink ready = prepareResourceForReply(link);
                if (ready == null) {
                    transferNotes.add("分享链接校验失败，可能违规或分享内没有视频文件");
                }
                return ready;
            }
            if (refreshed != null && hasText(refreshed.getFailureReason())) {
                transferNotes.add(userFacingTransferError(refreshed.getFailureReason()));
            }
        } catch (Exception error) {
            transferNotes.add(userFacingTransferError(error.getMessage()));
        }
        return null;
    }

    private void syncTaskShareToDiscovery(ResourceDiscoveryResult discovery, Object task) {
        if (discovery == null || task == null) {
            return;
        }
        String shareUrl = null;
        String shareHash = null;
        if (task instanceof QuarkTransferTask quark) {
            shareUrl = quark.getShareUrl();
            shareHash = quark.getShareUrlHash();
        } else if (task instanceof XunleiTransferTask xunlei) {
            shareUrl = xunlei.getShareUrl();
            shareHash = xunlei.getShareUrlHash();
        }
        if (!hasText(shareUrl)) {
            return;
        }
        discovery.setShareUrl(shareUrl);
        discovery.setShareUrlHash(shareHash);
        discovery.setStatus("DISCOVERED");
        discovery.setFailureReason(null);
        discovery.setUpdatedAt(LocalDateTime.now());
        discoveryResultService.updateById(discovery);
    }

    private void submitTransferTask(QuarkTransferTask task, List<String> transferNotes) {
        if (task == null) {
            transferNotes.add("夸克转存任务不存在");
            return;
        }
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
            if (refreshed != null && hasText(refreshed.getLastError())) {
                transferNotes.add(userFacingTransferError(refreshed.getLastError()));
            }
            if (result != null && result.getFailed() > 0
                    && result.getErrors() != null && !result.getErrors().isEmpty()) {
                transferNotes.add(safeError(result.getErrors().get(0)));
            }
        } catch (Exception e) {
            transferNotes.add(userFacingTransferError(e.getMessage()));
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

    private void submitXunleiTransferTask(XunleiTransferTask task, List<String> transferNotes) {
        if (task == null) {
            transferNotes.add("迅雷转存任务不存在");
            return;
        }
        try {
            QuarkTransferRunResult result = xunleiTransferRunnerService.submitOne(task.getId());
            XunleiTransferTask refreshed = xunleiTransferTaskService.getById(task.getId());
            if (refreshed != null && hasText(refreshed.getShareUrl())) {
                transferNotes.add("已创建我的迅雷分享");
            }
            addSavedPathNote(refreshed, transferNotes);
            if (refreshed != null && hasText(refreshed.getLastError())) {
                transferNotes.add(userFacingTransferError(refreshed.getLastError()));
            }
            if (result != null && result.getFailed() > 0
                    && result.getErrors() != null && !result.getErrors().isEmpty()) {
                transferNotes.add(safeError(result.getErrors().get(0)));
            }
        } catch (Exception e) {
            transferNotes.add(userFacingTransferError(e.getMessage()));
        }
    }

    private List<MovieMetadata> findMovieCandidates(String keyword) {
        List<String> lookupKeywords = buildLookupKeywords(keyword);
        List<MovieMetadata> candidates = movieService.list(new QueryWrapper<MovieMetadata>()
                .eq("status", "ACTIVE")
                .and(query -> {
                    boolean first = true;
                    for (String lookupKeyword : lookupKeywords) {
                        if (!first) {
                            query.or();
                        }
                        query.like("title_cn", lookupKeyword)
                                .or().like("title_en", lookupKeyword)
                                .or().like("series_name", lookupKeyword)
                                .or().like("aliases", lookupKeyword);
                        first = false;
                    }
                })
                .orderByDesc("popularity")
                .orderByDesc("tmdb_popularity")
                .orderByDesc("created_at")
                .last("LIMIT 30"));
        return candidates.stream()
                .sorted(Comparator.comparingInt((MovieMetadata movie) -> scoreMovie(movie, keyword)).reversed())
                .toList();
    }

    private MovieMetadata syncExactMovieMetadata(String keyword) {
        for (String searchKeyword : buildMetadataSearchKeywords(keyword)) {
            try {
                MovieMetadata movie = tmdbMetadataSyncService.syncExactByKeyword(searchKeyword);
                if (MovieTitleMatcher.isExactMatch(movie, keyword)) {
                    return movie;
                }
            } catch (Exception e) {
                log.warn("TMDB metadata sync failed for QQ keyword {}", searchKeyword, e);
            }
        }
        return null;
    }

    private List<String> buildLookupKeywords(String keyword) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String baseTitle = SeasonSearchUtils.baseTitle(keyword);
        addSearchKeyword(values, baseTitle);
        addSearchKeyword(values, keyword);
        addSearchKeyword(values, compactTitle(keyword));
        String compact = compactTitle(baseTitle);
        if (compact.length() >= 3) {
            addSearchKeyword(values, compact.substring(0, Math.min(3, compact.length())));
        }
        return List.copyOf(values);
    }

    private List<String> buildMetadataSearchKeywords(String keyword) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addSearchKeyword(values, keyword);
        addSearchKeyword(values, SeasonSearchUtils.baseTitle(keyword));
        addSearchKeyword(values, compactTitle(keyword));
        return List.copyOf(values);
    }

    private void addSearchKeyword(Set<String> values, String value) {
        if (hasText(value) && compactTitle(value).length() >= 2) {
            values.add(value.trim());
        }
    }

    private String compactTitle(String value) {
        if (!hasText(value)) {
            return "";
        }
        StringBuilder compact = new StringBuilder();
        value.trim().toLowerCase(Locale.ROOT)
                .codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(compact::appendCodePoint);
        return compact.toString();
    }

    private List<MovieSearchCandidate> findSearchCandidates(
            String keyword,
            List<MovieMetadata> localCandidates) {
        List<MovieSearchCandidate> local = localCandidates.stream()
                .map(movie -> new MovieSearchCandidate(
                        movie.getTmdbId(),
                        resolveCandidateMediaType(movie),
                        title(movie),
                        movie.getTitleEn(),
                        movie.getYear(),
                        scoreMovie(movie, keyword),
                        "LOCAL",
                        resolveCandidateMediaType(movie),
                        movie.getTmdbId() == null ? movie.getId() : String.valueOf(movie.getTmdbId()),
                        movie.getId()))
                .toList();
        List<MovieSearchCandidate> gying;
        try {
            gying = gyingSourceWorkflowService.searchCandidates(keyword, MAX_CANDIDATE_SUGGESTIONS);
        } catch (Exception e) {
            log.warn("GYING candidate search failed for QQ keyword {}", keyword, e);
            gying = List.of();
        }
        List<MovieSearchCandidate> tmdb;
        try {
            tmdb = tmdbMetadataSyncService.searchCandidatesByKeyword(keyword, MAX_CANDIDATE_SUGGESTIONS);
            tmdb.forEach(candidate -> {
                if (!hasText(candidate.getSource())) {
                    candidate.setSource("TMDB");
                }
                if (!hasText(candidate.getSourceType())) {
                    candidate.setSourceType(candidate.getMediaType());
                }
                if (!hasText(candidate.getSourceId()) && candidate.getTmdbId() != null) {
                    candidate.setSourceId(String.valueOf(candidate.getTmdbId()));
                }
            });
        } catch (Exception e) {
            log.warn("TMDB candidate search failed for QQ keyword {}", keyword, e);
            tmdb = List.of();
        }
        List<MovieSearchCandidate> fallback = MovieSearchCandidateUtils.merge(
                local, tmdb, MAX_CANDIDATE_SUGGESTIONS * 2);
        return MovieSearchCandidateUtils.mergeBalanced(
                gying,
                fallback,
                MAX_CANDIDATE_SUGGESTIONS,
                MAX_GYING_CANDIDATE_SUGGESTIONS);
    }

    private String resolveCandidateMediaType(MovieMetadata movie) {
        String type = firstText(movie.getTmdbType(), movie.getCategory());
        return hasText(type) && List.of("tv", "series", "show", "drama", "ac").contains(type.toLowerCase())
                ? "tv"
                : "movie";
    }

    private void rememberSuggestedCandidates(String userKey, List<MovieSearchCandidate> candidates) {
        String key = candidateUserKey(userKey);
        resourceCandidates.remove(key);
        suggestedCandidates.put(key, new SuggestedCandidates(
                now().plusSeconds(CANDIDATE_TTL_SECONDS),
                List.copyOf(candidates)));
    }

    private SuggestedCandidates activeSuggestedCandidates(String userKey) {
        String key = candidateUserKey(userKey);
        SuggestedCandidates suggestions = suggestedCandidates.get(key);
        if (suggestions != null && suggestions.expiresAt().isBefore(now())) {
            suggestedCandidates.remove(key);
            return null;
        }
        return suggestions;
    }

    private MovieSearchCandidate resolveSuggestedCandidate(
            SuggestedCandidates suggestions,
            String keyword) {
        if (suggestions == null || !isCandidateSelection(keyword)) {
            return null;
        }
        int index = Integer.parseInt(keyword.trim()) - 1;
        return index < 0 || index >= suggestions.candidates().size()
                ? null
                : suggestions.candidates().get(index);
    }

    private String candidateTitle(MovieSearchCandidate candidate) {
        return candidate == null
                ? null
                : firstText(candidate.getTitle(), candidate.getOriginalTitle());
    }

    private MovieMetadata resolveSelectedCandidateMovie(MovieSearchCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if ("GYING".equalsIgnoreCase(candidate.getSource())
                && hasText(candidate.getSourceType())
                && hasText(candidate.getSourceId())) {
            try {
                Map<String, Object> result = gyingSourceWorkflowService.ensureMovieMetadata(
                        candidate.getSourceType(),
                        candidate.getSourceId());
                String localMovieId = result == null ? null : String.valueOf(result.get("localMovieId"));
                if (hasText(localMovieId) && !"null".equalsIgnoreCase(localMovieId)) {
                    MovieMetadata local = movieService.getById(localMovieId);
                    if (local != null && !"DELETED".equalsIgnoreCase(local.getStatus())) {
                        return local;
                    }
                }
            } catch (Exception error) {
                log.info("Selected GYING candidate {}/{} metadata could not be prepared directly; "
                                + "continuing with local metadata and PanSou fallback",
                        candidate.getSourceType(), candidate.getSourceId(), error);
            }
            return findMovieCandidates(candidateTitle(candidate)).stream()
                    .filter(movie -> matchesCandidate(movie, candidate))
                    .max(Comparator.comparingInt(movie -> scoreMovie(movie, candidateTitle(candidate))))
                    .orElse(null);
        }
        if (hasText(candidate.getLocalMovieId())) {
            MovieMetadata local = movieService.getById(candidate.getLocalMovieId());
            if (local != null && !"DELETED".equalsIgnoreCase(local.getStatus())) {
                return local;
            }
        }
        return syncExactMovieMetadata(candidateTitle(candidate));
    }

    private boolean hasDistinctCandidate(
            MovieMetadata exactLocalMovie,
            List<MovieSearchCandidate> candidates) {
        if (exactLocalMovie == null || candidates == null || candidates.size() < 2) {
            return false;
        }
        return candidates.stream().anyMatch(candidate -> !matchesCandidate(exactLocalMovie, candidate));
    }

    private boolean matchesCandidate(MovieMetadata movie, MovieSearchCandidate candidate) {
        if (movie == null || candidate == null || !MovieTitleMatcher.isExactMatch(
                movie,
                candidateTitle(candidate))) {
            return false;
        }
        String candidateType = normalizeCandidateMediaType(candidate.getMediaType());
        String movieType = resolveCandidateMediaType(movie);
        if (hasText(candidateType) && hasText(movieType) && !candidateType.equals(movieType)) {
            return false;
        }
        return candidate.getYear() == null
                || movie.getYear() == null
                || candidate.getYear().equals(movie.getYear());
    }

    private String normalizeCandidateMediaType(String mediaType) {
        return hasText(mediaType)
                && List.of("tv", "series", "show", "drama", "ac").contains(mediaType.toLowerCase(Locale.ROOT))
                ? "tv"
                : hasText(mediaType) ? "movie" : "";
    }

    private boolean isCandidateSelection(String value) {
        return hasText(value) && value.trim().matches("(?:[1-9]|10)");
    }

    private String candidateUserKey(String userKey) {
        return hasText(userKey) ? userKey.trim() : "anonymous";
    }

    private void rememberResourceSearchContext(String userKey, MovieMetadata movie, String keyword) {
        if (movie == null || !hasText(movie.getId()) || !hasText(keyword)) {
            return;
        }
        resourceSearchContexts.put(candidateUserKey(userKey), new ResourceSearchContext(
                now().plusSeconds(RESOURCE_CONTEXT_TTL_SECONDS),
                movie.getId(),
                keyword.trim()));
    }

    private void rememberResourceCandidates(String userKey, MovieMetadata movie, List<ResourceChoice> links) {
        if (movie == null || links == null || links.isEmpty()) {
            return;
        }
        String key = candidateUserKey(userKey);
        suggestedCandidates.remove(key);
        resourceCandidates.put(key, new ResourceCandidates(
                now().plusSeconds(RESOURCE_CONTEXT_TTL_SECONDS),
                movie.getId(),
                title(movie),
                List.copyOf(links),
                List.copyOf(links)));
    }

    private boolean removeExpiredResourceCandidates(String userKey) {
        String key = candidateUserKey(userKey);
        ResourceCandidates candidates = resourceCandidates.get(key);
        if (candidates == null || !candidates.expiresAt().isBefore(now())) {
            return false;
        }
        resourceCandidates.remove(key, candidates);
        return true;
    }

    private ResourceCandidates activeResourceCandidates(String userKey) {
        String key = candidateUserKey(userKey);
        ResourceCandidates candidates = resourceCandidates.get(key);
        if (candidates != null && candidates.expiresAt().isBefore(now())) {
            resourceCandidates.remove(key);
            return null;
        }
        return candidates;
    }

    private ResourceSearchContext activeResourceSearchContext(String userKey) {
        String key = candidateUserKey(userKey);
        ResourceSearchContext context = resourceSearchContexts.get(key);
        if (context != null && context.expiresAt().isBefore(now())) {
            resourceSearchContexts.remove(key);
            return null;
        }
        return context;
    }

    private ResourcePreference parseResourcePreference(String value) {
        return QqResourcePreferenceParser.parse(
                value,
                safeMaxResults(),
                MAX_SELECTABLE_RESOURCES);
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
        Instant now = now();
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
        String normalized = compactTitle(firstText(SeasonSearchUtils.baseTitle(keyword), keyword));
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
        String normalized = compactTitle(value);
        if (normalized.equals(keyword)) {
            return exactScore;
        }
        return normalized.contains(keyword) ? exactScore / 2 : 0;
    }

    private int containsScore(String value, String keyword, int score) {
        return hasText(value) && compactTitle(value).contains(keyword) ? score : 0;
    }

    private List<ResourceLink> loadResources(String movieId) {
        return loadResources(movieId, Set.of(), MAX_REPLY_RESOURCES);
    }

    private List<ResourceLink> loadResources(
            String movieId,
            Set<String> providers,
            int maxResults) {
        return loadResources(movieId, providers, maxResults, false);
    }

    private List<ResourceLink> loadOwnedResources(String movieId, Set<String> providers, int maxResults) {
        int limit = Math.min(Math.max(maxResults, 1), 100);
        return resourceLinkService.list(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .in("provider", providers)
                .eq("audit_status", 1)
                .eq("status", "ACTIVE")
                .orderByDesc("created_at")
                .last("LIMIT " + limit)).stream()
                .filter(this::isOwnedShare)
                .toList();
    }

    private List<ResourceLink> loadOwnedResourcesForReply(
            MovieMetadata movie,
            Set<String> providers,
            int maxResults) {
        int limit = safeMaxResults(maxResults);
        int candidateLimit = Math.min(Math.max(limit * 5, 20), 100);
        List<ResourceLink> sameMovieCandidates = loadOwnedResources(movie.getId(), providers, candidateLimit).stream()
                .sorted(Comparator.comparingInt(link -> ownedResourcePriority(link, movie)))
                .toList();
        Map<String, ResourceLink> unique = new LinkedHashMap<>();
        Set<String> inspected = new LinkedHashSet<>();
        for (ResourceLink candidate : sameMovieCandidates) {
            if (!inspected.add(resourceIdentity(candidate))) {
                continue;
            }
            ResourceLink ready = prepareResourceForReply(candidate);
            if (ready != null) {
                unique.put(resourceIdentity(ready), ready);
            }
            if (unique.size() >= limit && hasProviderCoverage(unique.values(), providers)) {
                break;
            }
        }

        String requestedTitle = title(movie);
        String baseTitle = SeasonSearchUtils.baseTitle(requestedTitle);
        if (!hasText(baseTitle) || baseTitle.length() < 2) {
            return selectOwnedResources(unique.values(), providers, limit);
        }
        List<ResourceLink> relatedCandidates = resourceLinkService.list(new QueryWrapper<ResourceLink>()
                .ne("movie_id", movie.getId())
                .in("provider", providers)
                .eq("source", "RESOURCE_HUB")
                .eq("audit_status", 1)
                .eq("status", "ACTIVE")
                .like("name", baseTitle)
                .orderByDesc("created_at")
                .last("LIMIT 50"));
        for (ResourceLink candidate : relatedCandidates) {
            if (!inspected.add(resourceIdentity(candidate))) {
                continue;
            }
            if (!isOwnedShare(candidate)
                    || !SeasonSearchUtils.hasSeasonMarker(candidate.getName())
                    || !SeasonSearchUtils.matchesRequestedSeason(candidate.getName(), requestedTitle)
                    || !ResourceTitleMatcher.isRelevant(movie, candidate.getName(), requestedTitle)) {
                continue;
            }
            ResourceLink ready = prepareResourceForReply(candidate);
            if (ready != null) {
                unique.putIfAbsent(resourceIdentity(ready), ready);
            }
            if (unique.size() >= limit && hasProviderCoverage(unique.values(), providers)) {
                break;
            }
        }
        return selectOwnedResources(unique.values(), providers, limit);
    }

    private boolean hasProviderCoverage(
            java.util.Collection<ResourceLink> links,
            Set<String> providers) {
        return providers.stream().allMatch(provider -> links.stream()
                .anyMatch(link -> provider.equalsIgnoreCase(link.getProvider())));
    }

    private List<ResourceLink> selectOwnedResources(
            java.util.Collection<ResourceLink> candidates,
            Set<String> providers,
            int limit) {
        List<ResourceLink> result = new ArrayList<>();
        for (String provider : List.of("QUARK", "XUNLEI")) {
            if (!providers.contains(provider)) {
                continue;
            }
            candidates.stream()
                    .filter(link -> provider.equalsIgnoreCase(link.getProvider()))
                    .findFirst()
                    .ifPresent(link -> {
                        if (result.size() < limit && !result.contains(link)) {
                            result.add(link);
                        }
                    });
        }
        for (ResourceLink link : candidates) {
            if (result.size() >= limit) {
                break;
            }
            if (!providers.contains(firstText(link.getProvider(), "").toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (!result.contains(link)) {
                result.add(link);
            }
        }
        return List.copyOf(result);
    }

    private String resourceIdentity(ResourceLink link) {
        if (link != null && hasText(link.getUrl())) {
            return link.getUrl().trim().toLowerCase();
        }
        return link != null && link.getId() != null ? "id:" + link.getId() : "missing";
    }

    private List<ResourceLink> loadResources(
            String movieId,
            Set<String> providers,
            int maxResults,
            boolean ownedQuarkOnly) {
        int limit = safeMaxResults(maxResults);
        QueryWrapper<ResourceLink> query = new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("audit_status", 1)
                .eq("status", "ACTIVE");
        if (providers != null && !providers.isEmpty()) {
            query.in("provider", providers);
        }
        if (ownedQuarkOnly) {
            query.eq("source", "RESOURCE_HUB");
        }
        List<ResourceLink> candidates = resourceLinkService.list(query
                .orderByDesc("created_at")
                .last("LIMIT " + Math.min(Math.max(limit * 5, 20), 100))).stream()
                .filter(link -> providers == null
                        || providers.isEmpty()
                        || providers.contains(link != null && hasText(link.getProvider())
                                ? link.getProvider().trim().toUpperCase()
                                : ""))
                .filter(link -> !ownedQuarkOnly || isOwnedShare(link))
                .sorted(Comparator.comparingInt(this::resourceReplyPriority))
                .toList();
        List<ResourceLink> resources = new ArrayList<>();
        for (ResourceLink link : candidates) {
            ResourceLink ready = prepareResourceForReply(link);
            if (ready != null) {
                resources.add(ready);
            }
            if (resources.size() >= limit) {
                break;
            }
        }
        return resources;
    }

    private int resourceReplyPriority(ResourceLink link) {
        if (isOwnedShare(link)) {
            return 0;
        }
        return link != null && "QUARK".equalsIgnoreCase(link.getProvider()) ? 1 : 2;
    }

    private boolean isOwnedShare(ResourceLink link) {
        if (link == null || !OWNED_SHARE_PROVIDERS.contains(
                firstText(link.getProvider(), "").toUpperCase(Locale.ROOT))) {
            return false;
        }
        if ("RESOURCE_HUB".equalsIgnoreCase(link.getSource())
                || "GYING_PUBLISHED".equalsIgnoreCase(link.getSource())) {
            return true;
        }
        if (!"GYING".equalsIgnoreCase(link.getSource())
                || link.getId() == null
                || !hasText(link.getUrl())) {
            return false;
        }
        try {
            ResourceDiscoveryResult discovery = discoveryResultService.getOne(
                    new QueryWrapper<ResourceDiscoveryResult>()
                    .eq("resource_link_id", link.getId())
                    .eq("share_url", link.getUrl())
                    .eq("status", "SAVED")
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"),
                    false);
            if (discovery == null) {
                return false;
            }
            if ("XUNLEI".equalsIgnoreCase(link.getProvider())) {
                return xunleiTransferTaskService.count(new QueryWrapper<XunleiTransferTask>()
                        .eq("discovery_result_id", discovery.getId())
                        .eq("share_url", link.getUrl())
                        .eq("status", "SUCCEEDED")
                        .isNotNull("saved_path")) > 0;
            }
            return quarkTransferTaskService.count(new QueryWrapper<QuarkTransferTask>()
                    .eq("discovery_result_id", discovery.getId())
                    .eq("share_url", link.getUrl())
                    .isNotNull("saved_path")) > 0;
        } catch (Exception error) {
            log.warn("Failed to verify legacy GYING owned share {}", link.getId(), error);
            return false;
        }
    }

    private int ownedResourcePriority(ResourceLink link, MovieMetadata movie) {
        int health = isNormalLink(link) ? 0 : 20;
        int coverage = resourceCoveragePriority(link == null ? null : link.getName(), title(movie));
        int ownership = link != null && link.getUploaderId() != null ? 0 : 1;
        return health + coverage + ownership;
    }

    private int resourceCoveragePriority(String resourceTitle, String requestedTitle) {
        if (!hasText(resourceTitle)) {
            return 8;
        }
        String compact = resourceTitle.replaceAll("\\s+", "");
        if (compact.matches("(?s).*(?:\u5168\u96c6|\u5168\u5b63|\u5168\\d+\u5b63|\\d+[-~\u81f3]\\d+\u5b63).*$")) {
            return 0;
        }
        boolean requestedSeason = SeasonSearchUtils.matchesRequestedSeason(resourceTitle, requestedTitle);
        if (requestedSeason && compact.matches("(?s).*(?:\u5b8c\u7ed3|\u5168\u96c6|\u5168\\d+\u96c6).*$")) {
            return 1;
        }
        if (compact.matches("(?is).*(?:\u66f4\u65b0\u81f3|\u7b2c\\d+\u96c6|S\\d+E\\d+|\\bE\\d+).*$")) {
            return 6;
        }
        return requestedSeason ? 2 : 4;
    }

    private ResourceLink prepareResourceForReply(ResourceLink link) {
        if (link == null || !hasText(link.getUrl())) {
            return null;
        }
        if (isPermanentInvalid(link)) {
            return null;
        }
        if (!requiresLiveValidation(link)) {
            return isNormalLink(link) ? link : null;
        }

        LinkCheckResult currentCheck = checkLink(link.getUrl());
        if (currentCheck.checked() && currentCheck.valid()) {
            try {
                verifySavedFolder(link);
                markLinkNormal(link);
                return link;
            } catch (Exception e) {
                String reason = "Saved Quark folder is unusable: " + safeError(e.getMessage());
                markLinkInvalid(link, reason);
                retireTransferForRediscovery(link, reason);
                return null;
            }
        }
        if (!currentCheck.checked()) {
            String reason = "Unable to verify share link: " + safeError(currentCheck.message());
            recordLinkValidationUnavailable(link, reason);
            if (isNormalLink(link)) {
                return link;
            }
            return null;
        }
        if (!canRefreshShare(link)) {
            String reason = firstText(currentCheck.message(), "Link is invalid and cannot be refreshed");
            if (isTrustedPublishedShare(link)) {
                markLinkSuspected(link, reason);
                return link;
            }
            markLinkInvalid(link, reason);
            retireTransferForRediscovery(link, reason);
            return null;
        }

        ResourceLink refreshed;
        try {
            refreshed = refreshShareLink(link);
        } catch (Exception e) {
            log.warn("Failed to refresh invalid resource link {}", link.getId(), e);
            String reason = "Share refresh failed: " + safeError(e.getMessage());
            markLinkInvalid(link, reason);
            retireTransferForRediscovery(link, reason);
            return null;
        }
        if (refreshed == null || !hasText(refreshed.getUrl())) {
            String reason = "Unable to refresh invalid share link";
            markLinkInvalid(link, reason);
            retireTransferForRediscovery(link, reason);
            return null;
        }
        LinkCheckResult refreshedCheck = checkLink(refreshed.getUrl());
        if (refreshedCheck.checked() && refreshedCheck.valid()) {
            try {
                verifySavedFolder(refreshed);
                markLinkNormal(refreshed);
                return refreshed;
            } catch (Exception e) {
                String reason = "Refreshed saved folder is unusable: " + safeError(e.getMessage());
                markLinkInvalid(refreshed, reason);
                retireTransferForRediscovery(refreshed, reason);
                return null;
            }
        }
        if (!refreshedCheck.checked()) {
            markLinkSuspected(refreshed, "Unable to verify refreshed share link: " + safeError(refreshedCheck.message()));
            return null;
        }
        markLinkInvalid(refreshed, PERMANENT_LINK_INVALID_REASON);
        retireTransferForRediscovery(refreshed, PERMANENT_LINK_INVALID_REASON);
        return null;
    }

    private boolean requiresLiveValidation(ResourceLink link) {
        return "QUARK".equalsIgnoreCase(link.getProvider())
                || "XUNLEI".equalsIgnoreCase(link.getProvider())
                || link.getUrl().toLowerCase().contains("pan.quark.cn/s/")
                || link.getUrl().toLowerCase().contains("pan.xunlei.com/s/");
    }

    private void verifySavedFolder(ResourceLink link) {
        if (!"QUARK".equalsIgnoreCase(link.getProvider())) {
            return;
        }
        QuarkTransferTask task = findTransferTask(link);
        if (task == null || !"SUBMITTED".equalsIgnoreCase(task.getStatus()) || !hasText(task.getSavedPath())) {
            return;
        }
        quarkShareService.ensureShareUrl(task);
    }

    private void retireTransferForRediscovery(ResourceLink link, String reason) {
        LocalDateTime now = LocalDateTime.now();
        if ("XUNLEI".equalsIgnoreCase(link.getProvider())) {
            XunleiTransferTask task = findXunleiTransferTask(link);
            if (task != null) {
                task.setStatus("FAILED");
                task.setLastError(trim(reason, 1000));
                task.setFinishedAt(now);
                task.setUpdatedAt(now);
                xunleiTransferTaskService.updateById(task);
            }
        } else {
            QuarkTransferTask task = findTransferTask(link);
            if (task != null) {
                task.setStatus("FAILED");
                task.setLastError(trim(reason, 1000));
                task.setFinishedAt(now);
                task.setUpdatedAt(now);
                quarkTransferTaskService.updateById(task);
            }
        }
        ResourceDiscoveryResult discovery = findDiscovery(link);
        if (discovery != null) {
            discovery.setStatus("FAILED");
            discovery.setFailureReason(trim(reason, 1000));
            discovery.setUpdatedAt(now);
            discoveryResultService.updateById(discovery);
        }
    }
    private LinkCheckResult checkLink(String url) {
        try {
            LinkCheckResult result = panSouClient.checkLink(url);
            return result == null
                    ? new LinkCheckResult(url, false, false, "Link validation unavailable")
                    : result;
        } catch (Exception e) {
            log.warn("PanSou link check failed for {}", url, e);
            return new LinkCheckResult(url, false, false, safeError(e.getMessage()));
        }
    }

    private ResourceLink refreshShareLink(ResourceLink link) {
        QuarkTransferTask task = findTransferTask(link);
        if (task == null || !"SUBMITTED".equalsIgnoreCase(task.getStatus()) || !hasText(task.getSavedPath())) {
            return null;
        }
        ResourceDiscoveryResult discovery = findDiscovery(link);
        LocalDateTime now = LocalDateTime.now();
        task.setShareUrl(null);
        task.setShareUrlHash(null);
        task.setUpdatedAt(now);
        quarkTransferTaskService.updateById(task);
        if (discovery != null) {
            discovery.setShareUrl(null);
            discovery.setShareUrlHash(null);
            discovery.setUpdatedAt(now);
            discoveryResultService.updateById(discovery);
        }
        String shareUrl = quarkShareService.ensureShareUrl(task);
        if (!hasText(shareUrl)) {
            return null;
        }
        return resourceLinkService.getById(link.getId());
    }

    private ResourceDiscoveryResult findDiscovery(ResourceLink link) {
        if (link == null || link.getId() == null) {
            return null;
        }
        return discoveryResultService.getOne(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("resource_link_id", link.getId())
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
    }

    private QuarkTransferTask findTransferTask(ResourceLink link) {
        ResourceDiscoveryResult discovery = findDiscovery(link);
        if (discovery != null && discovery.getId() != null) {
            QuarkTransferTask byDiscovery = quarkTransferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                    .eq("discovery_result_id", discovery.getId())
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"), false);
            if (byDiscovery != null) {
                return byDiscovery;
            }
        }
        if (!hasText(link.getUrlHash())) {
            return null;
        }
        return quarkTransferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                .eq("movie_id", link.getMovieId())
                .eq("share_url_hash", link.getUrlHash())
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
    }

    private XunleiTransferTask findXunleiTransferTask(ResourceLink link) {
        ResourceDiscoveryResult discovery = findDiscovery(link);
        if (discovery != null && discovery.getId() != null) {
            XunleiTransferTask byDiscovery = xunleiTransferTaskService.getOne(
                    new QueryWrapper<XunleiTransferTask>()
                    .eq("discovery_result_id", discovery.getId())
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"), false);
            if (byDiscovery != null) {
                return byDiscovery;
            }
        }
        if (!hasText(link.getUrlHash())) {
            return null;
        }
        return xunleiTransferTaskService.getOne(new QueryWrapper<XunleiTransferTask>()
                .eq("movie_id", link.getMovieId())
                .eq("share_url_hash", link.getUrlHash())
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
    }

    private boolean isNormalLink(ResourceLink link) {
        return !hasText(link.getLinkStatus()) || "NORMAL".equalsIgnoreCase(link.getLinkStatus());
    }

    private boolean isPermanentInvalid(ResourceLink link) {
        return "INVALID".equalsIgnoreCase(link.getLinkStatus())
                && PERMANENT_LINK_INVALID_REASON.equals(link.getLastCheckError());
    }

    private boolean canRefreshShare(ResourceLink link) {
        return "QUARK".equalsIgnoreCase(link.getProvider())
                && "RESOURCE_HUB".equalsIgnoreCase(link.getSource())
                && resourceHubProperties.getQuark().isShareEnabled();
    }

    private boolean isTrustedPublishedShare(ResourceLink link) {
        return link != null && ("GYING_PUBLISHED".equalsIgnoreCase(link.getSource())
                || "RESOURCE_HUB".equalsIgnoreCase(link.getSource()))
                && OWNED_SHARE_PROVIDERS.contains(firstText(link.getProvider(), "").toUpperCase(Locale.ROOT));
    }

    private void markLinkNormal(ResourceLink link) {
        link.setLinkStatus("NORMAL");
        link.setValidatedAt(LocalDateTime.now());
        link.setLastCheckError(null);
        resourceLinkService.updateById(link);
    }

    private void markLinkInvalid(ResourceLink link, String reason) {
        link.setStatus("INACTIVE");
        link.setLinkStatus("INVALID");
        link.setValidatedAt(LocalDateTime.now());
        link.setLastCheckError(trim(reason, 1000));
        resourceLinkService.updateById(link);
    }

    private void markLinkSuspected(ResourceLink link, String reason) {
        link.setLinkStatus("SUSPECTED_INVALID");
        link.setValidatedAt(LocalDateTime.now());
        link.setLastCheckError(trim(reason, 1000));
        resourceLinkService.updateById(link);
    }

    private void recordLinkValidationUnavailable(ResourceLink link, String reason) {
        link.setLastCheckError(trim(reason, 1000));
        link.setUpdatedAt(LocalDateTime.now());
        resourceLinkService.updateById(link);
    }

    private String firstBlockedKeyword(String keyword) {
        if (!hasText(qqBotProperties.getBlockedKeywords())) {
            return null;
        }
        String normalized = keyword.trim().toLowerCase();
        for (String item : qqBotProperties.getBlockedKeywords().split("[,，|;；\\n\\r]+")) {
            String blocked = item.trim();
            if (hasText(blocked) && normalized.contains(blocked.toLowerCase())) {
                return blocked;
            }
        }
        return null;
    }

    private String buildNoResourceReply(MovieMetadata movie, List<String> searchNotes) {
        StringBuilder reply = new StringBuilder();
        reply.append("片名：").append(title(movie));
        if (movie.getYear() != null) {
            reply.append(" (").append(movie.getYear()).append(")");
        }
        appendLine(reply, "类型", join(movie.getGenres()));
        appendLine(reply, "地区", join(movie.getRegions()));
        appendLine(reply, "评分", rating(movie));
        appendLine(reply, "简介", trim(movie.getSummary(), 180));
        reply.append("\n\n多次搜索后仍未找到可供选择的夸克或迅雷资源。");
        if (searchNotes != null && !searchNotes.isEmpty()) {
            reply.append("\n处理提示：").append(String.join("；", searchNotes));
        }
        appendResourcePreferenceHint(reply);
        return reply.toString();
    }

    private String buildResourceSelectionReply(MovieMetadata movie, List<ResourceChoice> links, List<String> transferNotes) {
        StringBuilder reply = new StringBuilder("影片：").append(title(movie));
        reply.append("\n\n请选择资源（回复序号后再返回对应资源）：");
        int index = 1;
        for (ResourceChoice choice : links) {
            reply.append("\n").append(index++).append(". ")
                    .append(displayResourceChoiceName(choice, title(movie)))
                    .append(" [").append(resourceProviderLabel(choice.provider())).append("]");
        }
        if (transferNotes != null && !transferNotes.isEmpty()) {
            reply.append("\n\n处理提示：").append(String.join("；", transferNotes));
        }
        return reply.toString();
    }

    private String displayResourceChoiceName(ResourceChoice choice, String movieTitle) {
        String name = firstText(choice == null ? null : choice.name(), movieTitle, "资源");
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("magnet:")) {
            name = firstText(movieTitle, "资源");
        }
        return trim(name, 100);
    }

    private String resourceProviderLabel(String provider) {
        return firstText(
                provider == null ? null : QqResourcePreferenceParser.label(provider.toUpperCase(Locale.ROOT)),
                "网盘");
    }

    private String buildSelectedResourceReply(String movieTitle, ResourceLink link) {
        StringBuilder reply = new StringBuilder("已选择资源：").append(firstText(link.getName(), movieTitle));
        reply.append("\n网盘：").append(firstText(link.getProvider(), "未知"));
        if (hasText(link.getUrl())) {
            reply.append("\n").append(link.getUrl());
        }
        if (hasText(link.getCode())) {
            reply.append("\n提取码：").append(link.getCode());
        }
        return reply.toString();
    }

    private void appendResourcePreferenceHint(StringBuilder reply) {
        reply.append("\n\n需要筛选网盘，可在 5 分钟内回复“夸克”或“迅雷”；不再支持按数量批量转存，请从资源候选中回复序号。");
    }

    private void addSavedPathNote(QuarkTransferTask task, List<String> transferNotes) {
        if (task != null && hasText(task.getSavedPath())) {
            transferNotes.add("已转存到 " + task.getSavedPath());
        }
    }

    private void addSavedPathNote(XunleiTransferTask task, List<String> transferNotes) {
        if (task != null && hasText(task.getSavedPath())) {
            transferNotes.add("已转存到 " + task.getSavedPath());
        }
    }

    private List<String> buildSearchKeywords(MovieMetadata movie, String fallback) {
        String title = firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), fallback);
        Set<String> keywords = new LinkedHashSet<>();
        boolean seasonQualified = SeasonSearchUtils.parse(fallback) != null;
        if (seasonQualified) {
            keywords.addAll(SeasonSearchUtils.searchVariants(title, fallback));
        }
        if (movie.getYear() != null) {
            keywords.add(title + " " + movie.getYear());
        }
        if (!seasonQualified) {
            keywords.addAll(SeasonSearchUtils.searchVariants(title, fallback));
        }
        keywords.add(title);
        return List.copyOf(keywords);
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

    private boolean isBotMentioned(JsonNode event, Long selfId) {
        if (event == null || selfId == null) {
            return false;
        }
        if (event.path("to_me").asBoolean(false)) {
            return true;
        }
        JsonNode message = event.path("message");
        if (message.isArray()) {
            for (JsonNode segment : message) {
                if ("at".equalsIgnoreCase(segment.path("type").asText())
                        && String.valueOf(selfId).equals(segment.path("data").path("qq").asText())) {
                    return true;
                }
            }
        }
        String marker = "[CQ:at,qq=" + selfId + "]";
        return message.isTextual() && message.asText("").contains(marker)
                || event.path("raw_message").asText("").contains(marker);
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
            return List.of("\u641c\u7d22", "\u641c", "\u627e", "/movie", "/search");
        }
        for (String item : raw.split(",")) {
            String value = item.trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        result.sort(Comparator.comparingInt(String::length).reversed());
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

    private int safeMaxResults(int requested) {
        return Math.min(Math.max(requested, 1), MAX_SELECTABLE_RESOURCES);
    }

    private String safeError(String message) {
        return firstText(trim(message, 120), "未知错误");
    }

    private String userFacingTransferError(String message) {
        String detail = safeError(message);
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("no video")
                || lower.contains("no transferred media")
                || lower.contains("no new matching")
                || lower.contains("没有视频")
                || lower.contains("无视频")) {
            return "转存失败：分享内没有视频文件";
        }
        if (lower.contains("违规")
                || lower.contains("forbidden")
                || lower.contains("violation")
                || lower.contains("policy")
                || lower.contains("blocked")) {
            return "转存失败：资源可能违规或已被平台拦截";
        }
        if (lower.contains("not found")
                || lower.contains("不存在")
                || lower.contains("invalid")
                || lower.contains("失效")
                || lower.contains("分享为空")) {
            return "转存失败：资源无效或已无资源";
        }
        return "转存失败：" + detail;
    }

    private void trySend(Long groupId, Long userId, String message) {
        try {
            if ("qqbot".equalsIgnoreCase(qqBotProperties.getReplyProvider())) {
                String mention = userId == null ? "" : "<@" + userId + "> ";
                qqOfficialBotClient.sendGroupMessage(groupId, mention + trim(message, 1800));
            } else {
                napCatClient.sendGroupMessage(groupId, userId, trim(message, 1800));
            }
        } catch (Exception e) {
            log.warn("Failed to send QQ group message to {}", groupId, e);
        }
    }

    private String defaultHelpReply() {
        return firstText(
                qqBotProperties.getDefaultReply(),
                "机器人使用方法：@机器人 搜/找 影片名\n"
                        + "影片和资源候选保留 5 分钟；先选影片，再按资源名称回复序号，确认后才转存。可回复“夸克”或“迅雷”筛选，百度网盘暂不支持。");
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

    private record SuggestedCandidates(Instant expiresAt, List<MovieSearchCandidate> candidates) {
    }

    private record ResourceSearchContext(Instant expiresAt, String movieId, String keyword) {
    }

    private record ResourceCandidates(
            Instant expiresAt,
            String movieId,
            String movieTitle,
            List<ResourceChoice> allResources,
            List<ResourceChoice> resources) {
    }

    private record ResourceChoice(String name, String provider, String identity, ResourceLink resourceLink, Long discoveryId) {
        static ResourceChoice owned(ResourceLink link) {
            return new ResourceChoice(link.getName(), link.getProvider(), link.getUrl(), link, null);
        }

        static ResourceChoice discovery(ResourceDiscoveryResult discovery) {
            return new ResourceChoice(
                    discoveryDisplayName(discovery),
                    discovery.getProvider(),
                    firstTextStatic(discovery.getShareUrl(), discovery.getOriginalUrl(), "discovery:" + discovery.getId()),
                    null,
                    discovery.getId());
        }

        private static String discoveryDisplayName(ResourceDiscoveryResult discovery) {
            String base = firstTextStatic(discovery.getTitle(), "资源");
            String detail = firstTextStatic(
                    discovery.getQuality(),
                    discovery.getVersionNote(),
                    discovery.getFileSize());
            if (detail != null && !base.contains(detail)) {
                return base + " · " + detail;
            }
            return base;
        }

        private static String firstTextStatic(String... values) {
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        return value.trim();
                    }
                }
            }
            return null;
        }
    }
}
