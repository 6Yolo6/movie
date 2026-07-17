package com.gying.movie.client;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GyingSourceClient {
    private final RestClient restClient;
    private final String internalToken;

    public GyingSourceClient(
            @Value("${gying-source.base-url:http://localhost:8091}") String baseUrl,
            @Value("${gying-source.token:}") String internalToken) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String path, Map<String, Object> payload) {
        RestClient.RequestBodySpec request = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (internalToken != null && !internalToken.isBlank()) {
            request.header("X-Internal-Token", internalToken);
        }
        Map<String, Object> result = request.body(payload).retrieve().body(Map.class);
        return result == null ? Map.of() : result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String path) {
        RestClient.RequestHeadersSpec<?> request = restClient.get().uri(path);
        if (internalToken != null && !internalToken.isBlank()) {
            request.header("X-Internal-Token", internalToken);
        }
        Map<String, Object> result = request.retrieve().body(Map.class);
        return result == null ? Map.of() : result;
    }
}
