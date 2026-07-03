package com.gying.movie.service.impl;

import com.gying.movie.client.QuarkShareClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkShareResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class QuarkShareServiceImpl implements IQuarkShareService {

    private final ResourceHubProperties resourceHubProperties;
    private final QuarkShareClient quarkShareClient;
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final IMovieMetadataService movieService;

    public QuarkShareServiceImpl(ResourceHubProperties resourceHubProperties,
            QuarkShareClient quarkShareClient,
            IQuarkTransferTaskService quarkTransferTaskService,
            IMovieMetadataService movieService) {
        this.resourceHubProperties = resourceHubProperties;
        this.quarkShareClient = quarkShareClient;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.movieService = movieService;
    }

    @Override
    public String ensureShareUrl(QuarkTransferTask task) {
        if (task == null || !resourceHubProperties.getQuark().isShareEnabled()) {
            return null;
        }
        if (hasText(task.getShareUrl())) {
            return task.getShareUrl();
        }
        if (!"SUBMITTED".equalsIgnoreCase(task.getStatus()) || !hasText(task.getSavedPath())) {
            return null;
        }
        MovieMetadata movie = movieService.getById(task.getMovieId());
        QuarkShareResult result = quarkShareClient.createShareForPath(task.getSavedPath(), buildShareTitle(movie, task));
        task.setShareUrl(result.getShareUrl());
        task.setShareUrlHash(ResourceHubHashUtils.sha256(result.getShareUrl()));
        task.setUpdatedAt(LocalDateTime.now());
        quarkTransferTaskService.updateById(task);
        return task.getShareUrl();
    }

    private String buildShareTitle(MovieMetadata movie, QuarkTransferTask task) {
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
