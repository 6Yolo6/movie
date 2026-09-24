package com.gying.movie.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GyingSourceClientTest {
    @Test void outageOpensCircuitAndRecoversAfterCooldown() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fixture");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.build();
        AtomicLong clock = new AtomicLong(1000);
        GyingSourceClient client = new GyingSourceClient(rest, rest, "fixture-internal", clock::get);
        server.expect(requestTo("http://fixture/search")).andExpect(header("X-Internal-Token", "fixture-internal"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("private upstream response"));
        server.expect(requestTo("http://fixture/search")).andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        var error = assertThrows(ResponseStatusException.class, () -> client.get("/search"));
        assertEquals(503, error.getStatusCode().value());
        assertFalse(error.getMessage().contains("private upstream"));
        assertThrows(ResponseStatusException.class, () -> client.get("/search", Map.of("keyword", "test")));
        clock.addAndGet(30_001);
        assertTrue(client.get("/search").containsKey("items"));
        server.verify();
    }
    @Test void missingMovieDoesNotDisableOtherReads() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fixture");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.build();
        GyingSourceClient client = new GyingSourceClient(rest, rest, "", System::currentTimeMillis);
        server.expect(requestTo("http://fixture/missing")).andRespond(withResourceNotFound());
        server.expect(requestTo("http://fixture/search")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(org.springframework.web.client.RestClientResponseException.class, () -> client.get("/missing"));
        assertEquals(Map.of(), client.get("/search"));
        server.verify();
    }
}
