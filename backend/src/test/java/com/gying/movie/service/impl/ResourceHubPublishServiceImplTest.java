package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResourceHubPublishServiceImplTest {

    @Test
    void doesNotPublishOriginalXunleiLinkBeforeOwnShareIsReady() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IResourceLinkService resourceService = mock(IResourceLinkService.class);
        IQuarkTransferTaskService transferService = mock(IQuarkTransferTaskService.class);
        IQuarkShareService shareService = mock(IQuarkShareService.class);
        IMovieMetadataService movieService = mock(IMovieMetadataService.class);
        ResourceHubPublishServiceImpl service = new ResourceHubPublishServiceImpl(
                properties, discoveryService, resourceService, transferService, shareService, movieService);

        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(900L);
        discovery.setMovieId("bLL9W");
        discovery.setTitle("青年华盛顿 (2026) 4K");
        discovery.setProvider("XUNLEI");
        discovery.setResourceType("DISK");
        discovery.setOriginalUrl("https://pan.xunlei.com/s/source-share");
        discovery.setOriginalUrlHash("source-hash");
        discovery.setStatus("DISCOVERED");
        MovieMetadata movie = new MovieMetadata();
        movie.setId(discovery.getMovieId());
        movie.setTitleCn("青年华盛顿");
        movie.setYear(2026);
        movie.setStatus("ACTIVE");

        when(discoveryService.getById(900L)).thenReturn(discovery);
        when(movieService.getById(discovery.getMovieId())).thenReturn(movie);

        ResourceHubPublishResult result = service.publishDiscovery(900L);

        assertEquals(0, result.getPublished());
        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getFailed());
        assertEquals("Xunlei own share is not ready", discovery.getFailureReason());
        verify(resourceService, never()).save(any(ResourceLink.class));
    }

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
        movie.setSeason(1);
        movie.setYear(2026);
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
        AtomicReference<ResourceLink> savedLink = new AtomicReference<>();
        doAnswer(invocation -> {
            ResourceLink link = invocation.getArgument(0);
            link.setId(1700L);
            savedLink.set(link);
            return true;
        }).when(resourceService).save(any(ResourceLink.class));

        ResourceHubPublishResult result = service.publishDiscovery(585L);

        assertEquals(1, result.getPublished());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getFailed());
        assertEquals("SAVED", discovery.getStatus());
        assertEquals(1700L, discovery.getResourceLinkId());
        assertEquals("\u5c3c\u53e4\u55b5\u55b5 \u7b2c1\u5b63 (2026)", savedLink.get().getName());
    }
    @Test
    void publishesCollectionRangeAndResolutionInsteadOfOverwritingWithSeasonOne() {
        ResourceHubProperties properties = new ResourceHubProperties(); properties.setEnabled(true);
        IResourceDiscoveryResultService discoveries = mock(IResourceDiscoveryResultService.class);
        IResourceLinkService resources = mock(IResourceLinkService.class);
        IMovieMetadataService movies = mock(IMovieMetadataService.class);
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(100L); discovery.setMovieId("series"); discovery.setStatus("DISCOVERED");
        discovery.setTitle("【全六季】《破产姐妹》1-6季【1080P蓝光】");
        discovery.setResourceType("DISK"); discovery.setProvider("XUNLEI");
        discovery.setOriginalUrl("https://pan.xunlei.com/s/source");
        discovery.setShareUrl("https://pan.xunlei.com/s/owned");
        MovieMetadata movie = new MovieMetadata(); movie.setId("series"); movie.setTitleCn("破产姐妹 第一季");
        movie.setSeriesName("破产姐妹"); movie.setSeason(1); movie.setCategory("tv");
        when(discoveries.getById(100L)).thenReturn(discovery); when(movies.getById("series")).thenReturn(movie);
        AtomicReference<ResourceLink> saved = new AtomicReference<>();
        doAnswer(call -> { ResourceLink link=call.getArgument(0); link.setId(101L); saved.set(link); return true; })
                .when(resources).save(any(ResourceLink.class));
        ResourceHubPublishResult result = new ResourceHubPublishServiceImpl(properties, discoveries, resources,
                mock(IQuarkTransferTaskService.class), mock(IQuarkShareService.class), movies).publishDiscovery(100L);
        assertEquals(1, result.getPublished());
        assertEquals("破产姐妹 第1-6季合集 1080p", saved.get().getName());
    }
}
