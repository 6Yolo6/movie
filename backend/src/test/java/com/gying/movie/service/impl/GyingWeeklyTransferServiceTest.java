package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.GyingWeeklyTransferSchedule;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GyingWeeklyTransferServiceTest {
    private final ResourceHubProperties props = new ResourceHubProperties();
    private final GyingWeeklyTransferSettings settings = mock(GyingWeeklyTransferSettings.class);
    private final GyingSourceWorkflowService workflow = mock(GyingSourceWorkflowService.class);
    private final IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    private final GyingWeeklyTransferService service = new GyingWeeklyTransferService(props, settings, workflow, tasks, new ObjectMapper());
    GyingWeeklyTransferServiceTest() {
        props.setEnabled(true); props.getWorker().setEnabled(true);
        when(tasks.enqueue(any())).thenAnswer(call -> { ResourceHubTask task = call.getArgument(0); task.setId(5L); return task; });
    }
    private void due(String type, int max) {
        var schedule = new GyingWeeklyTransferSchedule(); schedule.setTypeCode(type); schedule.setMaxItems(max);
        when(settings.claimDue()).thenReturn(schedule, (GyingWeeklyTransferSchedule) null);
    }
    @Test void allThreeChartsUseOnlyWeeklyCandidatesAndBoundedOwnWorkflow() {
        for (String type : List.of("mv", "tv", "ac")) {
            due(type, 1);
            when(workflow.weeklyPopularCandidates(type, 1)).thenReturn(List.of(
                    Map.of("typeCode", type, "mid", "ONE"), Map.of("typeCode", type, "mid", "TWO")));
            when(workflow.ensureWeeklyMovieResource(type, "ONE")).thenReturn(Map.of("status", "ALREADY_PUBLISHED"));
            service.runDue(); service.runDue();
            verify(workflow).ensureWeeklyMovieResource(type, "ONE");
            verify(workflow, never()).ensureWeeklyMovieResource(type, "TWO");
        }
        verify(workflow, never()).ensureMovieResource(any(), any());
    }
    @Test void failureIsAuditedWithoutImmediateRetryOrFallback() {
        due("mv", 5); when(workflow.weeklyPopularCandidates("mv", 5)).thenThrow(new IllegalStateException("upstream"));
        service.runDue(); service.runDue();
        verify(settings).finished("mv", "FAILED", 5L);
        verify(workflow, never()).ensureWeeklyMovieResource(any(), any());
        ArgumentCaptor<ResourceHubTask> task = ArgumentCaptor.forClass(ResourceHubTask.class);
        verify(tasks).updateById(task.capture()); assertEquals("WEEKLY_TRANSFER", task.getValue().getTaskType());
    }
    @Test void disabledWorkerDoesNotClaimOrWriteCloudData() {
        props.getWorker().setEnabled(false); service.runDue();
        verifyNoInteractions(settings, workflow, tasks);
    }
    @Test void wrongChartIdentityFailsInsteadOfTransferringIt() {
        due("mv", 5); when(workflow.weeklyPopularCandidates("mv", 5)).thenReturn(List.of(Map.of("typeCode", "tv", "mid", "ONE")));
        service.runDue(); verify(settings).finished("mv", "FAILED", 5L);
        verify(workflow, never()).ensureWeeklyMovieResource(any(), any());
    }
    @Test void missingAuditRecordPreventsAllCloudWork() {
        due("mv", 5);
        doAnswer(call -> call.getArgument(0)).when(tasks).enqueue(any());
        service.runDue();
        verifyNoInteractions(workflow);
        verify(settings).finished("mv", "FAILED", null);
    }
    @Test void nullMovieIdFailsClosed() {
        due("mv", 5);
        var candidate = new java.util.HashMap<String, Object>(); candidate.put("typeCode", "mv"); candidate.put("mid", null);
        when(workflow.weeklyPopularCandidates("mv", 5)).thenReturn(List.of(candidate));
        service.runDue();
        verify(workflow, never()).ensureWeeklyMovieResource(any(), any());
        verify(settings).finished("mv", "FAILED", 5L);
    }
    @Test void auditStorageExceptionPreventsCloudWorkAndRecordsFailure() {
        due("mv", 5); doThrow(new IllegalStateException("storage unavailable")).when(tasks).enqueue(any());
        service.runDue();
        verifyNoInteractions(workflow);
        verify(settings).finished("mv", "FAILED", null);
    }
}
