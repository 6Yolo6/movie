package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gying.movie.client.SocialPublisherClient;
import com.gying.movie.entity.SocialPostLog;
import com.gying.movie.entity.SocialPublishTarget;
import com.gying.movie.service.ISocialPostLogService;
import com.gying.movie.service.ISocialPublishTargetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SocialPublishingServiceTest {
    private final ISocialPublishTargetService targets = mock(ISocialPublishTargetService.class);
    private final ISocialPostLogService logs = mock(ISocialPostLogService.class);
    private final SocialPublisherClient publisher = mock(SocialPublisherClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SocialPublishingService service = new SocialPublishingService(targets, logs, publisher, jdbc);
    private SocialPublishTarget target;

    @BeforeEach
    void setUp() {
        target = target(7L);
        when(targets.getById(7L)).thenReturn(target);
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(
                Map.of("resource_link_id", 19L, "movie_id", "fixture-movie", "title", "Fixture")));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    @Test
    void existingProtectedOutcomesAreNeverResetOrRepublishedEvenWithAStaleCandidate() {
        for (String status : List.of("POSTED", "PREPARING", "PUBLISHING", "UNKNOWN", "FAILED", "FUTURE_STATE")) {
            SocialPostLog existing = log(status);
            when(logs.getOne(any(Wrapper.class), eq(false))).thenReturn(existing);

            Map<String, Object> result = service.publishNext(7L, true);

            assertEquals(status, result.get("status"));
            assertEquals(status, existing.getStatus());
            assertEquals("keep diagnostic", existing.getErrorMessage());
            assertEquals(false, result.get("retryable"));
        }
        verifyNoInteractions(publisher);
        verify(logs, never()).save(any(SocialPostLog.class));
        verify(logs, never()).saveOrUpdate(any(SocialPostLog.class));
        verify(logs, never()).updateById(any(SocialPostLog.class));
    }

    @Test
    void aNewLogIsInsertedOnceAndThePublisherOwnsAllFurtherTransitions() {
        when(logs.save(any(SocialPostLog.class))).thenAnswer(call -> {
            SocialPostLog created = call.getArgument(0); created.setId(12L); return true;
        });
        when(publisher.publish(12L)).thenReturn(Map.of("status", "POSTED", "ok", true));

        var result = service.publishNext(7L, true);

        assertEquals("POSTED", result.get("status"));
        ArgumentCaptor<SocialPostLog> captor = ArgumentCaptor.forClass(SocialPostLog.class);
        verify(logs).save(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(19L, captor.getValue().getResourceLinkId());
        verify(publisher).publish(12L);
        verify(logs, never()).saveOrUpdate(any(SocialPostLog.class));
        verify(logs, never()).updateById(any(SocialPostLog.class));
    }

    @Test
    void aConcurrentInsertReturnsTheWinningRowWithoutClearingItsOwnership() {
        SocialPostLog winner = log("PUBLISHING");
        when(logs.getOne(any(Wrapper.class), eq(false))).thenReturn(null, winner);
        when(logs.save(any(SocialPostLog.class))).thenThrow(new DuplicateKeyException("fixture race"));

        var result = service.publishNext(7L, true);

        assertEquals("PUBLISHING", result.get("status"));
        assertEquals("keep diagnostic", winner.getErrorMessage());
        verifyNoInteractions(publisher);
        verify(logs, never()).updateById(any(SocialPostLog.class));
    }

    @Test
    void anUnresolvedInsertRaceOrFailedSaveNeverStartsPublishing() {
        when(logs.save(any(SocialPostLog.class))).thenThrow(new DuplicateKeyException("fixture race"));
        assertThrows(DuplicateKeyException.class, () -> service.publishNext(7L, true));
        when(logs.save(any(SocialPostLog.class))).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.publishNext(7L, true));
        verifyNoInteractions(publisher);
    }

    @Test
    void queuingWithoutRunNowDoesNotSubmitExistingPendingLogs() {
        SocialPostLog pending = log("PENDING");
        when(logs.getOne(any(Wrapper.class), eq(false))).thenReturn(pending);

        var result = service.publishNext(7L, false);

        assertEquals("PENDING", result.get("status"));
        verifyNoInteractions(publisher);
        verify(logs, never()).saveOrUpdate(any(SocialPostLog.class));
    }

    @Test
    void retryOnlyAllowsUnsubmittedAndProvenPreparationFailuresWithoutResettingStatus() {
        when(publisher.publish(12L)).thenReturn(Map.of("status", "POSTED"));
        for (String status : List.of("PENDING", "PREPARE_FAILED")) {
            SocialPostLog existing = log(status);
            when(logs.getById(12L)).thenReturn(existing);
            assertEquals("POSTED", service.retry(12L).get("status"));
            assertEquals(status, existing.getStatus());
            assertEquals("keep diagnostic", existing.getErrorMessage());
        }
        verify(publisher, times(2)).publish(12L);
        verify(logs, never()).updateById(any(SocialPostLog.class));
    }

    @Test
    void retryRejectsHistoricalFailedUnknownPostedAndActiveStates() {
        for (String status : Arrays.asList("FAILED", "UNKNOWN", "POSTED", "PREPARING", "PUBLISHING", "FUTURE", null)) {
            when(logs.getById(12L)).thenReturn(log(status));
            ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.retry(12L));
            assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        }
        verifyNoInteractions(publisher);
        verify(logs, never()).updateById(any(SocialPostLog.class));
    }

    @Test
    void retryRejectsDisabledOrMissingTargetsBeforeSending() {
        when(logs.getById(12L)).thenReturn(log("PENDING"));
        target.setEnabled(false);
        assertThrows(IllegalStateException.class, () -> service.retry(12L));
        when(targets.getById(7L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.retry(12L));
        when(logs.getById(12L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.retry(12L));
        verifyNoInteractions(publisher);
    }

    @Test
    void publisherUncertaintyIsNotOverwrittenByAnOlderDatabaseRead() {
        when(logs.getOne(any(Wrapper.class), eq(false))).thenReturn(log("PENDING"));
        when(publisher.publish(12L)).thenReturn(Map.of("status", "UNKNOWN", "blocked", true));

        var result = service.publishNext(7L, true);

        assertEquals("UNKNOWN", result.get("status")); assertEquals(true, result.get("blocked"));
        verify(logs, never()).getById(12L);
    }

    @Test
    void candidateSelectionBlocksProtectedMoviesAcrossResourceVersions() {
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());
        assertEquals("SKIPPED", service.publishNext(7L, true).get("status"));
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(query.capture(), eq(7L));
        assertTrue(query.getValue().contains("existing.movie_id = rl.movie_id"));
        assertTrue(query.getValue().contains("existing.status NOT IN ('PENDING', 'PREPARE_FAILED')"));
        assertTrue(query.getValue().contains("rl.source = 'RESOURCE_HUB'"));
        verifyNoInteractions(publisher);
    }

    @Test
    void batchSummariesKeepInFlightAndUnconfirmedResultsSeparateFromSuccess() {
        var spied = spy(service);
        when(targets.listByIds(List.of(7L, 8L, 9L, 10L))).thenReturn(List.of(target(7L), target(8L), target(9L), target(10L)));
        doReturn(Map.of("status", "POSTED")).when(spied).publishNext(7L, true);
        doReturn(Map.of("status", "PUBLISHING")).when(spied).publishNext(8L, true);
        doReturn(Map.of("status", "PREPARE_FAILED")).when(spied).publishNext(9L, true);
        doThrow(new IllegalStateException("publisher acknowledgement missing")).when(spied).publishNext(10L, true);

        var result = spied.publishNext(List.of(7L, 8L, 9L, 10L), true);

        assertEquals(1, result.get("posted")); assertEquals(1, result.get("processing"));
        assertEquals(1, result.get("failed")); assertEquals(1, result.get("unknown"));
    }

    private SocialPostLog log(String status) {
        SocialPostLog log = new SocialPostLog(); log.setId(12L); log.setTargetId(7L);
        log.setStatus(status); log.setErrorMessage("keep diagnostic"); return log;
    }

    private SocialPublishTarget target(Long id) {
        SocialPublishTarget target = new SocialPublishTarget(); target.setId(id);
        target.setEnabled(true); target.setPlatform("WEIBO"); return target;
    }
}
