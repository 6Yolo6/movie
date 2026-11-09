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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class P2pArchiveTaskServiceTest {
    ResourceHubProperties properties = new ResourceHubProperties();
    IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    IResourceLinkService links = mock(IResourceLinkService.class);
    IMovieMetadataService movies = mock(IMovieMetadataService.class);
    IQuarkTransferTaskService quark = mock(IQuarkTransferTaskService.class);
    IXunleiTransferTaskService xunlei = mock(IXunleiTransferTaskService.class);
    IBaiduTransferTaskService baidu = mock(IBaiduTransferTaskService.class);
    GyingSourceClient source = mock(GyingSourceClient.class);
    P2pCloudArchiveClient cloud = mock(P2pCloudArchiveClient.class);
    ObjectMapper mapper = new ObjectMapper();
    P2pArchiveTaskService service;
    @BeforeEach void setup() {
        properties.setEnabled(true); properties.getWorker().setEnabled(true);
        properties.getXunlei().setShareEnabled(true);
        when(cloud.isConfigured(anyString())).thenReturn(true);
        service = new P2pArchiveTaskService(properties, tasks, links, movies, quark, xunlei, baidu, source, cloud, mapper);
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
        when(tasks.update(any(ResourceHubTask.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
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
    @Test void successfulRetryExplicitlyClearsThePersistedFailureAudit() throws Exception {
        ResourceHubTask task = runnableTask(); task.setStatus("FAILED"); task.setAttempts(1);
        task.setLastError("Previous metadata upload failed");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("errorCategory", "IllegalStateException"); task.setPayload(payload.toString());
        service.runTask(task);
        assertEquals("SUCCEEDED", task.getStatus()); assertEquals(2, task.getAttempts());
        assertNull(task.getLastError()); assertFalse(mapper.readTree(task.getPayload()).has("errorCategory"));
        var update = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(tasks).update(same(task), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("last_error="));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(null));
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
    @Test void internalTorrentUsesMagnetIdAndPreservesLegacyFingerprint() throws Exception {
        List<ResourceLink> legacy = selection();
        List<ResourceLink> selected = List.of(legacy.get(0), P2pArchiveTaskService.torrentDescriptor(legacy.get(0)));
        assertEquals(P2pArchiveTaskService.fingerprint(legacy), P2pArchiveTaskService.fingerprint(selected));
        when(tasks.enqueue(any())).thenAnswer(call -> { ResourceHubTask task = call.getArgument(0); task.setId(10L); return task; });
        service.enqueue("movie1", "mv", "MID1", selected);
        var created = ArgumentCaptor.forClass(ResourceHubTask.class);
        verify(tasks, times(2)).enqueue(created.capture());
        var payload = mapper.readTree(created.getValue().getPayload());
        assertEquals(2, payload.path("version").asInt());
        assertEquals("[1]", payload.path("resourceIds").toString());
        assertEquals("[1]", payload.path("torrentMagnetIds").toString());
        assertFalse(created.getValue().getPayload().contains("/dbt"));
        assertFalse(created.getValue().getPayload().contains("ticket"));
        verifyNoInteractions(cloud, source);
    }

    ResourceHubTask versionTwoTask() throws Exception {
        ResourceHubTask task = runnableTask();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("version", 2);
        payload.putArray("resourceIds").add(1);
        payload.putArray("torrentMagnetIds").add(1);
        task.setPayload(payload.toString());
        return task;
    }

    @Test void newQueueArchivesRealTorrentWithoutLoadingLegacyTorrentRow() throws Exception {
        ResourceHubTask task = versionTwoTask(); service.runTask(task);
        assertEquals("SUCCEEDED", task.getStatus());
        verify(links, never()).getById(2L);
        verify(source).get("/torrent-file/mv/MID1/BT1");
        var files = ArgumentCaptor.forClass(P2pCloudArchiveClient.FileData.class);
        verify(cloud, times(2)).upload(eq("QUARK"), eq("folder1"), files.capture());
        assertEquals(List.of(".txt", ".torrent"), files.getAllValues().stream()
                .map(file -> file.name().substring(file.name().lastIndexOf('.'))).toList());
        assertFalse(task.getPayload().contains("/dbt/"));
    }

    @Test void missingTorrentPairFailsBeforeAnyCloudWork() throws Exception {
        ResourceHubTask task = versionTwoTask();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.putArray("torrentMagnetIds").add(99); task.setPayload(payload.toString());
        service.runTask(task);
        assertEquals("FAILED", task.getStatus()); verifyNoInteractions(cloud, source);
        var unpaired = List.of(P2pArchiveTaskService.torrentDescriptor(selection().get(0)));
        assertThrows(IllegalArgumentException.class, () -> service.enqueue("movie1", "mv", "MID1", unpaired));
    }

    @Test void replacedMagnetCancelsNewQueueBeforeDownloadingOrUploading() throws Exception {
        ResourceHubTask task = versionTwoTask();
        ResourceLink replaced = selection().get(0); replaced.setUrl("magnet:?xt=urn:btih:" + "b".repeat(40));
        when(links.getById(1L)).thenReturn(replaced);
        service.runTask(task);
        assertEquals("CANCELED", task.getStatus()); verifyNoInteractions(cloud, source);
    }

    @Test void legacyQueueCancelsWhenOnlyMagnetSlotWasReplaced() throws Exception {
        ResourceHubTask task = runnableTask();
        ResourceLink replaced = selection().get(0); replaced.setSourceRef("BTNEW");
        replaced.setUrl("magnet:?xt=urn:btih:" + "b".repeat(40));
        when(links.getById(1L)).thenReturn(replaced);
        service.runTask(task);
        assertEquals("CANCELED", task.getStatus()); verifyNoInteractions(cloud, source);
    }

    @Test void schedulerHonorsWorkerDisabledAndRejectsLegacyTasks() {
        properties.getWorker().setEnabled(false); service.runDue(); verifyNoInteractions(tasks, cloud);
        ResourceHubTask task = new ResourceHubTask(); task.setTaskType("RESOURCE_DISCOVERY");
        assertThrows(IllegalArgumentException.class, () -> service.runTask(task));
    }
    void enableBaidu() {
        properties.getBaidu().setDiscoveryEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true);
        properties.getBaidu().setSavePath("/Baidu Root");
    }
    @Test void allThreeDrivesCanArchiveTorrentsWithVideoCollectionDisabled() {
        enableBaidu(); properties.getQuark().setVideoEnabled(false); properties.getXunlei().setVideoEnabled(false);
        when(tasks.enqueue(any())).thenAnswer(call -> { ResourceHubTask task = call.getArgument(0); task.setId(10L); return task; });
        service.enqueue("movie1", "mv", "MID1", selection());
        var created = ArgumentCaptor.forClass(ResourceHubTask.class); verify(tasks, times(3)).enqueue(created.capture());
        assertEquals(List.of("QUARK", "XUNLEI", "BAIDU"), created.getAllValues().stream().map(ResourceHubTask::getSource).toList());
        verifyNoInteractions(cloud, source, quark, xunlei, baidu);
    }
    @Test void enqueueHonorsEachDrivesDiscoveryAndTorrentSwitch() {
        enableBaidu(); properties.getQuark().setTorrentEnabled(false); properties.getXunlei().setDiscoveryEnabled(false);
        when(tasks.enqueue(any())).thenAnswer(call -> { ResourceHubTask task = call.getArgument(0); task.setId(10L); return task; });
        service.enqueue("movie1", "mv", "MID1", selection());
        var created = ArgumentCaptor.forClass(ResourceHubTask.class); verify(tasks).enqueue(created.capture());
        assertEquals("BAIDU", created.getValue().getSource());
        properties.getBaidu().setTorrentEnabled(false); clearInvocations(tasks);
        service.enqueue("movie1", "mv", "MID1", selection()); verifyNoInteractions(tasks, cloud, source);
    }
    ResourceHubTask queuedTask(String provider) {
        ResourceHubTask task = new ResourceHubTask(); task.setId(99L); task.setTaskType("P2P_ARCHIVE");
        task.setMovieId("movie1"); task.setSource(provider); task.setStatus("FAILED"); task.setAttempts(2);
        task.setLastError("previous upload failure"); task.setPayload("{\"folderId\":\"existing\",\"uploadedFiles\":{}}");
        return task;
    }
    @Test void disabledTorrentQueuesKeepExistingCheckpointsAndRetryBudgets() {
        enableBaidu(); properties.getXunlei().setEnabled(true);
        properties.getQuark().setTorrentEnabled(false); properties.getXunlei().setTorrentEnabled(false);
        properties.getBaidu().setTorrentEnabled(false);
        for (String provider : List.of("QUARK", "XUNLEI", "BAIDU")) {
            ResourceHubTask task = queuedTask(provider); String before = task.getPayload(); service.runTask(task);
            assertEquals("FAILED", task.getStatus()); assertEquals(2, task.getAttempts());
            assertEquals("previous upload failure", task.getLastError()); assertEquals(before, task.getPayload());
        }
        service.runDue(); service.recoverInterruptedArchives(); verifyNoInteractions(tasks, links, movies, cloud, source, quark, xunlei, baidu);
    }
    @Test void disabledTransferQueuesKeepExistingCheckpointsAndRetryBudgets() {
        enableBaidu(); properties.getQuark().setTransferEnabled(false); properties.getXunlei().setEnabled(false);
        properties.getBaidu().setEnabled(false);
        for (String provider : List.of("QUARK", "XUNLEI", "BAIDU")) {
            ResourceHubTask task = queuedTask(provider); String before = task.getPayload(); service.runTask(task);
            assertEquals("FAILED", task.getStatus()); assertEquals(2, task.getAttempts()); assertEquals(before, task.getPayload());
        }
        service.runDue(); service.recoverInterruptedArchives(); verifyNoInteractions(tasks, links, movies, cloud, source, quark, xunlei, baidu);
    }
    @Test void schedulerFiltersClosedProvidersBeforeItsLimitSoTheyCannotStarveBaidu() {
        enableBaidu(); properties.getQuark().setTorrentEnabled(false); properties.getXunlei().setEnabled(false);
        service.runDue();
        var query = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(tasks).list(query.capture()); assertTrue(query.getValue().getSqlSegment().contains("source IN"));
        var values = query.getValue().getParamNameValuePairs().values();
        assertTrue(values.contains("BAIDU")); assertFalse(values.contains("QUARK")); assertFalse(values.contains("XUNLEI"));
        verify(cloud).isConfigured("BAIDU"); verifyNoMoreInteractions(cloud); verifyNoInteractions(source, quark, xunlei, baidu);
    }
    @Test void unknownArchiveProviderIsRejectedBeforeClaimingTheTask() {
        for (String provider : Arrays.asList(null, "OTHER", "baidu")) {
            ResourceHubTask task = queuedTask(provider);
            assertThrows(IllegalArgumentException.class, () -> service.runTask(task)); assertEquals(2, task.getAttempts());
        }
        verifyNoInteractions(tasks, cloud, source, quark, xunlei, baidu);
    }
    ResourceHubTask baiduTask() throws Exception {
        ResourceHubTask task = versionTwoTask(); task.setSource("BAIDU"); enableBaidu();
        String folder = "/Baidu Root/mv/测试电影（movie1）/磁力种子";
        when(cloud.ensureFolder(eq("BAIDU"), any(), anyString())).thenReturn(folder);
        when(cloud.upload(eq("BAIDU"), eq(folder), any())).thenReturn("101", "102");
        when(cloud.findUploadedFile(eq("BAIDU"), eq(folder), any())).thenAnswer(call ->
                ((P2pCloudArchiveClient.FileData) call.getArgument(2)).name().endsWith(".txt") ? "101" : "102");
        when(cloud.createShare(eq("BAIDU"), eq(folder), anyString())).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        when(cloud.findOwnShare(eq("BAIDU"), eq(folder))).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        return task;
    }
    @Test void baiduPublishesOnlyItsMetadataShareWithBothFileKindsAndOwnPath() throws Exception {
        ResourceHubTask task = baiduTask(); service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        verify(cloud).ensureFolder("BAIDU", null, "/Baidu Root/mv/测试电影（movie1）");
        var files = ArgumentCaptor.forClass(P2pCloudArchiveClient.FileData.class);
        verify(cloud, times(2)).upload(eq("BAIDU"), anyString(), files.capture());
        assertEquals(List.of(".txt", ".torrent"), files.getAllValues().stream()
                .map(file -> file.name().substring(file.name().lastIndexOf('.'))).toList());
        var saved = ArgumentCaptor.forClass(ResourceLink.class); verify(links).save(saved.capture());
        assertEquals("BAIDU", saved.getValue().getProvider()); assertEquals("TORRENT", saved.getValue().getType());
        assertEquals("https://pan.baidu.com/s/1fixture?pwd=abcd", saved.getValue().getUrl());
        assertEquals("GYING_P2P_ARCHIVE", saved.getValue().getSource());
        verifyNoInteractions(quark, xunlei); verify(baidu, never()).save(any());
    }
    @Test void baiduIgnoresAnUnclassifiedHistoricalFolderWhenCreatingANewMetadataFolder() throws Exception {
        ResourceHubTask task = baiduTask();
        BaiduTransferTask previous = new BaiduTransferTask(); previous.setSavedPath("/Previously Saved/Movie");
        when(baidu.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(previous));
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        verify(cloud).ensureFolder("BAIDU", null, "/Baidu Root/mv/测试电影（movie1）");
        verifyNoInteractions(quark, xunlei);
    }
    @Test void baiduCannotPublishAnotherProvidersShareIncludingPersistedCheckpoints() throws Exception {
        ResourceHubTask task = baiduTask();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("shareUrl", "https://pan.xunlei.com/s/fixture"); task.setPayload(payload.toString());
        service.runTask(task); assertEquals("FAILED", task.getStatus());
        verify(cloud, never()).createShare(anyString(), anyString(), anyString()); verify(links, never()).save(any());
    }
    @Test void baiduUncertainShareSubmissionIsDurableAndNeverSubmittedTwice() throws Exception {
        ResourceHubTask task = baiduTask();
        when(cloud.createShare(eq("BAIDU"), anyString(), anyString()))
                .thenThrow(new BaiduActionException("NETWORK_UNCERTAIN", false, true, true));
        when(cloud.findOwnShare(eq("BAIDU"), anyString())).thenReturn(null);
        doAnswer(call -> {
            ResourceHubTask checkpoint = call.getArgument(0);
            if (mapper.readTree(checkpoint.getPayload()).path("baiduShareSubmitting").asBoolean()) {
                verify(cloud, never()).createShare(anyString(), anyString(), anyString());
            }
            return true;
        }).when(tasks).update(any(ResourceHubTask.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        service.runTask(task); assertEquals("FAILED", task.getStatus());
        assertTrue(mapper.readTree(task.getPayload()).path("baiduShareSubmitting").asBoolean());
        service.runTask(task); assertEquals("FAILED", task.getStatus());
        verify(cloud).createShare(eq("BAIDU"), anyString(), anyString());
        verify(cloud).findOwnShare(eq("BAIDU"), anyString()); verify(links, never()).save(any());
    }
    @Test void baiduUncertainShareCanRecoverReadOnlyWhenTheExistingShareAppears() throws Exception {
        ResourceHubTask task = baiduTask();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("baiduShareSubmitting", true); task.setPayload(payload.toString());
        when(cloud.findOwnShare(eq("BAIDU"), anyString())).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        verify(cloud, never()).createShare(anyString(), anyString(), anyString());
        verify(cloud, times(2)).findOwnShare(eq("BAIDU"), anyString()); verify(links).save(any());
        assertFalse(mapper.readTree(task.getPayload()).has("baiduShareSubmitting"));
    }
    @Test void baiduExplicitShareRejectionClearsIntentAndAllowsASafeRetry() throws Exception {
        ResourceHubTask task = baiduTask();
        when(cloud.createShare(eq("BAIDU"), anyString(), anyString()))
                .thenThrow(new BaiduActionException("SHARE_REJECTED", true, false, false))
                .thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        service.runTask(task); assertEquals("FAILED", task.getStatus());
        assertFalse(mapper.readTree(task.getPayload()).has("baiduShareSubmitting"));
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        verify(cloud, times(2)).createShare(eq("BAIDU"), anyString(), anyString());
        verify(cloud).findOwnShare(eq("BAIDU"), anyString());
    }
    @Test void disabledSharingDoesNotClaimOrConsumeExistingQueues() {
        enableBaidu(); properties.getXunlei().setEnabled(true);
        properties.getQuark().setShareEnabled(false); properties.getXunlei().setShareEnabled(false);
        properties.getBaidu().setShareEnabled(false);
        for (String provider : List.of("QUARK", "XUNLEI", "BAIDU")) {
            ResourceHubTask task = queuedTask(provider); service.runTask(task);
            assertEquals("FAILED", task.getStatus()); assertEquals(2, task.getAttempts());
            assertEquals(1, service.submitPending(provider, 5).getSkipped());
        }
        service.runDue(); service.recoverInterruptedArchives();
        verifyNoInteractions(tasks, links, movies, cloud, source, quark, xunlei, baidu);
    }
    @Test void recoveryFiltersClosedProvidersAndNeverResetsTheirRunningTasks() {
        enableBaidu(); properties.getQuark().setTorrentEnabled(false); properties.getXunlei().setEnabled(false);
        service.recoverInterruptedArchives();
        var update = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(tasks).update(update.capture()); assertTrue(update.getValue().getSqlSegment().contains("source IN"));
        var values = update.getValue().getParamNameValuePairs().values();
        assertTrue(values.contains("BAIDU")); assertFalse(values.contains("QUARK")); assertFalse(values.contains("XUNLEI"));
        verify(cloud).isConfigured("BAIDU"); verifyNoMoreInteractions(cloud); verifyNoInteractions(source, quark, xunlei, baidu);
    }
    @Test void explicitBaiduRunHonorsSingleProviderAndClampsLimitWithoutWorker() throws Exception {
        ResourceHubTask task = baiduTask(); properties.getWorker().setEnabled(false);
        ResourceHubTask other = queuedTask("QUARK"); when(tasks.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(other, task));
        var result = service.submitPending("BAIDU", 100);
        assertEquals(1, result.getSubmitted()); assertEquals(1, result.getSkipped()); assertEquals(0, result.getFailed());
        assertEquals("FAILED", other.getStatus()); assertEquals(2, other.getAttempts());
        var query = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(tasks).list(query.capture()); assertTrue(query.getValue().getSqlSegment().contains("LIMIT 20"));
        var values = query.getValue().getParamNameValuePairs().values(); assertTrue(values.contains("BAIDU"));
        assertFalse(values.contains("QUARK")); assertFalse(values.contains("XUNLEI"));
        verify(cloud, never()).upload(eq("QUARK"), anyString(), any()); verifyNoInteractions(quark, xunlei);
    }
    @Test void explicitRunReportsClaimConflictsAsSkippedAndFailuresAsFailed() throws Exception {
        ResourceHubTask task = runnableTask(); when(tasks.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(task));
        when(tasks.update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(false);
        var skipped = service.submitPending("QUARK", 0); assertEquals(1, skipped.getSkipped()); assertEquals(0, skipped.getSubmitted());
        verifyNoInteractions(cloud, source);
        when(tasks.update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        when(cloud.upload(eq("QUARK"), anyString(), any())).thenThrow(new IllegalStateException("fixture"));
        var failed = service.submitPending("QUARK", 1); assertEquals(1, failed.getFailed()); assertEquals(0, failed.getSubmitted());
        assertEquals(1, failed.getErrors().size()); assertTrue(failed.getErrors().get(0).contains("UPLOAD_"));
    }
    @Test void missingBaiduSessionPreservesPendingArchiveAndDoesNotTouchTheCloud() {
        enableBaidu(); properties.getQuark().setTorrentEnabled(false); properties.getXunlei().setTorrentEnabled(false);
        when(cloud.isConfigured("BAIDU")).thenReturn(false);
        ResourceHubTask task = queuedTask("BAIDU"); task.setStatus("PENDING"); String before = task.getPayload();
        service.runTask(task); service.runDue(); service.recoverInterruptedArchives();
        assertEquals(1, service.submitPending("BAIDU", 5).getSkipped());
        assertEquals("PENDING", task.getStatus()); assertEquals(2, task.getAttempts()); assertEquals(before, task.getPayload());
        verify(cloud, times(4)).isConfigured("BAIDU"); verifyNoMoreInteractions(cloud);
        verifyNoInteractions(tasks, links, movies, source, quark, xunlei, baidu);
    }
    @Test void baiduNeverCreatesAMetadataShareWhenTheFolderContainsOtherFiles() throws Exception {
        ResourceHubTask task = baiduTask();
        doThrow(new BaiduActionException("METADATA_FOLDER_INVALID", false, true, false))
                .when(cloud).verifyMetadataOnlyFolder(eq("BAIDU"), anyString());
        service.runTask(task); assertEquals("FAILED", task.getStatus());
        verify(cloud).verifyMetadataOnlyFolder(eq("BAIDU"), anyString());
        verify(cloud, never()).createShare(anyString(), anyString(), anyString());
        verify(cloud, never()).findOwnShare(anyString(), anyString()); verify(links, never()).save(any());
        assertFalse(mapper.readTree(task.getPayload()).has("baiduShareSubmitting"));
    }
    @Test void baiduChecksTheMetadataTreeBeforeReusingOrReconcilingAShare() throws Exception {
        for (boolean uncertain : List.of(false, true)) {
            ResourceHubTask task = baiduTask();
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
            if (uncertain) payload.put("baiduShareSubmitting", true);
            else payload.put("shareUrl", "https://pan.baidu.com/s/1fixture?pwd=abcd");
            task.setPayload(payload.toString());
            doThrow(new BaiduActionException("METADATA_FOLDER_INVALID", false, true, false))
                    .when(cloud).verifyMetadataOnlyFolder(eq("BAIDU"), anyString());
            service.runTask(task); assertEquals("FAILED", task.getStatus());
            assertEquals(uncertain, mapper.readTree(task.getPayload()).path("baiduShareSubmitting").asBoolean());
        }
        verify(cloud, times(2)).verifyMetadataOnlyFolder(eq("BAIDU"), anyString());
        verify(cloud, never()).createShare(anyString(), anyString(), anyString());
        verify(cloud, never()).findOwnShare(anyString(), anyString()); verify(links, never()).save(any());
    }
    @Test void expiredBaiduShareCheckpointNeverPublishesOrCreatesAnotherShare() throws Exception {
        ResourceHubTask task = baiduTask();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("shareUrl", "https://pan.baidu.com/s/1expired?pwd=abcd"); task.setPayload(payload.toString());
        when(cloud.findOwnShare(eq("BAIDU"), anyString())).thenReturn(null);
        service.runTask(task); assertEquals("FAILED", task.getStatus());
        assertEquals("https://pan.baidu.com/s/1expired?pwd=abcd", mapper.readTree(task.getPayload()).path("shareUrl").asText());
        verify(cloud).findOwnShare(eq("BAIDU"), anyString());
        verify(cloud, never()).createShare(anyString(), anyString(), anyString()); verify(links, never()).save(any());
    }
    @Test void baiduPublishesTheCurrentlyVerifiedOwnShareAndPersistsItBeforeTheResource() throws Exception {
        ResourceHubTask task = baiduTask();
        when(cloud.findOwnShare(eq("BAIDU"), anyString())).thenReturn("https://pan.baidu.com/s/1verified?pwd=wxyz");
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        assertEquals("https://pan.baidu.com/s/1verified?pwd=wxyz", mapper.readTree(task.getPayload()).path("shareUrl").asText());
        var saved = ArgumentCaptor.forClass(ResourceLink.class); verify(links).save(saved.capture());
        assertEquals("https://pan.baidu.com/s/1verified?pwd=wxyz", saved.getValue().getUrl());
        var order = inOrder(cloud, links); order.verify(cloud).findOwnShare(eq("BAIDU"), anyString()); order.verify(links).save(any());
    }
    @Test void backfillCloneIgnoresDiscoverySwitchAndDropsAllRemoteCheckpoints() throws Exception {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true); properties.getBaidu().setDiscoveryEnabled(false); properties.getBaidu().setTorrentEnabled(true);
        ResourceHubTask original = runnableTask(); original.setStatus("SUCCEEDED");
        var old = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original.getPayload());
        old.put("folderId", "quark-folder"); old.put("shareUrl", "https://pan.quark.cn/s/old");
        old.put("baiduShareSubmitting", true); old.putObject("uploadedFiles").put("file", "quark-fid");
        original.setPayload(old.toString()); when(tasks.getById(11L)).thenReturn(original);
        when(tasks.enqueue(any())).thenAnswer(call -> { ResourceHubTask task = call.getArgument(0); task.setId(22L); return task; });
        ResourceHubTask clone = service.cloneExistingForProvider(11L, "BAIDU", 100L);
        var clean = mapper.readTree(clone.getPayload());
        assertEquals("BAIDU", clone.getSource()); assertEquals(100, clean.path("backfillParentId").asLong());
        assertFalse(clean.has("folderId")); assertFalse(clean.has("shareUrl")); assertFalse(clean.has("uploadedFiles")); assertFalse(clean.has("baiduShareSubmitting"));
        assertEquals(old.path("resourceIds"), clean.path("resourceIds")); verifyNoInteractions(quark, xunlei, baidu); verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }
    @Test void existingUnknownBaiduArchiveIsReusedWithoutResetOrRemoteWork() throws Exception {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        ResourceHubTask original = runnableTask(); when(tasks.getById(11L)).thenReturn(original);
        ResourceHubTask unknown = new ResourceHubTask(); unknown.setId(22L); unknown.setStatus("UNKNOWN");
        when(tasks.getOne(any(), eq(false))).thenReturn(unknown);
        assertSame(unknown, service.cloneExistingForProvider(11L, "BAIDU", 100L));
        assertEquals("UNKNOWN", unknown.getStatus()); verify(tasks, never()).enqueue(any()); verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }
    @Test void exactSuccessfulBaiduChildVerifiesOnlyItsOwnMetadataFolderAndShare() throws Exception {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        ResourceHubTask child = new ResourceHubTask(); child.setId(22L); child.setTaskType("P2P_ARCHIVE"); child.setSource("BAIDU"); child.setStatus("SUCCEEDED");
        child.setMovieId("movie1"); child.setPayload("{\"folderId\":\"/fixture/metadata\",\"resourceLinkId\":50}"); when(tasks.getById(22L)).thenReturn(child);
        ResourceLink resource = new ResourceLink(); resource.setId(50L); resource.setMovieId("movie1"); resource.setProvider("BAIDU"); resource.setType("TORRENT");
        resource.setSource("GYING_P2P_ARCHIVE"); resource.setStatus("ACTIVE"); when(links.getById(50L)).thenReturn(resource);
        when(links.update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        when(tasks.update(any(ResourceHubTask.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        when(cloud.findOwnShare("BAIDU", "/fixture/metadata")).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        assertSame(child, service.submitOne(22L, "BAIDU"));
        verify(cloud).verifyMetadataOnlyFolder("BAIDU", "/fixture/metadata"); verify(cloud).findOwnShare("BAIDU", "/fixture/metadata");
        verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
        verify(tasks, never()).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertThrows(IllegalArgumentException.class, () -> service.submitOne(22L, "QUARK"));
    }

    @Test void expiredBaiduAccountStopsBackfillArchiveWithoutSpendingRetryBudget() throws Exception {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        ResourceHubTask task = runnableTask(); task.setSource("BAIDU");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload()); payload.put("backfillParentId", 100L); task.setPayload(payload.toString());
        doThrow(new BaiduActionException("SESSION_EXPIRED", false, true, false)).when(cloud).ensureFolder(eq("BAIDU"), any(), anyString());
        when(tasks.getById(task.getId())).thenReturn(task); service.submitOne(task.getId(), "BAIDU", 100L);
        assertEquals("ACTION_REQUIRED", task.getStatus()); assertEquals(0, task.getAttempts());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }

    void assertOwnedBaiduUploadFailure(String code, boolean actionRequired, boolean uncertain, boolean afterMagnet, int initialAttempts) throws Exception {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        ResourceHubTask task = runnableTask(); task.setSource("BAIDU"); task.setAttempts(initialAttempts);
        var original = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        original.put("backfillParentId", 100L); task.setPayload(original.toString());
        var account = mock(BaiduWebClient.class); when(account.isConfigured()).thenReturn(true);
        when(account.p2pFolder(any(), anyString())).thenReturn("/fixture/metadata");
        var failure = new BaiduActionException(code, false, actionRequired, uncertain);
        if (afterMagnet) when(account.p2pUpload(eq("/fixture/metadata"), anyString(), any())).thenReturn("confirmed-magnet").thenThrow(failure);
        else when(account.p2pUpload(eq("/fixture/metadata"), anyString(), any())).thenThrow(failure);
        var adapter = new P2pCloudArchiveClient(mock(QuarkShareClient.class), mock(XunleiClient.class), account, mapper);
        service = new P2pArchiveTaskService(properties, tasks, links, movies, quark, xunlei, baidu, source, adapter, mapper);
        when(tasks.getById(task.getId())).thenReturn(task);
        assertSame(task, service.submitOne(task.getId(), "BAIDU", 100L));
        assertEquals(uncertain ? "UNKNOWN" : "ACTION_REQUIRED", task.getStatus());
        assertEquals(initialAttempts + (actionRequired ? 0 : 1), task.getAttempts());
        var checkpoint = mapper.readTree(task.getPayload());
        assertEquals(afterMagnet ? "UPLOAD_TORRENT" : "UPLOAD_MAGNET_TEXT", checkpoint.path("stage").asText());
        assertEquals("BaiduActionException", checkpoint.path("errorCategory").asText());
        assertEquals(code, checkpoint.path("errorCode").asText()); assertTrue(task.getLastError().contains(code));
        assertEquals(uncertain ? "UNKNOWN" : "REJECTED", checkpoint.path("baiduUploadIntent").path("status").asText());
        assertEquals(code, checkpoint.path("baiduUploadIntent").path("errorCode").asText());
        assertEquals(64, checkpoint.path("baiduUploadIntent").path("sha256").asText().length());
        assertEquals(100, checkpoint.path("backfillParentId").asLong()); assertEquals(original.path("resourceIds"), checkpoint.path("resourceIds"));
        assertEquals(original.path("fingerprint"), checkpoint.path("fingerprint")); assertEquals("/fixture/metadata", checkpoint.path("folderId").asText());
        assertEquals(afterMagnet ? 1 : 0, checkpoint.path("uploadedFiles").size());
        assertFalse(checkpoint.has("baiduShareSubmitting")); assertFalse(checkpoint.has("shareUrl"));
        verify(account, times(afterMagnet ? 2 : 1)).p2pUpload(eq("/fixture/metadata"), anyString(), any());
        verify(account, never()).createOwnShare(anyString()); verify(account, never()).findOwnShare(anyString());
        verify(links, never()).save(any()); verifyNoInteractions(quark, xunlei);
        var location = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(baidu).list(location.capture());
        String locationSql = location.getValue().getSqlSegment();
        assertTrue(locationSql.contains("movie_id") && locationSql.contains("status") && locationSql.contains("saved_path IS NOT NULL") && locationSql.contains("LIMIT 20"));
        assertTrue(location.getValue().getParamNameValuePairs().containsValue("movie1"));
        assertTrue(location.getValue().getParamNameValuePairs().containsValue("SUCCEEDED"));
        verifyNoMoreInteractions(baidu);
    }
    @Test void realBaiduAdapterPreservesUnavailableUploadScopeWithoutSpendingOwnedRetryBudget() throws Exception {
        assertOwnedBaiduUploadFailure("UPLOAD_SESSION_SCOPE_UNAVAILABLE", true, false, false, 0);
    }
    @Test void realBaiduAdapterPreservesExpiredSessionAfterConfirmedMagnetWithoutSpendingOwnedRetryBudget() throws Exception {
        assertOwnedBaiduUploadFailure("SESSION_EXPIRED", true, false, true, 1);
    }
    @Test void realBaiduAdapterMarksUncertainTorrentUploadUnknownAndKeepsConfirmedMagnet() throws Exception {
        assertOwnedBaiduUploadFailure("UPLOAD_OUTCOME_UNKNOWN", false, true, true, 0);
    }

    @Test void explicitBackfillEnqueueCreatesOnlyBaiduEvenWhenAllDiscoverySwitchesAreEnabled() throws Exception {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        when(tasks.enqueue(any())).thenAnswer(call -> { ResourceHubTask task = call.getArgument(0); task.setId(99L); return task; });
        ResourceHubTask child = service.enqueueForProvider("movie1", "mv", "MID1", selection(), "BAIDU", 100L);
        assertEquals("BAIDU", child.getSource()); assertEquals(100, mapper.readTree(child.getPayload()).path("backfillParentId").asLong());
        verify(tasks, times(1)).enqueue(any()); verify(cloud, never()).upload(anyString(), anyString(), any()); verifyNoInteractions(quark, xunlei, baidu, source);
    }
    @Test void genericAndWrongParentExactEntryCannotRunAnOwnedChild() throws Exception {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setShareEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        ResourceHubTask child = runnableTask(); child.setSource("BAIDU");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(child.getPayload()); payload.put("backfillParentId", 100L); child.setPayload(payload.toString());
        when(tasks.getById(child.getId())).thenReturn(child); clearInvocations(tasks, cloud);
        service.runTask(child); service.submitOne(child.getId(), "BAIDU"); service.submitOne(child.getId(), "BAIDU", 101L);
        assertEquals("PENDING", child.getStatus()); assertEquals(0, child.getAttempts());
        verify(tasks, never()).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)); verify(cloud, never()).upload(anyString(), anyString(), any());
    }
    @Test void protectedSeedLookupIncludesUnknownStatesAndShareIntentWithoutMutatingThem() throws Exception {
        ResourceHubTask child = new ResourceHubTask(); child.setId(22L); child.setStatus("UNKNOWN"); child.setPayload("{\"baiduShareSubmitting\":true,\"resourceIds\":[1,2]}");
        when(tasks.getOne(any(), eq(false))).thenReturn(child); String before = child.getPayload();
        assertSame(child, service.protectedArchive("movie1", "BAIDU")); assertEquals(before, child.getPayload());
        var scope = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class); verify(tasks).getOne(scope.capture(), eq(false));
        assertTrue(scope.getValue().getSqlSegment().contains("baiduShareSubmitting")); verify(tasks, never()).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)); verifyNoInteractions(cloud, source);
    }


    @Test void baiduUploadIntentAndSafeErrorCodeSurviveTheFirstRemoteFailure() throws Exception {
        ResourceHubTask task = baiduTask();
        when(cloud.upload(eq("BAIDU"), anyString(), any())).thenAnswer(call -> {
            var file = (P2pCloudArchiveClient.FileData) call.getArgument(2);
            var saved = mapper.readTree(task.getPayload());
            assertEquals("UPLOAD_MAGNET_TEXT", saved.path("stage").asText());
            assertEquals(file.name(), saved.path("baiduUploadIntent").path("name").asText());
            assertEquals(file.bytes().length, saved.path("baiduUploadIntent").path("size").asInt());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.bytes())),
                    saved.path("baiduUploadIntent").path("sha256").asText());
            assertEquals("SUBMITTING", saved.path("baiduUploadIntent").path("status").asText());
            throw new BaiduActionException("UPLOAD_BLOCK_API_31024", true, false, false);
        });
        service.runTask(task);
        var saved = mapper.readTree(task.getPayload());
        assertEquals("FAILED", task.getStatus()); assertEquals("UPLOAD_BLOCK_API_31024", saved.path("errorCode").asText());
        assertEquals("REJECTED", saved.path("baiduUploadIntent").path("status").asText());
        assertTrue(task.getLastError().contains("UPLOAD_BLOCK_API_31024"));
        assertFalse(task.getPayload().contains("ticket"));
        verify(cloud, never()).createShare(anyString(), anyString(), anyString()); verify(links, never()).save(any());
    }

    @Test void baiduRetryVerifiesTheConfirmedMagnetAndUploadsOnlyTheRejectedTorrent() throws Exception {
        ResourceHubTask task = baiduTask();
        when(cloud.upload(eq("BAIDU"), anyString(), any())).thenReturn("101")
                .thenThrow(new BaiduActionException("UPLOAD_BLOCK_API_31024", true, false, false)).thenReturn("102");
        service.runTask(task); assertEquals("FAILED", task.getStatus());
        assertEquals(1, mapper.readTree(task.getPayload()).path("uploadedFiles").size());
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        var uploaded = mapper.readTree(task.getPayload()).path("uploadedFiles");
        assertEquals(2, uploaded.size()); assertTrue(uploaded.toString().contains("101"));
        assertFalse(mapper.readTree(task.getPayload()).has("baiduUploadIntent"));
        assertFalse(mapper.readTree(task.getPayload()).has("errorCode"));
        var actual = ArgumentCaptor.forClass(P2pCloudArchiveClient.FileData.class);
        verify(cloud, times(3)).upload(eq("BAIDU"), anyString(), actual.capture());
        assertEquals(List.of(true, false, false), actual.getAllValues().stream().map(file -> file.name().endsWith(".txt")).toList());
        verify(cloud).findUploadedFile(eq("BAIDU"), anyString(), argThat(file -> file.name().endsWith(".txt")));
        verify(cloud).createShare(eq("BAIDU"), anyString(), anyString());
    }

    @Test void baiduDoesNotReuploadAConfirmedFileAfterItWasDeletedOrReplaced() throws Exception {
        ResourceHubTask task = baiduTask();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("backfillParentId", 100L); task.setPayload(payload.toString());
        when(tasks.getById(task.getId())).thenReturn(task);
        when(cloud.createShare(eq("BAIDU"), anyString(), anyString())).thenThrow(new BaiduActionException("SHARE_REJECTED", true, false, false));
        service.submitOne(task.getId(), "BAIDU", 100L); assertEquals("FAILED", task.getStatus());
        when(cloud.findUploadedFile(eq("BAIDU"), anyString(), any())).thenReturn("changed-id");
        service.submitOne(task.getId(), "BAIDU", 100L);
        assertEquals("ACTION_REQUIRED", task.getStatus()); assertEquals(1, task.getAttempts());
        assertEquals("UPLOAD_CHECKPOINT_MISMATCH", mapper.readTree(task.getPayload()).path("errorCode").asText());
        assertEquals(2, mapper.readTree(task.getPayload()).path("uploadedFiles").size());
        verify(cloud, times(2)).upload(eq("BAIDU"), anyString(), any());
        verify(cloud).createShare(eq("BAIDU"), anyString(), anyString()); verify(links, never()).save(any());
    }

    ResourceHubTask interruptedBaiduUpload() throws Exception {
        ResourceHubTask task = baiduTask(); task.setStatus("FAILED"); task.setAttempts(1);
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        Map<String, Object> checkpoint = mapper.convertValue(payload, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        var files = service.prepareFiles(movies.getById("movie1"), checkpoint, service.loadSelection(checkpoint));
        var first = files.get(0); payload.put("folderId", "/Baidu Root/mv/测试电影（movie1）/磁力种子");
        payload.putObject("baiduUploadIntent").put("name", first.name()).put("size", first.bytes().length)
                .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first.bytes())))
                .put("status", "SUBMITTING"); task.setPayload(payload.toString());
        return task;
    }

    @Test void interruptedBaiduUploadWithAnEmptyDestinationOnlyReadsAndStaysUnknown() throws Exception {
        ResourceHubTask task = interruptedBaiduUpload();
        when(cloud.findUploadedFile(eq("BAIDU"), anyString(), any())).thenReturn(null);
        service.runTask(task);
        assertEquals("UNKNOWN", task.getStatus()); assertEquals("UPLOAD_MAGNET_TEXT", mapper.readTree(task.getPayload()).path("stage").asText());
        assertEquals("UPLOAD_OUTCOME_UNKNOWN", mapper.readTree(task.getPayload()).path("errorCode").asText());
        when(tasks.getById(task.getId())).thenReturn(task); service.submitOne(task.getId(), "BAIDU");
        verify(cloud).findUploadedFile(eq("BAIDU"), anyString(), any());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }

    @Test void interruptedBaiduUploadWithAConfirmedDestinationDoesNotSubmitTheFileAgain() throws Exception {
        ResourceHubTask task = interruptedBaiduUpload();
        when(cloud.upload(eq("BAIDU"), anyString(), any())).thenReturn("102");
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        verify(cloud).upload(eq("BAIDU"), anyString(), argThat(file -> file.name().endsWith(".torrent")));
        verify(cloud, never()).upload(eq("BAIDU"), anyString(), argThat(file -> file.name().endsWith(".txt")));
        assertEquals(2, mapper.readTree(task.getPayload()).path("uploadedFiles").size());
    }

    @Test void baiduIntentWithDifferentBytesIsRejectedBeforeAnyUploadOrReconciliation() throws Exception {
        ResourceHubTask task = interruptedBaiduUpload();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("baiduUploadIntent")).put("sha256", "0".repeat(64));
        task.setPayload(payload.toString()); service.runTask(task);
        assertEquals("UPLOAD_CHECKPOINT_INVALID", mapper.readTree(task.getPayload()).path("errorCode").asText());
        verify(cloud, never()).findUploadedFile(anyString(), anyString(), any());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }

    @Test void baiduCannotSendAnUploadWhenItsSubmissionIntentCannotBePersisted() throws Exception {
        ResourceHubTask task = baiduTask();
        when(tasks.update(any(ResourceHubTask.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call ->
                !mapper.readTree(((ResourceHubTask) call.getArgument(0)).getPayload()).has("baiduUploadIntent"));
        service.runTask(task); assertEquals("UNKNOWN", task.getStatus());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
        assertEquals("UPLOAD_STATE_OR_STORAGE_FAILURE", mapper.readTree(task.getPayload()).path("errorCode").asText());
    }

    @Test void baiduCannotSendTheNextFileWhenItsConfirmedReceiptCannotBePersisted() throws Exception {
        ResourceHubTask task = baiduTask();
        when(tasks.update(any(ResourceHubTask.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call ->
                !"CONFIRMED".equals(mapper.readTree(((ResourceHubTask) call.getArgument(0)).getPayload()).path("baiduUploadIntent").path("status").asText()));
        service.runTask(task); assertEquals("FAILED", task.getStatus());
        verify(cloud).upload(eq("BAIDU"), anyString(), argThat(file -> file.name().endsWith(".txt")));
        verify(cloud, never()).upload(eq("BAIDU"), anyString(), argThat(file -> file.name().endsWith(".torrent")));
        verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }


    @Test void failedBaiduRecoveryReadsCannotRetractTheOriginalUploadIntent() throws Exception {
        for (boolean requiresLogin : List.of(false, true)) {
            ResourceHubTask task = interruptedBaiduUpload();
            when(cloud.findUploadedFile(eq("BAIDU"), anyString(), any()))
                    .thenThrow(new BaiduActionException(requiresLogin ? "LOGIN_REQUIRED" : "NETWORK_UNAVAILABLE", !requiresLogin, requiresLogin, false))
                    .thenReturn(null);
            service.runTask(task);
            var saved = mapper.readTree(task.getPayload());
            assertEquals("UNKNOWN", saved.path("baiduUploadIntent").path("status").asText());
            assertEquals("UNKNOWN", task.getStatus());
            if (!requiresLogin) {
                // Even a legacy startup reset cannot turn an unresolved write into permission to upload again.
                task.setStatus("FAILED");
                service.runTask(task); assertEquals("UNKNOWN", task.getStatus());
                assertEquals("UNKNOWN", mapper.readTree(task.getPayload()).path("baiduUploadIntent").path("status").asText());
            }
        }
        verify(cloud, never()).upload(anyString(), anyString(), any());
        verify(cloud, never()).createShare(anyString(), anyString(), anyString()); verify(links, never()).save(any());
    }

    @Test void malformedBaiduUploadIntentStaysReviewableWithoutBreakingFailurePersistence() throws Exception {
        for (String invalidField : List.of("status", "name", "size", "sha256")) {
            ResourceHubTask task = interruptedBaiduUpload();
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
            ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("baiduUploadIntent")).remove(invalidField);
            task.setPayload(payload.toString());
            assertDoesNotThrow(() -> service.runTask(task));
            assertEquals("UPLOAD_CHECKPOINT_INVALID", mapper.readTree(task.getPayload()).path("errorCode").asText());
            assertEquals("ACTION_REQUIRED", task.getStatus());
        }
        verify(cloud, never()).findUploadedFile(anyString(), anyString(), any());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }


    @Test void changingTheSelectionCannotCancelAnUnconfirmedBaiduUpload() throws Exception {
        ResourceHubTask task = interruptedBaiduUpload();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("fingerprint", "changed-selection"); task.setPayload(payload.toString());
        service.runTask(task);
        assertEquals("ACTION_REQUIRED", task.getStatus());
        assertEquals("UPLOAD_CHECKPOINT_INVALID", mapper.readTree(task.getPayload()).path("errorCode").asText());
        assertTrue(mapper.readTree(task.getPayload()).has("baiduUploadIntent"));
        verify(cloud, never()).upload(anyString(), anyString(), any());
        verify(cloud, never()).findUploadedFile(anyString(), anyString(), any());
        verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }

    @Test void newPreparationFailureClearsTheStaleBaiduUploadErrorCode() throws Exception {
        ResourceHubTask task = baiduTask();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("errorCode", "UPLOAD_BLOCK_API_31024"); task.setPayload(payload.toString());
        when(source.get(anyString())).thenThrow(new IllegalStateException("fixture-unavailable"));
        service.runTask(task);
        assertEquals("FAILED", task.getStatus());
        assertEquals("PREPARE_METADATA_FILES", mapper.readTree(task.getPayload()).path("stage").asText());
        assertFalse(mapper.readTree(task.getPayload()).has("errorCode"));
        assertFalse(task.getLastError().contains("UPLOAD_BLOCK_API_31024"));
        verify(cloud, never()).upload(anyString(), anyString(), any());
    }


    @ParameterizedTest
    @CsvSource({"mv,mv", "tv,tv", "ac,ac", "movie,mv", "anime,ac"})
    void baiduNewMetadataFoldersUseTheCanonicalMovieTvAndAnimeRoots(String category, String expected) throws Exception {
        ResourceHubTask task = baiduTask(); movies.getById("movie1").setCategory(category);
        when(cloud.ensureFolder(eq("BAIDU"), any(), anyString())).thenAnswer(call -> call.getArgument(2) + "/磁力种子");
        when(cloud.upload(eq("BAIDU"), anyString(), any())).thenReturn("101", "102");
        when(cloud.createShare(eq("BAIDU"), anyString(), anyString())).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        when(cloud.findOwnShare(eq("BAIDU"), anyString())).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        String movieFolder = "/Baidu Root/" + expected + "/测试电影（movie1）";
        assertEquals(movieFolder + "/磁力种子", mapper.readTree(task.getPayload()).path("folderId").asText());
        verify(cloud).ensureFolder("BAIDU", null, movieFolder);
        verify(cloud, times(2)).upload(eq("BAIDU"), eq(movieFolder + "/磁力种子"), any());
        verifyNoInteractions(quark, xunlei);
    }

    @Test void unknownBaiduCategoryStopsBeforeCreatingOrUploadingACloudFolder() throws Exception {
        ResourceHubTask task = baiduTask(); movies.getById("movie1").setCategory("music");
        service.runTask(task); assertEquals("ACTION_REQUIRED", task.getStatus()); assertEquals(0, task.getAttempts());
        assertEquals("CATEGORY_REQUIRED", mapper.readTree(task.getPayload()).path("errorCode").asText());
        verify(cloud, never()).ensureFolder(anyString(), any(), anyString());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
        verifyNoInteractions(baidu, quark, xunlei);
    }

    @Test void baiduOnlyReusesSuccessfulFoldersUnderTheMatchingCategoryRoot() throws Exception {
        ResourceHubTask task = baiduTask();
        BaiduTransferTask unclassified = new BaiduTransferTask(); unclassified.setSavedPath("/Baidu Root/Old movie");
        BaiduTransferTask otherCategory = new BaiduTransferTask(); otherCategory.setSavedPath("/Baidu Root/tv/Other movie");
        BaiduTransferTask prefixCollision = new BaiduTransferTask(); prefixCollision.setSavedPath("/Baidu Root/mv-other/Other movie");
        BaiduTransferTask traversal = new BaiduTransferTask(); traversal.setSavedPath("/Baidu Root/mv/../tv/Other movie");
        BaiduTransferTask valid = new BaiduTransferTask(); valid.setSavedPath("/Baidu Root/mv/Previously Saved Movie");
        when(baidu.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(unclassified, otherCategory, prefixCollision, traversal, valid));
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        verify(cloud).ensureFolder("BAIDU", valid.getSavedPath(), "/Baidu Root/mv/测试电影（movie1）");
        verifyNoInteractions(quark, xunlei);
    }

    @Test void aPersistedBaiduMetadataFolderKeepsItsOriginalPathAfterTheCategoryLayoutChanges() throws Exception {
        ResourceHubTask task = baiduTask(); movies.getById("movie1").setCategory(null);
        String original = "/Baidu Root/Original unclassified movie/磁力种子";
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("folderId", original); task.setPayload(payload.toString());
        when(cloud.upload(eq("BAIDU"), eq(original), any())).thenReturn("101", "102");
        when(cloud.createShare(eq("BAIDU"), eq(original), anyString())).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        when(cloud.findOwnShare(eq("BAIDU"), eq(original))).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        assertEquals(original, mapper.readTree(task.getPayload()).path("folderId").asText());
        verify(cloud, never()).ensureFolder(anyString(), any(), anyString());
        verify(cloud, times(2)).upload(eq("BAIDU"), eq(original), any()); verifyNoInteractions(baidu);
    }

    @Test void anUnknownHistoricalBaiduMetadataCheckpointIsNotRoutedIntoTheNewCategories() throws Exception {
        ResourceHubTask task = interruptedBaiduUpload(); task.setStatus("UNKNOWN"); movies.getById("movie1").setCategory("tv");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getPayload());
        payload.put("folderId", "/Baidu Root/Original unclassified movie/磁力种子"); task.setPayload(payload.toString());
        String before = task.getPayload(); when(tasks.getById(task.getId())).thenReturn(task);
        assertSame(task, service.submitOne(task.getId(), "BAIDU")); assertEquals(before, task.getPayload());
        assertEquals("UNKNOWN", task.getStatus());
        verify(cloud, never()).ensureFolder(anyString(), any(), anyString());
        verify(cloud, never()).findUploadedFile(anyString(), anyString(), any());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verifyNoInteractions(baidu);
    }

}
