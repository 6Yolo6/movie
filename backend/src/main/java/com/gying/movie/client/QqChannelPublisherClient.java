package com.gying.movie.client;

import com.gying.movie.config.ResourceHubProperties;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class QqChannelPublisherClient {

    private final RestClient restClient;
    private final ResourceHubProperties properties;

    public QqChannelPublisherClient(ResourceHubProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getQqChannelPublisher().getBaseUrl())
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> publish(Long postLogId) {
        if (postLogId == null) {
            throw new IllegalArgumentException("QQ channel post log id is required");
        }
        String token = properties.getQqChannelPublisher().getToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("QQ channel publisher token is not configured");
        }
        Map<String, Object> result = restClient.post()
                .uri("/posts/{id}", postLogId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Token", token.trim())
                .retrieve()
                .body(Map.class);
        return result == null ? Map.of() : result;
    }
}
