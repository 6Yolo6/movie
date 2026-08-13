package com.gying.movie.client;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SocialPublisherClient {
    private final RestClient restClient;
    private final String token;

    public SocialPublisherClient(
            @Value("${social-publisher.base-url:${SOCIAL_PUBLISHER_BASE_URL:http://social-publisher:8093}}") String baseUrl,
            @Value("${social-publisher.token:${SOCIAL_PUBLISHER_TOKEN:${APP_INTERNAL_TOKEN:}}}") String token) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.token = token == null ? "" : token.trim();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> publish(Long logId) {
        if (token.isBlank()) {
            throw new IllegalStateException("Social publisher token is not configured");
        }
        Map<String, Object> response = restClient.post()
                .uri("/posts/{id}", logId)
                .header("X-Internal-Token", token)
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        Map<String, Object> response = restClient.get().uri("/health").retrieve().body(Map.class);
        return response == null ? Map.of() : response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> qqAccounts() {
        Map<String, Object> response = restClient.get()
                .uri("/accounts/qq")
                .header("X-Internal-Token", requiredToken())
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> startQqLogin(Map<String, Object> request) {
        Map<String, Object> response = restClient.post()
                .uri("/accounts/qq/login")
                .header("X-Internal-Token", requiredToken())
                .body(request)
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> qqLoginStatus(String accountKey) {
        Map<String, Object> response = restClient.get()
                .uri("/accounts/qq/{accountKey}/login-status", accountKey)
                .header("X-Internal-Token", requiredToken())
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> removeQqAccount(String accountKey) {
        Map<String, Object> response = restClient.delete()
                .uri("/accounts/qq/{accountKey}", accountKey)
                .header("X-Internal-Token", requiredToken())
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> startWeiboLogin() {
        Map<String, Object> response = restClient.post()
                .uri("/accounts/weibo/login")
                .header("X-Internal-Token", requiredToken())
                .body(Map.of())
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> weiboLoginStatus() {
        Map<String, Object> response = restClient.get()
                .uri("/accounts/weibo/login-status")
                .header("X-Internal-Token", requiredToken())
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }

    private String requiredToken() {
        if (token.isBlank()) {
            throw new IllegalStateException("Social publisher token is not configured");
        }
        return token;
    }
}
