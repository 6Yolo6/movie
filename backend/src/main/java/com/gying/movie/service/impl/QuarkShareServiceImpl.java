package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gying.movie.client.QuarkShareClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkShareResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.ResourceHubHashUtils;
import com.gying.movie.utils.SeasonSearchUtils;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class QuarkShareServiceImpl implements IQuarkShareService {

    private final ResourceHubProperties resourceHubProperties;
    private final QuarkShareClient quarkShareClient;
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IResourceLinkService resourceLinkService;
    private final IMovieMetadataService movieService;

    public QuarkShareServiceImpl(ResourceHubProperties resourceHubProperties,
            QuarkShareClient quarkShareClient,
            IQuarkTransferTaskService quarkTransferTaskService,
            IResourceDiscoveryResultService discoveryResultService,
            IResourceLinkService resourceLinkService,
            IMovieMetadataService movieService) {
        this.resourceHubProperties = resourceHubProperties;
        this.quarkShareClient = quarkShareClient;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.discoveryResultService = discoveryResultService;
        this.resourceLinkService = resourceLinkService;
        this.movieService = movieService;
    }

    @Override
    public String ensureShareUrl(QuarkTransferTask task) {
        if (task == null || !resourceHubProperties.getQuark().isShareEnabled()) {
            return null;
        }
        if (!hasText(task.getSavedPath())
                || (!"SUBMITTED".equalsIgnoreCase(task.getStatus())
                        && !"FAILED".equalsIgnoreCase(task.getStatus()))) {
            return null;
        }
        QuarkShareClient.FolderContentCheck contentCheck = quarkShareClient.waitForFolderContent(
                task.getSavedPath(),
                resourceHubProperties.getQuark().getSharePollAttempts(),
                resourceHubProperties.getQuark().getSharePollIntervalMs());
        if (!contentCheck.hasContent()) {
            throw new IllegalStateException("Saved Quark folder is empty: " + task.getSavedPath());
        }
        if (hasText(task.getShareUrl())) {
            markShareReady(task);
            updateDiscoveryShare(task);
            return task.getShareUrl();
        }
        MovieMetadata movie = movieService.getById(task.getMovieId());
        QuarkShareResult result = quarkShareClient.createShareForPath(task.getSavedPath(), buildShareTitle(movie, task));
        task.setShareUrl(result.getShareUrl());
        task.setShareUrlHash(ResourceHubHashUtils.sha256(result.getShareUrl()));
        markShareReady(task);
        updateDiscoveryShare(task);
        return task.getShareUrl();
    }

    private void markShareReady(QuarkTransferTask task) {
        task.setStatus("SUBMITTED");
        task.setLastError(null);
        task.setUpdatedAt(LocalDateTime.now());
        quarkTransferTaskService.updateById(task);
        quarkTransferTaskService.update(new UpdateWrapper<QuarkTransferTask>()
                .eq("id", task.getId())
                .set("status", "SUBMITTED")
                .set("last_error", null)
                .set("share_url", task.getShareUrl())
                .set("share_url_hash", task.getShareUrlHash())
                .set("updated_at", task.getUpdatedAt()));
    }

    private void updateDiscoveryShare(QuarkTransferTask task) {
        if (task.getDiscoveryResultId() == null || !hasText(task.getShareUrl())) {
            return;
        }
        ResourceDiscoveryResult discovery = discoveryResultService.getById(task.getDiscoveryResultId());
        if (discovery == null) {
            return;
        }
        discovery.setShareUrl(task.getShareUrl());
        discovery.setShareUrlHash(task.getShareUrlHash());
        discovery.setFailureReason(null);
        if ("FAILED".equalsIgnoreCase(discovery.getStatus()) || "IGNORED".equalsIgnoreCase(discovery.getStatus())) {
            discovery.setStatus("DISCOVERED");
        }
        discovery.setUpdatedAt(LocalDateTime.now());
        discoveryResultService.updateById(discovery);
        discoveryResultService.update(new UpdateWrapper<ResourceDiscoveryResult>()
                .eq("id", discovery.getId())
                .set("status", discovery.getStatus())
                .set("share_url", discovery.getShareUrl())
                .set("share_url_hash", discovery.getShareUrlHash())
                .set("failure_reason", null)
                .set("updated_at", discovery.getUpdatedAt()));
        updateResourceLink(discovery, task);
    }

    private void updateResourceLink(ResourceDiscoveryResult discovery, QuarkTransferTask task) {
        if (discovery.getResourceLinkId() == null || !hasText(task.getShareUrl())) {
            return;
        }
        ResourceLink link = resourceLinkService.getById(discovery.getResourceLinkId());
        if (link == null) {
            return;
        }
        link.setUrl(task.getShareUrl());
        link.setUrlHash(task.getShareUrlHash());
        link.setProvider("QUARK");
        link.setCode(null);
        link.setSourceUrl(firstText(discovery.getOriginalUrl(), link.getSourceUrl()));
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setValidatedAt(LocalDateTime.now());
        link.setLastCheckError(null);
        link.setUpdatedAt(LocalDateTime.now());
        resourceLinkService.updateById(link);
    }

    private String buildShareTitle(MovieMetadata movie, QuarkTransferTask task) {
        if (movie != null && movie.getSeason() != null && movie.getSeason() > 0) {
            String title = SeasonSearchUtils.seasonQualifiedTitle(
                    firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), movie.getId()),
                    movie.getSeason());
            return movie.getYear() == null ? title : title + " (" + movie.getYear() + ")";
        }
        ResourceDiscoveryResult discovery = task.getDiscoveryResultId() == null
                ? null
                : discoveryResultService.getById(task.getDiscoveryResultId());
        if (discovery != null && hasText(discovery.getTitle())) {
            return discovery.getTitle().trim();
        }
        if (movie == null) {
            return "GYing-" + task.getMovieId();
        }
        String title = firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getSeriesName(), movie.getId());
        if (movie.getYear() != null) {
            return title + " (" + movie.getYear() + ")";
        }
        return title;
    }

    private String firstText(String... values) {
        if (values == null) {
            return "GYing Resource";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "GYing Resource";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
