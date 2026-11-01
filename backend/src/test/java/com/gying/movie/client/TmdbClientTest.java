package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TmdbClientTest {

    @Test
    void retriesRateLimitedRequestsWithoutDroppingTheSync() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getTmdb().setApiKey("test-key");
        properties.getTmdb().setRequestIntervalMs(0);
        properties.getTmdb().setMaxRetries(1);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String url = "https://api.themoviedb.org/3/movie/popular?api_key=test-key"
                + "&language=zh-CN&page=1&include_adult=false";
        server.expect(requestTo(url)).andRespond(withTooManyRequests().header("Retry-After", "0"));
        server.expect(requestTo(url)).andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        TmdbClient client = new TmdbClient(restTemplate, new ObjectMapper(), properties);

        assertEquals(0, client.fetchList("POPULAR_MOVIE", 1).size());
        server.verify();
    }
    @Test
    void aStalledHttpResponseCannotHoldARequestIndefinitely() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/movie/popular", exchange -> {
            try { release.await(20, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var properties = new ResourceHubProperties();
            properties.getTmdb().setApiKey("fixture-only");
            properties.getTmdb().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getTmdb().setMaxRetries(0);
            properties.getTmdb().setRequestIntervalMs(0);
            var client = new TmdbClient(new org.springframework.boot.web.client.RestTemplateBuilder(), new ObjectMapper(), properties);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(15), () -> {
                IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> client.fetchList("POPULAR_MOVIE", 1));
                org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("timed out"));
            });
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void interactiveSuggestionsDoNotWaitThroughBackgroundRetryBackoff() {
        var properties = new ResourceHubProperties();
        properties.getTmdb().setApiKey("test-key");
        properties.getTmdb().setRequestIntervalMs(0);
        properties.getTmdb().setMaxRetries(5);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://api.themoviedb.org/3/search/multi?api_key=test-key&language=zh-CN&query=test&page=1&include_adult=false"))
                .andRespond(withTooManyRequests().header("Retry-After", "30"));
        var client = new TmdbClient(rest, new ObjectMapper(), properties);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> client.searchMultiForSuggestion("test", 10)));
        server.verify();
    }

}
