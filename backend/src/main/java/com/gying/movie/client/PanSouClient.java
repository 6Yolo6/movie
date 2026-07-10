package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.DiscoveredResource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;

    public PanSouClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper,
            ResourceHubProperties properties) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<DiscoveredResource> searchQuark(String keyword, int maxResults) {
        if (!hasText(keyword)) {
            return List.of();
        }
        String baseUrl = properties.getPansou().getBaseUrl();
        if (!hasText(baseUrl)) {
            throw new IllegalStateException("PanSou base URL is not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kw", keyword.trim());
        body.put("cloud_types", List.of("quark"));
        body.put("res", "all");

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
            return parseResults(root, maxResults);
        } catch (RestClientException e) {
            throw new IllegalStateException("PanSou search request failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("PanSou search response parse failed", e);
        }
    }

    public LinkCheckResult checkLink(String link) {
        if (!hasText(link)) {
            return new LinkCheckResult(link, false, false, "empty link");
        }
        String baseUrl = properties.getPansou().getBaseUrl();
        if (!hasText(baseUrl)) {
            throw new IllegalStateException("PanSou base URL is not configured");
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("disk_type", "quark");
        item.put("url", link.trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(item));

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
            return parseLinkCheckResult(root, link.trim());
        } catch (RestClientException e) {
            throw new IllegalStateException("PanSou link check request failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("PanSou link check response parse failed", e);
        }
    }

    private List<DiscoveredResource> parseResults(JsonNode root, int maxResults) {
        List<DiscoveredResource> results = new ArrayList<>();
        collectResources(root.path("data").path("list"), results, maxResults, null, null);
        collectResources(root.path("data").path("results"), results, maxResults, null, null);
        collectResources(root.path("list"), results, maxResults, null, null);
        collectResources(root.path("results"), results, maxResults, null, null);

        JsonNode merged = root.path("data").path("merged_by_type");
        if (merged.isObject()) {
            Iterator<JsonNode> values = merged.elements();
            while (values.hasNext() && results.size() < maxResults) {
                collectResources(values.next(), results, maxResults, null, null);
            }
        }
        return results;
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
        if (lower.contains("invalid")
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
        if (lower.contains("valid")
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

    private void collectResources(JsonNode node, List<DiscoveredResource> results, int maxResults,
            String parentTitle, String parentRef) {
        if (node == null || node.isMissingNode() || node.isNull() || results.size() >= maxResults) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectResources(item, results, maxResults, parentTitle, parentRef);
                if (results.size() >= maxResults) {
                    break;
                }
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String currentTitle = firstText(
                node.path("work_title").asText(null),
                node.path("title").asText(null),
                node.path("name").asText(null),
                node.path("filename").asText(null),
                parentTitle);
        String currentRef = firstText(
                node.path("unique_id").asText(null),
                node.path("message_id").asText(null),
                node.path("id").asText(null),
                node.path("source_id").asText(null),
                node.path("channel").asText(null),
                node.path("datetime").asText(null),
                parentRef);
        String url = firstText(
                node.path("url").asText(null),
                node.path("link").asText(null),
                node.path("share_url").asText(null),
                node.path("shareUrl").asText(null));
        if (isQuarkUrl(url)) {
            DiscoveredResource resource = new DiscoveredResource();
            resource.setTitle(firstText(currentTitle, url));
            resource.setProvider("QUARK");
            resource.setUrl(url.trim());
            resource.setCode(firstText(
                    node.path("password").asText(null),
                    node.path("pwd").asText(null),
                    node.path("code").asText(null)));
            resource.setSource("PANSOU");
            resource.setSourceRef(currentRef);
            results.add(resource);
        }

        collectResources(node.path("links"), results, maxResults, currentTitle, currentRef);
        collectResources(node.path("items"), results, maxResults, currentTitle, currentRef);
        collectResources(node.path("resources"), results, maxResults, currentTitle, currentRef);
    }

    private boolean isQuarkUrl(String value) {
        if (!hasText(value)) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://")
                ? lower.contains("pan.quark.cn/s/")
                : false;
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
