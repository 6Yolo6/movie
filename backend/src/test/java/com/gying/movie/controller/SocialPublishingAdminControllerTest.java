package com.gying.movie.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gying.movie.client.SocialPublisherClient;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.entity.SocialPublishTarget;
import com.gying.movie.service.ISocialPostLogService;
import com.gying.movie.service.ISocialPublishTargetService;
import com.gying.movie.service.impl.SocialPublishingService;
import com.gying.movie.utils.AuthHelper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SocialPublishingAdminControllerTest {
    private AuthHelper authHelper;
    private ISocialPublishTargetService targetService;
    private SocialPublishingAdminController controller;

    @BeforeEach
    void setUp() {
        authHelper = mock(AuthHelper.class);
        targetService = mock(ISocialPublishTargetService.class);
        controller = new SocialPublishingAdminController(
                authHelper,
                targetService,
                mock(ISocialPostLogService.class),
                mock(SocialPublishingService.class),
                mock(SocialPublisherClient.class));
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
    void rejectsUnsupportedCredentialProfile() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                controller.createTarget(Map.of(
                        "platform", "QQ_CHANNEL",
                        "accountKey", "another-account",
                        "name", "频道",
                        "targetRef", "pd12345678"), "Bearer admin"));

        assertEquals("QQ publisher currently supports account key: secondary", error.getMessage());
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
}
