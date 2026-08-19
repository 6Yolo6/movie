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
}
