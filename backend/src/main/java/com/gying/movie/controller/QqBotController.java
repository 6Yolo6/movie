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

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.gying.movie.service.impl.AiAssistantService aiAssistant;

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
        if (aiAssistant != null && keyword.startsWith("自然语言：")) {
            String question=keyword.substring(5).trim();
            String interpreted=String.valueOf(aiAssistant.interpret(question).getOrDefault("keyword",""));
            if(interpreted.isBlank()) {
                result.put("reply","未识别到明确片名，请用“搜/找 片名”，或重新描述影片。请选择单个影片和资源，不支持批量转存。");
                return result;
            }
            String raw=question.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\s]","");
            String extracted=interpreted.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\s]","");
            if(!raw.contains(extracted) || question.matches("(?s).*(?:批量|全部|所有|删除|执行|发布|转存|https?://|magnet:).*")) {
                result.put("reply","请指定单部影片，不支持批量操作。"); return result;
            }
            keyword=interpreted;
        }
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
