package com.gying.movie.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GyingSourceClient {
    private final RestClient restClient;
    private final String internalToken;
    private final RestClient readClient;
    private final LongSupplier clock;
    private final AtomicLong unavailableUntil = new AtomicLong();

    @Autowired
    public GyingSourceClient(
            @Value("${gying-source.base-url:http://localhost:8091}") String baseUrl,
            @Value("${gying-source.token:}") String internalToken) {
        this(client(baseUrl, 20_000), client(baseUrl, 120_000), internalToken, System::currentTimeMillis);
    }

    GyingSourceClient(RestClient readClient, RestClient writeClient, String internalToken, LongSupplier clock) {
        this.readClient = readClient;
        this.restClient = writeClient;
        this.internalToken = internalToken;
        this.clock = clock;
    }

    private static RestClient client(String baseUrl, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
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
        RestClient.RequestHeadersSpec<?> request = readClient.get().uri(path);
        return retrieve(request);
    }

    public Map<String, Object> get(String path, Map<String, ?> query) {
        RestClient.RequestHeadersSpec<?> request = readClient.get().uri(uriBuilder -> {
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
        if (clock.getAsLong() < unavailableUntil.get()) {
            throw unavailable();
        }
        if (internalToken != null && !internalToken.isBlank()) {
            request.header("X-Internal-Token", internalToken);
        }
        try {
            Map<String, Object> result = request.retrieve().body(Map.class);
            unavailableUntil.set(0);
            return result == null ? Map.of() : result;
        } catch (ResourceAccessException error) {
            unavailableUntil.set(clock.getAsLong() + 30_000);
            throw unavailable();
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status >= 500 || status == 429 || status == 401 || status == 403) {
                unavailableUntil.set(clock.getAsLong() + 30_000);
                throw unavailable();
            }
            throw error;
        }
    }

    private ResponseStatusException unavailable() {
        // Do not relay upstream response bodies (cookies, URLs or HTML) to users/logs.
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GYING 暂时不可用，稍后自动重试");
    }
}
