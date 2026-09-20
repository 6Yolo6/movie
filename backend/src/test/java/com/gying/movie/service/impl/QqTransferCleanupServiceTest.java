package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.client.QuarkAutoSaveClient;
import com.gying.movie.client.XunleiClient;
import com.gying.movie.entity.QqTransferCleanupJob;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IQqTransferCleanupJobService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.IXunleiTransferTaskService;
import com.gying.movie.utils.QqTransferMarker;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QqTransferCleanupServiceTest {
    private IQqTransferCleanupJobService jobService;
    private IQuarkTransferTaskService quarkTaskService;
    private IXunleiTransferTaskService xunleiTaskService;
    private IResourceDiscoveryResultService discoveryService;
    private IResourceLinkService resourceLinkService;
    private QuarkAutoSaveClient quarkClient;
    private XunleiClient xunleiClient;
    private QqTransferCleanupService service;

    @BeforeEach
    void setUp() {
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.getConfigValue(eq(QqTransferCleanupSettings.KEY_ENABLED), any())).thenReturn("true");
        when(config.getConfigValue(eq(QqTransferCleanupSettings.KEY_DELAY_MINUTES), any())).thenReturn("10");
        when(config.getConfigValue(eq(QqTransferCleanupSettings.KEY_QUARK_ROOT), any()))
                .thenReturn("/GYing QQ Temp");
        when(config.getConfigValue(eq(QqTransferCleanupSettings.KEY_XUNLEI_ROOT), any()))
                .thenReturn("/Xunlei/GYing QQ Temp");

        jobService = mock(IQqTransferCleanupJobService.class);
        quarkTaskService = mock(IQuarkTransferTaskService.class);
        xunleiTaskService = mock(IXunleiTransferTaskService.class);
        discoveryService = mock(IResourceDiscoveryResultService.class);
        resourceLinkService = mock(IResourceLinkService.class);
        quarkClient = mock(QuarkAutoSaveClient.class);
        xunleiClient = mock(XunleiClient.class);
        service = new QqTransferCleanupService(
                new QqTransferCleanupSettings(config), jobService, quarkTaskService, xunleiTaskService,
                discoveryService, resourceLinkService, quarkClient, xunleiClient);
    }

    @Test
    void schedulesMarkedQuarkTransferWithConfiguredDelay() {
        QuarkTransferTask task = new QuarkTransferTask();
        task.setId(11L);
        task.setMovieId("movie-1");
        task.setStatus("SUCCEEDED");
        task.setSavedPath("/GYing QQ Temp/quark-11");
        task.setShareUrl("https://example.invalid/share");
        task.setRequestPayload(QqTransferMarker.payload("/GYing QQ Temp/quark-11"));
        when(xunleiTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(null);
        when(quarkTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(task);
        when(jobService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(null);
        when(jobService.save(any())).thenReturn(true);

        LocalDateTime before = LocalDateTime.now().plusMinutes(10).minusSeconds(1);
        service.scheduleForDiscovery(21L, 31L);
        LocalDateTime after = LocalDateTime.now().plusMinutes(10).plusSeconds(1);

        ArgumentCaptor<QqTransferCleanupJob> captor = ArgumentCaptor.forClass(QqTransferCleanupJob.class);
        verify(jobService).save(captor.capture());
        QqTransferCleanupJob job = captor.getValue();
        assertEquals("QUARK", job.getProvider());
        assertEquals(11L, job.getTransferTaskId());
        assertEquals("/GYing QQ Temp/quark-11", job.getSavedPath());
        assertEquals("PENDING", job.getStatus());
        assertTrue(!job.getDeleteAfter().isBefore(before) && !job.getDeleteAfter().isAfter(after));
    }

    @Test
    void schedulesMarkedXunleiTransferUsingFolderIdAndSafeTargetPath() {
        XunleiTransferTask task = new XunleiTransferTask();
        task.setId(12L);
        task.setMovieId("movie-2");
        task.setStatus("SUCCEEDED");
        task.setSavedPath("folder-id-12");
        task.setRequestPayload(QqTransferMarker.payload("/Xunlei/GYing QQ Temp/xunlei-12"));
        when(xunleiTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(task);
        when(jobService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(null);
        when(jobService.save(any())).thenReturn(true);

        service.scheduleForDiscovery(22L, 32L);

        ArgumentCaptor<QqTransferCleanupJob> captor = ArgumentCaptor.forClass(QqTransferCleanupJob.class);
        verify(jobService).save(captor.capture());
        QqTransferCleanupJob job = captor.getValue();
        assertEquals("XUNLEI", job.getProvider());
        assertEquals("folder-id-12", job.getSavedPath());
        assertEquals("/Xunlei/GYing QQ Temp/xunlei-12", job.getTargetPath());
    }

    @Test
    void doesNotScheduleRegularResourceHubTransfer() {
        QuarkTransferTask task = new QuarkTransferTask();
        task.setId(13L);
        task.setStatus("SUCCEEDED");
        task.setSavedPath("/GYing Resource Hub/movie/title");
        task.setShareUrl("https://example.invalid/share");
        task.setRequestPayload("{\"origin\":\"AUTO\"}");
        when(xunleiTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(null);
        when(quarkTaskService.getOne(any(QueryWrapper.class), eq(false))).thenReturn(task);

        service.scheduleForDiscovery(23L, 33L);

        verify(jobService, never()).save(any());
        verify(jobService, never()).updateById(any());
    }

    @Test
    void cleanupDeletesOnlyMarkedPathBelowTemporaryRoot() {
        QqTransferCleanupJob job = dueQuarkJob("/GYing QQ Temp/quark-14");
        QuarkTransferTask task = new QuarkTransferTask();
        task.setId(14L);
        task.setRequestPayload(QqTransferMarker.payload("/GYing QQ Temp/quark-14"));
        when(jobService.list(any(QueryWrapper.class))).thenReturn(List.of(job));
        when(jobService.updateById(any())).thenReturn(true);
        when(quarkTaskService.getById(14L)).thenReturn(task);

        service.cleanupDueTransfers();

        verify(quarkClient).deletePath("/GYing QQ Temp/quark-14");
        assertEquals("SUCCEEDED", job.getStatus());
        assertNotNull(job.getFinishedAt());
    }

    @Test
    void cleanupRejectsUnmarkedOrOutsideRootTransfer() {
        QqTransferCleanupJob job = dueQuarkJob("/GYing Resource Hub/movie/title");
        QuarkTransferTask task = new QuarkTransferTask();
        task.setId(14L);
        task.setRequestPayload("{\"origin\":\"AUTO\",\"targetPath\":\"/GYing Resource Hub/movie/title\"}");
        when(jobService.list(any(QueryWrapper.class))).thenReturn(List.of(job));
        when(jobService.updateById(any())).thenReturn(true);
        when(quarkTaskService.getById(14L)).thenReturn(task);

        service.cleanupDueTransfers();

        verify(quarkClient, never()).deletePath(any());
        assertEquals("FAILED", job.getStatus());
        assertEquals(1, job.getAttempts());
    }

    @Test
    void prepareTemporaryTaskFailsClosedWhenMarkerCannotBePersisted() {
        QuarkTransferTask task = new QuarkTransferTask();
        task.setId(15L);
        when(quarkTaskService.updateById(task)).thenReturn(false);

        boolean failed = false;
        try {
            service.prepareTemporaryTask(task);
        } catch (IllegalStateException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertTrue(QqTransferMarker.isTemporary(task.getRequestPayload()));
    }

    private QqTransferCleanupJob dueQuarkJob(String targetPath) {
        QqTransferCleanupJob job = new QqTransferCleanupJob();
        job.setId(100L);
        job.setProvider("QUARK");
        job.setTransferTaskId(14L);
        job.setDiscoveryResultId(24L);
        job.setResourceLinkId(34L);
        job.setSavedPath(targetPath);
        job.setTargetPath(targetPath);
        job.setStatus("PENDING");
        job.setAttempts(0);
        job.setDeleteAfter(LocalDateTime.now().minusMinutes(1));
        return job;
    }
}
