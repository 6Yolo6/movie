package com.gying.movie.service.impl;

import static com.gying.movie.service.impl.BaiduScopeTestAssertions.*;
import static org.junit.jupiter.api.Assertions.*;

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

    @Test void temporaryCloneIsRejectedWithoutChangingItsHistoryOrPublishing() {
        var properties = new ResourceHubProperties(); properties.setEnabled(true);
        var discoveries = mock(IResourceDiscoveryResultService.class);
        var resources = mock(IResourceLinkService.class); var movies = mock(IMovieMetadataService.class);
        var shares = mock(IQuarkShareService.class); var transfers = mock(IQuarkTransferTaskService.class);
        var service = new ResourceHubPublishServiceImpl(properties, discoveries, resources, transfers, shares, movies);
        var clone = new ResourceDiscoveryResult(); clone.setId(991L); clone.setStatus("SAVED");
        clone.setSource("QQ_BOT"); clone.setSourceRef("BAIDU_TEMP:" + TEMP_SCOPE); clone.setResourceLinkId(88L);
        clone.setShareUrl("https://pan.baidu.com/s/1temporary"); clone.setFailureReason("original history");
        when(discoveries.getById(991L)).thenReturn(clone);
        var result = service.publishDiscovery(991L);
        assertEquals(1, result.getFailed()); assertEquals(0, result.getPublished());
        assertEquals("original history", clone.getFailureReason()); assertEquals("SAVED", clone.getStatus());
        verify(discoveries, never()).updateById(any());
        org.mockito.Mockito.verifyNoInteractions(resources, movies, shares, transfers);
    }

    @Test void pendingPublicationExcludesClonesBeforeApplyingItsLimit() {
        var properties = new ResourceHubProperties(); properties.setEnabled(true);
        var discoveries = mock(IResourceDiscoveryResultService.class);
        when(discoveries.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call -> {
            permanentDiscoveryQuery(call.getArgument(0)); return java.util.List.of();
        });
        var service = new ResourceHubPublishServiceImpl(properties, discoveries, mock(IResourceLinkService.class),
                mock(IQuarkTransferTaskService.class), mock(IQuarkShareService.class), mock(IMovieMetadataService.class));
        assertEquals(0, service.publishPending(1).getPublished());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {TEMP_SCOPE, "UNRECOGNIZED"})
    void baiduPublishCannotUseATemporaryOrUnknownScopeTask(String scope) {
        var properties = new ResourceHubProperties(); properties.setEnabled(true);
        properties.getBaidu().setEnabled(true); properties.getBaidu().setVideoEnabled(true);
        var discoveries = mock(IResourceDiscoveryResultService.class);
        var resources = mock(IResourceLinkService.class); var movies = mock(IMovieMetadataService.class);
        var tasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        var client = mock(com.gying.movie.client.BaiduWebClient.class);
        var service = new ResourceHubPublishServiceImpl(properties, discoveries, resources,
                mock(IQuarkTransferTaskService.class), mock(IQuarkShareService.class), movies);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", tasks);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduWebClient", client);
        var discovery = publishableDiscovery("BAIDU");
        when(discoveries.getById(992L)).thenReturn(discovery); when(movies.getById("movie1")).thenReturn(publishableMovie());
        var temporary = new com.gying.movie.entity.BaiduTransferTask(); temporary.setScopeKey(scope);
        temporary.setStatus("SUCCEEDED"); temporary.setPhase("SHARED"); temporary.setShareUrl(discovery.getShareUrl());
        when(tasks.getOne(any(), eq(false))).thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return temporary; });
        var result = service.publishDiscovery(992L);
        assertEquals(1, result.getSkipped()); assertEquals(0, result.getPublished());
        org.mockito.Mockito.verifyNoInteractions(client, resources);
    }

    @Test void permanentPublicationCannotOverwriteAnExplicitlyLinkedTemporaryResource() {
        var properties = new ResourceHubProperties(); properties.setEnabled(true);
        var discoveries = mock(IResourceDiscoveryResultService.class); var resources = mock(IResourceLinkService.class);
        var movies = mock(IMovieMetadataService.class);
        var service = new ResourceHubPublishServiceImpl(properties, discoveries, resources,
                mock(IQuarkTransferTaskService.class), mock(IQuarkShareService.class), movies);
        var discovery = publishableDiscovery("XUNLEI"); discovery.setResourceLinkId(88L);
        var temporary = new ResourceLink(); temporary.setId(88L); temporary.setSource("BAIDU_TEMP");
        temporary.setSourceRef(TEMP_SCOPE); temporary.setUrl("https://pan.baidu.com/s/1temporary");
        when(discoveries.getById(992L)).thenReturn(discovery); when(movies.getById("movie1")).thenReturn(publishableMovie());
        when(resources.getById(88L)).thenReturn(temporary);
        assertEquals(1, service.publishDiscovery(992L).getFailed());
        assertEquals("BAIDU_TEMP", temporary.getSource()); assertEquals(TEMP_SCOPE, temporary.getSourceRef());
        assertEquals("https://pan.baidu.com/s/1temporary", temporary.getUrl());
        verify(resources, never()).updateById(any()); verify(resources, never()).update(any()); verify(resources, never()).save(any());
    }

    @Test void permanentPublicationDoesNotReuseTemporaryLinksFoundByUrlOrSourceUrl() {
        var properties = new ResourceHubProperties(); properties.setEnabled(true);
        var discoveries = mock(IResourceDiscoveryResultService.class); var resources = mock(IResourceLinkService.class);
        var movies = mock(IMovieMetadataService.class);
        var service = new ResourceHubPublishServiceImpl(properties, discoveries, resources,
                mock(IQuarkTransferTaskService.class), mock(IQuarkShareService.class), movies);
        var discovery = publishableDiscovery("XUNLEI");
        var temporary = new ResourceLink(); temporary.setId(88L); temporary.setSource("BAIDU_TEMP"); temporary.setSourceRef(TEMP_SCOPE);
        when(discoveries.getById(992L)).thenReturn(discovery); when(movies.getById("movie1")).thenReturn(publishableMovie());
        when(resources.getOne(any())).thenAnswer(call -> { permanentResourceQuery(call.getArgument(0)); return temporary; });
        when(resources.getOne(any(), eq(false))).thenAnswer(call -> { permanentResourceQuery(call.getArgument(0)); return temporary; });
        when(resources.save(any())).thenAnswer(call -> { ((ResourceLink) call.getArgument(0)).setId(89L); return true; });
        assertEquals(1, service.publishDiscovery(992L).getPublished());
        assertEquals("BAIDU_TEMP", temporary.getSource()); assertEquals(TEMP_SCOPE, temporary.getSourceRef());
        verify(resources, never()).updateById(any()); verify(resources, never()).update(any());
    }

    private ResourceDiscoveryResult publishableDiscovery(String provider) {
        var row = new ResourceDiscoveryResult(); row.setId(992L); row.setMovieId("movie1"); row.setTitle("Known movie");
        row.setProvider(provider); row.setResourceType("DISK"); row.setStatus("DISCOVERED");
        row.setOriginalUrl("BAIDU".equals(provider) ? "https://pan.baidu.com/s/1source" : "https://pan.xunlei.com/s/source");
        row.setShareUrl("BAIDU".equals(provider) ? "https://pan.baidu.com/s/1owned" : "https://pan.xunlei.com/s/owned");
        return row;
    }

    private MovieMetadata publishableMovie() {
        var movie = new MovieMetadata(); movie.setId("movie1"); movie.setTitleEn("Known movie"); movie.setCategory("mv");
        return movie;
    }


    @Test void permanentBaiduShareStillRequiresLiveValidationAndPublishes() {
        var properties = new ResourceHubProperties(); properties.setEnabled(true);
        properties.getBaidu().setEnabled(true); properties.getBaidu().setVideoEnabled(true);
        var discoveries = mock(IResourceDiscoveryResultService.class); var resources = mock(IResourceLinkService.class);
        var movies = mock(IMovieMetadataService.class); var tasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        var client = mock(com.gying.movie.client.BaiduWebClient.class);
        var service = new ResourceHubPublishServiceImpl(properties, discoveries, resources,
                mock(IQuarkTransferTaskService.class), mock(IQuarkShareService.class), movies);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduTransferTaskService", tasks);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baiduWebClient", client);
        var discovery = publishableDiscovery("BAIDU"); var task = new com.gying.movie.entity.BaiduTransferTask();
        task.setStatus("SUCCEEDED"); task.setPhase("SHARED"); task.setShareUrl(discovery.getShareUrl());
        when(discoveries.getById(992L)).thenReturn(discovery); when(movies.getById("movie1")).thenReturn(publishableMovie());
        when(tasks.getOne(any(), eq(false))).thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return task; });
        when(client.validateOwnShare(discovery.getShareUrl())).thenReturn(true);
        when(resources.getOne(any())).thenAnswer(call -> { permanentResourceQuery(call.getArgument(0)); return null; });
        when(resources.getOne(any(), eq(false))).thenAnswer(call -> { permanentResourceQuery(call.getArgument(0)); return null; });
        when(resources.save(any())).thenAnswer(call -> { ((ResourceLink) call.getArgument(0)).setId(89L); return true; });
        assertEquals(1, service.publishDiscovery(992L).getPublished());
        verify(client).validateOwnShare(discovery.getShareUrl());
        var saved = org.mockito.ArgumentCaptor.forClass(ResourceLink.class); verify(resources).save(saved.capture());
        assertEquals("RESOURCE_HUB", saved.getValue().getSource());
    }

}
