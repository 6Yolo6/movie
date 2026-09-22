package com.gying.movie.controller;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.service.IQqBotService;
import com.gying.movie.utils.AuthHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated web adapter for the same bounded search/transfer workflow used by QQ. */
@RestController
@RequestMapping("/api/resource-search")
public class WebResourceSearchController {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+|magnet:\\?[^\\s<>]+", Pattern.CASE_INSENSITIVE);
    private final AuthHelper auth;
    private final IQqBotService qqBotService;

    public WebResourceSearchController(AuthHelper auth, IQqBotService qqBotService) {
        this.auth = auth;
        this.qqBotService = qqBotService;
    }

    @PostMapping("/query")
    public Map<String, Object> query(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthUser user = auth.requireUser(authorization);
        String keyword = body == null || body.get("keyword") == null
                ? "" : String.valueOf(body.get("keyword")).trim();
        if (keyword.isBlank() || keyword.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1-80 个字符的影片名或候选序号");
        }
        String reply = qqBotService.buildSearchReply(keyword, "web:" + user.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("reply", reply);
        result.put("links", extractLinks(reply));
        return result;
    }

    private List<Map<String, String>> extractLinks(String reply) {
        List<Map<String, String>> links = new ArrayList<>();
        if (reply == null || reply.isBlank()) return links;
        Matcher matcher = URL.matcher(reply);
        while (matcher.find() && links.size() < 10) {
            String value = matcher.group().replaceAll("[\\s\\],；。]+$", "");
            if (value.isBlank() || links.stream().anyMatch(item -> value.equals(item.get("url")))) continue;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("url", value);
            item.put("name", "资源链接 " + (links.size() + 1));
            links.add(item);
        }
        return links;
    }
}
