package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.dto.ResourceHubIngestRequest;
import com.gying.movie.dto.ResourceHubIngestResponse;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.utils.InternalAuthHelper;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/internal/resource-hub")
public class ResourceHubInternalController {

    private static final Set<String> ALLOWED_TYPES = Set.of("DISK", "MAGNET", "TORRENT", "ONLINE");

    private final InternalAuthHelper internalAuthHelper;
    private final ResourceHubProperties resourceHubProperties;
    private final IMovieMetadataService movieService;
    private final IResourceLinkService resourceLinkService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IXunleiTransferRunnerService xunleiTransferRunnerService;

    public ResourceHubInternalController(
            InternalAuthHelper internalAuthHelper,
            ResourceHubProperties resourceHubProperties,
            IMovieMetadataService movieService,
            IResourceLinkService resourceLinkService,
            IResourceDiscoveryResultService discoveryResultService,
            IXunleiTransferRunnerService xunleiTransferRunnerService) {
        this.internalAuthHelper = internalAuthHelper;
        this.resourceHubProperties = resourceHubProperties;
        this.movieService = movieService;
        this.resourceLinkService = resourceLinkService;
        this.discoveryResultService = discoveryResultService;
        this.xunleiTransferRunnerService = xunleiTransferRunnerService;
    }

    @PostMapping("/xunlei-transfers/{taskId}/run")
    public ApiResponse<QuarkTransferRunResult> runXunleiTransfer(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @org.springframework.web.bind.annotation.PathVariable Long taskId) {
        internalAuthHelper.requireInternal(token);
        return ApiResponse.ok(xunleiTransferRunnerService.submitOne(taskId));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        internalAuthHelper.requireInternal(token);
        return ApiResponse.ok(Map.of(
                "enabled", resourceHubProperties.isEnabled(),
                "internalTokenConfigured", internalAuthHelper.isConfigured(),
                "tmdbConfigured", hasText(resourceHubProperties.getTmdb().getApiKey()),
                "quarkBaseUrl", nullToEmpty(resourceHubProperties.getQuark().getBaseUrl())
        ));
    }

    @PostMapping("/ingest")
    public ApiResponse<ResourceHubIngestResponse> ingest(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody ResourceHubIngestRequest request) {
        internalAuthHelper.requireInternal(token);
        if (!resourceHubProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Hub is disabled");
        }
        validateRequest(request);

        MovieMetadata movie = movieService.getById(request.getMovieId());
        if (movie == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found");
        }

        String url = request.getUrl().trim();
        String type = normalizeType(request.getType());
        String provider = normalizeProvider(type, request.getProvider());
        String urlHash = ResourceHubHashUtils.sha256(url);
        String sourceUrl = firstNonBlank(request.getSourceUrl(), url);
        String sourceUrlHash = ResourceHubHashUtils.sha256(sourceUrl);
        LocalDateTime now = LocalDateTime.now();

        ResourceLink existing = findExistingResource(request.getMovieId(), url, urlHash);

        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setTaskId(request.getTaskId());
        discovery.setMovieId(request.getMovieId());
        discovery.setSource(cleanOptional(firstNonBlank(request.getSource(), "RESOURCE_HUB"), 50));
        discovery.setSourceRef(cleanOptional(request.getSourceRef(), 100));
        discovery.setTitle(cleanOptional(firstNonBlank(request.getName(), movie.getTitleCn(), movie.getTitleEn()), 255));
        discovery.setProvider(provider);
        discovery.setResourceType(type);
        discovery.setOriginalUrl(sourceUrl);
        discovery.setOriginalUrlHash(sourceUrlHash);
        discovery.setShareUrl(url);
        discovery.setShareUrlHash(urlHash);
        discovery.setCode("DISK".equals(type) ? cleanOptional(request.getCode(), 50) : null);
        discovery.setQuality(cleanOptional(request.getQuality(), 50));
        discovery.setSubtitle(cleanOptional(request.getSubtitle(), 50));
        discovery.setFileSize(cleanOptional(request.getFileSize(), 50));
        discovery.setVersionNote(cleanOptional(request.getVersionNote(), 255));
        discovery.setConfidence(request.getConfidence());
        discovery.setCreatedAt(now);
        discovery.setUpdatedAt(now);

        ResourceHubIngestResponse response = new ResourceHubIngestResponse();
        if (existing != null) {
            updateResourceLink(existing, request, movie, type, provider, url, urlHash, sourceUrl, now);
            discovery.setStatus("SAVED");
            discovery.setResourceLinkId(existing.getId());
            discoveryResultService.save(discovery);
            response.setResourceId(existing.getId());
            response.setDiscoveryResultId(discovery.getId());
            response.setDuplicate(false);
            response.setStatus("SAVED");
            return ApiResponse.ok(response);
        }

        ResourceLink link = new ResourceLink();
        link.setMovieId(request.getMovieId());
        link.setName(cleanOptional(firstNonBlank(request.getName(), movie.getTitleCn(), movie.getTitleEn()), 255));
        link.setType(type);
        link.setProvider(provider);
        link.setUrl(url);
        link.setUrlHash(urlHash);
        link.setCode("DISK".equals(type) ? cleanOptional(request.getCode(), 50) : null);
        link.setUploaderId(null);
        link.setAuditStatus(shouldAutoApprove(request) ? 1 : 0);
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setReportCount(0);
        link.setQuality(cleanOptional(request.getQuality(), 50));
        link.setSubtitle(cleanOptional(request.getSubtitle(), 50));
        link.setFileSize(cleanOptional(request.getFileSize(), 50));
        link.setVersionNote(cleanOptional(request.getVersionNote(), 255));
        link.setRejectReason(null);
        link.setSource("RESOURCE_HUB");
        link.setSourceRef(cleanOptional(request.getSourceRef(), 100));
        link.setSourceUrl(sourceUrl);
        link.setAutoCollected(true);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        link.setDeletedAt(null);
        resourceLinkService.save(link);

        discovery.setStatus("SAVED");
        discovery.setResourceLinkId(link.getId());
        discoveryResultService.save(discovery);

        response.setResourceId(link.getId());
        response.setDiscoveryResultId(discovery.getId());
        response.setDuplicate(false);
        response.setStatus("SAVED");
        return ApiResponse.ok(response);
    }

