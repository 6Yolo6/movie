package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceHubMetadataSyncRequest;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.dto.ResourceHubWorkerResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IGyingMetadataSyncService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.ITmdbMetadataSyncService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class ResourceHubWorkerServiceImplTest {

    @Test
    void enqueuesOneTmdbSourcePerIntervalInRoundRobinOrder() {
        Fixture fixture = new Fixture();
        ResourceHubTask latest = new ResourceHubTask();
        latest.setKeyword("TOP_RATED_MOVIE");
        latest.setCreatedAt(LocalDateTime.now().minusHours(13));
        when(fixture.taskService.count(any(Wrapper.class))).thenReturn(0L);
        when(fixture.taskService.getOne(any(Wrapper.class), eq(false))).thenReturn(latest);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());

        ResourceHubWorkerResult result = fixture.service.runOnce();

        ArgumentCaptor<ResourceHubMetadataSyncRequest> requestCaptor =
                ArgumentCaptor.forClass(ResourceHubMetadataSyncRequest.class);
        verify(fixture.tmdbService).enqueue(requestCaptor.capture());
        assertEquals("TOP_RATED_TV", requestCaptor.getValue().getSource());
        assertEquals(1, result.getMetadataSyncTasksCreated());
    }

    @Test
    void doesNotEnqueueWhileTmdbTaskIsActive() {
        Fixture fixture = new Fixture();
        when(fixture.taskService.count(any(Wrapper.class))).thenReturn(1L);
        when(fixture.taskService.list(any(Wrapper.class))).thenReturn(List.of());

        ResourceHubWorkerResult result = fixture.service.runOnce();

        verify(fixture.tmdbService, never()).enqueue(any());
        assertEquals(0, result.getMetadataSyncTasksCreated());
    }

    @Test
    void wrapsToFirstSourceAfterLastConfiguredSource() {
        assertEquals(
                "TRENDING_MOVIE_DAY",
                ResourceHubWorkerServiceImpl.nextAutoSyncSource(
                        List.of("TRENDING_MOVIE_DAY", "TRENDING_TV_DAY"),
                        "TRENDING_TV_DAY"));
    }

    @Test
    void runsXunleiBeforeQuarkSoQuarkBacklogCannotStarveIt() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        properties.getWorker().setEnabled(true);
        IResourceHubTaskService taskService = mock(IResourceHubTaskService.class);
        ITmdbMetadataSyncService tmdbService = mock(ITmdbMetadataSyncService.class);
        IGyingMetadataSyncService gyingService = mock(IGyingMetadataSyncService.class);
        IResourceDiscoveryService discoveryService = mock(IResourceDiscoveryService.class);
        IQuarkTransferRunnerService quarkService = mock(IQuarkTransferRunnerService.class);
        IXunleiTransferRunnerService xunleiService = mock(IXunleiTransferRunnerService.class);
        IResourceHubPublishService publishService = mock(IResourceHubPublishService.class);
        IResourceHubConfigService configService = mock(IResourceHubConfigService.class);
        when(taskService.list(any(Wrapper.class))).thenReturn(List.of());
        when(quarkService.submitPending(any(Integer.class))).thenReturn(new QuarkTransferRunResult());
        when(xunleiService.submitPending(any(Integer.class))).thenReturn(new QuarkTransferRunResult());
        when(publishService.publishPending(any(Integer.class))).thenReturn(new ResourceHubPublishResult());
        ResourceHubWorkerServiceImpl service = new ResourceHubWorkerServiceImpl(
                properties, taskService, tmdbService, gyingService, discoveryService,
                quarkService, xunleiService, publishService, configService);

        service.runOnce();

        InOrder order = Mockito.inOrder(xunleiService, quarkService);
        order.verify(xunleiService).submitPending(any(Integer.class));
        order.verify(quarkService).submitPending(any(Integer.class));
    }

    private static class Fixture {
        private final ResourceHubProperties properties = new ResourceHubProperties();
        private final IResourceHubTaskService taskService = mock(IResourceHubTaskService.class);
        private final ITmdbMetadataSyncService tmdbService = mock(ITmdbMetadataSyncService.class);
        private final IGyingMetadataSyncService gyingService = mock(IGyingMetadataSyncService.class);
        private final IResourceDiscoveryService discoveryService = mock(IResourceDiscoveryService.class);
        private final IQuarkTransferRunnerService transferService = mock(IQuarkTransferRunnerService.class);
        private final IResourceHubPublishService publishService = mock(IResourceHubPublishService.class);
        private final IResourceHubConfigService configService = mock(IResourceHubConfigService.class);
        private final ResourceHubWorkerServiceImpl service;

        private Fixture() {
            properties.setEnabled(true);
            properties.getWorker().setEnabled(true);
            properties.getTmdb().setApiKey("configured");
            properties.getTmdb().setAutoSyncEnabled(true);
            properties.getTmdb().setAutoSyncSources("TOP_RATED_MOVIE,TOP_RATED_TV");
            properties.getTmdb().setAutoSyncIntervalHours(12);
            when(transferService.submitPending(any(Integer.class))).thenReturn(new QuarkTransferRunResult());
            when(publishService.publishPending(any(Integer.class))).thenReturn(new ResourceHubPublishResult());
            service = new ResourceHubWorkerServiceImpl(
                    properties,
                    taskService,
                    tmdbService,
                    gyingService,
                    discoveryService,
                    transferService,
                    publishService,
                    configService);
        }
    }
}
