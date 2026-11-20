package com.gying.movie.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gying.movie.client.SocialPublisherClient;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.entity.SocialPublishTarget;
import com.gying.movie.service.ISocialPostLogService;
import com.gying.movie.service.ISocialPublishTargetService;
import com.gying.movie.service.impl.SocialPublishingService;
import com.gying.movie.utils.AuthHelper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SocialPublishingAdminControllerTest {
    private AuthHelper authHelper;
    private ISocialPublishTargetService targetService;
    private ISocialPostLogService logService;
    private SocialPublisherClient publisherClient;
    private SocialPublishingAdminController controller;

    @BeforeEach
    void setUp() {
        authHelper = mock(AuthHelper.class);
        targetService = mock(ISocialPublishTargetService.class);
        logService = mock(ISocialPostLogService.class);
        publisherClient = mock(SocialPublisherClient.class);
        controller = new SocialPublishingAdminController(
                authHelper,
                targetService,
                logService,
                mock(SocialPublishingService.class),
                publisherClient);
    }

    @Test
    void createsQqPublishingTargetWithValidatedDefaults() {
        when(targetService.count(any())).thenReturn(0L);
        when(targetService.save(any())).thenAnswer(invocation -> {
            SocialPublishTarget target = invocation.getArgument(0);
            target.setId(7L);
            return true;
        });

        ApiResponse<SocialPublishTarget> response = controller.createTarget(Map.of(
                "platform", "qq_channel",
                "accountKey", "secondary",
                "name", "备用频道",
                "targetRef", "pd12345678"), "Bearer admin");

        assertEquals("OK", response.getCode());
        assertEquals(7L, response.getData().getId());
        assertEquals("QQ_CHANNEL", response.getData().getPlatform());
        assertEquals("10:00", response.getData().getScheduleTime());
        assertEquals(1, response.getData().getPostsPerRun());
        assertEquals(60, response.getData().getPostIntervalSeconds());
        assertTrue(response.getData().getEnabled());
        verify(authHelper).requireAdmin("Bearer admin");
        ArgumentCaptor<SocialPublishTarget> captor = ArgumentCaptor.forClass(SocialPublishTarget.class);
        verify(targetService).save(captor.capture());
        assertTrue(captor.getValue().getTemplate().contains("{{title}}"));
    }

    @Test
    void rejectsInvalidQqAccountKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                controller.createTarget(Map.of(
                        "platform", "QQ_CHANNEL",
                        "accountKey", "bad account key",
                        "name", "频道",
                        "targetRef", "pd12345678"), "Bearer admin"));

        assertEquals(
                "QQ account key must be 2-32 characters using letters, numbers, _ or -",
                error.getMessage());
    }

    @Test
    void removesQqAccountAndDisablesItsTargets() {
        SocialPublishTarget target = new SocialPublishTarget();
        target.setId(9L);
        target.setEnabled(true);
        target.setAutoPostEnabled(true);
        when(targetService.list(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(publisherClient.removeQqAccount("secondary")).thenReturn(Map.of("deleted", true));

        ApiResponse<Map<String, Object>> response = controller.removeQqAccount("secondary", "Bearer admin");

        assertEquals("OK", response.getCode());
        assertEquals(1, response.getData().get("disabledTargets"));
        assertTrue(!target.getEnabled());
        assertTrue(!target.getAutoPostEnabled());
        verify(targetService).updateBatchById(List.of(target));
        verify(publisherClient).removeQqAccount("secondary");
    }

    @Test
    void rejectsDuplicateTarget() {
        when(targetService.count(any())).thenReturn(1L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                controller.createTarget(Map.of(
                        "platform", "WEIBO",
                        "accountKey", "default",
                        "name", "新浪微博"), "Bearer admin"));

        assertEquals("Publishing account target already exists", error.getMessage());
    }

    @Test
    void deletesTargetAndRetainsPublishingHistory() {
        SocialPublishTarget target = new SocialPublishTarget();
        target.setId(11L);
        when(targetService.getById(11L)).thenReturn(target);
        when(targetService.removeById(11L)).thenReturn(true);

        ApiResponse<Map<String, Object>> response = controller.deleteTarget(11L, "Bearer admin");

        assertEquals("OK", response.getCode());
        assertEquals(true, response.getData().get("deleted"));
        assertEquals(true, response.getData().get("historyRetained"));
        verify(authHelper).requireAdmin("Bearer admin");
        verify(logService).update(any(UpdateWrapper.class));
        verify(targetService).removeById(11L);
    }

    @Test
    void overviewIncludesProcessingAndUnconfirmedOutcomesWithOneGroupedCountQuery() {
        when(logService.listMaps(any(QueryWrapper.class))).thenReturn(List.of(
                Map.of("status", "POSTED", "cnt", 12L),
                Map.of("status", "PENDING", "cnt", 2L),
                Map.of("status", "PREPARING", "cnt", 3L),
                Map.of("status", "PUBLISHING", "cnt", 4L),
                Map.of("status", "PREPARE_FAILED", "cnt", 5L),
                Map.of("status", "FAILED", "cnt", 6L),
                Map.of("status", "UNKNOWN", "cnt", 7L)));
        when(publisherClient.health()).thenReturn(Map.of("ok", true));

        var data = controller.overview("Bearer admin").getData();

        assertEquals(12L, data.get("posted")); assertEquals(2L, data.get("pending"));
        assertEquals(7L, data.get("processing")); assertEquals(11L, data.get("failed"));
        assertEquals(7L, data.get("unknown"));
        verify(logService).listMaps(any(QueryWrapper.class));
        verify(authHelper).requireAdmin("Bearer admin");
    }

    @Test
    void overviewNormalizesGroupedStatusCaseWithoutLosingCounts() {
        when(logService.listMaps(any(QueryWrapper.class))).thenReturn(List.of(
                Map.of("status", "posted", "cnt", 4L),
                Map.of("status", "POSTED", "cnt", 5L),
                Map.of("status", "prepare_failed", "cnt", 2L),
                Map.of("status", "publishing", "cnt", 3L),
                Map.of("status", "unknown", "cnt", 1L)));
        when(publisherClient.health()).thenReturn(Map.of("ok", true));

        var data = controller.overview("Bearer admin").getData();

        assertEquals(9L, data.get("posted"));
        assertEquals(2L, data.get("failed"));
        assertEquals(3L, data.get("processing"));
        assertEquals(1L, data.get("unknown"));
        assertEquals(0L, data.get("pending"));
    }
}
