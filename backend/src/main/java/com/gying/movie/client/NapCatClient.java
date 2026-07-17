package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.QqBotProperties;
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
public class NapCatClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final QqBotProperties properties;

    public NapCatClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper,
            QqBotProperties properties) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void sendGroupMessage(Long groupId, String message) {
        requireConfigured();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("group_id", groupId);
        payload.put("message", message);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hasText(properties.getNapcat().getAccessToken())) {
            headers.setBearerAuth(properties.getNapcat().getAccessToken().trim());
        }

        String url = UriComponentsBuilder.fromUriString(properties.getNapcat().getBaseUrl())
                .path("/send_group_msg")
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(payload, headers),
                    String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            String status = body.path("status").asText("");
            if (body.has("retcode") && body.path("retcode").asInt(0) != 0) {
                throw new IllegalStateException(body.path("message").asText("NapCat send_group_msg failed"));
            }
            if (hasText(status) && !"ok".equalsIgnoreCase(status)) {
                throw new IllegalStateException(body.path("message").asText("NapCat send_group_msg failed"));
            }
        } catch (RestClientException e) {
            throw new IllegalStateException("NapCat send_group_msg request failed", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("NapCat send_group_msg response parse failed", e);
        }
    }

    private void requireConfigured() {
        if (!hasText(properties.getNapcat().getBaseUrl())) {
            throw new IllegalStateException("NapCat base URL is not configured");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
