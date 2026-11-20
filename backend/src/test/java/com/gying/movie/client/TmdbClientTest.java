package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.TmdbListPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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


    @Test
    void automaticListRetainsValidatedPaginationAndItemIdentity() {
        ListFixture fixture = listResponse(2, """
                {"page":2,"total_pages":501,"total_results":2147483648,
                 "results":[{"id":112732,"title":"Fixture movie","original_title":"Fixture original",
                              "release_date":"2026-01-01","popularity":12.5}]}
                """);

        TmdbListPage page = fixture.client().fetchListPage(" popular_movie ", 2);

        assertEquals(2, page.page());
        assertEquals(501, page.totalPages());
        assertEquals(2147483648L, page.totalResults());
        assertEquals(1, page.items().size());
        assertEquals(112732L, page.items().get(0).getTmdbId());
        assertEquals("movie", page.items().get(0).getMediaType());
        assertEquals("Fixture movie", page.items().get(0).getTitle());
        assertEquals("Fixture original", page.items().get(0).getOriginalTitle());
        assertEquals("2026-01-01", page.items().get(0).getReleaseDate());
        assertEquals(12.5, page.items().get(0).getPopularity());
        fixture.server().verify();
    }

    @Test
    void anEmptyPageBeyondTheReportedEndIsAValidatedExhaustion() {
        ListFixture fixture = listResponse(3,
                "{\"page\":3,\"total_pages\":2,\"total_results\":25,\"results\":[]}");

        TmdbListPage page = fixture.client().fetchListPage("POPULAR_MOVIE", 3);

        assertEquals(3, page.page());
        assertEquals(2, page.totalPages());
        assertEquals(25L, page.totalResults());
        assertTrue(page.items().isEmpty());
        fixture.server().verify();
    }

    @Test
    void aCatalogWithNoPagesAndNoResultsIsAValidatedEmptyCatalog() {
        ListFixture fixture = listResponse(1,
                "{\"page\":1,\"total_pages\":0,\"total_results\":0,\"results\":[]}");

        TmdbListPage page = fixture.client().fetchListPage("POPULAR_MOVIE", 1);

        assertEquals(0, page.totalPages());
        assertEquals(0L, page.totalResults());
        assertTrue(page.items().isEmpty());
        fixture.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "{}", "{\"results\":[]}",
            "{\"total_pages\":0,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":0}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":0,\"results\":null}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":0,\"results\":{}}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":0,\"results\":\"empty\"}",
            "{\"page\":null,\"total_pages\":0,\"total_results\":0,\"results\":[]}",
            "{\"page\":\"1\",\"total_pages\":0,\"total_results\":0,\"results\":[]}",
            "{\"page\":1.0,\"total_pages\":0,\"total_results\":0,\"results\":[]}",
            "{\"page\":true,\"total_pages\":0,\"total_results\":0,\"results\":[]}",
            "{\"page\":2147483648,\"total_pages\":0,\"total_results\":0,\"results\":[]}",
            "{\"page\":0,\"total_pages\":0,\"total_results\":0,\"results\":[]}",
            "{\"page\":2,\"total_pages\":0,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":null,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":\"0\",\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0.0,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":false,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":-1,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":2147483648,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":null,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":\"0\",\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":0.0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":false,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":-1,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":9223372036854775808,\"results\":[]}",
            "{\"page\":1,\"total_pages\":1,\"total_results\":1,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":1,\"results\":[]}",
            "{\"page\":1,\"total_pages\":1,\"total_results\":0,\"results\":[]}",
            "{\"page\":1,\"total_pages\":0,\"total_results\":0,\"results\":[{\"id\":1}]}"
    })
    void malformedOrContradictoryPaginationCannotBeTreatedAsAnEmptyCatalog(String body) {
        ListFixture fixture = listResponse(1, body);

        assertThrows(IllegalStateException.class,
                () -> fixture.client().fetchListPage("POPULAR_MOVIE", 1));
        fixture.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "true", "7", "\"item\"", "[]", "{}",
            "{\"id\":null}", "{\"id\":true}", "{\"id\":0}", "{\"id\":-1}",
            "{\"id\":\"112732\"}", "{\"id\":112732.5}", "{\"id\":112732.0}",
            "{\"id\":9223372036854775808}"
    })
    void invalidItemsFailTheWholeAutomaticPageRatherThanBeingSilentlyDropped(String item) {
        ListFixture fixture = listResponse(1,
                "{\"page\":1,\"total_pages\":1,\"total_results\":2,\"results\":[{\"id\":1}," + item + "]}");

        assertThrows(IllegalStateException.class,
                () -> fixture.client().fetchListPage("POPULAR_MOVIE", 1));
        fixture.server().verify();
    }

    @Test
    void itemsBeyondTheReportedPageBoundsFailInsteadOfCreatingAFalseCheckpoint() {
        ListFixture fixture = listResponse(3,
                "{\"page\":3,\"total_pages\":2,\"total_results\":25,\"results\":[{\"id\":1}]}");

        assertThrows(IllegalStateException.class,
                () -> fixture.client().fetchListPage("POPULAR_MOVIE", 3));
        fixture.server().verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 501})
    void invalidRequestedPagesFailBeforeMakingAnHttpRequest(int page) {
        var properties = new ResourceHubProperties();
        properties.getTmdb().setApiKey("test-key");
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var client = new TmdbClient(rest, new ObjectMapper(), properties);

        assertThrows(IllegalArgumentException.class,
                () -> client.fetchListPage("POPULAR_MOVIE", page));
        server.verify();
    }

    @Test
    void legacyFetchListStillAcceptsResultsWithoutPaginationMetadata() {
        ListFixture fixture = listResponse(1,
                "{\"results\":[{\"id\":112732,\"title\":\"Fixture movie\"},{\"id\":0}]}");

        var items = fixture.client().fetchList("POPULAR_MOVIE", 1);

        assertEquals(1, items.size());
        assertEquals(112732L, items.get(0).getTmdbId());
        fixture.server().verify();
    }

    private ListFixture listResponse(int page, String body) {
        var properties = new ResourceHubProperties();
        properties.getTmdb().setApiKey("test-key");
        properties.getTmdb().setRequestIntervalMs(0);
        properties.getTmdb().setMaxRetries(0);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://api.themoviedb.org/3/movie/popular?api_key=test-key"
                + "&language=zh-CN&page=" + page + "&include_adult=false"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        return new ListFixture(new TmdbClient(rest, new ObjectMapper(), properties), server);
    }

    private record ListFixture(TmdbClient client, MockRestServiceServer server) { }

}