    private void validateRequest(ResourceHubIngestRequest request) {
        if (request == null || !hasText(request.getMovieId()) || !hasText(request.getUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "movieId and url are required");
        }
        String type = normalizeType(request.getType());
        if (!ALLOWED_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource type");
        }
        String lowerUrl = request.getUrl().trim().toLowerCase();
        if ("MAGNET".equals(type) && !lowerUrl.startsWith("magnet:?xt=urn:btih:")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Magnet resources must start with magnet:?xt=urn:btih:");
        }
        if ("TORRENT".equals(type) && !(isHttpUrl(lowerUrl) && lowerUrl.contains(".torrent"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Torrent resources must be an http(s) .torrent URL");
        }
        if (("DISK".equals(type) || "ONLINE".equals(type)) && !isHttpUrl(lowerUrl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cloud disk and online resources must be http(s) URLs");
        }
    }

    private ResourceLink findExistingResource(String movieId, String url, String urlHash) {
        ResourceLink existing = resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("url_hash", urlHash)
                .isNull("deleted_at")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        existing = resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("url", url)
                .isNull("deleted_at")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        existing = resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("url_hash", urlHash)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        return resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("url", url)
                .last("LIMIT 1"));
    }

    private void updateResourceLink(ResourceLink link,
            ResourceHubIngestRequest request,
            MovieMetadata movie,
            String type,
            String provider,
            String url,
            String urlHash,
            String sourceUrl,
            LocalDateTime now) {
        link.setMovieId(request.getMovieId());
        link.setName(cleanOptional(firstNonBlank(request.getName(), link.getName(), movie.getTitleCn(), movie.getTitleEn()), 255));
        link.setType(type);
        link.setProvider(provider);
        link.setUrl(url);
        link.setUrlHash(urlHash);
        link.setCode("DISK".equals(type) ? cleanOptional(request.getCode(), 50) : null);
        link.setAuditStatus(shouldAutoApprove(request) ? 1 : link.getAuditStatus());
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setQuality(cleanOptional(request.getQuality(), 50));
        link.setSubtitle(cleanOptional(request.getSubtitle(), 50));
        link.setFileSize(cleanOptional(request.getFileSize(), 50));
        link.setVersionNote(cleanOptional(request.getVersionNote(), 255));
        link.setRejectReason(null);
        link.setSource("RESOURCE_HUB");
        link.setSourceRef(cleanOptional(request.getSourceRef(), 100));
        link.setSourceUrl(sourceUrl);
        link.setAutoCollected(true);
        link.setValidatedAt(now);
        link.setLastCheckError(null);
        link.setUpdatedAt(now);
        link.setDeletedAt(null);
        resourceLinkService.updateById(link);
        resourceLinkService.update(new UpdateWrapper<ResourceLink>()
                .eq("id", link.getId())
                .set("code", link.getCode())
                .set("deleted_at", null)
                .set("last_check_error", null)
                .set("reject_reason", null)
                .set("updated_at", now));
    }

    private boolean shouldAutoApprove(ResourceHubIngestRequest request) {
        if (request.getAutoApprove() != null) {
            return request.getAutoApprove();
        }
        return resourceHubProperties.isAutoApprove();
    }

    private String normalizeType(String type) {
        return hasText(type) ? type.trim().toUpperCase() : "DISK";
    }

    private String normalizeProvider(String type, String provider) {
        if (!"DISK".equals(type)) {
            return "OTHER";
        }
        return hasText(provider) ? provider.trim().toUpperCase() : "QUARK";
    }

    private boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private String firstNonBlank(String... values) {
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

    private String cleanOptional(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
