package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.PanSouClient;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.DiscoveredResource;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.ResourceHubHashUtils;
import com.gying.movie.utils.SeasonSearchUtils;
import com.gying.movie.utils.ResourceTitleMatcher;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceDiscoveryServiceImpl implements IResourceDiscoveryService {

    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int MAX_RESULTS_LIMIT = 50;

    private final ResourceHubProperties resourceHubProperties;
    private final PanSouClient panSouClient;
    private final IMovieMetadataService movieService;
    private final IResourceHubTaskService taskService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final IResourceLinkService resourceLinkService;
    private final ObjectMapper objectMapper;

    public ResourceDiscoveryServiceImpl(
            ResourceHubProperties resourceHubProperties,
            PanSouClient panSouClient,
            IMovieMetadataService movieService,
            IResourceHubTaskService taskService,
            IResourceDiscoveryResultService discoveryResultService,
            IQuarkTransferTaskService quarkTransferTaskService,
            IResourceLinkService resourceLinkService,
            ObjectMapper objectMapper) {
        this.resourceHubProperties = resourceHubProperties;
        this.panSouClient = panSouClient;
        this.movieService = movieService;
        this.taskService = taskService;
        this.discoveryResultService = discoveryResultService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.resourceLinkService = resourceLinkService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResourceHubTask enqueue(ResourceDiscoveryRequest request) {
        ensureEnabled();
        DiscoveryPayload payload = normalizePayload(request);
        MovieMetadata movie = movieService.getById(payload.movieId());
        if (movie == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found");
        }

        ResourceHubTask task = new ResourceHubTask();
        task.setTaskType("RESOURCE_DISCOVERY");
        task.setMovieId(payload.movieId());
        task.setSource(payload.source());
        task.setKeyword(payload.keyword());
        task.setPriority(5);
        task.setPayload(writePayload(payload));
        return taskService.enqueue(task);
    }

    @Override
    public ResourceDiscoveryRunResult runTask(Long taskId) {
        ensureEnabled();
        ResourceHubTask task = taskService.getById(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource Hub task not found");
        }
        if (!"RESOURCE_DISCOVERY".equalsIgnoreCase(task.getTaskType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task is not a resource discovery task");
        }

        DiscoveryPayload payload = readPayload(task);
        MovieMetadata movie = movieService.getById(payload.movieId());
        if (movie == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found");
        }

        ResourceDiscoveryRunResult result = new ResourceDiscoveryRunResult();
        result.setTaskId(task.getId());
        result.setMovieId(movie.getId());
        result.setSource(payload.source());
        result.setKeyword(payload.keyword());

        markRunning(task);
        try {
            List<DiscoveredResource> resources = discover(payload, movie);
            LocalDateTime now = LocalDateTime.now();
            for (DiscoveredResource resource : resources) {
                try {
                    if (!ResourceTitleMatcher.isRelevant(movie, resource.getTitle(), payload.keyword())) {
                        ResourceDiscoveryResult ignored = saveDiscovery(
                                task, movie, resource, ResourceHubHashUtils.sha256(resource.getUrl()), "IGNORED", now);
                        ignored.setFailureReason("Resource title does not match movie title");
                        discoveryResultService.updateById(ignored);
                        continue;
                    }
                    String urlHash = ResourceHubHashUtils.sha256(resource.getUrl());
                    LinkCheckResult sourceCheck = checkSourceLink(resource.getUrl());
                    if (sourceCheck.checked() && !sourceCheck.valid()) {
                        ResourceDiscoveryResult ignored = saveDiscovery(
                                task, movie, resource, urlHash, "IGNORED", now);
                        ignored.setFailureReason("PanSou detected invalid source link: "
                                + trim(sourceCheck.message(), 900));
                        discoveryResultService.updateById(ignored);
                        continue;
                    }
                    if (isDuplicate(movie.getId(), urlHash, resource.getUrl())) {
                        saveDiscovery(task, movie, resource, urlHash, "DUPLICATE", now);
                        result.setDuplicate(result.getDuplicate() + 1);
                        continue;
                    }
                    ResourceDiscoveryResult discovery = saveDiscovery(task, movie, resource, urlHash, "DISCOVERED", now);
                    result.setDiscovered(result.getDiscovered() + 1);
                    if (createQuarkTransferTask(discovery, resource, urlHash, now)) {
                        result.setTransferTasksCreated(result.getTransferTasksCreated() + 1);
                    }
                } catch (Exception itemError) {
                    result.setFailed(result.getFailed() + 1);
                    addError(result, itemError.getMessage());
                }
            }
            if (result.getDiscovered() + result.getDuplicate() == 0 && result.getFailed() == 0) {
                markResourceStatus(movie, "TRAILER", now);
            }
            String status = result.getFailed() > 0 && result.getDiscovered() + result.getDuplicate() == 0
                    ? "FAILED"
                    : "SUCCEEDED";
            String error = "FAILED".equals(status)
                    ? "Resource discovery failed"
                    : result.getDiscovered() + result.getDuplicate() == 0 ? "No resources discovered; marked as trailer" : null;
            finishTask(task, status, error);
            result.setStatus(task.getStatus());
            return result;
        } catch (Exception e) {
            finishTask(task, "FAILED", e.getMessage());
            result.setStatus("FAILED");
            addError(result, e.getMessage());
            return result;
        }
    }

    private List<DiscoveredResource> discover(DiscoveryPayload payload, MovieMetadata movie) {
        if (!"PANSOU".equalsIgnoreCase(payload.source())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported discovery source");
        }
        return panSouClient.searchQuark(resolveKeyword(payload, movie), payload.maxResults());
    }

    private LinkCheckResult checkSourceLink(String url) {
        try {
            return panSouClient.checkLink(url);
        } catch (Exception ignored) {
            return new LinkCheckResult(url, false, false, "Source link validation unavailable");
        }
    }

    private ResourceDiscoveryResult saveDiscovery(
            ResourceHubTask task,
            MovieMetadata movie,
            DiscoveredResource resource,
            String urlHash,
            String status,
            LocalDateTime now) {
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setTaskId(task.getId());
        discovery.setMovieId(movie.getId());
        discovery.setSource("PANSOU");
        discovery.setSourceRef(trim(resource.getSourceRef(), 100));
        discovery.setTitle(trim(firstText(resource.getTitle(), movie.getTitleCn(), movie.getTitleEn()), 255));
        discovery.setProvider("QUARK");
        discovery.setResourceType("DISK");
        discovery.setOriginalUrl(resource.getUrl());
        discovery.setOriginalUrlHash(urlHash);
        discovery.setCode(trim(resource.getCode(), 50));
        discovery.setConfidence(BigDecimal.valueOf(80));
        discovery.setStatus(status);
        discovery.setCreatedAt(now);
        discovery.setUpdatedAt(now);
        discoveryResultService.save(discovery);
        return discovery;
    }

    private boolean createQuarkTransferTask(
            ResourceDiscoveryResult discovery,
            DiscoveredResource resource,
            String urlHash,
            LocalDateTime now) {
        if (hasMovieTransferTask(discovery.getMovieId())) {
            return false;
        }
        QuarkTransferTask transfer = new QuarkTransferTask();
        transfer.setDiscoveryResultId(discovery.getId());
        transfer.setMovieId(discovery.getMovieId());
        transfer.setOriginalUrl(resource.getUrl());
        transfer.setOriginalUrlHash(urlHash);
        transfer.setStatus("PENDING");
        transfer.setAttempts(0);
        transfer.setCreatedAt(now);
        transfer.setUpdatedAt(now);
        quarkTransferTaskService.save(transfer);
        return true;
    }

    private boolean hasMovieTransferTask(String movieId) {
        return quarkTransferTaskService.count(new QueryWrapper<QuarkTransferTask>()
                .eq("movie_id", movieId)
                .in("status", List.of("PENDING", "RUNNING", "SUBMITTED"))) > 0;
    }


    private boolean isDuplicate(String movieId, String urlHash, String url) {
        long existingLinks = resourceLinkService.count(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("url_hash", urlHash)
                .isNull("deleted_at")
                .eq("status", "ACTIVE"));
        if (existingLinks > 0) {
            return true;
        }
        return resourceLinkService.count(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("url", url)
                .isNull("deleted_at")
                .eq("status", "ACTIVE")) > 0;
    }

    private void markResourceStatus(MovieMetadata movie, String resourceStatus, LocalDateTime now) {
        if (movie == null || !hasText(resourceStatus) || resourceStatus.equalsIgnoreCase(movie.getResourceStatus())) {
            return;
        }
        movie.setResourceStatus(resourceStatus);
        movie.setUpdatedAt(now);
        movieService.updateById(movie);
    }

    private DiscoveryPayload normalizePayload(ResourceDiscoveryRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "movieId or movieTitle is required");
        }
        String movieId = resolveMovieId(request);
        String source = hasText(request.getSource()) ? request.getSource().trim().toUpperCase() : "PANSOU";
        if (!"PANSOU".equals(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported discovery source");
        }
        int maxResults = Math.min(Math.max(request.getMaxResults() == null
                ? DEFAULT_MAX_RESULTS
                : request.getMaxResults(), 1), MAX_RESULTS_LIMIT);
        String keyword = hasText(request.getKeyword()) ? request.getKeyword().trim() : null;
        return new DiscoveryPayload(movieId, keyword, source, maxResults);
    }

    private String resolveMovieId(ResourceDiscoveryRequest request) {
        if (hasText(request.getMovieId())) {
            String movieId = request.getMovieId().trim();
            if (movieService.getById(movieId) != null) {
                return movieId;
            }
            MovieMetadata byText = findMovieByText(movieId);
            if (byText != null) {
                return byText.getId();
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found: " + movieId);
        }

        String text = firstText(request.getMovieTitle(), request.getKeyword());
        if (!hasText(text)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "movieId or movieTitle is required");
        }
        MovieMetadata movie = findMovieByText(text);
        if (movie == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found: " + text);
        }
        return movie.getId();
    }

    private MovieMetadata findMovieByText(String value) {
        String text = value == null ? null : value.trim();
        if (!hasText(text)) {
            return null;
        }
        MovieMetadata exact = movieService.getOne(new QueryWrapper<MovieMetadata>()
                .eq("status", "ACTIVE")
                .and(w -> w.eq("id", text)
                        .or().eq("title_cn", text)
                        .or().eq("title_en", text)
                        .or().eq("series_name", text))
                .orderByDesc("popularity")
                .orderByAsc("season")
                .last("LIMIT 1"), false);
        if (exact != null) {
            return exact;
        }
        return movieService.getOne(new QueryWrapper<MovieMetadata>()
                .eq("status", "ACTIVE")
                .and(w -> w.like("title_cn", text)
                        .or().like("title_en", text)
                        .or().like("series_name", text)
                        .or().like("aliases", text))
                .orderByDesc("popularity")
                .orderByAsc("season")
                .last("LIMIT 1"), false);
    }

    private DiscoveryPayload readPayload(ResourceHubTask task) {
        if (!hasText(task.getPayload())) {
            return new DiscoveryPayload(task.getMovieId(), task.getKeyword(), task.getSource(), DEFAULT_MAX_RESULTS);
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(task.getPayload(),
                    new TypeReference<Map<String, Object>>() {
                    });
            String movieId = (String) payload.get("movieId");
            String movieTitle = (String) payload.get("movieTitle");
            String keyword = (String) payload.get("keyword");
            String source = (String) payload.get("source");
            int maxResults = payload.get("maxResults") instanceof Number number
                    ? number.intValue()
                    : DEFAULT_MAX_RESULTS;
            ResourceDiscoveryRequest request = new ResourceDiscoveryRequest();
            request.setMovieId(movieId);
            request.setMovieTitle(movieTitle);
            request.setKeyword(keyword);
            request.setSource(source);
            request.setMaxResults(maxResults);
            return normalizePayload(request);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid discovery task payload");
        }
    }

    private String writePayload(DiscoveryPayload payload) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("movieId", payload.movieId());
            value.put("keyword", payload.keyword());
            value.put("source", payload.source());
            value.put("maxResults", payload.maxResults());
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize task payload", e);
        }
    }

    private String resolveKeyword(DiscoveryPayload payload, MovieMetadata movie) {
        if (hasText(payload.keyword())) {
            return payload.keyword();
        }
        Set<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, movie.getTitleCn());
        addCandidate(candidates, movie.getTitleEn());
        addCandidate(candidates, movie.getSeriesName());
        String title = candidates.stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Movie has no searchable title"));
        if (movie.getSeason() != null && movie.getSeason() > 0) {
            return SeasonSearchUtils.seasonQualifiedTitle(title, movie.getSeason());
        }
        return movie.getYear() == null ? title : title + " " + movie.getYear();
    }

    private void addCandidate(Set<String> candidates, String value) {
        if (hasText(value)) {
            candidates.add(value.trim());
        }
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

    private void ensureEnabled() {
        if (!resourceHubProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Hub is disabled");
        }
    }

    private void addError(ResourceDiscoveryRunResult result, String message) {
        if (result.getErrors().size() < 10 && hasText(message)) {
            result.getErrors().add(trim(message, 500));
        }
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

    private record DiscoveryPayload(String movieId, String keyword, String source, int maxResults) {
    }
}
