package com.gying.movie.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.service.IQqBotService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/qq-bot")
public class QqBotController {

    private final QqBotProperties qqBotProperties;
    private final IQqBotService qqBotService;

    public QqBotController(QqBotProperties qqBotProperties, IQqBotService qqBotService) {
        this.qqBotProperties = qqBotProperties;
        this.qqBotService = qqBotService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/search-reply")
    public Map<String, Object> searchReply(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "userKey", required = false) String userKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-QQ-Bot-Token", required = false) String headerToken) {
        requireWebhookToken(authorization, headerToken);
        if (keyword.isBlank() || keyword.length() > 100 || (userKey != null && userKey.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search parameters");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("reply", qqBotService.buildSearchReply(keyword, userKey));
        return result;
    }

    @PostMapping("/onebot")
    public Map<String, Object> oneBotWebhook(
            @RequestBody JsonNode event,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-QQ-Bot-Token", required = false) String headerToken) {
        requireWebhookToken(authorization, headerToken);
        boolean accepted = qqBotService.handleOneBotEvent(event);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("accepted", accepted);
        return result;
    }

    private void requireWebhookToken(String authorization, String headerToken) {
        String expected = qqBotProperties.getWebhookToken();
        if (!hasText(expected)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "QQ bot authentication is not configured");
        }
        String supplied = headerToken;
        if (supplied == null && authorization != null && authorization.startsWith("Bearer ")) {
            supplied = authorization.substring(7);
        }
        if (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid QQ bot token");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
