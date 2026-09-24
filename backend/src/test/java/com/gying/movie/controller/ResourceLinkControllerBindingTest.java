package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.dto.ResourceSubmissionDTO;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.*;
import com.gying.movie.utils.AuthHelper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceLinkControllerBindingTest {
    @Mock IResourceLinkService resourceLinkService;
    @Mock IMovieMetadataService movieService;
    @Mock ISysConfigService sysConfigService;
    @Mock AuthHelper authHelper;
    @InjectMocks ResourceLinkController controller;
    AutoCloseable mocks;
    ResourceSubmissionDTO dto;
    ResourceLink existing;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        when(authHelper.requireResourcePublisher("owner")).thenReturn(new AuthUser(1L, "fixture", "ADMIN"));
        existing = new ResourceLink(); existing.setId(10L); existing.setMovieId("first");
        existing.setUrl("https://pan.quark.cn/s/fixture"); existing.setType("DISK"); existing.setStatus("ACTIVE");
        existing.setUploaderId(1L); existing.setAuditStatus(1);
        when(resourceLinkService.getById(10L)).thenReturn(existing);
        when(resourceLinkService.updateById(any())).thenReturn(true);
        for (String id : List.of("first", "second")) {
            MovieMetadata movie = new MovieMetadata(); movie.setId(id); movie.setStatus("ACTIVE");
            when(movieService.getById(id)).thenReturn(movie);
        }
        dto = new ResourceSubmissionDTO(); dto.setMovieId("first"); dto.setType("DISK");
        dto.setUrl(existing.getUrl()); dto.setBindMovieIds(List.of("first", "second", "second"));
    }
    @AfterEach void close() throws Exception { controller.shutdownRepairInvalidExecutor(); mocks.close(); }

    @Test void adminSubmissionSkipsUserTotalQuotaButStillChecksDuplicateUrl() {
        dto.setBindMovieIds(List.of());
        when(sysConfigService.getConfigValue("resource.audit.enabled", "true")).thenReturn("true");
        when(sysConfigService.getConfigValue("resource.submit.interval.seconds", "60")).thenReturn("0");
        assertEquals(200,controller.submitResource(dto,"owner").getStatusCode().value());
        verify(sysConfigService,never()).getConfigValue(eq("resource.max.per.user"),anyString());
        verify(resourceLinkService).addResource(any());
        when(resourceLinkService.count(any())).thenReturn(1L);
        assertEquals(409,controller.submitResource(dto,"owner").getStatusCode().value());
    }
    @Test void publisherStillCannotExceedTotalQuota() {
        when(authHelper.requireResourcePublisher("owner")).thenReturn(new AuthUser(1L,"fixture","PUBLISHER"));
        when(sysConfigService.getConfigValue("resource.max.per.user","100")).thenReturn("1");
        when(resourceLinkService.count(any())).thenReturn(1L);dto.setBindMovieIds(List.of());
        assertEquals(403,controller.submitResource(dto,"owner").getStatusCode().value());
        verify(resourceLinkService,never()).addResource(any());
    }

    @Test void editAppendsDeduplicatedBindings() {
        var response = controller.updateOwnResource(10L, dto, "owner");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, ((Map<?, ?>) response.getBody()).get("boundCount"));
        var saved = ArgumentCaptor.forClass(ResourceLink.class);
        verify(resourceLinkService).addResource(saved.capture());
        assertNull(saved.getValue().getId());
        assertEquals("second", saved.getValue().getMovieId());
        assertEquals(existing.getUrl(), saved.getValue().getUrl());
    }
    @Test void alreadyBoundResourceIsSkipped() {
        when(resourceLinkService.count(any())).thenReturn(0L, 1L);
        assertEquals(200, controller.updateOwnResource(10L, dto, "owner").getStatusCode().value());
        verify(resourceLinkService, never()).addResource(any());
    }
    @Test void validatesAllBindingsBeforeUpdatingOriginal() {
        dto.setBindMovieIds(List.of("missing"));
        assertEquals(400, controller.updateOwnResource(10L, dto, "owner").getStatusCode().value());
        verify(resourceLinkService, never()).updateById(any());
        verify(resourceLinkService, never()).addResource(any());
    }
    @Test void movingPrimaryMovieChecksDuplicatesOnTarget() {
        dto.setMovieId("second"); dto.setBindMovieIds(List.of());
        when(resourceLinkService.count(any())).thenReturn(1L);
        assertEquals(409, controller.updateOwnResource(10L, dto, "owner").getStatusCode().value());
        var query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(resourceLinkService).count(query.capture());
        query.getValue().getSqlSegment();
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("second"));
        verify(resourceLinkService, never()).updateById(any());
    }
    @Test void rejectsAnotherPublishersResource() {
        when(authHelper.requireResourcePublisher("owner")).thenReturn(new AuthUser(2L, "other", "PUBLISHER"));
        assertEquals(403, controller.updateOwnResource(10L, dto, "owner").getStatusCode().value());
        verify(resourceLinkService, never()).updateById(any());
    }
}
