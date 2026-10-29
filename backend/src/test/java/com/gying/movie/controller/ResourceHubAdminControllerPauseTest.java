package com.gying.movie.controller;

import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.utils.AuthHelper;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResourceHubAdminControllerPauseTest {
    final AuthHelper auth = mock(AuthHelper.class);
    final IResourceHubConfigService config = mock(IResourceHubConfigService.class);
    ResourceHubAdminController controller;
    MockMvc mvc;
    @BeforeEach void setup() {
        controller = new ResourceHubAdminController(auth, new ResourceHubProperties(), null, null, null,
                null, null, null, null, null, null, null, config, null, null, null, null, null, null, null, null);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @AfterEach void close() { controller.shutdownDiscoveryPipelineExecutor(); }
    @Test void pauseAndResumeReturnOnlyTheRequestedSourceState() throws Exception {
        mvc.perform(put("/api/admin/resource-hub/gying/sources/HITS_MOVIE/pause")
                .header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"code\":\"OK\",\"message\":\"OK\",\"data\":{\"source\":\"HITS_MOVIE\",\"paused\":true}}", true));
        mvc.perform(put("/api/admin/resource-hub/gying/sources/HITS_MOVIE/pause")
                .header("Authorization", "Bearer fixture").contentType(MediaType.APPLICATION_JSON).content("{\"paused\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paused").value(false));
        verify(config).setGyingSourcePaused("HITS_MOVIE", true); verify(config).setGyingSourcePaused("HITS_MOVIE", false);
        verify(config, never()).getConfig(); verify(config, never()).updateConfig(any());
    }
    @ParameterizedTest @ValueSource(strings={"", "null", "[]", "{}", "{\"paused\":null}", "{\"paused\":1}", "{\"paused\":\"true\"}", "{\"paused\":true,\"other\":false}", "{\"paused\":true,\"paused\":false}", "{\"paused\":true} {}"})
    void rejectsMalformedOrNonBooleanPauseBodies(String body) throws Exception {
        mvc.perform(put("/api/admin/resource-hub/gying/sources/HITS_TV/pause")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(config);
    }
    @ParameterizedTest @ValueSource(strings={"POPULAR_MOVIE", "hits_movie", "UNKNOWN"})
    void rejectsUnknownCatalogSources(String source) throws Exception {
        mvc.perform(put("/api/admin/resource-hub/gying/sources/"+source+"/pause")
                .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}")).andExpect(status().isBadRequest());
        verifyNoInteractions(config);
    }
    @Test void unauthorizedRequestIsRejectedBeforeValidationOrPersistence() throws Exception {
        when(auth.requireAdmin(null)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc.perform(put("/api/admin/resource-hub/gying/sources/HITS_MOVIE/pause")
                .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}")).andExpect(status().isForbidden());
        verifyNoInteractions(config);
    }
}
