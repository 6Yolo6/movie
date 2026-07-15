package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.gying.movie.service.IResourceLinkService;
import org.junit.jupiter.api.Test;

class ResourceHubPublishServiceImplTest {

    @Test
    void recoversFailedShareAndPublishesResource() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        properties.setAutoApprove(true);
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IResourceLinkService resourceService = mock(IResourceLinkService.class);
        IQuarkTransferTaskService transferService = mock(IQuarkTransferTaskService.class);
        IQuarkShareService shareService = mock(IQuarkShareService.class);
        IMovieMetadataService movieService = mock(IMovieMetadataService.class);
        ResourceHubPublishServiceImpl service = new ResourceHubPublishServiceImpl(
                properties,
                discoveryService,
                resourceService,
                transferService,
                shareService,
                movieService);

        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(585L);
        discovery.setMovieId("tmdb_tv_312949");
        discovery.setTitle("尼古喵喵 (2026)");
        discovery.setProvider("QUARK");
        discovery.setResourceType("DISK");
        discovery.setOriginalUrl("https://pan.quark.cn/s/source");
        discovery.setOriginalUrlHash("source-hash");
        discovery.setStatus("FAILED");
        discovery.setFailureReason("share creation failed: Saved Quark folder is empty");
        MovieMetadata movie = new MovieMetadata();
        movie.setId(discovery.getMovieId());
        movie.setTitleCn("尼古喵喵");
        movie.setStatus("ACTIVE");
        QuarkTransferTask transfer = new QuarkTransferTask();
        transfer.setId(220L);
        transfer.setDiscoveryResultId(585L);
        transfer.setMovieId(discovery.getMovieId());
        transfer.setStatus("FAILED");
        transfer.setSavedPath("/GYing Resource Hub/tv/尼古喵喵");

        when(discoveryService.getById(585L)).thenReturn(discovery);
        when(movieService.getById(discovery.getMovieId())).thenReturn(movie);
        when(transferService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(transfer);
        when(shareService.ensureShareUrl(transfer)).thenReturn("https://pan.quark.cn/s/own-share");
        doAnswer(invocation -> {
            ResourceLink link = invocation.getArgument(0);
            link.setId(1700L);
            return true;
        }).when(resourceService).save(any(ResourceLink.class));

        ResourceHubPublishResult result = service.publishDiscovery(585L);

        assertEquals(1, result.getPublished());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getFailed());
        assertEquals("SAVED", discovery.getStatus());
        assertEquals(1700L, discovery.getResourceLinkId());
    }
}
