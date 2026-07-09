package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.QqBotProperties;
import java.time.Instant;
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

@Component
public class QqOfficialBotClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final QqBotProperties properties;

    private String accessToken;
    private long accessTokenExpiresAt;

    public QqOfficialBotClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper,
            QqBotProperties properties) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void sendGroupMessage(Long groupId, String message) {
        requireConfigured();
        String groupOpenid = resolveGroupOpenid(groupId);
        if (!hasText(groupOpenid)) {
            throw new IllegalStateException("QQBot group openid is not configured for group " + groupId);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", message);
        payload.put("msg_type", 0);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "QQBot " + getAccessToken());

        String url = trimTrailingSlash(properties.getQqbot().getApiBaseUrl())
                + "/v2/groups/" + groupOpenid + "/messages";
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(payload, headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("QQBot send group message failed: " + response.getStatusCode());
            }
            validateResponse(response.getBody());
        } catch (RestClientException e) {
            throw new IllegalStateException("QQBot send group message request failed", e);
        }
    }

    private synchronized String getAccessToken() {
        long now = Instant.now().toEpochMilli();
        if (hasText(accessToken) && now < accessTokenExpiresAt - 300_000L) {
            return accessToken;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appId", properties.getQqbot().getAppId().trim());
        payload.put("clientSecret", properties.getQqbot().getClientSecret().trim());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(properties.getQqbot().getTokenUrl(),
                    new HttpEntity<>(payload, headers), String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            String token = body.path("access_token").asText("");
            if (!hasText(token)) {
                throw new IllegalStateException("QQBot access_token missing");
            }
            long expiresInSeconds = body.path("expires_in").asLong(7200L);
            accessToken = token;
            accessTokenExpiresAt = now + expiresInSeconds * 1000L;
            return accessToken;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("QQBot access_token request failed", e);
        }
    }

    private void validateResponse(String rawBody) {
        if (!hasText(rawBody)) {
            return;
        }
        try {
            JsonNode body = objectMapper.readTree(rawBody);
            if (body.has("code") && body.path("code").asInt(0) != 0) {
                throw new IllegalStateException(body.path("message").asText("QQBot send group message failed"));
            }
            if (body.has("err_code") && body.path("err_code").asInt(0) != 0) {
                throw new IllegalStateException(body.path("message").asText("QQBot send group message failed"));
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception ignored) {
        }
    }

    private String resolveGroupOpenid(Long groupId) {
        if (groupId == null || !hasText(properties.getQqbot().getGroupOpenids())) {
            return null;
        }
        String groupIdText = String.valueOf(groupId);
        for (String item : properties.getQqbot().getGroupOpenids().split("[,;]")) {
            String value = item.trim();
            if (value.isEmpty()) {
                continue;
            }
            int separator = value.indexOf(':');
            if (separator <= 0 || separator >= value.length() - 1) {
                continue;
            }
            if (groupIdText.equals(value.substring(0, separator).trim())) {
                return value.substring(separator + 1).trim();
            }
        }
        return null;
    }

    private void requireConfigured() {
        QqBotProperties.Qqbot qqbot = properties.getQqbot();
        if (!hasText(qqbot.getAppId()) || !hasText(qqbot.getClientSecret())) {
            throw new IllegalStateException("QQBot app id or client secret is not configured");
        }
        if (!hasText(qqbot.getApiBaseUrl()) || !hasText(qqbot.getTokenUrl())) {
            throw new IllegalStateException("QQBot API base URL or token URL is not configured");
        }
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
