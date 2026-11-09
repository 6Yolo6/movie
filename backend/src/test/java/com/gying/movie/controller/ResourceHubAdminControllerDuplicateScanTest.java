package com.gying.movie.controller;

import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.service.impl.MovieDuplicateScannerService;
import com.gying.movie.utils.AuthHelper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResourceHubAdminControllerDuplicateScanTest {
    final AuthHelper auth = mock(AuthHelper.class);
    final com.gying.movie.service.impl.MovieDuplicateMergeService merges = mock(com.gying.movie.service.impl.MovieDuplicateMergeService.class);
    final MovieDuplicateScannerService scanner = mock(MovieDuplicateScannerService.class);
    ResourceHubAdminController controller;
    MockMvc mvc;
    @BeforeEach void setup() {
        controller = new ResourceHubAdminController(auth, new ResourceHubProperties(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(controller, "duplicateScannerService", scanner);
        ReflectionTestUtils.setField(controller, "duplicateMergeService", merges);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @AfterEach void close() { controller.shutdownDiscoveryPipelineExecutor(); }
    @Test void defaultsToAnAdminOnlyFullLibraryScan() throws Exception {
        when(scanner.scan(1, 100)).thenReturn(Map.of("scanMode", "FULL_LIBRARY", "mergeEnabled", false));
        mvc.perform(post("/api/admin/resource-hub/cleanup/duplicate-tmdb").header("Authorization", "Bearer fixture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scanMode").value("FULL_LIBRARY"));
        verify(auth).requireAdmin("Bearer fixture"); verify(scanner).scan(1, 100);
    }
    @Test void pageAndLimitControlTheCandidatePageOnly() throws Exception {
        when(scanner.scan(3, 20)).thenReturn(Map.of("page", 3));
        mvc.perform(post("/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true&page=3&limit=20"))
                .andExpect(status().isOk()); verify(scanner).scan(3, 20);
    }
    @Test void legacyBulkMergeIsRejectedBeforeReadingOrWritingMovieReferences() throws Exception {
        mvc.perform(post("/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=false"))
                .andExpect(status().isConflict()); verifyNoInteractions(scanner);
    }
    @Test void unauthorizedScanNeverReadsTheCatalog() throws Exception {
        when(auth.requireAdmin(null)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc.perform(post("/api/admin/resource-hub/cleanup/duplicate-tmdb"))
                .andExpect(status().isForbidden()); verifyNoInteractions(scanner);
    }
    @Test void reviewedEndpointsBindExplicitPairsAndRequireAnAdministrator() throws Exception {
        when(auth.requireAdmin("Bearer fixture")).thenReturn(new com.gying.movie.dto.AuthUser(7L,"fixture-admin","ADMIN"));
        when(merges.preview(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(7L))).thenReturn(Map.of("reviewId","fixture-review","canMerge",true));
        mvc.perform(post("/api/admin/resource-hub/duplicates/preview").header("Authorization","Bearer fixture")
            .contentType("application/json").content("{\"pairs\":[{\"fromMovieId\":\"Source\",\"toMovieId\":\"Keep\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.canMerge").value(true));
        verify(merges).preview(new com.gying.movie.service.impl.MovieDuplicateMergeService.PreviewRequest(
            java.util.List.of(new com.gying.movie.service.impl.MovieDuplicateMergeService.Pair("Source","Keep"))),7L);
        when(auth.requireAdmin(null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Unauthorized"));
        mvc.perform(post("/api/admin/resource-hub/duplicates/merge").contentType("application/json")
            .content("{\"reviewId\":\"r\",\"idempotencyKey\":\"k\",\"confirmed\":true}"))
            .andExpect(status().isUnauthorized());
        verify(merges,never()).merge(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
}
