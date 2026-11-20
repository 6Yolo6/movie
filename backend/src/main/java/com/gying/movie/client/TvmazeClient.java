package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Public show API. All requests share the per-IP pacing lock; no API key is needed. */
@Component
public class TvmazeClient {
    public static final String LICENSE = "https://creativecommons.org/licenses/by-sa/4.0/";
    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final ResourceHubProperties properties;
    private final Object requestLock = new Object();
    private long lastRequestNanos;

    public static final class RateLimited extends IllegalStateException {
        private final Instant retryAt;
        public RateLimited(Instant retryAt) { super("TVmaze rate limited"); this.retryAt = retryAt; }
        public Instant retryAt() { return retryAt; }
    }

    @Autowired
    public TvmazeClient(RestTemplateBuilder builder, ObjectMapper mapper, ResourceHubProperties properties) {
        this(builder.connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(10)).build(), mapper, properties);
    }
    TvmazeClient(RestTemplate rest, ObjectMapper mapper, ResourceHubProperties properties) {
        this.rest = rest; this.mapper = mapper; this.properties = properties;
    }

    public Map<Long, Long> fetchUpdates(String window) {
        if (!Set.of("day", "week", "month").contains(window)) throw new IllegalArgumentException("Invalid TVmaze update window");
        JsonNode root = get("/updates/shows?since=" + window);
        if (root == null || !root.isObject()) throw new IllegalStateException("Invalid TVmaze update feed");
        Map<Long, Long> updates = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            if (!entry.getKey().matches("[1-9][0-9]{0,11}") || !entry.getValue().isIntegralNumber()
                    || !entry.getValue().canConvertToLong() || entry.getValue().asLong() <= 0
                    || entry.getValue().asLong() > Instant.now().plusSeconds(300).getEpochSecond()) {
                throw new IllegalStateException("Invalid TVmaze update identity/timestamp");
            }
            updates.put(Long.parseLong(entry.getKey()), entry.getValue().asLong());
        });
        return updates;
    }

    public JsonNode fetchShow(long id) {
        if (id <= 0) throw new IllegalArgumentException("Invalid TVmaze show ID");
        JsonNode show = get("/shows/" + id);
        if (show == null || !show.isObject() || !show.path("id").isIntegralNumber()
                || show.path("id").asLong() != id || !show.path("name").isTextual()
                || show.path("name").asText().isBlank() || !show.path("updated").isIntegralNumber()
                || !show.path("updated").canConvertToLong() || show.path("updated").asLong() <= 0
                || !show.path("externals").isObject() || !show.path("genres").isArray()) {
            throw new IllegalStateException("TVmaze show identity/details are invalid");
        }
        JsonNode imdb = show.path("externals").path("imdb"), tvdb = show.path("externals").path("thetvdb");
        if (!(imdb.isMissingNode() || imdb.isNull() || imdb.isTextual() && imdb.asText().matches("tt[0-9]{5,12}"))
                || !(tvdb.isMissingNode() || tvdb.isNull() || tvdb.isIntegralNumber() && tvdb.canConvertToLong()
                        && tvdb.asLong() > 0)) {
            throw new IllegalStateException("TVmaze external show identity is invalid");
        }
        return show;
    }

    private JsonNode get(String path) {
        String base = properties.getTvmaze().getBaseUrl();
        if (base == null || base.isBlank()) throw new IllegalStateException("TVmaze base URL is missing");
        int retries = Math.min(Math.max(properties.getTvmaze().getMaxRetries(), 0), 2);
        for (int attempt = 0; attempt <= retries; attempt++) {
            awaitSlot();
            try {
                String body = rest.getForObject(base.replaceAll("/+$", "") + path, String.class);
                return mapper.readTree(body);
            } catch (HttpStatusCodeException error) {
                int status = error.getStatusCode().value();
                // Do not shorten Retry-After or keep requesting other IDs in the same round.
                if (status == 429) throw new RateLimited(retryAt(error.getResponseHeaders()));
                if (status >= 500 && attempt < retries) { pause(1000L << attempt); continue; }
                throw new IllegalStateException("TVmaze request failed with HTTP " + status);
            } catch (ResourceAccessException error) {
                if (attempt < retries) { pause(1000L << attempt); continue; }
                throw new IllegalStateException("TVmaze request timed out or is unavailable");
            } catch (RestClientException error) {
                throw new IllegalStateException("TVmaze request failed");
            } catch (Exception error) {
                throw new IllegalStateException("TVmaze response parse failed");
            }
        }
        throw new IllegalStateException("TVmaze retry limit reached");
    }

    private Instant retryAt(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            if (value != null && value.trim().matches("[0-9]{1,9}"))
                return Instant.now().plusSeconds(Math.max(10, Long.parseLong(value.trim())));
            if (value != null) return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().isAfter(Instant.now()) ? ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                    : Instant.now().plusSeconds(10);
        } catch (RuntimeException ignored) { /* A malformed header uses the provider's ten-second window. */ }
        return Instant.now().plusSeconds(10);
    }

    private void awaitSlot() {
        long interval = Math.min(Math.max(properties.getTvmaze().getRequestIntervalMs(), 600), 10000);
        synchronized (requestLock) {
            long remaining = Duration.ofMillis(interval).toNanos() - (System.nanoTime() - lastRequestNanos);
            if (lastRequestNanos > 0 && remaining > 0) pause(Math.max(Duration.ofNanos(remaining).toMillis(), 1));
            lastRequestNanos = System.nanoTime();
        }
    }
    protected void pause(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("TVmaze request interrupted"); }
    }
}
