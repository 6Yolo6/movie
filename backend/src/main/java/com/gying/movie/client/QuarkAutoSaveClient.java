package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.config.ResourceHubProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
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
public class QuarkAutoSaveClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;

    public QuarkAutoSaveClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper,
            ResourceHubProperties properties) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> buildTaskPayload(String taskName, String shareUrl, String savePath) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskname", taskName);
        payload.put("shareurl", shareUrl);
        payload.put("savepath", savePath);
        payload.put("pattern", properties.getQuark().getPattern());
        payload.put("replace", properties.getQuark().getReplace());
        payload.put("update_subdir", "");
        payload.put("ignore_extension", false);
        return payload;
    }

    public JsonNode addTask(Map<String, Object> payload) {
        requireAccountReady();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/api/add_task")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(payload, headers),
                    String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("success").asBoolean(false)) {
                throw new IllegalStateException(body.path("message").asText("quark-auto-save add task failed"));
            }
            return body;
        } catch (RestClientException e) {
            throw new IllegalStateException("quark-auto-save add task request failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("quark-auto-save add task response parse failed: " + e.getMessage(), e);
        }
    }

    public String runTaskNow(Map<String, Object> payload) {
        requireConfigured();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tasklist", Collections.singletonList(payload));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/run_script_now")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(request, headers),
                    String.class);
            return response.getBody();
        } catch (RestClientException e) {
            throw new IllegalStateException("quark-auto-save run task request failed", e);
        }
    }

    public void requireAccountReady() {
        getPrimaryCookie();
    }

    public String getPrimaryCookie() {
        requireConfigured();
        JsonNode data = loadConfigData();
        JsonNode cookies = data.path("cookie");
        String cookie = cookies.isArray() && !cookies.isEmpty() ? cookies.get(0).asText(null) : null;
        if (!hasUsableCookie(cookie)) {
            String fallbackCookie = firstUsableCookie(System.getenv("QUARK_COOKIE"), System.getenv("quark_cookie"));
            if (fallbackCookie != null && data instanceof ObjectNode objectData) {
                ArrayNode cookieArray = objectMapper.createArrayNode();
                cookieArray.add(fallbackCookie);
                objectData.set("cookie", cookieArray);
                synchronizeRuntimeConfig(objectData);
                return fallbackCookie;
            }
            throw new IllegalStateException("quark-auto-save cookie is not configured");
        }
        synchronizeRuntimeConfig(data);
        return cookie.trim();
    }

    private JsonNode loadConfigData() {
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/data")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("success").asBoolean(false)) {
                throw new IllegalStateException(body.path("message").asText("quark-auto-save is not logged in"));
            }
            return body.path("data");
        } catch (RestClientException e) {
            throw new IllegalStateException("quark-auto-save config check request failed", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("quark-auto-save config check response parse failed", e);
        }
    }

    private void synchronizeRuntimeConfig(JsonNode data) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/update")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(data, headers),
                    String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("success").asBoolean(false)) {
                throw new IllegalStateException(body.path("message").asText("quark-auto-save config sync failed"));
            }
        } catch (RestClientException e) {
            throw new IllegalStateException("quark-auto-save config sync request failed", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("quark-auto-save config sync response parse failed", e);
        }
    }

    private boolean hasUsableCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return false;
        }
        String trimmed = cookie.trim();
        return !trimmed.startsWith("Your pan.quark.cn Cookie");
    }

    private String firstUsableCookie(String... cookies) {
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies) {
            if (hasUsableCookie(cookie)) {
                return cookie.trim();
            }
        }
        return null;
    }

    private void requireConfigured() {
        if (properties.getQuark().getBaseUrl() == null || properties.getQuark().getBaseUrl().isBlank()) {
            throw new IllegalStateException("quark-auto-save base URL is not configured");
        }
        if (properties.getQuark().getToken() == null || properties.getQuark().getToken().isBlank()) {
            throw new IllegalStateException("quark-auto-save API token is not configured");
        }
    }
}
