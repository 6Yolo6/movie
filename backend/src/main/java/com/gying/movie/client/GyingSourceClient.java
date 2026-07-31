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
        return retrieve(request);
    }

    public Map<String, Object> get(String path, Map<String, ?> query) {
        RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uriBuilder -> {
            var builder = uriBuilder.path(path);
            if (query != null) {
                query.forEach((name, value) -> {
                    if (value != null) {
                        builder.queryParam(name, value);
                    }
                });
            }
            return builder.build();
        });
        return retrieve(request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> retrieve(RestClient.RequestHeadersSpec<?> request) {
        if (internalToken != null && !internalToken.isBlank()) {
            request.header("X-Internal-Token", internalToken);
        }
        Map<String, Object> result = request.retrieve().body(Map.class);
        return result == null ? Map.of() : result;
    }
}
