package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.*;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.*;
import com.gying.movie.service.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class P2pArchiveTaskServiceTest {
    ResourceHubProperties properties = new ResourceHubProperties();
    IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    IResourceLinkService links = mock(IResourceLinkService.class);
    IMovieMetadataService movies = mock(IMovieMetadataService.class);
    IQuarkTransferTaskService quark = mock(IQuarkTransferTaskService.class);
    IXunleiTransferTaskService xunlei = mock(IXunleiTransferTaskService.class);
    GyingSourceClient source = mock(GyingSourceClient.class);
    P2pCloudArchiveClient cloud = mock(P2pCloudArchiveClient.class);
    ObjectMapper mapper = new ObjectMapper();
    P2pArchiveTaskService service;
    @BeforeEach void setup() {
        properties.setEnabled(true); properties.getWorker().setEnabled(true);
        properties.getXunlei().setShareEnabled(true);
        service = new P2pArchiveTaskService(properties, tasks, links, movies, quark, xunlei, source, cloud, mapper);
    }
    List<ResourceLink> selection() {
        List<ResourceLink> selected = new ArrayList<>();
        for (String type : List.of("MAGNET", "TORRENT")) {
            ResourceLink link = new ResourceLink(); link.setId((long) selected.size() + 1); link.setMovieId("movie1");
            link.setType(type); link.setProvider("P2P"); link.setSource("GYING"); link.setSourceRef("BT1");
            link.setQuality("1080P"); link.setSubtitle("中文字幕（来源标注）"); link.setStatus("ACTIVE"); link.setAuditStatus(1);
            link.setUrl(type.equals("MAGNET") ? "magnet:?xt=urn:btih:" + "a".repeat(40) : "https://example.invalid/dbt/BT1/ticket");
            selected.add(link);
        }
        return selected;
    }
    @Test void onlyTwoQualitiesWithAtLeastOneChineseVersionAreAllowed() {
        var selected = selection(); P2pArchiveTaskService.validateSelection("movie1", selected);
        selected.forEach(link -> link.setSubtitle("国语"));
        assertThrows(IllegalArgumentException.class, () -> P2pArchiveTaskService.validateSelection("movie1", selected));
        selected.get(0).setSubtitle("中文字幕"); selected.get(0).setQuality("720P");
        assertThrows(IllegalArgumentException.class, () -> P2pArchiveTaskService.validateSelection("movie1", selected));
        assertThrows(IllegalArgumentException.class, () -> P2pArchiveTaskService.validateSelection("other", selection()));
        var duplicate = new ArrayList<>(selection()); duplicate.add(duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> P2pArchiveTaskService.validateSelection("movie1", duplicate));
    }
    @Test void changingTorrentTicketDoesNotCreateNewArchiveIdentity() {
        var selected = selection(); String before = P2pArchiveTaskService.fingerprint(selected);
        selected.get(1).setUrl("https://example.invalid/dbt/BT1/rotated-ticket");
        assertEquals(before, P2pArchiveTaskService.fingerprint(selected));
    }
    @Test void enqueueCreatesProviderIsolatedJobsWithoutDoingCloudWork() {
        when(tasks.enqueue(any())).thenAnswer(call -> { ResourceHubTask task = call.getArgument(0); task.setId(10L); return task; });
        service.enqueue("movie1", "mv", "MID1", selection());
        var created = ArgumentCaptor.forClass(ResourceHubTask.class); verify(tasks, times(2)).enqueue(created.capture());
        assertEquals(List.of("QUARK", "XUNLEI"), created.getAllValues().stream().map(ResourceHubTask::getSource).toList());
        assertTrue(created.getAllValues().stream().allMatch(task -> "P2P_ARCHIVE".equals(task.getTaskType()) && task.getMaxAttempts() == 3));
        verifyNoInteractions(cloud, source, quark, xunlei);
        when(tasks.getOne(any(), eq(false))).thenReturn(created.getValue());
        service.enqueue("movie1", "mv", "MID1", selection());
        verify(tasks, times(2)).enqueue(any());
    }
    ResourceHubTask runnableTask() throws Exception {
        var selected = selection();
        selected.forEach(link -> when(links.getById(link.getId())).thenReturn(link));
        MovieMetadata movie = new MovieMetadata(); movie.setId("movie1"); movie.setTitleCn("测试电影"); movie.setCategory("mv");
        when(movies.getById("movie1")).thenReturn(movie);
        when(tasks.update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        when(tasks.updateById(any(ResourceHubTask.class))).thenReturn(true);
        when(links.save(any(ResourceLink.class))).thenAnswer(call -> { ((ResourceLink) call.getArgument(0)).setId(50L); return true; });
        when(cloud.ensureFolder(eq("QUARK"), any(), anyString())).thenReturn("folder1");
        when(cloud.upload(eq("QUARK"), eq("folder1"), any())).thenReturn("fid1", "fid2");
        when(cloud.createShare(eq("QUARK"), eq("folder1"), anyString())).thenReturn("https://pan.quark.cn/s/fixture");
        byte[] data = "d4:infod6:pieces20:xxxxxxxxxxxxxxxxxxxxee".getBytes(StandardCharsets.UTF_8);
        when(source.get("/torrent-file/mv/MID1/BT1")).thenReturn(Map.of("infoHash", "a".repeat(40),
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),
                "dataBase64", Base64.getEncoder().encodeToString(data)));
        ResourceHubTask task = new ResourceHubTask(); task.setId(11L); task.setTaskType("P2P_ARCHIVE"); task.setMovieId("movie1");
        task.setSource("QUARK"); task.setStatus("PENDING"); task.setAttempts(0); task.setMaxAttempts(3);
        task.setPayload(mapper.writeValueAsString(Map.of("typeCode", "mv", "mid", "MID1", "resourceIds", List.of(1, 2),
                "fingerprint", P2pArchiveTaskService.fingerprint(selected))));
        return task;
    }
    @Test void shareIsPublishedOnlyAfterAllMetadataFilesAreConfirmed() throws Exception {
        ResourceHubTask task = runnableTask(); service.runTask(task);
        assertEquals("SUCCEEDED", task.getStatus());
        var saved = ArgumentCaptor.forClass(ResourceLink.class); verify(links).save(saved.capture());
        assertEquals("TORRENT", saved.getValue().getType()); assertEquals("QUARK", saved.getValue().getProvider());
        assertEquals("GYING_P2P_ARCHIVE", saved.getValue().getSource()); assertTrue(saved.getValue().getName().contains("不含视频"));
        var order = inOrder(cloud, links);
        order.verify(cloud).ensureFolder(eq("QUARK"), any(), anyString());
        order.verify(cloud, times(2)).upload(eq("QUARK"), eq("folder1"), any());
        order.verify(cloud).createShare(eq("QUARK"), eq("folder1"), anyString()); order.verify(links).save(any(ResourceLink.class));
        verify(quark, never()).save(any()); verify(xunlei, never()).save(any());
    }
    @Test void partialUploadFailurePreservesCheckpointAndNeverPublishes() throws Exception {
        ResourceHubTask task = runnableTask();
        when(cloud.upload(eq("QUARK"), eq("folder1"), any())).thenReturn("fid1").thenThrow(new IllegalStateException("fixture failure"));
        service.runTask(task);
        assertEquals("FAILED", task.getStatus()); assertEquals(1, task.getAttempts());
        assertEquals("UPLOAD_TORRENT", mapper.readTree(task.getPayload()).path("stage").asText());
        assertEquals(1, mapper.readTree(task.getPayload()).path("uploadedFiles").size());
        verify(cloud, never()).createShare(anyString(), anyString(), anyString()); verify(links, never()).save(any());
    }
    @Test void malformedTorrentStopsBeforeCreatingCloudFolder() throws Exception {
        ResourceHubTask task = runnableTask(); when(source.get(anyString())).thenReturn(Map.of("infoHash", "b".repeat(40), "dataBase64", "ZA==", "sha256", "fixture"));
        service.runTask(task); assertEquals("FAILED", task.getStatus()); verifyNoInteractions(cloud);
    }
    @Test void schedulerHonorsWorkerDisabledAndRejectsLegacyTasks() {
        properties.getWorker().setEnabled(false); service.runDue(); verifyNoInteractions(tasks, cloud);
        ResourceHubTask task = new ResourceHubTask(); task.setTaskType("RESOURCE_DISCOVERY");
        assertThrows(IllegalArgumentException.class, () -> service.runTask(task));
    }
}
