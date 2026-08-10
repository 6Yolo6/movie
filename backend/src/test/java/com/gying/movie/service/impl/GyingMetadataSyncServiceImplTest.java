package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceHubTaskService;
import com.gying.movie.service.IResourceLinkService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GyingMetadataSyncServiceImplTest {

    @Test
    void successfulCatalogSyncEnqueuesWebsitePublicationDiscovery() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        properties.getGying().setDiscoveryEnabled(true);
        IResourceHubTaskService taskService = mock(IResourceHubTaskService.class);
        GyingSourceWorkflowService workflowService = mock(GyingSourceWorkflowService.class);
        IResourceDiscoveryService discoveryService = mock(IResourceDiscoveryService.class);
        IResourceLinkService resourceLinkService = mock(IResourceLinkService.class);
        GyingMetadataSyncServiceImpl service = new GyingMetadataSyncServiceImpl(
                properties,
                taskService,
                workflowService,
                discoveryService,
                resourceLinkService,
                new ObjectMapper());

        ResourceHubTask task = new ResourceHubTask();
        task.setId(100L);
        task.setTaskType("METADATA_SYNC");
        task.setSource("GYING");
        task.setPayload("{\"source\":\"HITS_MOVIE\",\"page\":1,\"maxItems\":10}");
        when(taskService.getById(task.getId())).thenReturn(task);
        when(workflowService.syncCatalogMetadata("HITS_MOVIE", 1, 10)).thenReturn(Map.of(
                "processed", 1,
                "inserted", 1,
                "linked", 0,
                "failed", 0,
                "movieIds", List.of("gying_mv_NEW1"),
                "errors", List.of()));
        when(resourceLinkService.count(any())).thenReturn(0L);
        when(taskService.count(any())).thenReturn(0L);

        var result = service.runTask(task.getId());

        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(1, result.getDiscoveryTasksCreated());
        ArgumentCaptor<ResourceDiscoveryRequest> request = ArgumentCaptor.forClass(ResourceDiscoveryRequest.class);
        verify(discoveryService).enqueue(request.capture());
        assertEquals("gying_mv_NEW1", request.getValue().getMovieId());
        assertEquals("AUTO", request.getValue().getSource());
    }
}
