package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuarkMovieDirectoryAiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TITLE = "穿普拉达的女王2";
    private static final String ENGLISH = "The Devil Wears Prada 2";
    private static final List<QuarkMovieDirectoryAiClient.Candidate> CANDIDATES = List.of(
            new QuarkMovieDirectoryAiClient.Candidate("d0", "/全集/【普.拉.DA.2 (2026)】4K REMUX",
                    List.of("2026.2160p.UHD.BluRay.REMUX.mkv")));

    @Test
    void acceptsOnlyAnObservedDirectoryWithTheTargetIdentity() throws Exception {
        HttpClient http = mock(HttpClient.class);
        ResourceHubProperties properties = new ResourceHubProperties();
        QuarkMovieDirectoryAiClient client = fixture(http, properties, output("MATCH", "d0", TITLE, 2026, 2));
        assertEquals("d0", select(client));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).sendAsync(requests.capture(), any());
        HttpRequest request = requests.getValue();
        assertEquals("http://host.docker.internal:21434/api/chat", request.uri().toString());
        assertEquals(20, request.timeout().orElseThrow().toSeconds());
        assertEquals("POST", request.method());
        assertEquals(List.of("application/json"), request.headers().allValues("Content-Type"));
        assertFalse(request.headers().firstValue("Authorization").isPresent());
        String payload = body(request);
        JsonNode json = mapper.readTree(payload);
        assertEquals("qwen3:4b-instruct-2507-q4_K_M", json.path("model").asText());
        assertFalse(json.path("stream").asBoolean(true));
        assertFalse(json.path("think").asBoolean(true));
        assertEquals(4096, json.path("options").path("num_ctx").asInt());
        assertEquals(256, json.path("options").path("num_predict").asInt());
        assertEquals(0, json.path("options").path("temperature").asDouble());
        assertFalse(json.path("format").path("additionalProperties").asBoolean(true));
        assertEquals(List.of("", "d0"), mapper.convertValue(json.path("format").path("properties")
                .path("directoryId").path("enum"), List.class));
        assertTrue(payload.contains("不可信数据"));
        assertTrue(payload.contains("UNKNOWN"));
        assertFalse(payload.contains("pan.quark.cn"));
    }

    @Test
    void rejectsInventedDirectoriesAndIncorrectIdentityFields() throws Exception {
        for (String content : List.of(
                output("MATCH", "not-observed", TITLE, 2026, 2),
                output("MATCH", "d0", "穿普拉达的女王", 2026, 2),
                output("MATCH", "d0", TITLE, 2006, 2),
                output("MATCH", "d0", TITLE, 2026, 1),
                output("UNKNOWN", "d0", TITLE, 2026, 2),
                output("MATCH", "", TITLE, 2026, 2),
                output("MATCH", "d0", TITLE, 2026, 2).replace("2026", "\"2026\""),
                output("MATCH", "d0", TITLE, 2026, 2).replace("2026", "2026.0"),
                output("MATCH", "d0", TITLE, 2026, 2).replace("2}", "2,\"url\":\"https://unobserved.example/\"}"),
                output("MATCH", "d0", TITLE, 2026, 2) + " {}",
                output("MATCH", "d0", TITLE, 2026, 2).replace("{", "{\"year\":2026,"),
                "```json\n" + output("MATCH", "d0", TITLE, 2026, 2) + "\n```")) {
            HttpClient http = mock(HttpClient.class);
            assertNull(select(fixture(http, new ResourceHubProperties(), content)), content);
            verify(http, times(1)).sendAsync(any(), any());
        }
    }

    @Test
    void acceptsTheExactEnglishTitleWithoutAddingAliasesToTheAllowedIdentity() throws Exception {
        HttpClient http = mock(HttpClient.class);
        assertEquals("d0", select(fixture(http, new ResourceHubProperties(), output("MATCH", "d0", ENGLISH, 2026, 2))));
    }

    @Test
    void doesNotFollowRedirectsOrAcceptUnfinishedResponses() throws Exception {
        for (int status : List.of(302, 400, 500)) {
            HttpClient http = mock(HttpClient.class);
            HttpResponse<byte[]> response = response(status, wrapper(output("MATCH", "d0", TITLE, 2026, 2)));
            when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(response));
            assertNull(select(new QuarkMovieDirectoryAiClient(mapper, new ResourceHubProperties(), http)));
            verify(http, times(1)).sendAsync(any(), any());
        }
        for (String body : List.of(
                wrapper(output("MATCH", "d0", TITLE, 2026, 2)).replace("\"done\":true", "\"done\":false"),
                wrapper(output("MATCH", "d0", TITLE, 2026, 2)).replace("\"stop\"", "\"length\""),
                wrapper(output("MATCH", "d0", TITLE, 2026, 2)).replace("\"assistant\"", "\"user\""),
                wrapper(output("MATCH", "d0", TITLE, 2026, 2)) + " {}",
                "not-json", " ", "{\"done\":true}")) {
            HttpClient http = mock(HttpClient.class);
            var reply = java.util.concurrent.CompletableFuture.completedFuture(response(200, body));
            when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(reply);
            assertNull(select(new QuarkMovieDirectoryAiClient(mapper, new ResourceHubProperties(), http)));
        }
    }

    @Test
    void enforcesResponseAndContentBounds() throws Exception {
        for (String body : List.of(" ".repeat(16 * 1024 + 1), wrapper(" ".repeat(4097)))) {
            HttpClient http = mock(HttpClient.class);
            var reply = java.util.concurrent.CompletableFuture.completedFuture(response(200, body));
            when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(reply);
            assertNull(select(new QuarkMovieDirectoryAiClient(mapper, new ResourceHubProperties(), http)));
        }
        QuarkMovieDirectoryAiClient.LimitedBodySubscriber subscriber = new QuarkMovieDirectoryAiClient.LimitedBodySubscriber(4);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        assertFalse(subscriber.getBody().toCompletableFuture().isDone());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {4, 5})));
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
        verify(subscription).cancel();
    }

    @Test
    void bodySubscriberPreservesBytesAtTheExactBound() throws Exception {
        QuarkMovieDirectoryAiClient.LimitedBodySubscriber subscriber = new QuarkMovieDirectoryAiClient.LimitedBodySubscriber(4);
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4})));
        subscriber.onComplete();
        assertArrayEquals(new byte[] {1, 2, 3, 4}, subscriber.getBody().toCompletableFuture().get());
    }

    @Test
    void failsClosedOnTimeoutAndCanHandleTheNextIndependentSelection() throws Exception {
        HttpClient http = mock(HttpClient.class);
        var pending = new java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>();
        var success = java.util.concurrent.CompletableFuture.completedFuture(response(200, wrapper(output("MATCH", "d0", TITLE, 2026, 2))));
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(pending).thenReturn(success);
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().getDirectoryAi().setTimeoutSeconds(1);
        QuarkMovieDirectoryAiClient client = new QuarkMovieDirectoryAiClient(mapper, properties, http);
        long started = System.nanoTime();
        assertNull(select(client));
        assertTrue(pending.isCancelled());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
        assertEquals("d0", select(client));
        verify(http, times(2)).sendAsync(any(), any());
    }

    @Test
    void acceptsOnlyLocalHttpEndpointsWithoutCredentialsPathsOrParameters() throws Exception {
        for (String base : List.of("https://127.0.0.1:21434", "http://remote.example:21434", "http://127.0.0.1.evil:21434",
                "http://127.0.0.2:21434", "http://user:password@127.0.0.1:21434", "http://127.0.0.1:21434/?token=hidden",
                "http://127.0.0.1:21434/#fragment", "http://127.0.0.1:21434/other", "http://127.0.0.1:0", "http://127.0.0.1:99999")) {
            HttpClient http = mock(HttpClient.class);
            ResourceHubProperties properties = new ResourceHubProperties();
            properties.getQuark().getDirectoryAi().setBaseUrl(base);
            assertNull(select(new QuarkMovieDirectoryAiClient(mapper, properties, http)), base);
            verifyNoInteractions(http);
        }
        for (String base : List.of("http://localhost:21434", "http://127.0.0.1:21434/", "http://[::1]:21434")) {
            HttpClient http = mock(HttpClient.class);
            ResourceHubProperties properties = new ResourceHubProperties();
            properties.getQuark().getDirectoryAi().setBaseUrl(base);
            assertEquals("d0", select(fixture(http, properties, output("MATCH", "d0", TITLE, 2026, 2))));
        }
    }

    @Test
    void rejectsDisabledOversizedDuplicateAndEmptyInputWithoutCallingTheModel() throws Exception {
        HttpClient http = mock(HttpClient.class);
        ResourceHubProperties properties = new ResourceHubProperties();
        QuarkMovieDirectoryAiClient client = new QuarkMovieDirectoryAiClient(mapper, properties, http);
        properties.getQuark().getDirectoryAi().setEnabled(false);
        assertNull(select(client));
        properties.getQuark().getDirectoryAi().setEnabled(true);
        assertNull(client.select(null, null, null, 2026, 2, CANDIDATES));
        assertNull(client.select(TITLE, ENGLISH, null, null, 2, CANDIDATES));
        assertNull(client.select(TITLE, ENGLISH, null, 2026, 0, CANDIDATES));
        assertNull(client.select(TITLE, ENGLISH, "x".repeat(2001), 2026, 2, CANDIDATES));
        assertNull(client.select(TITLE, ENGLISH, "别".repeat(1500), 2026, 2, CANDIDATES));
        assertNull(client.select(TITLE, ENGLISH, null, 2026, 2, List.of()));
        assertNull(client.select(TITLE, ENGLISH, null, 2026, 2, List.of(CANDIDATES.get(0), CANDIDATES.get(0))));
        assertNull(client.select(TITLE, ENGLISH, null, 2026, 2,
                List.of(new QuarkMovieDirectoryAiClient.Candidate("d0", "p".repeat(601), List.of("video.mkv")))));
        assertNull(client.select(TITLE, ENGLISH, null, 2026, 2,
                List.of(new QuarkMovieDirectoryAiClient.Candidate("d0", "path", List.of("x".repeat(221))))));
        assertNull(client.select(TITLE, ENGLISH, null, 2026, 2,
                java.util.Collections.nCopies(9, CANDIDATES.get(0))));
        assertNull(client.select(TITLE, ENGLISH, null, 2026, 2,
                List.of(new QuarkMovieDirectoryAiClient.Candidate("d0", "path", java.util.Collections.nCopies(9, "video.mkv")))));
        List<QuarkMovieDirectoryAiClient.Candidate> large = java.util.stream.IntStream.range(0, 8).mapToObj(i ->
                new QuarkMovieDirectoryAiClient.Candidate("d" + i, "p".repeat(600) + i,
                        List.of("v".repeat(220), "w".repeat(220), "x".repeat(220)))).toList();
        assertNull(client.select(TITLE, ENGLISH, null, 2026, 2, large));
        verifyNoInteractions(http);
    }

    @Test
    void avoidsASecondInferenceWhileOneSelectionIsInFlight() throws Exception {
        HttpClient http = mock(HttpClient.class);
        CountDownLatch entered = new CountDownLatch(1);
        var pending = new java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>();
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            entered.countDown();
            return pending;
        });
        QuarkMovieDirectoryAiClient client = new QuarkMovieDirectoryAiClient(mapper, new ResourceHubProperties(), http);
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> select(client));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNull(select(client));
            verify(http, times(1)).sendAsync(any(), any());
        } finally { pending.complete(response(200, wrapper(output("MATCH", "d0", TITLE, 2026, 2)))); }
        assertEquals("d0", first.get(3, TimeUnit.SECONDS));
    }

    private QuarkMovieDirectoryAiClient fixture(HttpClient http, ResourceHubProperties properties, String content) throws Exception {
        var reply = java.util.concurrent.CompletableFuture.completedFuture(response(200, wrapper(content)));
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(reply);
        return new QuarkMovieDirectoryAiClient(mapper, properties, http);
    }
    private HttpResponse<byte[]> response(int status, String body) {
        @SuppressWarnings("unchecked") HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }
    private String wrapper(String content) throws Exception {
        var body = mapper.createObjectNode().put("done", true).put("done_reason", "stop");
        body.putObject("message").put("role", "assistant").put("content", content);
        return body.toString();
    }
    private String output(String decision, String id, String title, int year, int installment) {
        return mapper.createObjectNode().put("decision", decision).put("directoryId", id).put("expandedTitle", title)
                .put("year", year).put("installment", installment).toString();
    }
    private String select(QuarkMovieDirectoryAiClient client) { return client.select(TITLE, ENGLISH, null, 2026, 2, CANDIDATES); }

    private String body(HttpRequest request) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes); }
            public void onError(Throwable failure) { done.completeExceptionally(failure); }
            public void onComplete() { done.complete(null); }
        });
        done.get(1, TimeUnit.SECONDS);
        return output.toString(StandardCharsets.UTF_8);
    }
}
