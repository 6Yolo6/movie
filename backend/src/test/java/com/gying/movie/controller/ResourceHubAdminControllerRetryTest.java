package com.gying.movie.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.dto.ResourceHubConfigResponse;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IXunleiTransferTaskService;
import com.gying.movie.utils.AuthHelper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ResourceHubAdminControllerRetryTest {

    @Test
    void resumesWaitingShareWithoutRestoringAgain() {
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IMovieMetadataService movieService = mock(IMovieMetadataService.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        IXunleiTransferRunnerService runner = mock(IXunleiTransferRunnerService.class);
        IResourceHubPublishService publisher = mock(IResourceHubPublishService.class);
        IResourceLinkService linkService = mock(IResourceLinkService.class);

        ResourceDiscoveryResult discovery = discovery(11L);
        MovieMetadata movie = movie();
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(101L);
        task.setDiscoveryResultId(11L);
        task.setMovieId("movie-1");
        task.setStatus("WAITING_SHARE");
        task.setSavedPath("saved-folder-id");
        task.setAttempts(3);
        AtomicReference<String> statusAtRunner = new AtomicReference<>();

        when(discoveryService.getById(11L)).thenReturn(discovery, refreshedDiscovery(11L));
        when(movieService.getById("movie-1")).thenReturn(movie);
        when(taskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(task);
        when(taskService.getById(101L)).thenReturn(task);
        when(runner.submitOne(101L)).thenAnswer(invocation -> {
            statusAtRunner.set(task.getStatus());
            task.setShareUrl("https://pan.xunlei.com/s/owned?pwd=code");
            task.setShareUrlHash("hash");
            QuarkTransferRunResult result = new QuarkTransferRunResult();
            result.setSubmitted(1);
            return result;
        });
        ResourceHubPublishResult publishResult = new ResourceHubPublishResult();
        publishResult.setPublished(1);
        publishResult.setResourceIds(List.of(200L));
        when(publisher.publishDiscovery(11L)).thenReturn(publishResult);
        when(linkService.count(any(QueryWrapper.class))).thenReturn(1L);

        ResourceHubAdminController controller = controller(
                discoveryService, taskService, runner, publisher, linkService, movieService,
                mock(IResourceDiscoveryService.class), mock(IResourceHubConfigService.class));

        ApiResponse<Map<String, Object>> response = controller.retryShareAndPublish(11L, "Bearer test");

        assertEquals("WAITING_SHARE", statusAtRunner.get());
        assertEquals(1, response.getData().get("transferSubmitted"));
        verify(taskService).updateById(task);
    }

    @Test
    void returnsConflictAndPersistsFailureReasonWhenRetryCannotRecover() {
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IMovieMetadataService movieService = mock(IMovieMetadataService.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        IXunleiTransferRunnerService runner = mock(IXunleiTransferRunnerService.class);
        IResourceDiscoveryService resourceDiscoveryService = mock(IResourceDiscoveryService.class);
        IResourceHubConfigService configService = mock(IResourceHubConfigService.class);

        ResourceDiscoveryResult discovery = discovery(12L);
        MovieMetadata movie = movie();
        AtomicReference<XunleiTransferTask> taskRef = new AtomicReference<>();
        when(discoveryService.getById(12L)).thenReturn(discovery);
        when(movieService.getById("movie-1")).thenReturn(movie);
        when(taskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(null);
        when(taskService.save(any(XunleiTransferTask.class))).thenAnswer(invocation -> {
            XunleiTransferTask task = invocation.getArgument(0);
            task.setId(102L);
            taskRef.set(task);
            return true;
        });
        when(taskService.getById(anyLong())).thenAnswer(invocation -> taskRef.get());
        QuarkTransferRunResult transferResult = new QuarkTransferRunResult();
        transferResult.setFailed(1);
        transferResult.getErrors().add("source unavailable");
        when(runner.submitOne(102L)).thenReturn(transferResult);

        ResourceHubTask rediscoveryTask = new ResourceHubTask();
        rediscoveryTask.setId(77L);
        when(resourceDiscoveryService.enqueue(any())).thenReturn(rediscoveryTask);
        when(resourceDiscoveryService.runTask(77L)).thenReturn(new ResourceDiscoveryRunResult());
        when(discoveryService.list(any(QueryWrapper.class))).thenReturn(List.of());
        when(configService.getConfig()).thenReturn(new ResourceHubConfigResponse());

        ResourceHubAdminController controller = controller(
                discoveryService, taskService, runner, null, null, movieService,
                resourceDiscoveryService, configService);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.retryShareAndPublish(12L, "Bearer test"));

        assertEquals(409, error.getStatusCode().value());
        assertEquals("source unavailable", error.getReason());
        assertEquals("source unavailable", discovery.getFailureReason());
        verify(discoveryService).updateById(discovery);
    }

    private static ResourceHubAdminController controller(
            IResourceDiscoveryResultService discoveryService,
            IXunleiTransferTaskService taskService,
            IXunleiTransferRunnerService runner,
            IResourceHubPublishService publisher,
            IResourceLinkService linkService,
            IMovieMetadataService movieService,
            IResourceDiscoveryService resourceDiscoveryService,
            IResourceHubConfigService configService) {
        return new ResourceHubAdminController(
                mock(AuthHelper.class),
                new ResourceHubProperties(),
                null,
                discoveryService,
                null,
                taskService,
                null,
                resourceDiscoveryService,
                null,
                runner,
                publisher,
                null,
                configService,
                linkService,
                null,
                null,
                movieService,
                null,
                null,
                null,
                null);
    }

    private static ResourceDiscoveryResult discovery(Long id) {
        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(id);
        discovery.setMovieId("movie-1");
        discovery.setProvider("XUNLEI");
        discovery.setStatus("DISCOVERED");
        discovery.setTitle("测试影片");
        discovery.setOriginalUrl("https://pan.xunlei.com/s/source?pwd=abcd");
        return discovery;
    }

    private static ResourceDiscoveryResult refreshedDiscovery(Long id) {
        ResourceDiscoveryResult discovery = discovery(id);
        discovery.setResourceLinkId(200L);
        return discovery;
    }

    private static MovieMetadata movie() {
        MovieMetadata movie = new MovieMetadata();
        movie.setId("movie-1");
        movie.setTitleCn("测试影片");
        movie.setCategory("movie");
        return movie;
    }
}