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
    @Test
    void automaticBatchResumesRemainingItemsAndPersistsNextPage() throws Exception {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        properties.getGying().setAutoSyncPage(2);
        properties.getGying().setAutoSyncEndPage(6);
        properties.getGying().setAutoSyncMaxItems(15);
        properties.getGying().setDiscoveryEnabled(false);
        var tasks = mock(IResourceHubTaskService.class);
        var workflow = mock(GyingSourceWorkflowService.class);
        ObjectMapper mapper = new ObjectMapper();
        var planner = new MetadataCrawlPlanner(tasks, mapper);
        ResourceHubTask previous = new ResourceHubTask();
        previous.setStatus("SUCCEEDED");
        previous.setPayload(planner.attach("{\"page\":2,\"maxItems\":15}",
                new MetadataCrawlPlanner.Position(2, 6, 2, 0, "PENDING", null)));
        planner.completed(previous, 30, 15, 0);
        when(tasks.getOne(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(previous);
        when(tasks.enqueue(any())).thenAnswer(call -> call.getArgument(0));
        var service = new GyingMetadataSyncServiceImpl(properties, tasks, workflow,
                mock(IResourceDiscoveryService.class), mock(IResourceLinkService.class), mapper);
        ResourceHubTask task = service.enqueueAutomatic("HITS_MOVIE");
        task.setId(20L);
        when(tasks.getById(20L)).thenReturn(task);
        when(workflow.syncCatalogMetadata("HITS_MOVIE", 2, 15, 15)).thenReturn(Map.of(
                "pageSize", 30, "processed", 15, "inserted", 1, "linked", 14, "failed", 0));
        assertEquals("SUCCEEDED", service.runTask(20L).getStatus());
        var checkpoint = mapper.readTree(task.getPayload()).path("crawl");
        assertEquals(3, checkpoint.path("nextPage").asInt());
        assertEquals(0, checkpoint.path("nextOffset").asInt());
        verify(workflow).syncCatalogMetadata("HITS_MOVIE", 2, 15, 15);
        verify(tasks, org.mockito.Mockito.times(2)).updateById(task);
    }
}
