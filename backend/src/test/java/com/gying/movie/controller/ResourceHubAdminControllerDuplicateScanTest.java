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
    final MovieDuplicateScannerService scanner = mock(MovieDuplicateScannerService.class);
    ResourceHubAdminController controller;
    MockMvc mvc;
    @BeforeEach void setup() {
        controller = new ResourceHubAdminController(auth, new ResourceHubProperties(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(controller, "duplicateScannerService", scanner);
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
}
