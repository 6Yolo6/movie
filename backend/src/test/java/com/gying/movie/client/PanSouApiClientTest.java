package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.DiscoveredResource;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class PanSouApiClientTest {

    @Test
    void readsSuccessfulMergedByTypeResponse() throws Exception {
        PanSouApiClient client = client(delay -> {
        });
        MockRestServiceServer server = server(client);
        server.expect(requestTo("https://www.panso.best/api/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "test-key"))
                .andExpect(content().json("{\"kw\":\"福尔摩斯\",\"res\":\"all\"}", true))
                .andRespond(withSuccess("""
                        {"code":0,"message":"success","data":{"merged_by_type":{
                          "quark":[{"note":"福尔摩斯","url":"https://pan.quark.cn/s/api-result",
                                    "source":"plugin:test"}]
                        }}}
                        """, MediaType.APPLICATION_JSON));

        List<DiscoveredResource> results = client.searchQuark("福尔摩斯", 5);

        assertEquals(1, results.size());
        assertEquals("福尔摩斯", results.get(0).getTitle());
        assertEquals("https://pan.quark.cn/s/api-result", results.get(0).getUrl());
        server.verify();
    }

    @Test
    void readsNonQuarkCloudDriveFallbacks() throws Exception {
        PanSouApiClient client = client(delay -> {
        });
        MockRestServiceServer server = server(client);
        server.expect(requestTo("https://www.panso.best/api/search"))
                .andExpect(content().json("{\"kw\":\"哈哈哈哈哈\",\"res\":\"all\"}", true))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"merged_by_type":{
                          "baidu":[{"note":"哈哈哈哈哈 第二季","url":"https://pan.baidu.com/s/test",
                                    "password":"abcd"}],
                          "magnet":[{"note":"忽略磁力","url":"magnet:?xt=test"}]
                        }}}
                        """, MediaType.APPLICATION_JSON));

        List<DiscoveredResource> results = client.searchOtherClouds("哈哈哈哈哈", 5);

        assertEquals(1, results.size());
        assertEquals("BAIDU", results.get(0).getProvider());
        assertEquals("abcd", results.get(0).getCode());
        server.verify();
    }

    @Test
    void readsCurrentDataResultsShapeAndFiltersBeforeLimit() throws Exception {
        PanSouApiClient client = client(delay -> {
        });
        MockRestServiceServer server = server(client);
        server.expect(requestTo("https://www.panso.best/api/search"))
                .andExpect(content().json("{\"kw\":\"密室大逃脱7\",\"res\":\"all\"}", true))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"total":3,"results":[
                          {"title":"磁力结果","links":[
                            {"work_title":"密室大逃脱 第7季","url":"magnet:?xt=test"}
                          ]},
                          {"title":"百度结果","links":[
                            {"work_title":"密室大逃脱 第7季","url":"https://pan.baidu.com/s/season7"}
                          ]},
                          {"title":"夸克结果","links":[
                            {"work_title":"密室大逃脱 第7季","url":"https://pan.quark.cn/s/season7"}
                          ]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        List<DiscoveredResource> results = client.searchClouds("密室大逃脱7", java.util.Set.of("BAIDU"), 1);

        assertEquals(1, results.size());
        assertEquals("BAIDU", results.get(0).getProvider());
        assertEquals("密室大逃脱 第7季", results.get(0).getTitle());
        server.verify();
    }

    @Test
    void treatsUnauthorizedAsError() throws Exception {
        PanSouApiClient client = client(delay -> {
        });
        MockRestServiceServer server = server(client);
        server.expect(requestTo("https://www.panso.best/api/search"))
                .andExpect(content().json("{\"kw\":\"福尔摩斯\",\"res\":\"all\"}", true))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> client.searchQuark("福尔摩斯", 5));

        assertTrue(error.getMessage().contains("HTTP 401"));
        server.verify();
    }

    @Test
    void retriesRateLimitUsingCappedRetryAfter() throws Exception {
        List<Long> delays = new ArrayList<>();
        PanSouApiClient client = client(delays::add);
        MockRestServiceServer server = server(client);
        server.expect(requestTo("https://www.panso.best/api/search"))
                .andExpect(content().json("{\"kw\":\"福尔摩斯\",\"res\":\"all\"}", true))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "30"));
        server.expect(requestTo("https://www.panso.best/api/search"))
                .andExpect(content().json("{\"kw\":\"福尔摩斯\",\"res\":\"all\"}", true))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"merged_by_type":{"quark":[]}}}
                        """, MediaType.APPLICATION_JSON));

        client.searchQuark("福尔摩斯", 5);

        assertEquals(List.of(5000L), delays);
        server.verify();
    }

    @Test
    void reportsTimeoutAsError() throws Exception {
        PanSouApiClient client = client(delay -> {
        });
        MockRestServiceServer server = server(client);
        server.expect(requestTo("https://www.panso.best/api/search"))
                .andExpect(content().json("{\"kw\":\"福尔摩斯\",\"res\":\"all\"}", true))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> client.searchQuark("福尔摩斯", 5));

        assertTrue(error.getMessage().contains("timed out"));
        server.verify();
    }

    private PanSouApiClient client(PanSouApiClient.Sleeper sleeper) {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getPansou().setApiBaseUrl("https://www.panso.best");
        properties.getPansou().setApiKey("test-key");
        return new PanSouApiClient(
                new RestTemplateBuilder(),
                new ObjectMapper(),
                properties,
                Duration.ofMillis(100),
                sleeper);
    }

    private MockRestServiceServer server(PanSouApiClient client) throws Exception {
        Field field = PanSouApiClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return MockRestServiceServer.createServer((RestTemplate) field.get(client));
    }
}
