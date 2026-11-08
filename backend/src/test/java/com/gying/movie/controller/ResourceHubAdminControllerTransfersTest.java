package com.gying.movie.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IXunleiTransferRunnerService;
import com.gying.movie.service.IResourceHubConfigService;
import com.gying.movie.utils.AuthHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class ResourceHubAdminControllerTransfersTest {
    final AuthHelper auth = mock(AuthHelper.class);
    final ResourceHubProperties properties = new ResourceHubProperties();
    final IResourceHubConfigService config = mock(IResourceHubConfigService.class);
    final IQuarkTransferRunnerService quark = mock(IQuarkTransferRunnerService.class);
    final IXunleiTransferRunnerService xunlei = mock(IXunleiTransferRunnerService.class);
    ResourceHubAdminController controller;
    MockMvc mvc;

    @BeforeEach void setup() {
        properties.setEnabled(true); properties.getXunlei().setEnabled(true);
        properties.getWorker().setQuarkLimit(4); properties.getWorker().setXunleiLimit(7);
        controller = new ResourceHubAdminController(auth, properties, null, null, null, null, null, null,
                quark, xunlei, null, null, config, null, null, null, null, null, null, null, null);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var result = new QuarkTransferRunResult(); result.setSubmitted(1);
        when(quark.submitPending(anyInt())).thenReturn(result); when(xunlei.submitPending(anyInt())).thenReturn(result);
    }

    @AfterEach void close() { controller.shutdownDiscoveryPipelineExecutor(); }

    @Test void unauthorizedRequestNeverParsesWritesConfigurationOrExecutesTransfers() throws Exception {
        when(auth.requireAdmin(null)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"BAIDU\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(config, quark, xunlei);
    }

    @ParameterizedTest @ValueSource(strings = {"QUARK", "XUNLEI"})
    void dispatchesOnlyTheRequestedProviderWithItsConfiguredBatchLimit(String provider) throws Exception {
        mvc.perform(post("/api/admin/resource-hub/transfers/run").header("Authorization", "Bearer fixture")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"" + provider + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.submitted").value(1));
        verify(auth).requireAdmin("Bearer fixture"); verify(config).reload();
        if ("QUARK".equals(provider)) { verify(quark).submitPending(4); verifyNoInteractions(xunlei); }
        else { verify(xunlei).submitPending(7); verifyNoInteractions(quark); }
        verify(config, never()).updateConfig(any());
        Assertions.assertFalse(properties.getWorker().isEnabled());
    }

    @Test void explicitBatchLimitOverridesOnlyThisRunWithoutSavingSettings() throws Exception {
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"QUARK\",\"limit\":2}"))
                .andExpect(status().isOk());
        verify(quark).submitPending(2); verifyNoInteractions(xunlei); verify(config).reload();
        verify(config, never()).updateConfig(any()); Assertions.assertEquals(4, properties.getWorker().getQuarkLimit());
    }

    @ParameterizedTest @ValueSource(strings = {
        "", "null", "[]", "{}", "{\"provider\":null}", "{\"provider\":1}",
        "{\"provider\":\"UNKNOWN\"}",
        "{\"provider\":\"QUARK\",\"limit\":0}", "{\"provider\":\"QUARK\",\"limit\":21}",
        "{\"provider\":\"QUARK\",\"limit\":null}", "{\"provider\":\"QUARK\",\"limit\":1.5}",
        "{\"provider\":\"QUARK\",\"limit\":\"2\"}", "{\"provider\":\"QUARK\",\"limit\":true}",
        "{\"provider\":\"QUARK\",\"limit\":2,\"other\":1}",
        "{\"provider\":\"QUARK\",\"provider\":\"XUNLEI\"}",
        "{\"provider\":\"QUARK\",\"limit\":2,\"limit\":3}", "{\"provider\":\"QUARK\"} {}"
    })
    void rejectsInvalidBodiesAndUnsupportedProvidersWithoutRunningOrReloading(String body) throws Exception {
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(config, quark, xunlei);
    }

    @ParameterizedTest @ValueSource(strings = {"QUARK", "XUNLEI"})
    void disabledProviderCannotBeRunEvenWhenCalledDirectlyThroughTheApi(String provider) throws Exception {
        if ("QUARK".equals(provider)) properties.getQuark().setTransferEnabled(false);
        else properties.getXunlei().setEnabled(false);
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"" + provider + "\",\"limit\":2}"))
                .andExpect(status().isConflict());
        verify(config).reload(); verifyNoInteractions(quark, xunlei);
    }

    @Test void baiduDefaultsRejectExecutionAndNeverFallThroughToLegacyRunners() throws Exception {
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"BAIDU\"}")).andExpect(status().isConflict());
        verify(config).reload(); verifyNoInteractions(quark, xunlei);
    }

    @Test void torrentOnlyManualBatchExecutesOnlyThatProvidersMetadataQueue() throws Exception {
        properties.getQuark().setVideoEnabled(false); properties.getQuark().setTorrentEnabled(true);
        var archives = mock(com.gying.movie.service.impl.P2pArchiveTaskService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "p2pArchiveTaskService", archives);
        var archived = new QuarkTransferRunResult(); archived.setSubmitted(2);
        when(archives.submitPending("QUARK", 4)).thenReturn(archived);
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"QUARK\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.submitted").value(2));
        verify(archives).submitPending("QUARK", 4); verifyNoInteractions(quark, xunlei);
        Assertions.assertFalse(properties.getWorker().isEnabled());
    }

    @Test void sharedVideoAndTorrentBudgetNeverExceedsConfiguredBatchLimit() throws Exception {
        var archives = mock(com.gying.movie.service.impl.P2pArchiveTaskService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "p2pArchiveTaskService", archives);
        var videos = new QuarkTransferRunResult(); videos.setSubmitted(3);
        var metadata = new QuarkTransferRunResult(); metadata.setSubmitted(1);
        when(quark.submitPending(4)).thenReturn(videos); when(archives.submitPending("QUARK", 1)).thenReturn(metadata);
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"QUARK\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.submitted").value(4));
        verify(archives).submitPending("QUARK", 1); verifyNoInteractions(xunlei);
    }

    @Test void baiduHasADedicatedBatchRunnerWithoutEnablingWorkerOrOtherProviders() throws Exception {
        properties.getBaidu().setEnabled(true); properties.getBaidu().setVideoEnabled(true);
        var baidu = mock(com.gying.movie.service.IBaiduTransferRunnerService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "baiduTransferRunnerService", baidu);
        var result = new QuarkTransferRunResult(); result.setSubmitted(1);
        when(baidu.submitPending(5)).thenReturn(result);
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"BAIDU\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.submitted").value(1));
        verify(baidu).submitPending(5); verifyNoInteractions(quark, xunlei);
        Assertions.assertFalse(properties.getWorker().isEnabled());
    }

    @Test void disabledResourceHubNeverExecutesTransfer() throws Exception {
        properties.setEnabled(false);
        mvc.perform(post("/api/admin/resource-hub/transfers/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"QUARK\"}"))
                .andExpect(status().isConflict());
        verify(config).reload(); verifyNoInteractions(quark, xunlei);
    }
}
