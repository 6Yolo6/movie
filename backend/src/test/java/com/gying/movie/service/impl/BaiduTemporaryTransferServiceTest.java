package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduSessionVault;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.*;
import com.gying.movie.utils.BaiduTemporaryScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class BaiduTemporaryTransferServiceTest {
    ResourceHubProperties properties; BaiduWebClient client; BaiduSessionVault vault;
    IBaiduTransferTaskService tasks; IResourceDiscoveryResultService discoveries; IResourceLinkService links;
    IBaiduTransferRunnerService runner; QqTransferCleanupSettings settings; BaiduTemporaryCleanupService cleanup;
    JdbcTemplate jdbc; TransactionTemplate transactions; BaiduTemporaryTransferService service;
    ResourceDiscoveryResult source; List<BaiduTransferTask> inserted; ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() {
        properties = new ResourceHubProperties(); properties.setEnabled(true); properties.getBaidu().setEnabled(true);
        properties.getBaidu().setVideoEnabled(true);
        client = mock(BaiduWebClient.class); vault = mock(BaiduSessionVault.class); tasks = mock(IBaiduTransferTaskService.class);
        discoveries = mock(IResourceDiscoveryResultService.class); links = mock(IResourceLinkService.class);
        runner = mock(IBaiduTransferRunnerService.class); settings = mock(QqTransferCleanupSettings.class);
        cleanup = mock(BaiduTemporaryCleanupService.class); jdbc = mock(JdbcTemplate.class); transactions = mock(TransactionTemplate.class);
        when(client.isConfigured()).thenReturn(true);
        when(transactions.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("movie1"))).thenReturn(List.of("movie1"));
        when(settings.baiduTarget(anyLong())).thenAnswer(i -> "/GYing QQ Temp/baidu-" + i.getArgument(0));
        when(settings.delayMinutes()).thenReturn(10);
        inserted = new ArrayList<>();
        when(tasks.save(any())).thenAnswer(i -> {
            BaiduTransferTask task = i.getArgument(0); assertEquals("INITIALIZING", task.getStatus()); assertNull(task.getRequestPayload());
            task.setId(100L + inserted.size()); inserted.add(task); return true;
        });
        when(discoveries.save(any())).thenAnswer(i -> { ResourceDiscoveryResult clone = i.getArgument(0); clone.setId(200L + inserted.size()); return true; });
        when(tasks.update(any(Wrapper.class))).thenReturn(true);
        source = new ResourceDiscoveryResult(); source.setId(1L); source.setMovieId("movie1"); source.setProvider("BAIDU");
        source.setTitle("测试影片"); source.setOriginalUrl("https://pan.baidu.com/s/1SourceCase"); source.setCode("abcd"); source.setStatus("DISCOVERED");
        when(discoveries.getById(1L)).thenReturn(source);
        service = new BaiduTemporaryTransferService(properties, client, vault, tasks, discoveries, links, runner, settings, cleanup, jdbc, transactions, mapper);
    }
    @Test void temporaryInsertAndExactMarkerCompleteBeforeAnyRunnerOrCloudCall() {
        BaiduTransferTask task = service.findOrCreate(source, "web:1");
        assertTrue(BaiduTemporaryScope.isTemporary(task)); assertEquals("PENDING", task.getStatus()); assertNotNull(task.getDiscoveryResultId());
        assertEquals("/GYing QQ Temp/baidu-100", BaiduTemporaryScope.requireTarget(task, settings.baiduTarget(task.getId())));
        verify(tasks).update(any(Wrapper.class));
        verifyNoInteractions(runner); verify(client, never()).planTransfer(anyString(), any(), anyString(), anyBoolean(), anyBoolean());
        var clone = ArgumentCaptor.forClass(ResourceDiscoveryResult.class); verify(discoveries).save(clone.capture());
        assertEquals("QQ_BOT", clone.getValue().getSource()); assertEquals("BAIDU_TEMP:" + task.getScopeKey(), clone.getValue().getSourceRef());
        assertNull(source.getShareUrl()); assertNull(source.getResourceLinkId()); assertEquals("DISCOVERED", source.getStatus());
    }
    @Test void repeatedSelectionAndRestartReuseTheUncleanedScopeWithoutTouchingPermanentTasks() {
        BaiduTransferTask first = service.findOrCreate(source, "qq:user1");
        when(settings.delayMinutes()).thenReturn(60);
        when(tasks.getOne(any(QueryWrapper.class), eq(false))).thenReturn(first);
        assertSame(first, service.findOrCreate(source, "qq:user1"));
        assertEquals(10, BaiduTemporaryScope.retentionMinutes(first));
        assertEquals(1, inserted.size());
        var query = ArgumentCaptor.forClass(QueryWrapper.class); verify(tasks, times(2)).getOne(query.capture(), eq(false));
        assertTrue(query.getValue().getSqlSegment().contains("search_request_hash"));
        assertTrue(query.getValue().getSqlSegment().contains("FOR UPDATE"));
        verify(cleanup).isCleaned(first.getId());
    }
    @Test void onlyConfirmedCleanupAllowsANewScopeForTheSameSelection() {
        BaiduTransferTask first = service.findOrCreate(source, "web:1");
        when(tasks.getOne(any(QueryWrapper.class), eq(false))).thenReturn(first); when(cleanup.isCleaned(first.getId())).thenReturn(true);
        BaiduTransferTask second = service.findOrCreate(source, "web:1");
        assertNotEquals(first.getScopeKey(), second.getScopeKey()); assertEquals(first.getSearchRequestHash(), second.getSearchRequestHash());
        assertEquals(2, inserted.size());
    }
    @Test void expiredOrUnknownDeletionReusesTheOldTaskAndNeverCreatesAnotherTransfer() {
        BaiduTransferTask existing = service.findOrCreate(source, "web:1");
        when(tasks.getOne(any(QueryWrapper.class), eq(false))).thenReturn(existing);
        when(cleanup.isExpired(existing.getId())).thenReturn(true); when(cleanup.isCleaned(existing.getId())).thenReturn(false);
        var result = service.select(1L, "web:1");
        assertEquals("EXPIRED", result.status()); assertNull(result.resource()); assertEquals(1, inserted.size());
        verifyNoInteractions(runner);
    }
    @Test void disabledOrUnconfiguredBaiduDoesNotAllocateOrSend() {
        properties.getBaidu().setEnabled(false);
        assertEquals("BAIDU_NOT_READY", service.select(1L, "web:1").status());
        properties.getBaidu().setEnabled(true); when(client.isConfigured()).thenReturn(false);
        assertEquals("BAIDU_NOT_READY", service.select(1L, "web:1").status());
        verifyNoInteractions(jdbc, transactions, tasks, discoveries, runner);
    }
    @Test void aTemporaryCloneIsNotAcceptedAsANewSourceSelection() {
        source.setSourceRef("BAIDU_TEMP:corrupt");
        assertEquals("INVALID_RESOURCE", service.select(1L, "web:1").status());
        verifyNoInteractions(jdbc, transactions, tasks, runner);
    }
    @Test void normalOrTrustedLibraryStatusCannotBypassTheVideoShareValidator() {
        ResourceLink link = new ResourceLink(); link.setProvider("BAIDU"); link.setType("DISK"); link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL"); link.setSource("GYING_PUBLISHED"); link.setUrl("https://pan.baidu.com/s/1Own");
        when(client.validateOwnVideoShare(link.getUrl())).thenReturn(false);
        assertFalse(service.validateOwnedResource(link)); verify(client).validateOwnVideoShare(link.getUrl());
        verifyNoInteractions(tasks);
    }
    @Test void persistedMetadataOnlyPlanCannotBeReturnedAsVideo() throws Exception {
        ResourceLink link = new ResourceLink(); link.setProvider("BAIDU"); link.setType("DISK"); link.setUrl("https://pan.baidu.com/s/1Own");
        when(client.validateOwnVideoShare(link.getUrl())).thenReturn(true); when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(true, "100", "fixture", 1));
        BaiduTransferTask task = new BaiduTransferTask(); task.setAccountUid("100"); task.setSavedPath("/archive");
        task.setResponsePayload(mapper.writeValueAsString(new BaiduWebClient.TransferPlan(link.getUrl(), null, "/archive", "1", "200", List.of("10"),
                List.of(new BaiduWebClient.ExpectedFile("magnet.txt", 61, false)))));
        when(tasks.getOne(any(QueryWrapper.class), eq(false))).thenReturn(task);
        assertFalse(service.validateOwnedResource(link)); verify(client, never()).verifyDestination(any());
    }
    @Test void automaticCleanupRequiresBothTheSharedSwitchAndPermanentBaiduMode() {
        when(settings.enabled()).thenReturn(true); when(settings.baiduPermanentDeleteEnabled()).thenReturn(true);
        assertTrue(service.automaticCleanupEnabled());
        when(settings.enabled()).thenReturn(false); assertFalse(service.automaticCleanupEnabled());
        when(settings.enabled()).thenReturn(true); when(settings.baiduPermanentDeleteEnabled()).thenReturn(false);
        assertFalse(service.automaticCleanupEnabled());
    }
}
