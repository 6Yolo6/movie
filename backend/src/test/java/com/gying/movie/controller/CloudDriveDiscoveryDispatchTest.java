package com.gying.movie.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.service.*;
import com.gying.movie.utils.AuthHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class CloudDriveDiscoveryDispatchTest {
    final ResourceHubProperties properties = new ResourceHubProperties();
    final IResourceDiscoveryResultService discoveries = mock(IResourceDiscoveryResultService.class);
    final IQuarkTransferTaskService quarkTasks = mock(IQuarkTransferTaskService.class);
    final IXunleiTransferTaskService xunleiTasks = mock(IXunleiTransferTaskService.class);
    final IQuarkTransferRunnerService quark = mock(IQuarkTransferRunnerService.class);
    final IXunleiTransferRunnerService xunlei = mock(IXunleiTransferRunnerService.class);
    final IMovieMetadataService movies = mock(IMovieMetadataService.class);
    final IResourceHubPublishService publish = mock(IResourceHubPublishService.class);
    final ResourceHubAdminController controller = new ResourceHubAdminController(mock(AuthHelper.class), properties,
            null, discoveries, quarkTasks, xunleiTasks, null, null, quark, xunlei, publish, null, null, null, null, null,
            movies, null, null, null, null);
    @AfterEach void close() { controller.shutdownDiscoveryPipelineExecutor(); }

    @ParameterizedTest @ValueSource(strings = {"QUARK", "XUNLEI"})
    void discoveryTransferDispatchesItsOwnProviderAndChecksThatProvidersShare(String provider) {
        properties.setEnabled(true); properties.getXunlei().setEnabled(true);
        var discovery = discovery(provider);
        var quarkTask = new QuarkTransferTask(); quarkTask.setId(7L);
        var xunleiTask = new XunleiTransferTask(); xunleiTask.setId(8L);
        when(quarkTasks.getOne(any(), eq(false))).thenReturn(quarkTask);
        when(xunleiTasks.getOne(any(), eq(false))).thenReturn(xunleiTask);
        when(quarkTasks.getById(7L)).thenReturn(quarkTask); when(xunleiTasks.getById(8L)).thenReturn(xunleiTask);
        when(quark.submitOne(7L)).thenAnswer(call -> { quarkTask.setShareUrl("https://pan.quark.cn/s/own"); return new QuarkTransferRunResult(); });
        when(xunlei.submitOne(8L)).thenAnswer(call -> { xunleiTask.setShareUrl("https://pan.xunlei.com/s/own"); return new QuarkTransferRunResult(); });
        Object result = ReflectionTestUtils.invokeMethod(controller, "submitDiscoveryTransfer", discovery);
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(result, "hasOwnShare"));
        if ("QUARK".equals(provider)) {
            verify(quark).submitOne(7L); verify(quarkTasks).getById(7L); verifyNoInteractions(xunleiTasks, xunlei);
        } else {
            verify(xunlei).submitOne(8L); verify(xunleiTasks).getById(8L); verifyNoInteractions(quarkTasks, quark);
        }
        verifyNoInteractions(publish);
    }

    @ParameterizedTest @ValueSource(strings = {"QUARK", "XUNLEI", "BAIDU"})
    void retryAndTransferDispatchRejectDisabledOrPlannedProviderBeforeCreatingOrUpdatingTasks(String provider) {
        properties.getQuark().setTransferEnabled(false); properties.getXunlei().setEnabled(false);
        var discovery = discovery(provider); when(discoveries.getById(11L)).thenReturn(discovery);
        var retryError = assertThrows(ResponseStatusException.class, () -> controller.retryShareAndPublish(11L, "Bearer fixture"));
        assertEquals(409, retryError.getStatusCode().value());
        var dispatchError = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(controller, "submitDiscoveryTransfer", discovery));
        assertEquals(409, dispatchError.getStatusCode().value());
        verifyNoInteractions(quarkTasks, xunleiTasks, quark, xunlei, movies, publish);
        verify(discoveries, never()).updateById(any(ResourceDiscoveryResult.class));
        assertEquals("DISCOVERED", discovery.getStatus());
    }

    private ResourceDiscoveryResult discovery(String provider) {
        var discovery = new ResourceDiscoveryResult(); discovery.setId(11L); discovery.setMovieId("movie");
        discovery.setProvider(provider); discovery.setTitle("测试电影"); discovery.setStatus("DISCOVERED");
        discovery.setOriginalUrl("https://example.invalid/source"); discovery.setOriginalUrlHash("source-hash"); return discovery;
    }
}
