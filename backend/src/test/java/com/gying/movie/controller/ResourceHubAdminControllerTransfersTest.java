package com.gying.movie.controller;

import static com.gying.movie.service.impl.BaiduScopeTestAssertions.*;
import static org.junit.jupiter.api.Assertions.*;

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

    @ParameterizedTest @ValueSource(strings = {TEMP_SCOPE, "UNKNOWN_SCOPE"})
    void ordinaryBaiduSubmitRejectsTemporaryAndUnknownScopeBeforeReloadOrRemoteAction(String scope) throws Exception {
        var tasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        var runner = mock(com.gying.movie.service.IBaiduTransferRunnerService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "baiduTransferTaskService", tasks);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "baiduTransferRunnerService", runner);
        var row = new com.gying.movie.entity.BaiduTransferTask(); row.setId(88L); row.setScopeKey(scope);
        when(tasks.getOne(any(), eq(false))).thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return row; });
        mvc.perform(post("/api/admin/resource-hub/baidu/transfers/88/submit")).andExpect(status().isConflict());
        verifyNoInteractions(runner); verify(config, never()).reload();
    }

    @Test void ordinaryBaiduSubmitStillAllowsAnExplicitPermanentTask() throws Exception {
        var tasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        var runner = mock(com.gying.movie.service.IBaiduTransferRunnerService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "baiduTransferTaskService", tasks);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "baiduTransferRunnerService", runner);
        var row = new com.gying.movie.entity.BaiduTransferTask(); row.setId(88L);
        when(tasks.getOne(any(), eq(false))).thenAnswer(call -> { permanentTaskQuery(call.getArgument(0)); return row; });
        when(runner.submitOne(88L)).thenReturn(new QuarkTransferRunResult());
        mvc.perform(post("/api/admin/resource-hub/baidu/transfers/88/submit")).andExpect(status().isOk());
        verify(runner).submitOne(88L); verify(config).reload();
    }

    @Test void ordinaryBaiduListIsScopedAndDoesNotExposeTemporaryTaskHistory() throws Exception {
        var tasks = mock(com.gying.movie.service.IBaiduTransferTaskService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "baiduTransferTaskService", tasks);
        var permanent = new com.gying.movie.entity.BaiduTransferTask(); permanent.setId(88L);
        var temporary = new com.gying.movie.entity.BaiduTransferTask(); temporary.setId(89L); temporary.setScopeKey(TEMP_SCOPE);
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.gying.movie.entity.BaiduTransferTask>();
        page.setRecords(java.util.List.of(temporary, permanent)); page.setTotal(1);
        when(tasks.page(any(), any())).thenAnswer(call -> { permanentTaskQuery(call.getArgument(1)); return page; });
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/resource-hub/baidu/transfers"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(88));
        assertEquals(TEMP_SCOPE, temporary.getScopeKey());
    }

    @Test void temporaryCloneCannotBePublishedOrRetriedThroughOrdinaryAdminEndpoints() throws Exception {
        var discoveries = mock(com.gying.movie.service.IResourceDiscoveryResultService.class);
        var publish = mock(com.gying.movie.service.IResourceHubPublishService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "discoveryResultService", discoveries);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "resourceHubPublishService", publish);
        var clone = new com.gying.movie.entity.ResourceDiscoveryResult(); clone.setId(99L); clone.setStatus("SAVED");
        clone.setSource("QQ_BOT"); clone.setSourceRef("BAIDU_TEMP:" + TEMP_SCOPE); clone.setResourceLinkId(88L);
        when(discoveries.getById(99L)).thenReturn(clone);
        mvc.perform(post("/api/admin/resource-hub/discoveries/99/publish")).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/resource-hub/discoveries/99/retry-share-publish")).andExpect(status().isConflict());
        verifyNoInteractions(publish, quark, xunlei); verify(discoveries, never()).updateById(any());
        assertEquals("QQ_BOT", clone.getSource()); assertEquals("BAIDU_TEMP:" + TEMP_SCOPE, clone.getSourceRef());
    }

    @Test void ordinaryDiscoveryListExcludesScopedClonesAndAllowsNullSourceRefs() throws Exception {
        var discoveries = mock(com.gying.movie.service.IResourceDiscoveryResultService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "discoveryResultService", discoveries);
        var permanent = new com.gying.movie.entity.ResourceDiscoveryResult(); permanent.setId(88L);
        var temporary = new com.gying.movie.entity.ResourceDiscoveryResult(); temporary.setId(89L); temporary.setSourceRef("BAIDU_TEMP:" + TEMP_SCOPE);
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.gying.movie.entity.ResourceDiscoveryResult>();
        page.setRecords(java.util.List.of(temporary, permanent)); page.setTotal(1);
        when(discoveries.page(any(), any())).thenAnswer(call -> { permanentDiscoveryQuery(call.getArgument(1)); return page; });
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/resource-hub/discoveries"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(88));
    }

}
