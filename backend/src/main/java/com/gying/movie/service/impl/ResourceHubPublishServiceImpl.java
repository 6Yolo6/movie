package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.ResourceHubHashUtils;
import com.gying.movie.utils.SeasonSearchUtils;
import com.gying.movie.utils.ResourceTitleMatcher;
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
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final IQuarkShareService quarkShareService;
    private final IMovieMetadataService movieService;

    public ResourceHubPublishServiceImpl(
            ResourceHubProperties resourceHubProperties,
            IResourceDiscoveryResultService discoveryResultService,
            IResourceLinkService resourceLinkService,
            IQuarkTransferTaskService quarkTransferTaskService,
            IQuarkShareService quarkShareService,
            IMovieMetadataService movieService) {
        this.resourceHubProperties = resourceHubProperties;
        this.discoveryResultService = discoveryResultService;
        this.resourceLinkService = resourceLinkService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.quarkShareService = quarkShareService;
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
                        .isNotNull("share_url")
                        .isNull("resource_link_id")
                        .orderByDesc("confidence")
                        .orderByAsc("updated_at")
                        .last("LIMIT " + safeLimit));
        ResourceHubPublishResult result = new ResourceHubPublishResult();
        for (ResourceDiscoveryResult discovery : discoveries) {
            publishOne(discovery, result);
        }
        return result;
    }

    private void publishOne(ResourceDiscoveryResult discovery, ResourceHubPublishResult result) {
        try {
            boolean shareRecovery = "FAILED".equalsIgnoreCase(discovery.getStatus())
                    && isQuarkDiscovery(discovery);
            if (!"DISCOVERED".equalsIgnoreCase(discovery.getStatus())
                    && discovery.getResourceLinkId() == null
                    && !shareRecovery) {
                throw new IllegalStateException("Discovery result is not publishable: " + discovery.getStatus());
            }

            MovieMetadata movie = movieService.getById(discovery.getMovieId());
            if (movie == null) {
                throw new IllegalStateException("Movie not found: " + discovery.getMovieId());
            }
            if (!ResourceTitleMatcher.isRelevant(movie, discovery.getTitle(), null)) {
                discovery.setStatus("IGNORED");
                discovery.setFailureReason("Resource title does not match movie title");
                discovery.setUpdatedAt(LocalDateTime.now());
                discoveryResultService.updateById(discovery);
                result.setSkipped(result.getSkipped() + 1);
                return;
            }

            if (!ensurePublishableShare(discovery, result)) {
                return;
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
            ResourceLink existing = findExistingResource(discovery.getMovieId(), url, urlHash, discovery.getOriginalUrl());
            LocalDateTime now = LocalDateTime.now();
            if (discovery.getResourceLinkId() != null) {
                ResourceLink linked = resourceLinkService.getById(discovery.getResourceLinkId());
                if (linked != null) {
                    updateResourceLink(linked, discovery, movie, type, provider, url, urlHash, now);
                    discovery.setStatus("SAVED");
                    discovery.setFailureReason(null);
                    discovery.setUpdatedAt(now);
                    saveSuccessfulDiscovery(discovery);
                    markResourceAvailable(movie, now);
                    result.setUpdated(result.getUpdated() + 1);
                    result.getResourceIds().add(linked.getId());
                    return;
                }
            }
            if (existing != null) {
                updateResourceLink(existing, discovery, movie, type, provider, url, urlHash, now);
                discovery.setStatus("SAVED");
                discovery.setResourceLinkId(existing.getId());
                discovery.setFailureReason(null);
                discovery.setUpdatedAt(now);
                saveSuccessfulDiscovery(discovery);
                markResourceAvailable(movie, now);
                result.setUpdated(result.getUpdated() + 1);
                result.getResourceIds().add(existing.getId());
                return;
            }

            ResourceLink link = new ResourceLink();
            link.setMovieId(discovery.getMovieId());
            link.setName(resourceTitle(movie, discovery));
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
            link.setUpdatedAt(now);
            link.setDeletedAt(null);
            resourceLinkService.save(link);

            discovery.setStatus("SAVED");
            discovery.setResourceLinkId(link.getId());
            discovery.setFailureReason(null);
            discovery.setUpdatedAt(now);
            saveSuccessfulDiscovery(discovery);
            markResourceAvailable(movie, now);
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

    private void saveSuccessfulDiscovery(ResourceDiscoveryResult discovery) {
        discoveryResultService.updateById(discovery);
        discoveryResultService.update(new UpdateWrapper<ResourceDiscoveryResult>()
                .eq("id", discovery.getId())
                .set("status", discovery.getStatus())
                .set("resource_link_id", discovery.getResourceLinkId())
                .set("share_url", discovery.getShareUrl())
                .set("share_url_hash", discovery.getShareUrlHash())
                .set("failure_reason", null)
                .set("updated_at", discovery.getUpdatedAt()));
    }

    private boolean ensurePublishableShare(ResourceDiscoveryResult discovery, ResourceHubPublishResult result) {
        if (!resourceHubProperties.getQuark().isShareEnabled() || !isQuarkDiscovery(discovery)) {
            return true;
        }
        if (hasText(discovery.getShareUrl())) {
            return true;
        }
        QuarkTransferTask transfer = findTransferTask(discovery);
        if (transfer != null && hasText(transfer.getShareUrl())) {
            return applyTransferShare(discovery, transfer.getShareUrl(), transfer.getShareUrlHash());
        }
        if (transfer != null) {
            try {
                String shareUrl = quarkShareService.ensureShareUrl(transfer);
                if (hasText(shareUrl)) {
                    return applyTransferShare(discovery, shareUrl, transfer.getShareUrlHash());
                }
            } catch (Exception e) {
                discovery.setFailureReason(trim(e.getMessage(), 1000));
                discovery.setUpdatedAt(LocalDateTime.now());
                discoveryResultService.updateById(discovery);
                result.setFailed(result.getFailed() + 1);
                addError(result, "discovery " + discovery.getId() + ": " + e.getMessage());
                return false;
            }
        }
        result.setSkipped(result.getSkipped() + 1);
        return false;
    }

    private boolean applyTransferShare(
            ResourceDiscoveryResult discovery,
            String shareUrl,
            String shareUrlHash) {
        discovery.setShareUrl(shareUrl);
        discovery.setShareUrlHash(firstText(shareUrlHash, ResourceHubHashUtils.sha256(shareUrl)));
        discovery.setStatus("DISCOVERED");
        discovery.setFailureReason(null);
        discovery.setUpdatedAt(LocalDateTime.now());
        discoveryResultService.updateById(discovery);
        return true;
    }

    private boolean isQuarkDiscovery(ResourceDiscoveryResult discovery) {
        return discovery != null
                && ("QUARK".equalsIgnoreCase(discovery.getProvider())
                        || (hasText(discovery.getOriginalUrl())
                                && discovery.getOriginalUrl().toLowerCase().contains("pan.quark.cn/s/")));
    }

    private QuarkTransferTask findTransferTask(ResourceDiscoveryResult discovery) {
        if (discovery.getId() != null) {
            QuarkTransferTask byDiscovery = quarkTransferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                    .eq("discovery_result_id", discovery.getId())
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"), false);
            if (byDiscovery != null) {
                return byDiscovery;
            }
        }
        if (!hasText(discovery.getOriginalUrlHash())) {
            return null;
        }
        return quarkTransferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                .eq("movie_id", discovery.getMovieId())
                .eq("original_url_hash", discovery.getOriginalUrlHash())
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
    }

    private ResourceLink findExistingResource(String movieId, String url, String urlHash, String sourceUrl) {
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
        if (hasText(sourceUrl)) {
            existing = resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                    .eq("movie_id", movieId)
                    .eq("source_url", sourceUrl)
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"), false);
            if (existing != null) {
                return existing;
            }
        }
        existing = resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("source", "RESOURCE_HUB")
                .eq("provider", "QUARK")
                .and(w -> w.ne("status", "ACTIVE")
                        .or().in("link_status", List.of("INVALID", "SUSPECTED_INVALID")))
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
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
            ResourceDiscoveryResult discovery,
            MovieMetadata movie,
            String type,
            String provider,
            String url,
            String urlHash,
            LocalDateTime now) {
        link.setMovieId(discovery.getMovieId());
        link.setName(resourceTitle(movie, discovery));
        link.setType(type);
        link.setProvider(provider);
        link.setUrl(url);
        link.setUrlHash(urlHash);
        link.setCode("DISK".equals(type) ? trim(discovery.getCode(), 50) : null);
        link.setAuditStatus(resourceHubProperties.isAutoApprove() ? 1 : link.getAuditStatus());
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setSource("RESOURCE_HUB");
        link.setSourceRef(trim(discovery.getSourceRef(), 100));
        link.setSourceUrl(firstText(discovery.getOriginalUrl(), link.getSourceUrl(), url));
        link.setAutoCollected(true);
        link.setValidatedAt(now);
        link.setLastCheckError(null);
        link.setQuality(trim(discovery.getQuality(), 50));
        link.setSubtitle(trim(discovery.getSubtitle(), 50));
        link.setFileSize(trim(discovery.getFileSize(), 50));
        link.setVersionNote(trim(discovery.getVersionNote(), 255));
        link.setRejectReason(null);
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

    private void markResourceAvailable(MovieMetadata movie, LocalDateTime now) {
        if (movie == null || "AVAILABLE".equalsIgnoreCase(movie.getResourceStatus())) {
            return;
        }
        movie.setResourceStatus("AVAILABLE");
        movie.setUpdatedAt(now);
        movieService.updateById(movie);
    }

    private String resourceTitle(MovieMetadata movie, ResourceDiscoveryResult discovery) {
        if (movie != null && movie.getSeason() != null && movie.getSeason() > 0) {
            String title = SeasonSearchUtils.seasonQualifiedTitle(
                    firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), movie.getId()),
                    movie.getSeason());
            return movie.getYear() == null ? title : title + " (" + movie.getYear() + ")";
        }
        return firstText(discovery.getTitle(), movie.getTitleCn(), movie.getTitleEn(), movie.getId());
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
