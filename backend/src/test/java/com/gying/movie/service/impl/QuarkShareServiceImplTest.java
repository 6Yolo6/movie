package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gying.movie.client.QuarkShareClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkShareResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import org.junit.jupiter.api.Test;

class QuarkShareServiceImplTest {

    @Test
    void recoversFailedShareWhenSavedFolderHasContent() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setShareEnabled(true);
        QuarkShareClient shareClient = mock(QuarkShareClient.class);
        IQuarkTransferTaskService transferService = mock(IQuarkTransferTaskService.class);
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IMovieMetadataService movieService = mock(IMovieMetadataService.class);
        QuarkShareServiceImpl service = new QuarkShareServiceImpl(
                properties,
                shareClient,
                transferService,
                discoveryService,
                mock(IResourceLinkService.class),
                movieService);

        QuarkTransferTask task = new QuarkTransferTask();
        task.setId(220L);
        task.setDiscoveryResultId(585L);
        task.setMovieId("tmdb_tv_312949");
        task.setStatus("FAILED");
        task.setSavedPath("/GYing Resource Hub/tv/尼古喵喵");
        task.setLastError("share creation failed: Saved Quark folder is empty");
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(585L);
        discovery.setStatus("FAILED");
        MovieMetadata movie = new MovieMetadata();
        movie.setId("tmdb_tv_312949");
        movie.setTitleCn("尼古喵喵");
        movie.setYear(2026);
        QuarkShareResult shareResult = new QuarkShareResult();
        shareResult.setShareUrl("https://pan.quark.cn/s/own-share");

        when(shareClient.waitForFolderContent(
                eq(task.getSavedPath()),
                eq(properties.getQuark().getSharePollAttempts()),
                eq(properties.getQuark().getSharePollIntervalMs())))
                .thenReturn(new QuarkShareClient.FolderContentCheck("folder-id", "尼古喵喵", true, 2));
        when(movieService.getById(task.getMovieId())).thenReturn(movie);
        when(shareClient.createShareForPath(task.getSavedPath(), "尼古喵喵 (2026)")).thenReturn(shareResult);
        when(discoveryService.getById(585L)).thenReturn(discovery);

        String shareUrl = service.ensureShareUrl(task);

        assertEquals("https://pan.quark.cn/s/own-share", shareUrl);
        assertEquals("SUBMITTED", task.getStatus());
        assertNull(task.getLastError());
        assertEquals("DISCOVERED", discovery.getStatus());
        assertEquals(shareUrl, discovery.getShareUrl());
        verify(transferService).updateById(task);
        verify(discoveryService).updateById(discovery);
    }
}
