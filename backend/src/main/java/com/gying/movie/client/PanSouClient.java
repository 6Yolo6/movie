package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.DiscoveredResource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PanSouClient {

    private static final Logger log = LoggerFactory.getLogger(PanSouClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;
    private final PanSouApiClient panSouApiClient;

    public PanSouClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper,
            ResourceHubProperties properties, PanSouApiClient panSouApiClient) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.panSouApiClient = panSouApiClient;
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
        if (!hasText(keyword)) {
            return List.of();
        }
        Set<String> acceptedProviders = providers == null ? Set.of() : providers;
        RuntimeException localError = null;
        List<DiscoveredResource> localResults;
        try {
            localResults = searchLocal(keyword, acceptedProviders, maxResults);
        } catch (RuntimeException e) {
            localResults = List.of();
            localError = e;
        }

        List<DiscoveredResource> apiResults = List.of();
        if (panSouApiClient.isConfigured()) {
            try {
                apiResults = panSouApiClient.searchClouds(keyword, acceptedProviders, maxResults);
            } catch (RuntimeException e) {
                log.warn("External PanSou API search failed: {}", e.getMessage());
                if (localError != null) {
                    localError.addSuppressed(e);
                }
            }
        }
        if (localError != null && localResults.isEmpty() && apiResults.isEmpty()) {
            throw localError;
        }
        return mergeResults(localResults, apiResults, maxResults);
    }

    private List<DiscoveredResource> searchLocal(
            String keyword,
            Set<String> providers,
            int maxResults) {
        String baseUrl = properties.getPansou().getBaseUrl();
        if (!hasText(baseUrl)) {
            throw new IllegalStateException("PanSou base URL is not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kw", keyword.trim());
        body.put("res", "all");
        if (providers != null && !providers.isEmpty()) {
            body.put("cloud_types", providers.stream().map(this::diskType).toList());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hasText(properties.getPansou().getToken())) {
            headers.setBearerAuth(properties.getPansou().getToken().trim());
        }

        String url = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/search")
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(body, headers),
                    String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            return PanSouSearchResultParser.parseProviders(root, maxResults, providers);
        } catch (RestClientException e) {
            throw new IllegalStateException("PanSou search request failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("PanSou search response parse failed", e);
        }
    }

    private List<DiscoveredResource> mergeResults(
            List<DiscoveredResource> localResults,
            List<DiscoveredResource> apiResults,
            int maxResults) {
        int limit = Math.max(maxResults, 1);
        Map<String, DiscoveredResource> unique = new LinkedHashMap<>();
        int maxSize = Math.max(localResults.size(), apiResults.size());
        for (int index = 0; index < maxSize && unique.size() < limit; index++) {
            addResult(unique, apiResults, index);
            if (unique.size() < limit) {
                addResult(unique, localResults, index);
            }
        }
        return unique.values().stream().limit(limit).toList();
    }

    private void addResult(
            Map<String, DiscoveredResource> unique,
            List<DiscoveredResource> results,
            int index) {
        if (index >= results.size()) {
            return;
        }
        DiscoveredResource resource = results.get(index);
        if (resource != null && hasText(resource.getUrl())) {
            unique.putIfAbsent(resource.getUrl().trim().toLowerCase(), resource);
        }
    }

    public LinkCheckResult checkLink(String link) {
        if (!hasText(link)) {
            return new LinkCheckResult(link, false, false, "empty link");
        }
        return checkLinks(List.of(link)).getOrDefault(link, new LinkCheckResult(link, false, false,
                "PanSou check response did not include link status"));
    }

    public LinkCheckResult checkLink(DiscoveredResource resource) {
        if (resource == null || !hasText(resource.getUrl())) {
            return new LinkCheckResult(null, false, false, "empty link");
        }
        String provider = hasText(resource.getProvider()) ? resource.getProvider() : "QUARK";
        return checkTypedLinks(Map.of(resource.getUrl(), diskType(provider))).getOrDefault(
                resource.getUrl(),
                new LinkCheckResult(resource.getUrl(), false, false,
                        "PanSou check response did not include link status"));
    }

    public Map<String, LinkCheckResult> checkLinks(List<String> links) {
        if (links == null || links.isEmpty()) {
            return Map.of();
        }
        Map<String, String> typedLinks = new LinkedHashMap<>();
        links.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .forEach(link -> typedLinks.put(link, "quark"));
        return checkTypedLinks(typedLinks);
    }

    public Map<String, LinkCheckResult> checkLinksByProvider(Map<String, String> linksByProvider) {
        if (linksByProvider == null || linksByProvider.isEmpty()) {
            return Map.of();
        }
        Map<String, String> typedLinks = new LinkedHashMap<>();
        linksByProvider.forEach((link, provider) -> {
            if (hasText(link)) {
                typedLinks.put(link.trim(), diskType(hasText(provider) ? provider : "QUARK"));
            }
        });
        return checkTypedLinks(typedLinks);
    }

    private Map<String, LinkCheckResult> checkTypedLinks(Map<String, String> typedLinks) {
        if (typedLinks == null || typedLinks.isEmpty()) {
            return Map.of();
        }
        String baseUrl = properties.getPansou().getBaseUrl();
        if (!hasText(baseUrl)) {
            throw new IllegalStateException("PanSou base URL is not configured");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : typedLinks.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("disk_type", entry.getValue());
            item.put("url", entry.getKey());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hasText(properties.getPansou().getToken())) {
            headers.setBearerAuth(properties.getPansou().getToken().trim());
        }

        String url = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/check/links")
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(body, headers),
                    String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            Map<String, LinkCheckResult> results = new LinkedHashMap<>();
            for (String normalizedLink : typedLinks.keySet()) {
                results.put(normalizedLink, parseLinkCheckResult(root, normalizedLink));
            }
            return results;
        } catch (RestClientException e) {
            throw new IllegalStateException("PanSou link check request failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("PanSou link check response parse failed", e);
        }
    }

    private String diskType(String provider) {
        return switch (provider.trim().toUpperCase()) {
            case "123PAN" -> "123";
            case "TIANYI" -> "tianyi";
            case "MOBILE" -> "mobile";
            default -> provider.trim().toLowerCase();
        };
    }

    private LinkCheckResult parseLinkCheckResult(JsonNode root, String link) {
        LinkCheckResult result = findLinkCheckResult(root, link);
        if (result != null) {
            return result;
        }
        CheckDecision decision = decide(root);
        if (decision.checked()) {
            return new LinkCheckResult(link, true, decision.valid(), decision.message());
        }
        return new LinkCheckResult(link, false, false, "PanSou check response did not include link status");
    }

    private LinkCheckResult findLinkCheckResult(JsonNode node, String link) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                LinkCheckResult result = findLinkCheckResult(item, link);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        String itemLink = firstText(
                node.path("url").asText(null),
                node.path("link").asText(null),
                node.path("share_url").asText(null),
                node.path("shareUrl").asText(null));
        if (hasText(itemLink) && sameLink(itemLink, link)) {
            CheckDecision decision = decide(node);
            if (decision.checked()) {
                return new LinkCheckResult(link, true, decision.valid(), decision.message());
            }
        }
        LinkCheckResult result = findLinkCheckResult(node.path("data"), link);
        if (result != null) {
            return result;
        }
        result = findLinkCheckResult(node.path("list"), link);
        if (result != null) {
            return result;
        }
        result = findLinkCheckResult(node.path("results"), link);
        if (result != null) {
            return result;
        }
        return findLinkCheckResult(node.path("links"), link);
    }

    private CheckDecision decide(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new CheckDecision(false, false, null);
        }
        if (node.isBoolean()) {
            return new CheckDecision(true, node.asBoolean(), null);
        }
        for (String field : List.of("valid", "available", "alive", "accessible", "ok", "exists")) {
            JsonNode value = node.path(field);
            if (value.isBoolean()) {
                return new CheckDecision(true, value.asBoolean(), field + "=" + value.asBoolean());
            }
        }
        String status = firstText(
                node.path("status").asText(null),
                node.path("state").asText(null),
                node.path("link_status").asText(null),
                node.path("linkStatus").asText(null));
        CheckDecision byStatus = decideText(status);
        if (byStatus.checked()) {
            return byStatus;
        }
        String message = firstText(
                node.path("message").asText(null),
                node.path("msg").asText(null),
                node.path("error").asText(null),
                node.path("reason").asText(null));
        return decideText(message);
    }

    private CheckDecision decideText(String value) {
        if (!hasText(value)) {
            return new CheckDecision(false, false, null);
        }
        String lower = value.trim().toLowerCase();
        if ("bad".equals(lower)
                || "dead".equals(lower)
                || lower.contains("invalid")
                || lower.contains("expired")
                || lower.contains("deleted")
                || lower.contains("banned")
                || lower.contains("unavailable")
                || lower.contains("failed")
                || lower.contains("forbidden")
                || lower.contains("失效")
                || lower.contains("违规")
                || lower.contains("不存在")
                || lower.contains("取消")
                || lower.contains("删除")
                || lower.contains("过期")) {
            return new CheckDecision(true, false, value);
        }
        if ("good".equals(lower)
                || lower.contains("valid")
                || lower.contains("available")
                || lower.contains("alive")
                || lower.contains("normal")
                || lower.contains("success")
                || lower.contains("ok")
                || lower.contains("有效")
                || lower.contains("正常")) {
            return new CheckDecision(true, true, value);
        }
        return new CheckDecision(false, false, value);
    }

    private boolean sameLink(String left, String right) {
        return hasText(left) && hasText(right)
                && left.trim().split("\\?")[0].equalsIgnoreCase(right.trim().split("\\?")[0]);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record LinkCheckResult(String link, boolean checked, boolean valid, String message) {
    }

    private record CheckDecision(boolean checked, boolean valid, String message) {
    }
}
