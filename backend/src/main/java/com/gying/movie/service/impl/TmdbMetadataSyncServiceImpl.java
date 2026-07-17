package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.MovieSearchCandidate;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceHubMetadataSyncRequest;
import com.gying.movie.dto.TmdbListItem;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import com.gying.movie.utils.SeasonSearchUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TmdbMetadataSyncServiceImpl implements ITmdbMetadataSyncService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_MAX_ITEMS = 20;
    private static final int MAX_ITEMS_LIMIT = 20;
    private static final int MIN_KEYWORD_MATCH_SCORE = 80;

    private final TmdbClient tmdbClient;
    private final PosterStorageService posterStorageService;
    private final ResourceHubProperties resourceHubProperties;
    private final IMovieMetadataService movieService;
    private final IResourceHubTaskService taskService;
    private final IResourceDiscoveryService resourceDiscoveryService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IResourceLinkService resourceLinkService;
    private final ObjectMapper objectMapper;

    public TmdbMetadataSyncServiceImpl(
            TmdbClient tmdbClient,
            PosterStorageService posterStorageService,
            ResourceHubProperties resourceHubProperties,
            IMovieMetadataService movieService,
            IResourceHubTaskService taskService,
            IResourceDiscoveryService resourceDiscoveryService,
            IResourceDiscoveryResultService discoveryResultService,
            IResourceLinkService resourceLinkService,
            ObjectMapper objectMapper) {
        this.tmdbClient = tmdbClient;
        this.posterStorageService = posterStorageService;
        this.resourceHubProperties = resourceHubProperties;
        this.movieService = movieService;
        this.taskService = taskService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.discoveryResultService = discoveryResultService;
        this.resourceLinkService = resourceLinkService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResourceHubTask enqueue(ResourceHubMetadataSyncRequest request) {
        ensureEnabled();
        SyncPayload payload = normalizePayload(request);

        ResourceHubTask task = new ResourceHubTask();
        task.setTaskType("METADATA_SYNC");
        task.setSource("TMDB");
        task.setKeyword(payload.source());
        task.setPayload(writePayload(payload));
        task.setPriority(10);
        return taskService.enqueue(task);
    }

    @Override
    public TmdbSyncResult runTask(Long taskId) {
        ensureEnabled();
        ResourceHubTask task = taskService.getById(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource Hub task not found");
        }
        if (!"METADATA_SYNC".equalsIgnoreCase(task.getTaskType()) || !"TMDB".equalsIgnoreCase(task.getSource())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task is not a TMDB metadata sync task");
        }

        SyncPayload payload = readPayload(task);
        TmdbSyncResult result = new TmdbSyncResult();
        result.setTaskId(task.getId());
        result.setSource(payload.source());
        result.setPage(payload.page());
        result.setRequested(payload.maxItems());

        markRunning(task);
        try {
            List<TmdbListItem> items = tmdbClient.fetchList(payload.source(), payload.page());
            int limit = Math.min(payload.maxItems(), items.size());
            for (int i = 0; i < limit; i++) {
                TmdbListItem item = items.get(i);
                try {
                    JsonNode details = tmdbClient.fetchDetails(item.getMediaType(), item.getTmdbId());
                    MovieUpsertResult movieResult = upsertMovie(item, details);
                    result.setProcessed(result.getProcessed() + 1);
                    if (movieResult.inserted()) {
                        result.setInserted(result.getInserted() + 1);
                    } else {
                        result.setUpdated(result.getUpdated() + 1);
                    }
                    enqueueDiscoveryTask(movieResult.movie(), result);
                } catch (Exception itemError) {
                    result.setFailed(result.getFailed() + 1);
                    addError(result, item.getMediaType() + "/" + item.getTmdbId() + ": " + itemError.getMessage());
                }
            }
            result.setSkipped(Math.max(items.size() - limit, 0));
            String status = result.getProcessed() == 0 && result.getFailed() > 0 ? "FAILED" : "SUCCEEDED";
            String errorSummary = result.getFailed() > 0
                    ? result.getFailed() + " TMDB item(s) failed during sync"
                    : null;
            finishTask(task, status, errorSummary);
            result.setStatus(task.getStatus());
            return result;
        } catch (Exception e) {
            finishTask(task, "FAILED", e.getMessage());
            result.setStatus("FAILED");
            addError(result, e.getMessage());
            return result;
        }
    }

    @Override
    public MovieMetadata syncBestByKeyword(String keyword) {
        ensureEnabled();
        if (!hasText(keyword)) {
            return null;
        }
        List<TmdbListItem> items = tmdbClient.searchMulti(keyword, 5);
        MovieCandidate best = null;
        for (TmdbListItem item : items) {
            try {
                JsonNode details = tmdbClient.fetchDetails(item.getMediaType(), item.getTmdbId());
                int score = scoreKeywordMatch(keyword, item, details);
                if (score >= MIN_KEYWORD_MATCH_SCORE && (best == null || score > best.score())) {
                    best = new MovieCandidate(item, details, score);
                }
            } catch (Exception ignored) {
                // Try the next TMDB search result.
            }
        }
        return best == null ? null : upsertMovie(best.item(), best.details()).movie();
    }

    @Override
    public MovieMetadata syncExactByKeyword(String keyword) {
        ensureEnabled();
        if (!hasText(keyword)) {
            return null;
        }
        List<TmdbListItem> items = tmdbClient.searchMulti(keyword, 10);
        MovieCandidate best = null;
        for (TmdbListItem item : items) {
            try {
                JsonNode details = tmdbClient.fetchDetails(item.getMediaType(), item.getTmdbId());
                if (!isExactKeywordMatch(keyword, item, details)) {
                    continue;
                }
                int score = scoreKeywordMatch(keyword, item, details);
                if (best == null || score > best.score()) {
                    best = new MovieCandidate(item, details, score);
                }
            } catch (Exception ignored) {
                // Try the next exact TMDB candidate.
            }
        }
        return best == null ? null : upsertMovie(best.item(), best.details()).movie();
    }

    @Override
    public List<MovieSearchCandidate> searchCandidatesByKeyword(String keyword, int limit) {
        ensureEnabled();
        if (!hasText(keyword)) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        String normalizedKeyword = normalizeTitle(keyword);
        return tmdbClient.searchMulti(keyword, 20).stream()
                .map(item -> new MovieSearchCandidate(
                        item.getTmdbId(),
                        item.getMediaType(),
                        item.getTitle(),
                        item.getOriginalTitle(),
                        parseYear(item.getReleaseDate()),
                        scoreListCandidate(normalizedKeyword, item)))
                .filter(candidate -> candidate.getScore() >= 60)
                .sorted((left, right) -> Integer.compare(right.getScore(), left.getScore()))
                .limit(safeLimit)
                .toList();
    }

    private MovieUpsertResult upsertMovie(TmdbListItem item, JsonNode details) {
        MovieMetadata existing = findExistingMovie(item, details);
        MovieMetadata target = existing == null ? new MovieMetadata() : existing;
        boolean inserted = existing == null;
        LocalDateTime now = LocalDateTime.now();

        if (inserted) {
            target.setId(buildMovieId(item));
            target.setStatus("ACTIVE");
            target.setResourceStatus("UNKNOWN");
            target.setCategory(categoryFor(item.getMediaType()));
            target.setCreatedAt(now);
        }
        if (!hasText(target.getResourceStatus())) {
            target.setResourceStatus("UNKNOWN");
        }

        target.setTmdbId(item.getTmdbId());
        target.setTmdbType(item.getMediaType());
        target.setTitleCn(firstText(target.getTitleCn(), title(details, item.getMediaType()), 255));
        target.setTitleEn(firstText(target.getTitleEn(), originalTitle(details, item.getMediaType()), 500));
        if ("tv".equals(item.getMediaType())) {
            target.setSeriesName(firstText(target.getSeriesName(), title(details, item.getMediaType()), 255));
            target.setSeason(firstValue(target.getSeason(), 1));
        }
        target.setYear(firstValue(target.getYear(), parseYear(releaseDate(details, item.getMediaType()))));
        target.setRuntime(firstText(target.getRuntime(), runtime(details, item.getMediaType()), 100));
        target.setDirectors(firstList(target.getDirectors(), directors(details, item.getMediaType())));
        target.setActors(firstList(target.getActors(), names(details.path("credits").path("cast"), "name", 12)));
        target.setGenres(firstList(target.getGenres(), names(details.path("genres"), "name", 12)));
        target.setRegions(firstList(target.getRegions(), regions(details)));
        target.setLanguages(firstList(target.getLanguages(), languages(details)));
        target.setReleaseDates(firstText(target.getReleaseDates(), releaseDate(details, item.getMediaType()), 500));
        target.setAliases(firstText(target.getAliases(), aliases(details, item.getMediaType()), 2000));
        String posterObjectName = posterStorageService.storeTmdbPoster(
                item.getMediaType(),
                item.getTmdbId(),
                details.path("poster_path").asText(null));
        target.setPosterUrl(preferLocalPoster(target.getPosterUrl(), posterObjectName));
        target.setTmdbPopularity(decimal(details.path("popularity")));
        target.setTmdbVoteAverage(decimal(details.path("vote_average")));
        target.setSummary(firstText(target.getSummary(), details.path("overview").asText(null), 4000));
        if (target.getPopularity() == null) {
            target.setPopularity(0);
        }
        target.setTmdbLastSyncAt(now);
        target.setUpdatedAt(now);

        if (inserted) {
            movieService.save(target);
        } else {
            movieService.updateById(target);
        }
        return new MovieUpsertResult(target, inserted);
    }

    private void enqueueDiscoveryTask(MovieMetadata movie, TmdbSyncResult result) {
        if (!resourceHubProperties.getTmdb().isAutoDiscoveryEnabled() || movie == null || !hasText(movie.getId())) {
            return;
        }
        if (hasPublishableResource(movie.getId())
                || hasSavedDiscovery(movie.getId())
                || hasRecentDiscoveryTask(movie.getId())) {
            result.setDiscoveryTasksSkipped(result.getDiscoveryTasksSkipped() + 1);
            return;
        }

        try {
            ResourceDiscoveryRequest request = new ResourceDiscoveryRequest();
            request.setMovieId(movie.getId());
            request.setKeyword(buildDiscoveryKeyword(movie));
            request.setSource("PANSOU");
            request.setMaxResults(clamp(resourceHubProperties.getTmdb().getDiscoveryMaxResults(), 10, 1, 50));
            resourceDiscoveryService.enqueue(request);
            result.setDiscoveryTasksCreated(result.getDiscoveryTasksCreated() + 1);
        } catch (Exception e) {
            result.setDiscoveryTasksSkipped(result.getDiscoveryTasksSkipped() + 1);
            addError(result, "discovery task " + movie.getId() + ": " + e.getMessage());
        }
    }

    private boolean hasPublishableResource(String movieId) {
        return resourceLinkService.count(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("status", "ACTIVE")
                .ne("link_status", "INVALID")) > 0;
    }

    private boolean hasSavedDiscovery(String movieId) {
        return discoveryResultService.count(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("movie_id", movieId)
                .in("status", List.of("DISCOVERED", "SAVED", "DUPLICATE"))) > 0;
    }

    private boolean hasRecentDiscoveryTask(String movieId) {
        int cooldownHours = Math.max(resourceHubProperties.getTmdb().getDiscoveryCooldownHours(), 1);
        return taskService.count(new QueryWrapper<ResourceHubTask>()
                .eq("task_type", "RESOURCE_DISCOVERY")
                .eq("movie_id", movieId)
                .ge("created_at", LocalDateTime.now().minusHours(cooldownHours))) > 0;
    }

    private String buildDiscoveryKeyword(MovieMetadata movie) {
        String title = firstText(null, movie.getTitleCn(), 255);
        title = firstText(title, movie.getTitleEn(), 255);
        title = firstText(title, movie.getSeriesName(), 255);
        title = firstText(title, movie.getId(), 255);
        if (movie.getSeason() != null && movie.getSeason() > 0) {
            return SeasonSearchUtils.seasonQualifiedTitle(title, movie.getSeason());
        }
        return movie.getYear() == null ? title : title + " " + movie.getYear();
    }

    private MovieMetadata findExistingMovie(TmdbListItem item, JsonNode details) {
        MovieMetadata byTmdb = movieService.getOne(new QueryWrapper<MovieMetadata>()
                .eq("tmdb_type", item.getMediaType())
                .eq("tmdb_id", item.getTmdbId())
                .last("LIMIT 1"), false);
        if (byTmdb != null) {
            return byTmdb;
        }

        Integer year = parseYear(releaseDate(details, item.getMediaType()));
        List<String> rawTitles = new ArrayList<>();
        rawTitles.add(title(details, item.getMediaType()));
        rawTitles.add(originalTitle(details, item.getMediaType()));
        List<String> titles = distinct(rawTitles, 2);
        if (titles.isEmpty()) {
            return null;
        }

        if ("tv".equals(item.getMediaType())) {
            MovieMetadata bySeries = findExistingSeries(titles, year, item.getMediaType());
            if (bySeries != null) {
                return bySeries;
            }
        }

        if (year == null) {
            return null;
        }

        QueryWrapper<MovieMetadata> query = new QueryWrapper<MovieMetadata>()
                .eq("category", categoryFor(item.getMediaType()))
                .eq("year", year)
                .and(w -> {
                    for (int i = 0; i < titles.size(); i++) {
                        if (i > 0) {
                            w.or();
                        }
                        String candidate = titles.get(i);
                        w.eq("title_cn", candidate).or().eq("title_en", candidate);
                    }
                })
                .last("LIMIT 1");
        return movieService.getOne(query, false);
    }

    private MovieMetadata findExistingSeries(List<String> titles, Integer year, String mediaType) {
        QueryWrapper<MovieMetadata> query = new QueryWrapper<MovieMetadata>()
                .eq("category", categoryFor(mediaType))
                .and(w -> {
                    for (int i = 0; i < titles.size(); i++) {
                        if (i > 0) {
                            w.or();
                        }
                        String candidate = titles.get(i);
                        w.eq("series_name", candidate)
                                .or().eq("title_cn", candidate)
                                .or().eq("title_en", candidate)
                                .or().like("aliases", candidate);
                    }
                })
                .orderByAsc("season")
                .orderByDesc("popularity")
                .last("LIMIT 1");
        return movieService.getOne(query, false);
    }

    private void markRunning(ResourceHubTask task) {
        LocalDateTime now = LocalDateTime.now();
        task.setStatus("RUNNING");
        task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
        task.setStartedAt(now);
        task.setFinishedAt(null);
        task.setLastError(null);
        task.setUpdatedAt(now);
        taskService.updateById(task);
    }

    private void finishTask(ResourceHubTask task, String status, String error) {
        task.setStatus(status);
        task.setLastError(trim(error, 1000));
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskService.updateById(task);
    }

    private SyncPayload normalizePayload(ResourceHubMetadataSyncRequest request) {
        ResourceHubMetadataSyncRequest safeRequest = request == null ? new ResourceHubMetadataSyncRequest() : request;
        String source = tmdbClient.normalizeSource(safeRequest.getSource());
        int page = clamp(safeRequest.getPage(), DEFAULT_PAGE, 1, 500);
        int maxItems = clamp(safeRequest.getMaxItems(), DEFAULT_MAX_ITEMS, 1, MAX_ITEMS_LIMIT);
        return new SyncPayload(source, page, maxItems);
    }

    private SyncPayload readPayload(ResourceHubTask task) {
        if (!hasText(task.getPayload())) {
            return new SyncPayload(tmdbClient.normalizeSource(task.getKeyword()), DEFAULT_PAGE, DEFAULT_MAX_ITEMS);
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(task.getPayload(),
                    new TypeReference<Map<String, Object>>() {
                    });
            String source = tmdbClient.normalizeSource((String) payload.get("source"));
            int page = number(payload.get("page"), DEFAULT_PAGE);
            int maxItems = number(payload.get("maxItems"), DEFAULT_MAX_ITEMS);
            return new SyncPayload(source, clamp(page, DEFAULT_PAGE, 1, 500),
                    clamp(maxItems, DEFAULT_MAX_ITEMS, 1, MAX_ITEMS_LIMIT));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid metadata sync task payload");
        }
    }

    private String writePayload(SyncPayload payload) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source", payload.source());
            value.put("page", payload.page());
            value.put("maxItems", payload.maxItems());
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize task payload", e);
        }
    }

    private void ensureEnabled() {
        if (!resourceHubProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Hub is disabled");
        }
    }

    private String buildMovieId(TmdbListItem item) {
        return "tmdb_" + item.getMediaType() + "_" + item.getTmdbId();
    }

    private String categoryFor(String mediaType) {
        return "tv".equals(mediaType) ? "tv" : "mv";
    }

    private String title(JsonNode details, String mediaType) {
        return "tv".equals(mediaType) ? details.path("name").asText(null) : details.path("title").asText(null);
    }

    private String originalTitle(JsonNode details, String mediaType) {
        return "tv".equals(mediaType)
                ? details.path("original_name").asText(null)
                : details.path("original_title").asText(null);
    }

    private String releaseDate(JsonNode details, String mediaType) {
        return "tv".equals(mediaType)
                ? details.path("first_air_date").asText(null)
                : details.path("release_date").asText(null);
    }

    private String runtime(JsonNode details, String mediaType) {
        if ("tv".equals(mediaType)) {
            JsonNode runtimes = details.path("episode_run_time");
            if (runtimes.isArray() && !runtimes.isEmpty()) {
                int minutes = runtimes.get(0).asInt(0);
                return minutes > 0 ? minutes + " min" : null;
            }
            return null;
        }
        int minutes = details.path("runtime").asInt(0);
        return minutes > 0 ? minutes + " min" : null;
    }

    private List<String> directors(JsonNode details, String mediaType) {
        List<String> result = new ArrayList<>();
        if ("tv".equals(mediaType)) {
            result.addAll(names(details.path("created_by"), "name", 8));
        }
        for (JsonNode crew : details.path("credits").path("crew")) {
            String job = crew.path("job").asText("");
            if ("Director".equalsIgnoreCase(job) || ("tv".equals(mediaType) && "Executive Producer".equalsIgnoreCase(job))) {
                String name = crew.path("name").asText(null);
                if (hasText(name)) {
                    result.add(name.trim());
                }
            }
            if (result.size() >= 8) {
                break;
            }
        }
        return distinct(result, 8);
    }

    private List<String> regions(JsonNode details) {
        List<String> regions = names(details.path("production_countries"), "name", 8);
        if (!regions.isEmpty()) {
            return regions;
        }
        return texts(details.path("origin_country"), 8);
    }

    private List<String> languages(JsonNode details) {
        List<String> result = names(details.path("spoken_languages"), "english_name", 8);
        if (!result.isEmpty()) {
            return result;
        }
        return names(details.path("spoken_languages"), "name", 8);
    }

    private String aliases(JsonNode details, String mediaType) {
        JsonNode node = details.path("alternative_titles");
        JsonNode titles = "tv".equals(mediaType) ? node.path("results") : node.path("titles");
        return String.join(" / ", names(titles, "title", 20));
    }

    private int scoreKeywordMatch(String keyword, TmdbListItem item, JsonNode details) {
        String normalizedKeyword = normalizeTitle(keyword);
        if (!hasText(normalizedKeyword)) {
            return 0;
        }
        String sequelNumber = trailingNumber(normalizedKeyword);
        List<String> titles = new ArrayList<>();
        titles.add(title(details, item.getMediaType()));
        titles.add(originalTitle(details, item.getMediaType()));
        titles.addAll(names(alternativeTitleNodes(details, item.getMediaType()), "title", 20));

        int best = 0;
        boolean hasSequelNumber = !hasText(sequelNumber);
        for (String rawTitle : titles) {
            String normalizedTitle = normalizeTitle(rawTitle);
            if (!hasText(normalizedTitle)) {
                continue;
            }
            if (hasText(sequelNumber) && normalizedTitle.contains(sequelNumber)) {
                hasSequelNumber = true;
            }
            if (normalizedTitle.equals(normalizedKeyword)) {
                best = Math.max(best, 160);
            } else if (normalizedTitle.contains(normalizedKeyword)) {
                best = Math.max(best, 130);
            } else if (normalizedKeyword.contains(normalizedTitle) && normalizedTitle.length() >= 4) {
                best = Math.max(best, 75);
            } else {
                best = Math.max(best, overlapScore(normalizedKeyword, normalizedTitle));
            }
            if (normalizedTitle.contains("乐高") && !normalizedKeyword.contains("乐高")) {
                best -= 60;
            }
        }
        if (hasText(sequelNumber) && !hasSequelNumber && !containsExactKeyword(titles, normalizedKeyword)) {
            best -= 80;
        }
        Integer year = parseYear(releaseDate(details, item.getMediaType()));
        if (year != null && normalizedKeyword.contains(String.valueOf(year))) {
            best += 20;
        }
        return Math.max(best, 0);
    }

    private boolean isExactKeywordMatch(String keyword, TmdbListItem item, JsonNode details) {
        String normalizedKeyword = normalizeTitle(keyword);
        if (!hasText(normalizedKeyword)) {
            return false;
        }
        List<String> titles = new ArrayList<>();
        titles.add(title(details, item.getMediaType()));
        titles.add(originalTitle(details, item.getMediaType()));
        titles.addAll(names(alternativeTitleNodes(details, item.getMediaType()), "title", 20));
        return titles.stream().anyMatch(value -> normalizedKeyword.equals(normalizeTitle(value)));
    }

    private int scoreListCandidate(String normalizedKeyword, TmdbListItem item) {
        int best = 0;
        String[] values = {item.getTitle(), item.getOriginalTitle()};
        for (String value : values) {
            String normalizedTitle = normalizeTitle(value);
            if (!hasText(normalizedTitle)) {
                continue;
            }
            if (normalizedTitle.equals(normalizedKeyword)) {
                best = Math.max(best, 160);
            } else if (normalizedTitle.contains(normalizedKeyword)) {
                best = Math.max(best, 130);
            } else if (normalizedKeyword.contains(normalizedTitle) && normalizedTitle.length() >= 4) {
                best = Math.max(best, 75);
            } else {
                best = Math.max(best, overlapScore(normalizedKeyword, normalizedTitle));
            }
        }
        int popularityBoost = item.getPopularity() == null
                ? 0
                : Math.min((int) Math.round(item.getPopularity() / 20.0), 15);
        return best + popularityBoost;
    }

    private JsonNode alternativeTitleNodes(JsonNode details, String mediaType) {
        JsonNode node = details.path("alternative_titles");
        return "tv".equals(mediaType) ? node.path("results") : node.path("titles");
    }

    private boolean containsExactKeyword(List<String> titles, String normalizedKeyword) {
        for (String title : titles) {
            if (normalizeTitle(title).contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    private int overlapScore(String keyword, String title) {
        int longest = 0;
        for (int start = 0; start < keyword.length(); start++) {
            for (int end = start + 2; end <= keyword.length(); end++) {
                String part = keyword.substring(start, end);
                if (title.contains(part)) {
                    longest = Math.max(longest, part.length());
                }
            }
        }
        return longest >= 4 ? Math.min(longest * 12, 72) : 0;
    }

    private String normalizeTitle(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[\\s\\p{Punct}，。！？、：；（）《》【】「」『』·]+", "");
    }

    private String trailingNumber(String normalizedValue) {
        if (!hasText(normalizedValue)) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9]+)$").matcher(normalizedValue);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> names(JsonNode array, String field, int limit) {
        List<String> values = new ArrayList<>();
        if (!array.isArray()) {
            return values;
        }
        for (JsonNode node : array) {
            String value = node.path(field).asText(null);
            if (hasText(value)) {
                values.add(value.trim());
            }
            if (values.size() >= limit) {
                break;
            }
        }
        return distinct(values, limit);
    }

    private List<String> texts(JsonNode array, int limit) {
        List<String> values = new ArrayList<>();
        if (!array.isArray()) {
            return values;
        }
        for (JsonNode node : array) {
            String value = node.asText(null);
            if (hasText(value)) {
                values.add(value.trim());
            }
            if (values.size() >= limit) {
                break;
            }
        }
        return distinct(values, limit);
    }

    private List<String> distinct(List<String> values, int limit) {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (hasText(value)) {
                unique.add(value.trim());
            }
            if (unique.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return BigDecimal.valueOf(node.asDouble());
    }

    private Integer parseYear(String date) {
        if (!hasText(date) || date.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstText(String current, String incoming, int maxLength) {
        return hasText(current) ? current : trim(incoming, maxLength);
    }

    private String preferLocalPoster(String current, String incoming) {
        if (!hasText(incoming)) {
            return current;
        }
        if (!hasText(current) || isRemoteUrl(current)) {
            return trim(incoming, 500);
        }
        return current;
    }

    private boolean isRemoteUrl(String value) {
        String lower = value.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private <T> T firstValue(T current, T incoming) {
        return current != null ? current : incoming;
    }

    private List<String> firstList(List<String> current, List<String> incoming) {
        return current != null && !current.isEmpty() ? current : incoming;
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        int effective = value == null ? defaultValue : value;
        return Math.min(Math.max(effective, min), max);
    }

    private int clamp(int value, int defaultValue, int min, int max) {
        return clamp(Integer.valueOf(value), defaultValue, min, max);
    }

    private int number(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private void addError(TmdbSyncResult result, String message) {
        if (result.getErrors().size() < 10 && hasText(message)) {
            result.getErrors().add(trim(message, 500));
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

    private record MovieUpsertResult(MovieMetadata movie, boolean inserted) {
    }

    private record MovieCandidate(TmdbListItem item, JsonNode details, int score) {
    }

    private record SyncPayload(String source, int page, int maxItems) {
    }
}
