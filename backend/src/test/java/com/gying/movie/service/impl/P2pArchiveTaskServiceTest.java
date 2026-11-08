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
        String folder = "/Baidu Root/测试电影（movie1）/磁力种子";
        when(cloud.ensureFolder(eq("BAIDU"), any(), anyString())).thenReturn(folder);
        when(cloud.upload(eq("BAIDU"), eq(folder), any())).thenReturn("101", "102");
        when(cloud.createShare(eq("BAIDU"), eq(folder), anyString())).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        when(cloud.findOwnShare(eq("BAIDU"), eq(folder))).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        return task;
    }
    @Test void baiduPublishesOnlyItsMetadataShareWithBothFileKindsAndOwnPath() throws Exception {
        ResourceHubTask task = baiduTask(); service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        verify(cloud).ensureFolder("BAIDU", null, "/Baidu Root/测试电影（movie1）");
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
    @Test void baiduReusesItsSuccessfulMovieFolderWithoutConsultingOtherDrives() throws Exception {
        ResourceHubTask task = baiduTask();
        BaiduTransferTask previous = new BaiduTransferTask(); previous.setSavedPath("/Previously Saved/Movie");
        when(baidu.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(previous));
        service.runTask(task); assertEquals("SUCCEEDED", task.getStatus());
        verify(cloud).ensureFolder("BAIDU", "/Previously Saved/Movie", "/Baidu Root/测试电影（movie1）");
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
        service.runTask(task);
        assertEquals("ACTION_REQUIRED", task.getStatus()); assertEquals(0, task.getAttempts());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verify(cloud, never()).createShare(anyString(), anyString(), anyString());
    }

}
