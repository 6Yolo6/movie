package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.QuarkAutoSaveClient;
import com.gying.movie.client.XunleiClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Turning video collection off must pause existing queues before any remote or database work. */
class CloudFileCollectionPolicyTest {
    @Test void quarkTorrentOnlyDoesNotReadOrConsumeExistingVideoTasks() {
        var p = new ResourceHubProperties(); p.setEnabled(true);
        p.getQuark().setVideoEnabled(false); p.getQuark().setTorrentEnabled(true);
        var client = mock(QuarkAutoSaveClient.class); var shares = mock(IQuarkShareService.class);
        var tasks = mock(IQuarkTransferTaskService.class); var discoveries = mock(IResourceDiscoveryResultService.class);
        var hub = mock(IResourceHubTaskService.class); var movies = mock(IMovieMetadataService.class);
        var runner = new QuarkTransferRunnerServiceImpl(p, client, shares, tasks, discoveries, hub, movies, new ObjectMapper());
        assertThrows(ResponseStatusException.class, () -> runner.submitOne(12L));
        assertThrows(ResponseStatusException.class, () -> runner.submitPending(5));
        verifyNoInteractions(client, shares, tasks, discoveries, hub, movies);
    }

    @Test void xunleiTorrentOnlyDoesNotResumeVideoRestoreOrShareCheckpoint() {
        var p = new ResourceHubProperties(); p.setEnabled(true); p.getXunlei().setEnabled(true);
        p.getXunlei().setVideoEnabled(false); p.getXunlei().setTorrentEnabled(true);
        var client = mock(XunleiClient.class); var tasks = mock(IXunleiTransferTaskService.class);
        var discoveries = mock(IResourceDiscoveryResultService.class); var links = mock(IResourceLinkService.class);
        var runner = new XunleiTransferRunnerServiceImpl(p, client, tasks, discoveries, links);
        assertEquals(0, runner.submitPending(5).getSubmitted());
        assertThrows(ResponseStatusException.class, () -> runner.submitOne(17L));
        verifyNoInteractions(client, tasks, discoveries, links);
    }

    @Test void disabledVideoSelectionCannotCreateTasksFromExistingDiscoveryRows() {
        var p = new ResourceHubProperties(); p.setEnabled(true); p.getXunlei().setEnabled(true);
        p.getQuark().setVideoEnabled(false); p.getXunlei().setVideoEnabled(false);
        var discoveries = mock(IResourceDiscoveryResultService.class);
        var quark = mock(IQuarkTransferTaskService.class); var xunlei = mock(IXunleiTransferTaskService.class);
        var service = new ResourceDiscoveryServiceImpl(p, null, null, null, null, discoveries, quark, null, xunlei, new ObjectMapper());
        for (String provider : new String[] {"QUARK", "XUNLEI", "OTHER"}) {
            var row = new ResourceDiscoveryResult(); row.setId(1L); row.setMovieId("movie");
            row.setOriginalUrl("https://example.invalid/share"); row.setProvider(provider);
            when(discoveries.getById(1L)).thenReturn(row);
            assertFalse(service.ensureTransferTask(1L));
        }
        verifyNoInteractions(quark, xunlei);
    }
}
