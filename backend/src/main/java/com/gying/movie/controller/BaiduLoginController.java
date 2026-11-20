package com.gying.movie.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduSessionVault;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.service.impl.BaiduLoginService;
import com.gying.movie.utils.AuthHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class BaiduLoginController {
    private static final ObjectMapper REQUESTS = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AuthHelper auth;
    private final BaiduLoginService service;

    public BaiduLoginController(AuthHelper auth, BaiduLoginService service) { this.auth = auth; this.service = service; }

    @GetMapping("/api/admin/resource-hub/baidu/session")
    public ApiResponse<BaiduLoginService.SessionStatus> status(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        return ApiResponse.ok(service.status());
    }

    @PostMapping("/api/admin/resource-hub/baidu/login")
    public ApiResponse<BaiduLoginService.SessionStatus> start(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) {
        auth.requireAdmin(authorization);
        if (body != null && !body.isBlank()) object(body, Set.of());
        return ApiResponse.ok(service.start());
    }

    // These routes stay outside nginx/public routing. The host has only this one-use,
    // administrator-created ticket; it receives no JWT or general API credential.
    @PostMapping("/api/internal/resource-hub/baidu/login-state")
    public ApiResponse<BaiduLoginService.SessionStatus> helperState(@RequestBody String body) {
        JsonNode request = object(body, Set.of("ticketId", "nonce", "state", "failureCode"));
        return ApiResponse.ok(service.helperState(text(request, "ticketId", 36), text(request, "nonce", 43),
                text(request, "state", 20), optionalText(request, "failureCode", 40)));
    }

    @PostMapping("/api/internal/resource-hub/baidu/session")
    public ApiResponse<BaiduLoginService.SessionStatus> importSession(@RequestBody String body) {
        JsonNode request = object(body, Set.of("ticketId", "nonce", "cookies"));
        String ticketId = text(request, "ticketId", 36); String nonce = text(request, "nonce", 43);
        JsonNode values = request.get("cookies");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > 80) throw invalid();
        List<BaiduSessionVault.Cookie> cookies = new ArrayList<>();
        for (JsonNode value : values) {
            fields(value, Set.of("name", "value", "domain", "path", "expires", "secure"));
            if (!value.has("expires") || !value.get("expires").isNumber()
                    || !Double.isFinite(value.get("expires").doubleValue())
                    || !value.has("secure") || !value.get("secure").isBoolean()) throw invalid();
            cookies.add(new BaiduSessionVault.Cookie(text(value, "name", 100), text(value, "value", 8192),
                    text(value, "domain", 100), text(value, "path", 500), value.get("expires").doubleValue(),
                    value.get("secure").booleanValue()));
        }
        return ApiResponse.ok(service.importSession(ticketId, nonce, cookies));
    }

    private static JsonNode object(String body, Set<String> allowed) {
        if (body == null || body.length() > 128 * 1024) throw invalid();
        try { JsonNode value = REQUESTS.readTree(body); fields(value, allowed); return value; }
        catch (Exception failure) { throw invalid(); }
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        if (value == null || !value.isObject()) throw invalid();
        var fields = value.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid();
    }
    private static String text(JsonNode value, String field, int maximum) {
        JsonNode item = value.get(field);
        if (item == null || !item.isTextual() || item.textValue().isBlank() || item.textValue().length() > maximum) throw invalid();
        return item.textValue();
    }
    private static String optionalText(JsonNode value, String field, int maximum) {
        return !value.has(field) || value.get(field).isNull() ? null : text(value, field, maximum);
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Baidu login request");
    }
}
