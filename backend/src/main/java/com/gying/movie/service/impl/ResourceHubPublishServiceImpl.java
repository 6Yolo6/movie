package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceHubPublishServiceImpl implements IResourceHubPublishService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> ALLOWED_TYPES = Set.of("DISK", "MAGNET", "TORRENT", "ONLINE");

    private final ResourceHubProperties resourceHubProperties;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IResourceLinkService resourceLinkService;
    private final IMovieMetadataService movieService;

    public ResourceHubPublishServiceImpl(
            ResourceHubProperties resourceHubProperties,
            IResourceDiscoveryResultService discoveryResultService,
            IResourceLinkService resourceLinkService,
            IMovieMetadataService movieService) {
        this.resourceHubProperties = resourceHubProperties;
        this.discoveryResultService = discoveryResultService;
        this.resourceLinkService = resourceLinkService;
        this.movieService = movieService;
    }

    @Override
    public ResourceHubPublishResult publishDiscovery(Long discoveryResultId) {
        ensureEnabled();
        ResourceDiscoveryResult discovery = discoveryResultService.getById(discoveryResultId);
        if (discovery == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Discovery result not found");
        }
        ResourceHubPublishResult result = new ResourceHubPublishResult();
        result.setDiscoveryResultId(discoveryResultId);
        publishOne(discovery, result);
        return result;
    }

    @Override
    public ResourceHubPublishResult publishPending(int limit) {
        ensureEnabled();
        int safeLimit = Math.min(Math.max(limit <= 0 ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        List<ResourceDiscoveryResult> discoveries = discoveryResultService.list(
                new QueryWrapper<ResourceDiscoveryResult>()
                        .eq("status", "DISCOVERED")
                        .orderByDesc("confidence")
                        .orderByAsc("created_at")
                        .last("LIMIT " + safeLimit));
        ResourceHubPublishResult result = new ResourceHubPublishResult();
        for (ResourceDiscoveryResult discovery : discoveries) {
            publishOne(discovery, result);
        }
        return result;
    }

    private void publishOne(ResourceDiscoveryResult discovery, ResourceHubPublishResult result) {
        try {
            if (discovery.getResourceLinkId() != null
                    && ("SAVED".equalsIgnoreCase(discovery.getStatus())
                            || "DUPLICATE".equalsIgnoreCase(discovery.getStatus()))) {
                result.setDuplicate(result.getDuplicate() + 1);
                result.getResourceIds().add(discovery.getResourceLinkId());
                return;
            }
            if (!"DISCOVERED".equalsIgnoreCase(discovery.getStatus())) {
                throw new IllegalStateException("Discovery result is not publishable: " + discovery.getStatus());
            }

            MovieMetadata movie = movieService.getById(discovery.getMovieId());
            if (movie == null) {
                throw new IllegalStateException("Movie not found: " + discovery.getMovieId());
            }

            String url = firstText(discovery.getShareUrl(), discovery.getOriginalUrl());
            if (!hasText(url)) {
                throw new IllegalStateException("Discovery result has no publishable URL");
            }
            String urlHash = firstText(discovery.getShareUrlHash(), discovery.getOriginalUrlHash());
            if (!hasText(urlHash)) {
                urlHash = ResourceHubHashUtils.sha256(url);
            }
            String type = normalizeType(discovery.getResourceType());
            validateUrl(type, url);
            String provider = normalizeProvider(type, discovery.getProvider());
            ResourceLink existing = findExistingResource(discovery.getMovieId(), url, urlHash);
            LocalDateTime now = LocalDateTime.now();
            if (existing != null) {
                discovery.setStatus("DUPLICATE");
                discovery.setResourceLinkId(existing.getId());
                discovery.setUpdatedAt(now);
                discoveryResultService.updateById(discovery);
                result.setDuplicate(result.getDuplicate() + 1);
                result.getResourceIds().add(existing.getId());
                return;
            }

            ResourceLink link = new ResourceLink();
            link.setMovieId(discovery.getMovieId());
            link.setName(firstText(discovery.getTitle(), movie.getTitleCn(), movie.getTitleEn(), movie.getId()));
            link.setType(type);
            link.setProvider(provider);
            link.setUrl(url);
            link.setUrlHash(urlHash);
            link.setCode("DISK".equals(type) ? trim(discovery.getCode(), 50) : null);
            link.setUploaderId(null);
            link.setAuditStatus(resourceHubProperties.isAutoApprove() ? 1 : 0);
            link.setStatus("ACTIVE");
            link.setLinkStatus("NORMAL");
            link.setReportCount(0);
            link.setSource("RESOURCE_HUB");
            link.setSourceRef(trim(discovery.getSourceRef(), 100));
            link.setSourceUrl(firstText(discovery.getOriginalUrl(), url));
            link.setAutoCollected(true);
            link.setValidatedAt(now);
            link.setLastCheckError(null);
            link.setQuality(trim(discovery.getQuality(), 50));
            link.setSubtitle(trim(discovery.getSubtitle(), 50));
            link.setFileSize(trim(discovery.getFileSize(), 50));
            link.setVersionNote(trim(discovery.getVersionNote(), 255));
            link.setRejectReason(null);
            link.setCreatedAt(now);
            resourceLinkService.save(link);

            discovery.setStatus("SAVED");
            discovery.setResourceLinkId(link.getId());
            discovery.setFailureReason(null);
            discovery.setUpdatedAt(now);
            discoveryResultService.updateById(discovery);
            result.setPublished(result.getPublished() + 1);
            result.getResourceIds().add(link.getId());
        } catch (Exception e) {
            discovery.setFailureReason(trim(e.getMessage(), 1000));
            discovery.setUpdatedAt(LocalDateTime.now());
            discoveryResultService.updateById(discovery);
            result.setFailed(result.getFailed() + 1);
            addError(result, "discovery " + discovery.getId() + ": " + e.getMessage());
        }
    }

    private ResourceLink findExistingResource(String movieId, String url, String urlHash) {
        ResourceLink existing = resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("url_hash", urlHash)
                .eq("status", "ACTIVE")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        return resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("url", url)
                .eq("status", "ACTIVE")
                .last("LIMIT 1"));
    }

    private String normalizeType(String type) {
        String normalized = hasText(type) ? type.trim().toUpperCase() : "DISK";
        if (!ALLOWED_TYPES.contains(normalized)) {
            throw new IllegalStateException("Invalid resource type: " + normalized);
        }
        return normalized;
    }

    private String normalizeProvider(String type, String provider) {
        if (!"DISK".equals(type)) {
            return "OTHER";
        }
        return hasText(provider) ? provider.trim().toUpperCase() : "QUARK";
    }

    private void validateUrl(String type, String url) {
        String lowerUrl = url.trim().toLowerCase();
        if ("MAGNET".equals(type) && !lowerUrl.startsWith("magnet:?xt=urn:btih:")) {
            throw new IllegalStateException("Magnet resources must start with magnet:?xt=urn:btih:");
        }
        if ("TORRENT".equals(type) && !(isHttpUrl(lowerUrl) && lowerUrl.contains(".torrent"))) {
            throw new IllegalStateException("Torrent resources must be an http(s) .torrent URL");
        }
        if (("DISK".equals(type) || "ONLINE".equals(type)) && !isHttpUrl(lowerUrl)) {
            throw new IllegalStateException("Cloud disk and online resources must be http(s) URLs");
        }
    }

    private boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private void ensureEnabled() {
        if (!resourceHubProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource Hub is disabled");
        }
    }

    private void addError(ResourceHubPublishResult result, String message) {
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
}
