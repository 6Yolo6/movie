package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.TmdbListItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class TmdbClient {

    private static final String LANGUAGE = "zh-CN";
    private static final Map<String, String> LIST_ENDPOINTS = Map.of(
            "TRENDING_MOVIE_DAY", "/trending/movie/day",
            "TRENDING_TV_DAY", "/trending/tv/day",
            "POPULAR_MOVIE", "/movie/popular",
            "POPULAR_TV", "/tv/popular",
            "TOP_RATED_MOVIE", "/movie/top_rated",
            "TOP_RATED_TV", "/tv/top_rated",
            "UPCOMING_MOVIE", "/movie/upcoming"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;
    private final Object rateLimitLock = new Object();
    private long lastRequestNanos;

    @Autowired
    public TmdbClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper,
            ResourceHubProperties properties) {
        this(restTemplateBuilder.build(), objectMapper, properties);
    }

    TmdbClient(RestTemplate restTemplate, ObjectMapper objectMapper, ResourceHubProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<TmdbListItem> fetchList(String source, int page) {
        String normalizedSource = normalizeSource(source);
        String endpoint = LIST_ENDPOINTS.get(normalizedSource);
        if (endpoint == null) {
            throw new IllegalArgumentException("Unsupported TMDB source: " + source);
        }

        JsonNode root = getJson(endpoint, builder -> builder
                .queryParam("language", LANGUAGE)
                .queryParam("page", page)
                .queryParam("include_adult", false));
        List<TmdbListItem> items = new ArrayList<>();
        for (JsonNode node : root.path("results")) {
            long tmdbId = node.path("id").asLong(0L);
            String mediaType = mediaTypeForSource(normalizedSource, node.path("media_type").asText(null));
            if (tmdbId <= 0 || (!"movie".equals(mediaType) && !"tv".equals(mediaType))) {
                continue;
            }
            TmdbListItem item = new TmdbListItem();
            item.setTmdbId(tmdbId);
            item.setMediaType(mediaType);
            populateListFields(item, node, mediaType);
            items.add(item);
        }
        return items;
    }

    public JsonNode fetchDetails(String mediaType, long tmdbId) {
        if (!"movie".equals(mediaType) && !"tv".equals(mediaType)) {
            throw new IllegalArgumentException("Unsupported TMDB media type: " + mediaType);
        }
        return getJson("/" + mediaType + "/" + tmdbId, builder -> builder
                .queryParam("language", LANGUAGE)
                .queryParam("append_to_response", "credits,alternative_titles"));
    }

    public List<TmdbListItem> searchMulti(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        JsonNode root = getJson("/search/multi", builder -> builder
                .queryParam("language", LANGUAGE)
                .queryParam("query", query.trim())
                .queryParam("page", 1)
                .queryParam("include_adult", false));
        List<TmdbListItem> items = new ArrayList<>();
        int safeMax = Math.min(Math.max(maxResults <= 0 ? 5 : maxResults, 1), 20);
        for (JsonNode node : root.path("results")) {
            if (items.size() >= safeMax) {
                break;
            }
            String mediaType = node.path("media_type").asText(null);
            long tmdbId = node.path("id").asLong(0L);
            if (tmdbId <= 0 || (!"movie".equals(mediaType) && !"tv".equals(mediaType))) {
                continue;
            }
            TmdbListItem item = new TmdbListItem();
            item.setTmdbId(tmdbId);
            item.setMediaType(mediaType);
            populateListFields(item, node, mediaType);
            items.add(item);
        }
        return items;
    }

    public String normalizeSource(String source) {
        String normalized = source == null || source.isBlank()
                ? "TRENDING_MOVIE_DAY"
                : source.trim().toUpperCase(Locale.ROOT);
        if (!LIST_ENDPOINTS.containsKey(normalized)) {
            throw new IllegalArgumentException("Unsupported TMDB source: " + source);
        }
        return normalized;
    }

    private JsonNode getJson(String endpoint, QueryCustomizer customizer) {
        requireApiKey();
        String baseUrl = properties.getTmdb().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("TMDB API base URL is not configured");
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(baseUrl.replaceAll("/+$", "") + endpoint)
                .queryParam("api_key", properties.getTmdb().getApiKey());
        customizer.customize(builder);
        int maxRetries = Math.min(Math.max(properties.getTmdb().getMaxRetries(), 0), 5);
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            awaitRequestSlot();
            try {
                ResponseEntity<String> response = restTemplate.getForEntity(builder.toUriString(), String.class);
                return objectMapper.readTree(response.getBody());
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status >= 500) && attempt < maxRetries) {
                    sleep(retryDelayMillis(e.getResponseHeaders(), attempt));
                    continue;
                }
                throw new IllegalStateException(
                        "TMDB request failed with HTTP " + status + ": " + endpoint, e);
            } catch (ResourceAccessException e) {
                if (attempt < maxRetries) {
                    sleep(retryDelayMillis(null, attempt));
                    continue;
                }
                throw new IllegalStateException("TMDB request timed out or is unavailable: " + endpoint, e);
            } catch (RestClientException e) {
                throw new IllegalStateException("TMDB request failed: " + endpoint, e);
            } catch (Exception e) {
                throw new IllegalStateException("TMDB response parse failed: " + endpoint, e);
            }
        }
        throw new IllegalStateException("TMDB retry limit reached: " + endpoint);
    }

    private void awaitRequestSlot() {
        long intervalMs = Math.min(Math.max(properties.getTmdb().getRequestIntervalMs(), 0), 10000);
        if (intervalMs <= 0) {
            return;
        }
        synchronized (rateLimitLock) {
            long intervalNanos = Duration.ofMillis(intervalMs).toNanos();
            long remainingNanos = intervalNanos - (System.nanoTime() - lastRequestNanos);
            if (lastRequestNanos > 0 && remainingNanos > 0) {
                sleep(Math.max(Duration.ofNanos(remainingNanos).toMillis(), 1));
            }
            lastRequestNanos = System.nanoTime();
        }
    }

    private long retryDelayMillis(HttpHeaders headers, int attempt) {
        long fallback = Math.min(1000L * (1L << attempt), 10000L);
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return fallback;
        }
        try {
            return Math.min(Math.max(Long.parseLong(retryAfter.trim()) * 1000L, 0), 30000L);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TMDB request interrupted", e);
        }
    }

    private void requireApiKey() {
        if (properties.getTmdb().getApiKey() == null || properties.getTmdb().getApiKey().isBlank()) {
            throw new IllegalStateException("TMDB API key is not configured");
        }
    }

    private String mediaTypeForSource(String source, String fallback) {
        if (source.contains("_MOVIE")) {
            return "movie";
        }
        if (source.contains("_TV")) {
            return "tv";
        }
        return fallback;
    }

    private void populateListFields(TmdbListItem item, JsonNode node, String mediaType) {
        item.setTitle("tv".equals(mediaType)
                ? node.path("name").asText(null)
                : node.path("title").asText(null));
        item.setOriginalTitle("tv".equals(mediaType)
                ? node.path("original_name").asText(null)
                : node.path("original_title").asText(null));
        item.setReleaseDate("tv".equals(mediaType)
                ? node.path("first_air_date").asText(null)
                : node.path("release_date").asText(null));
        item.setPopularity(node.path("popularity").isNumber()
                ? node.path("popularity").asDouble()
                : null);
    }

    @FunctionalInterface
    private interface QueryCustomizer {
        void customize(UriComponentsBuilder builder);
    }
}
