package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.config.ResourceHubProperties;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PanSouClientTest {

    @Test
    void recognizesPanSouOkAndBadLinkStates() throws Exception {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getPansou().setBaseUrl("http://pansou.test");
        PanSouApiClient apiClient = mock(PanSouApiClient.class);
        PanSouClient client = new PanSouClient(
                new RestTemplateBuilder(), new ObjectMapper(), properties, apiClient);
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://pansou.test/api/check/links"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"results":[
                          {"url":"https://pan.quark.cn/s/good","state":"ok"},
                          {"url":"https://pan.quark.cn/s/bad","state":"bad"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        Map<String, LinkCheckResult> results = client.checkLinks(
                java.util.List.of("https://pan.quark.cn/s/good", "https://pan.quark.cn/s/bad"));

        assertTrue(results.get("https://pan.quark.cn/s/good").checked());
        assertTrue(results.get("https://pan.quark.cn/s/good").valid());
        assertTrue(results.get("https://pan.quark.cn/s/bad").checked());
        assertFalse(results.get("https://pan.quark.cn/s/bad").valid());
        server.verify();
    }

    @Test
    void interleavesLocalAndExternalResults() throws Exception {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getPansou().setBaseUrl("http://pansou.test");
        PanSouApiClient apiClient = mock(PanSouApiClient.class);
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchClouds("福尔摩斯", java.util.Set.of("QUARK"), 2)).thenReturn(List.of(resource(
                "外部结果",
                "https://pan.quark.cn/s/external")));
        PanSouClient client = new PanSouClient(
                new RestTemplateBuilder(), new ObjectMapper(), properties, apiClient);
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://pansou.test/api/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"data":{"results":[
                          {"title":"本地结果","url":"https://pan.quark.cn/s/local"}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        List<com.gying.movie.dto.DiscoveredResource> results = client.searchQuark("福尔摩斯", 2);

        assertTrue(results.get(0).getUrl().endsWith("/external"));
        assertTrue(results.get(1).getUrl().endsWith("/local"));
        server.verify();
    }

    private com.gying.movie.dto.DiscoveredResource resource(String title, String url) {
        com.gying.movie.dto.DiscoveredResource resource = new com.gying.movie.dto.DiscoveredResource();
        resource.setTitle(title);
        resource.setUrl(url);
        resource.setProvider("QUARK");
        return resource;
    }

    private RestTemplate restTemplate(PanSouClient client) throws Exception {
        Field field = PanSouClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
