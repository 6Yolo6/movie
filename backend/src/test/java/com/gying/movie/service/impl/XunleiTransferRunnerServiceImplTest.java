package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gying.movie.client.XunleiClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IXunleiTransferTaskService;
import org.junit.jupiter.api.Test;

class XunleiTransferRunnerServiceImplTest {

    @Test
    void isolatesEachTransferInItsOwnFolder() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getXunlei().setSavePath("/GYing Resource Hub");
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(42L);
        task.setMovieId("tmdb:movie/123");

        assertEquals(
                "/GYing Resource Hub",
                XunleiTransferRunnerServiceImpl.transferPath(properties, task));
    }

    @Test
    void skipsAlreadySucceededTask() {
        ResourceHubProperties properties = new ResourceHubProperties();
        XunleiClient client = mock(XunleiClient.class);
        IXunleiTransferTaskService taskService = mock(IXunleiTransferTaskService.class);
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(2L);
        task.setStatus("SUCCEEDED");
        task.setShareUrl("https://pan.xunlei.com/s/share?pwd=code");
        when(taskService.getById(2L)).thenReturn(task);
        XunleiTransferRunnerServiceImpl service = new XunleiTransferRunnerServiceImpl(
                properties,
                client,
                taskService,
                mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class));

        QuarkTransferRunResult result = service.submitOne(2L);

        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getFailed());
        verifyNoInteractions(client);
    }
}
