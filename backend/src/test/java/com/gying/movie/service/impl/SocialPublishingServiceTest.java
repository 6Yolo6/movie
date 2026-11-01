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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

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

    @Test
    void existingPendingQueueIsResumedBeforeNewCandidatesEvenAfterResourceVersionChanges() {
        SocialPostLog queued = log("PREPARE_FAILED");
        when(logs.list(any(Wrapper.class))).thenReturn(List.of(queued));
        when(publisher.publish(12L)).thenReturn(Map.of("status", "POSTED"));
        assertEquals("POSTED", service.publishNext(7L, true).get("status"));
        verifyNoInteractions(jdbc);
        verify(logs, never()).save(any(SocialPostLog.class));
        verify(publisher).publish(12L);
    }

    @Test
    void loggingInAfterAuthenticationFailureResumesTheSameLogEvenAfterTodaysAutomaticRun() {
        automaticTargetAlreadyRun();
        SocialPostLog failed = authenticationFailure(LocalDateTime.now().minusHours(1));
        when(logs.list(any(Wrapper.class))).thenReturn(List.of(failed));
        when(logs.getById(12L)).thenReturn(failed);
        when(publisher.weiboSessionStatus()).thenReturn(Map.of("ready", true,
                "sessionUpdatedAt", OffsetDateTime.now().toString()));
        when(publisher.publish(12L)).thenReturn(Map.of("status", "POSTED"));

        service.runDueTargets();

        verify(publisher, timeout(2000)).publish(12L);
        verify(logs, never()).updateById(any(SocialPostLog.class));
        verify(targets, never()).updateById(any(SocialPublishTarget.class));
        assertEquals("PREPARE_FAILED", failed.getStatus());
    }

    @Test
    void theSameExpiredSessionOrAnUnreadyPublisherDoesNotTriggerAuthenticationRecovery() {
        automaticTargetAlreadyRun();
        when(logs.list(any(Wrapper.class))).thenReturn(List.of(authenticationFailure(LocalDateTime.now())));
        when(publisher.weiboSessionStatus()).thenReturn(Map.of("ready", true,
                "sessionUpdatedAt", OffsetDateTime.now().minusHours(1).toString()));
        service.runDueTargets();
        when(publisher.weiboSessionStatus()).thenReturn(Map.of("ready", false,
                "sessionUpdatedAt", OffsetDateTime.now().plusHours(1).toString()));
        service.runDueTargets();
        verify(publisher, never()).publish(anyLong());
    }

    @Test
    void authenticationRecoveryOnlySelectsMarkedUnsubmittedWeiboLogs() {
        automaticTargetAlreadyRun();
        when(logs.list(any(Wrapper.class))).thenReturn(List.of());
        service.runDueTargets();
        ArgumentCaptor<Wrapper> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(logs).list(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("status =")); assertTrue(sql.contains("platform ="));
        assertTrue(sql.contains("error_message LIKE")); assertTrue(sql.contains("external_url IS NULL"));
        verify(publisher, never()).weiboSessionStatus();
        verify(publisher, never()).publish(anyLong());
    }

    @Test
    void disabledAutomaticTargetsNeverResumeOnLogin() {
        automaticTargetAlreadyRun();
        target.setAutoPostEnabled(false);
        service.runDueTargets();
        verifyNoInteractions(publisher);
        verify(logs, never()).list(any(Wrapper.class));
    }

    @Test
    void recoveryHonorsTheConfiguredBatchSize() {
        automaticTargetAlreadyRun(); target.setPostsPerRun(3);
        SocialPostLog first = authenticationFailure(LocalDateTime.now().minusHours(1));
        SocialPostLog second = authenticationFailure(LocalDateTime.now().minusHours(1)); second.setId(13L);
        SocialPostLog third = authenticationFailure(LocalDateTime.now().minusHours(1)); third.setId(14L);
        SocialPostLog fourth = authenticationFailure(LocalDateTime.now().minusHours(1)); fourth.setId(15L);
        when(logs.list(any(Wrapper.class))).thenReturn(List.of(first, second, third, fourth));
        when(logs.getById(12L)).thenReturn(first); when(logs.getById(13L)).thenReturn(second);
        when(logs.getById(14L)).thenReturn(third);
        when(publisher.weiboSessionStatus()).thenReturn(Map.of("ready", true,
                "sessionUpdatedAt", OffsetDateTime.now().toString()));
        when(publisher.publish(anyLong())).thenReturn(Map.of("status", "POSTED"));
        service.runDueTargets();
        verify(publisher, timeout(2000)).publish(14L);
        verify(publisher, never()).publish(15L);
    }

    @Test
    void aRenewedLoginFailureStopsTheRecoveredBatchBeforeTheNextPost() {
        automaticTargetAlreadyRun(); target.setPostsPerRun(3);
        SocialPostLog first = authenticationFailure(LocalDateTime.now().minusHours(1));
        SocialPostLog second = authenticationFailure(LocalDateTime.now().minusHours(1)); second.setId(13L);
        when(logs.list(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(logs.getById(12L)).thenReturn(first);
        when(publisher.weiboSessionStatus()).thenReturn(Map.of("ready", true,
                "sessionUpdatedAt", OffsetDateTime.now().toString()));
        when(publisher.publish(12L)).thenReturn(Map.of("status", "PREPARE_FAILED", "authenticationRequired", true));
        service.runDueTargets();
        verify(publisher, timeout(2000)).publish(12L);
        verify(publisher, after(100).never()).publish(13L);
    }

    @Test
    void recoveryKeepsTheConfiguredPostIntervalAndDoesNotOverlapSchedulerTicks() {
        automaticTargetAlreadyRun(); target.setPostsPerRun(3); target.setPostIntervalSeconds(500);
        SocialPostLog first = authenticationFailure(LocalDateTime.now().minusHours(1));
        SocialPostLog second = authenticationFailure(LocalDateTime.now().minusHours(1)); second.setId(13L);
        when(logs.list(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(logs.getById(12L)).thenReturn(first);
        when(publisher.weiboSessionStatus()).thenReturn(Map.of("ready", true,
                "sessionUpdatedAt", OffsetDateTime.now().toString()));
        when(publisher.publish(12L)).thenReturn(Map.of("status", "POSTED"));
        service.runDueTargets();
        verify(publisher, timeout(2000)).publish(12L);
        service.runDueTargets();
        verify(publisher, after(100).times(1)).publish(12L);
        verify(publisher, never()).publish(13L);
    }

    @Test
    void unsuccessfulPublicationStopsAnAutomaticBatchBeforeSelectingAnotherMovie() {
        automaticTargetAlreadyRun(); target.setLastAutoRunAt(null);
        target.setScheduleTime("00:00"); target.setPostsPerRun(3);
        var spied = spy(service);
        doReturn(Map.of("status", "UNKNOWN", "publisher", Map.of("ok", false, "status", "UNKNOWN")))
                .when(spied).publishNext(7L, true);
        spied.runDueTargets();
        verify(spied, timeout(2000)).publishNext(7L, true);
        verify(spied, after(100).times(1)).publishNext(7L, true);
    }

    @Test
    void aLostPublisherResponseStopsTheAutomaticBatch() {
        automaticTargetAlreadyRun(); target.setLastAutoRunAt(null);
        target.setScheduleTime("00:00"); target.setPostsPerRun(3);
        var spied = spy(service);
        doThrow(new IllegalStateException("publisher response lost")).when(spied).publishNext(7L, true);
        spied.runDueTargets();
        verify(spied, timeout(2000)).publishNext(7L, true);
        verify(spied, after(100).times(1)).publishNext(7L, true);
    }

    private void automaticTargetAlreadyRun() {
        target.setAutoPostEnabled(true); target.setLastAutoRunAt(LocalDateTime.now());
        target.setPostIntervalSeconds(0);
        when(targets.list(any(Wrapper.class))).thenReturn(List.of(target));
    }

    private SocialPostLog authenticationFailure(LocalDateTime failedAt) {
        SocialPostLog failed = log("PREPARE_FAILED"); failed.setPlatform("WEIBO");
        failed.setErrorMessage("[WEIBO_AUTH_REQUIRED] Log in again"); failed.setUpdatedAt(failedAt);
        return failed;
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
