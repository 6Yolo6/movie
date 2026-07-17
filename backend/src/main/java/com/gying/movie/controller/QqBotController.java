package com.gying.movie.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.service.IQqBotService;
import java.util.LinkedHashMap;
import java.util.Map;
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", qqBotProperties.isEnabled());
        result.put("napcatConfigured", hasText(qqBotProperties.getNapcat().getBaseUrl()));
        result.put("replyProvider", qqBotProperties.getReplyProvider());
        result.put("qqbotConfigured", hasText(qqBotProperties.getQqbot().getAppId())
                && hasText(qqBotProperties.getQqbot().getClientSecret())
                && hasText(qqBotProperties.getQqbot().getGroupOpenids()));
        result.put("allowedGroups", qqBotProperties.getAllowedGroups());
        result.put("commandPrefixes", qqBotProperties.getCommandPrefixes());
        return result;
    }

    @GetMapping("/search-reply")
    public Map<String, Object> searchReply(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "userKey", required = false) String userKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-QQ-Bot-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken) {
        requireWebhookToken(authorization, headerToken, queryToken);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("reply", qqBotService.buildSearchReply(keyword, userKey));
        return result;
    }

    @PostMapping("/onebot")
    public Map<String, Object> oneBotWebhook(
            @RequestBody JsonNode event,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-QQ-Bot-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken) {
        requireWebhookToken(authorization, headerToken, queryToken);
        boolean accepted = qqBotService.handleOneBotEvent(event);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("accepted", accepted);
        return result;
    }

    private void requireWebhookToken(String authorization, String headerToken, String queryToken) {
        String configured = qqBotProperties.getWebhookToken();
        if (!hasText(configured)) {
            return;
        }
        String expected = configured.trim();
        if (expected.equals(headerToken) || expected.equals(queryToken)) {
            return;
        }
        if (authorization != null && authorization.startsWith("Bearer ")
                && expected.equals(authorization.substring(7).trim())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid QQ bot webhook token");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
