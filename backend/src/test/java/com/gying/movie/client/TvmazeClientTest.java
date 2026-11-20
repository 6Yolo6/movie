package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TvmazeClientTest {
    final RestTemplate rest = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    final ResourceHubProperties properties = new ResourceHubProperties();
    final List<Long> waits = new ArrayList<>();
    final TvmazeClient client = new TvmazeClient(rest, new ObjectMapper(), properties) {
        @Override protected void pause(long ms) { waits.add(ms); }
    };

    @Test void noKeyIsNeededAndTheFeedCarriesRealUpstreamVersions() {
        server.expect(requestTo("https://api.tvmaze.com/updates/shows?since=day"))
                .andRespond(withSuccess("{\"65038\":1700000001,\"56716\":1700000002}", MediaType.APPLICATION_JSON));
        assertEquals(java.util.Map.of(65038L,1700000001L,56716L,1700000002L), client.fetchUpdates("day"));
        assertNull(properties.getTmdb().getApiKey()); server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{\"01\":1700000000}", "{\"x\":1700000000}",
            "{\"1\":\"1700000000\"}", "{\"1\":0}", "{\"1\":-1}", "{\"1\":1.5}", "{\"1\":999999999999999999999}"})
    void invalidFeedIsNeverTreatedAsEmptyOrSuccessful(String body) {
        server.expect(requestTo("https://api.tvmaze.com/updates/shows?since=week")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> client.fetchUpdates("week")); server.verify();
    }
    @Test void validatesShowAndOptionalExternalIds() {
        server.expect(requestTo("https://api.tvmaze.com/shows/65038")).andRespond(withSuccess("""
                {"id":65038,"name":"Three-Body","updated":1700000000,"genres":["Science-Fiction"],
                 "externals":{"imdb":"tt20242042","thetvdb":421555}}
                """, MediaType.APPLICATION_JSON));
        assertEquals("tt20242042", client.fetchShow(65038).path("externals").path("imdb").asText()); server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {
            "{\"id\":2,\"name\":\"Show\",\"updated\":1700000000,\"externals\":{},\"genres\":[]}",
            "{\"id\":1,\"name\":\"\",\"updated\":1700000000,\"externals\":{},\"genres\":[]}",
            "{\"id\":1,\"name\":\"Show\",\"updated\":1700000000,\"externals\":{\"imdb\":\"wrong\"},\"genres\":[]}",
            "{\"id\":1,\"name\":\"Show\",\"updated\":1700000000,\"externals\":{\"thetvdb\":\"123\"},\"genres\":[]}",
            "{\"id\":1,\"name\":\"Show\",\"updated\":1700000000,\"externals\":{},\"genres\":{}}"})
    void mismatchedOrMalformedShowCannotBind(String body) {
        server.expect(requestTo("https://api.tvmaze.com/shows/1")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> client.fetchShow(1)); server.verify();
    }
    @Test void rateLimitRetainsTheFullRetryAfterWithoutMoreHttpRequests() {
        server.expect(requestTo("https://api.tvmaze.com/updates/shows?since=day"))
                .andRespond(withTooManyRequests().header("Retry-After","3600"));
        Instant before = Instant.now();
        var limited = assertThrows(TvmazeClient.RateLimited.class, () -> client.fetchUpdates("day"));
        assertFalse(limited.retryAt().isBefore(before.plusSeconds(3600))); server.verify();
    }
    @Test void httpDateRetryAfterIsHonored() {
        Instant retry = Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        server.expect(requestTo("https://api.tvmaze.com/updates/shows?since=day"))
                .andRespond(withTooManyRequests().header("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(retry.atZone(ZoneOffset.UTC))));
        assertEquals(retry, assertThrows(TvmazeClient.RateLimited.class, () -> client.fetchUpdates("day")).retryAt());
        server.verify();
    }
    @Test void unavailableUpstreamUsesOnlyTwoRetriesAndAllRequestsArePaced() {
        String url = "https://api.tvmaze.com/updates/shows?since=month";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertTrue(client.fetchUpdates("month").isEmpty());
        assertTrue(waits.contains(1000L)); assertTrue(waits.contains(2000L));
        assertTrue(waits.stream().anyMatch(ms -> ms > 0 && ms <= 600)); server.verify();
    }
}
