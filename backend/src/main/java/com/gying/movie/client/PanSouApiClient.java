package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.DiscoveredResource;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PanSouApiClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 2;
    private static final long MAX_RETRY_DELAY_MS = 5000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;
    private final Sleeper sleeper;

    @Autowired
    public PanSouApiClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            ResourceHubProperties properties) {
        this(restTemplateBuilder, objectMapper, properties, REQUEST_TIMEOUT, Thread::sleep);
    }

    PanSouApiClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            ResourceHubProperties properties,
            Duration timeout,
            Sleeper sleeper) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(timeout)
                .setReadTimeout(timeout)
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    public boolean isConfigured() {
        return hasText(properties.getPansou().getApiBaseUrl())
                && hasText(properties.getPansou().getApiKey());
    }

    public List<DiscoveredResource> searchQuark(String keyword, int maxResults) {
        return searchClouds(keyword, Set.of("QUARK"), maxResults);
    }

    public List<DiscoveredResource> searchOtherClouds(String keyword, int maxResults) {
        return searchClouds(keyword, PanSouSearchResultParser.otherCloudProviders(), maxResults);
    }

    public List<DiscoveredResource> searchClouds(
            String keyword,
            Set<String> providers,
            int maxResults) {
        if (!hasText(keyword) || !isConfigured()) {
            return List.of();
        }
        String url = UriComponentsBuilder.fromUriString(properties.getPansou().getApiBaseUrl())
                .path("/api/search")
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", properties.getPansou().getApiKey().trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kw", keyword.trim());
        body.put("res", "all");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException(
                            "PanSou API search failed with HTTP " + response.getStatusCode().value());
                }
                JsonNode root = objectMapper.readTree(response.getBody());
                return PanSouSearchResultParser.parseProviders(root, maxResults, providers);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 429 && attempt < MAX_RETRIES) {
                    sleep(retryDelayMillis(e.getResponseHeaders(), attempt));
                    continue;
                }
                throw new IllegalStateException(
                        "PanSou API search failed with HTTP " + e.getStatusCode().value(), e);
            } catch (ResourceAccessException e) {
                throw new IllegalStateException("PanSou API search timed out or is unavailable", e);
            } catch (RestClientException e) {
                throw new IllegalStateException("PanSou API search request failed", e);
            } catch (Exception e) {
                throw new IllegalStateException("PanSou API search response parse failed", e);
            }
        }
        throw new IllegalStateException("PanSou API search retry limit reached");
    }

    private long retryDelayMillis(HttpHeaders headers, int attempt) {
        long fallback = Math.min(500L * (1L << attempt), MAX_RETRY_DELAY_MS);
        if (headers == null) {
            return fallback;
        }
        String retryAfter = headers.getFirst("Retry-After");
        if (!hasText(retryAfter)) {
            return fallback;
        }
        try {
            return Math.min(Math.max(Long.parseLong(retryAfter.trim()) * 1000L, 0), MAX_RETRY_DELAY_MS);
        } catch (NumberFormatException ignored) {
            try {
                long delay = Duration.between(
                        ZonedDateTime.now(),
                        ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME))
                        .toMillis();
                return Math.min(Math.max(delay, 0), MAX_RETRY_DELAY_MS);
            } catch (DateTimeParseException ignoredDate) {
                return fallback;
            }
        }
    }

    private void sleep(long delayMillis) {
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PanSou API retry interrupted", e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long delayMillis) throws InterruptedException;
    }
}
