package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.CloudDriveWorkflowConfig;
import com.gying.movie.dto.ResourceHubConfigRequest;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.ISysConfigService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CloudDriveWorkflowConfigTest {
    @Test void exposesSupportedProvidersAndBaiduPreparationWithoutCredentialsOrNewDefaultKeys() throws Exception {
        var values = new HashMap<String, String>();
        var properties = new ResourceHubProperties();
        properties.getQuark().setToken("private-test-quark");
        properties.getXunlei().setAuthorization("private-test-xunlei");
        var workflows = new ResourceHubConfigServiceImpl(properties, storage(values)).getConfig().getDriveWorkflows();
        assertEquals(List.of("QUARK", "XUNLEI", "BAIDU"), workflows.stream().map(CloudDriveWorkflowConfig::getProvider).toList());
        assertTrue(workflows.get(0).isSupported());
        assertEquals("AVAILABLE", workflows.get(1).getStatus());
        assertTrue(workflows.get(0).getCredentialConfigured());
        assertTrue(workflows.get(1).getCredentialConfigured());
        assertFalse(workflows.get(2).isSupported());
        assertEquals("PLANNED", workflows.get(2).getStatus());
        assertFalse(workflows.get(2).getTransferEnabled());
        assertNull(workflows.get(2).getSavePath());
        String serialized = new ObjectMapper().writeValueAsString(workflows);
        assertFalse(serialized.contains("private-test"));
        assertTrue(values.keySet().stream().noneMatch(key -> key.startsWith("resource.hub.drive.")));
        assertFalse(properties.getWorker().isEnabled());
    }

    @Test void savesIndependentSettingsAndPartialEditsSurviveFreshService() {
        var values = new HashMap<String, String>();
        var storage = storage(values);
        var properties = new ResourceHubProperties();
        var service = new ResourceHubConfigServiceImpl(properties, storage);
        var quark = workflow("QUARK");
        quark.setDiscoveryEnabled(false); quark.setTransferEnabled(false); quark.setGyingPublishEnabled(false);
        quark.setShareEnabled(false); quark.setSavePath("/影视/夸克"); quark.setBatchLimit(2);
        var xunlei = workflow("XUNLEI");
        xunlei.setDiscoveryEnabled(true); xunlei.setTransferEnabled(true); xunlei.setGyingPublishEnabled(true);
        xunlei.setShareEnabled(true); xunlei.setSavePath("/影视/迅雷"); xunlei.setBatchLimit(7);
        service.updateConfig(request(quark, xunlei));
        assertEquals(12, values.size());
        assertEquals("false", values.get("resource.hub.drive.quark.discovery_enabled"));
        assertEquals("7", values.get("resource.hub.drive.xunlei.batch_limit"));
        var partial = workflow("QUARK"); partial.setBatchLimit(4);
        service.updateConfig(request(partial));
        var restarted = new ResourceHubProperties();
        var response = new ResourceHubConfigServiceImpl(restarted, storage).getConfig();
        assertEquals(4, response.getDriveWorkflows().get(0).getBatchLimit());
        assertEquals("/影视/夸克", restarted.getQuark().getSavePath());
        assertFalse(restarted.isDriveDiscoveryEnabled("QUARK"));
        assertFalse(restarted.isDriveTransferEnabled("QUARK"));
        assertFalse(restarted.isDriveGyingPublishEnabled("QUARK"));
        assertEquals("/影视/迅雷", restarted.getXunlei().getSavePath());
        assertTrue(restarted.isDriveTransferEnabled("XUNLEI"));
        assertEquals(7, restarted.getWorker().getXunleiLimit());
        assertFalse(restarted.getWorker().isEnabled());
        assertEquals("false", values.get("resource.hub.worker.enabled"));
    }

    @Test void legacyLimitAndEnvironmentDefaultsRemainEffectiveAndLegacyClientCanEditNewLimit() {
        var values = new HashMap<String, String>();
        values.put("resource.hub.worker.quark_limit", "9");
        var properties = new ResourceHubProperties();
        properties.getQuark().setSavePath("/env/quark");
        properties.getXunlei().setEnabled(true); properties.getWorker().setXunleiLimit(6);
        var service = new ResourceHubConfigServiceImpl(properties, storage(values));
        var initial = service.getConfig().getDriveWorkflows();
        assertEquals(9, initial.get(0).getBatchLimit());
        assertEquals("/env/quark", initial.get(0).getSavePath());
        assertEquals(6, initial.get(1).getBatchLimit());
        assertTrue(initial.get(1).getTransferEnabled());
        var quark = workflow("QUARK"); quark.setBatchLimit(2);
        service.updateConfig(request(quark));
        var legacy = new ResourceHubConfigRequest(); legacy.setWorkerQuarkLimit(5);
        service.updateConfig(legacy);
        assertEquals("5", values.get("resource.hub.drive.quark.batch_limit"));
        assertEquals(5, service.getConfig().getDriveWorkflows().get(0).getBatchLimit());
    }

    @Test void rejectsEntireInvalidRequestBeforeOtherWorkflowCredentialsOrGlobalSettingsChange() {
        var invalid = new ArrayList<CloudDriveWorkflowConfig>();
        invalid.add(null); invalid.add(workflow("UNKNOWN")); invalid.add(workflow("BAIDU"));
        for (String path : List.of("", "relative", "/foo/../bar", "/foo\\bar", "/foo\nbar", "/" + "a".repeat(300))) {
            var item = workflow("XUNLEI"); item.setSavePath(path); invalid.add(item);
        }
        for (int limit : List.of(0, 21)) {
            var item = workflow("XUNLEI"); item.setBatchLimit(limit); invalid.add(item);
        }
        for (var bad : invalid) {
            var properties = new ResourceHubProperties(); var storage = mock(ISysConfigService.class);
            var service = new ResourceHubConfigServiceImpl(properties, storage);
            var first = workflow("QUARK"); first.setTransferEnabled(false);
            var request = request(first, bad); request.setEnabled(true); request.setXunleiAuthorization("replacement");
            var error = assertThrows(ResponseStatusException.class, () -> service.updateConfig(request));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
            assertTrue(properties.getQuark().isTransferEnabled());
            assertFalse(properties.isEnabled()); assertNull(properties.getXunlei().getAuthorization());
            verifyNoInteractions(storage);
        }
        var storage = mock(ISysConfigService.class);
        var duplicate = request(workflow("QUARK"), workflow(" quark "));
        assertThrows(ResponseStatusException.class, () -> new ResourceHubConfigServiceImpl(new ResourceHubProperties(), storage).updateConfig(duplicate));
        verifyNoInteractions(storage);
    }

    @Test void ignoresSpoofedCapabilityMetadataAndRejectsUnknownFields() throws Exception {
        var config = workflow("QUARK"); config.setSupported(false); config.setStatus("PLANNED");
        config.setCredentialConfigured(true); config.setBatchLimit(3);
        var response = new ResourceHubConfigServiceImpl(new ResourceHubProperties(), storage(new HashMap<>())).updateConfig(request(config));
        var actual = response.getDriveWorkflows().get(0);
        assertTrue(actual.isSupported()); assertEquals("AVAILABLE", actual.getStatus()); assertFalse(actual.getCredentialConfigured());
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> new ObjectMapper().readValue("{\"provider\":\"QUARK\",\"accessToken\":\"unexpected\"}", CloudDriveWorkflowConfig.class));
        var baidu = workflow("BAIDU"); baidu.setTransferEnabled(false); baidu.setSupported(true);
        assertThrows(ResponseStatusException.class,
                () -> new ResourceHubConfigServiceImpl(new ResourceHubProperties(), mock(ISysConfigService.class)).updateConfig(request(baidu)));
    }

    @Test void failedPersistenceDoesNotChangeLiveDrivePolicy() {
        var properties = new ResourceHubProperties(); var storage = mock(ISysConfigService.class);
        var quark = workflow("QUARK"); quark.setTransferEnabled(false);
        var update = request(quark); update.setEnabled(true); update.setWorkerEnabled(true); update.setXunleiAuthorization("replacement");
        assertThrows(IllegalStateException.class,
                () -> new ResourceHubConfigServiceImpl(properties, storage).updateConfig(update));
        assertTrue(properties.isDriveTransferEnabled("QUARK"));
        assertFalse(properties.isEnabled()); assertFalse(properties.getWorker().isEnabled());
        assertNull(properties.getXunlei().getAuthorization());
    }

    @Test void independentPoliciesNeverEnableUnsupportedProviderOrAutomaticQueues() {
        var properties = new ResourceHubProperties();
        assertTrue(properties.isDriveDiscoveryEnabled("XUNLEI"));
        assertFalse(properties.isDriveTransferEnabled("XUNLEI"));
        assertTrue(properties.isDriveGyingPublishEnabled("XUNLEI"));
        properties.getXunlei().setDiscoveryEnabled(false); properties.getXunlei().setEnabled(true);
        assertFalse(properties.isDriveDiscoveryEnabled("XUNLEI")); assertTrue(properties.isDriveTransferEnabled("XUNLEI"));
        for (String provider : new String[] {null, "BAIDU", "OTHER", ""}) {
            assertFalse(properties.isDriveDiscoveryEnabled(provider));
            assertFalse(properties.isDriveTransferEnabled(provider));
            assertFalse(properties.isDriveGyingPublishEnabled(provider));
        }
        assertFalse(properties.getWorker().isEnabled());
    }

    @Test void rejectsCoercedScalarsAndIgnoresReadonlyMetadataDuringDeserialization() throws Exception {
        var mapper = new ObjectMapper();
        for (String fragment : List.of("\"batchLimit\":1.9", "\"batchLimit\":\"2\"", "\"discoveryEnabled\":1", "\"transferEnabled\":\"true\"", "\"shareEnabled\":0", "\"gyingPublishEnabled\":\"false\"")) {
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> mapper.readValue("{\"provider\":\"QUARK\"," + fragment + "}", CloudDriveWorkflowConfig.class));
        }
        var parsed = mapper.readValue("{\"provider\":\"QUARK\",\"supported\":true,\"status\":\"spoofed\",\"credentialConfigured\":true,\"batchLimit\":2,\"transferEnabled\":false}", CloudDriveWorkflowConfig.class);
        assertFalse(parsed.isSupported()); assertNull(parsed.getStatus()); assertNull(parsed.getCredentialConfigured());
        assertEquals(2, parsed.getBatchLimit()); assertFalse(parsed.getTransferEnabled());
    }

    private CloudDriveWorkflowConfig workflow(String provider) {
        var config = new CloudDriveWorkflowConfig(); config.setProvider(provider); return config;
    }

    private ResourceHubConfigRequest request(CloudDriveWorkflowConfig... workflows) {
        var request = new ResourceHubConfigRequest(); request.setDriveWorkflows(java.util.Arrays.asList(workflows)); return request;
    }

    private String key(QueryWrapper<?> query) {
        query.getSqlSegment();
        return (String) query.getParamNameValuePairs().values().iterator().next();
    }

    private ISysConfigService storage(Map<String, String> values) {
        var storage = mock(ISysConfigService.class);
        when(storage.getConfigValue(anyString(), anyString())).thenAnswer(call -> values.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(storage.count(any())).thenAnswer(call -> values.containsKey(key(call.getArgument(0))) ? 1L : 0L);
        when(storage.getOne(any())).thenAnswer(call -> {
            String key = key(call.getArgument(0));
            if (!values.containsKey(key)) return null;
            var config = new SysConfig(); config.setId(1L); config.setConfigKey(key); config.setConfigValue(values.get(key)); return config;
        });
        org.mockito.stubbing.Answer<Boolean> save = call -> {
            SysConfig config = call.getArgument(0); values.put(config.getConfigKey(), config.getConfigValue()); return true;
        };
        when(storage.save(any())).thenAnswer(save); when(storage.updateById(any())).thenAnswer(save);
        return storage;
    }
}
