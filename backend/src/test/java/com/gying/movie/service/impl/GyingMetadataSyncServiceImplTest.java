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
    void successfulCatalogSyncDoesNotEnqueueCloudDiscoveryEvenWhenLegacyFlagIsEnabled() {
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
        assertEquals(0, result.getDiscoveryTasksCreated());
        org.mockito.Mockito.verifyNoInteractions(discoveryService, resourceLinkService);
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
                "pageSize", 30, "processed", 15, "inserted", 1, "linked", 14, "failed", 0,
                "directResourceLinks", 24, "directResourceFailures", 0));
        assertEquals("SUCCEEDED", service.runTask(20L).getStatus());
        var checkpoint = mapper.readTree(task.getPayload()).path("crawl");
        assertEquals(24, mapper.readTree(task.getPayload()).path("directResourceLinks").asInt());
        assertEquals(0, mapper.readTree(task.getPayload()).path("directResourceFailures").asInt());
        assertEquals(3, checkpoint.path("nextPage").asInt());
        assertEquals(0, checkpoint.path("nextOffset").asInt());
        verify(workflow).syncCatalogMetadata("HITS_MOVIE", 2, 15, 15);
        verify(tasks, org.mockito.Mockito.times(2)).updateById(task);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void p2pFailureFailsTaskAndKeepsAutomaticBatchForRetry(boolean automatic) throws Exception {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        var tasks = mock(IResourceHubTaskService.class);
        var workflow = mock(GyingSourceWorkflowService.class);
        var discovery = mock(IResourceDiscoveryService.class);
        var links = mock(IResourceLinkService.class);
        ObjectMapper mapper = new ObjectMapper();
        MetadataCrawlPlanner planner = new MetadataCrawlPlanner(tasks, mapper);
        ResourceHubTask task = new ResourceHubTask();
        task.setId(21L); task.setTaskType("METADATA_SYNC"); task.setSource("GYING");
        String payload = "{\"source\":\"HITS_MOVIE\",\"page\":2,\"maxItems\":15}";
        task.setPayload(automatic ? planner.attach(payload,
                new MetadataCrawlPlanner.Position(2, 6, 2, 15, "PENDING", null)) : payload);
        when(tasks.getById(21L)).thenReturn(task);
        Map<String, Object> synced = Map.of("pageSize", 30, "processed", 15, "inserted", 1,
                "linked", 14, "failed", 0, "directResourceLinks", 10, "directResourceFailures", 1,
                "errors", List.of("NEW1 P2P: fixture persistence failed"));
        if (automatic) when(workflow.syncCatalogMetadata("HITS_MOVIE", 2, 15, 15)).thenReturn(synced);
        else when(workflow.syncCatalogMetadata("HITS_MOVIE", 2, 15)).thenReturn(synced);
        var service = new GyingMetadataSyncServiceImpl(properties, tasks, workflow, discovery, links, mapper);
        var result = service.runTask(21L);
        assertEquals("FAILED", result.getStatus());
        assertEquals(15, result.getProcessed());
        assertEquals(1, result.getInserted());
        assertEquals(0, result.getFailed()); // Metadata is retained; P2P failures are a separate audit.
        assertEquals(List.of("NEW1 P2P: fixture persistence failed"), result.getErrors());
        assertEquals("1 GYING P2P item(s) failed; metadata saved", task.getLastError());
        var audit = mapper.readTree(task.getPayload());
        assertEquals(10, audit.path("directResourceLinks").asInt());
        assertEquals(1, audit.path("directResourceFailures").asInt());
        if (automatic) {
            org.junit.jupiter.api.Assertions.assertFalse(audit.path("crawl").has("nextPage"));
            assertEquals(1, audit.path("crawl").path("failed").asInt());
            when(tasks.getOne(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(task);
            var next = planner.next("GYING", "HITS_MOVIE", 2, 6);
            assertEquals(2, next.page());
            assertEquals(15, next.offset());
        }
        org.mockito.Mockito.verifyNoInteractions(discovery, links);
    }
}
