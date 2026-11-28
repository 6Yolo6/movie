package com.gying.movie.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.XunleiBackupAccounts;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.service.impl.BaiduLoginService;
import com.gying.movie.utils.AuthHelper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/resource-hub/backup-accounts")
public class DriveBackupAccountController {
    private static final ObjectMapper REQUESTS = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AuthHelper auth;
    private final XunleiBackupAccounts xunlei;
    private final BaiduLoginService baidu;

    public DriveBackupAccountController(AuthHelper auth, XunleiBackupAccounts xunlei, BaiduLoginService baidu) {
        this.auth = auth;
        this.xunlei = xunlei;
        this.baidu = baidu;
    }

    @GetMapping("/xunlei/{slot}")
    public ApiResponse<XunleiBackupAccounts.Status> xunleiStatus(@PathVariable int slot,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        return ApiResponse.ok(xunlei.status(checkSlot(slot)));
    }

    @PutMapping("/xunlei/{slot}")
    public ApiResponse<XunleiBackupAccounts.Status> saveXunlei(@PathVariable int slot,
            @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody String body) {
        auth.requireAdmin(authorization);
        checkSlot(slot);
        try {
            if (body.length() > 16384) throw invalid();
            var parsed = REQUESTS.readTree(body);
            if (parsed == null || !parsed.isObject() || parsed.size() != 1 || !parsed.path("authorization").isTextual()) throw invalid();
            return ApiResponse.ok(xunlei.save(slot, parsed.path("authorization").textValue()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ignored) {
            throw invalid();
        }
    }

    @GetMapping("/baidu/{slot}")
    public ApiResponse<BaiduLoginService.SessionStatus> baiduStatus(@PathVariable int slot,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        return ApiResponse.ok(baidu.backupStatus(checkSlot(slot)));
    }

    @PostMapping("/baidu/{slot}/login")
    public ApiResponse<BaiduLoginService.SessionStatus> startBaidu(@PathVariable int slot,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) {
        auth.requireAdmin(authorization);
        if (body != null && !body.isBlank()) {
            try {
                var parsed = REQUESTS.readTree(body);
                if (parsed == null || !parsed.isObject() || !parsed.isEmpty()) throw invalid();
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { throw invalid(); }
        }
        return ApiResponse.ok(baidu.startBackup(checkSlot(slot)));
    }

    private static int checkSlot(int slot) {
        if (slot < 1 || slot > 3) throw invalid();
        return slot;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid backup slot or authorization");
    }
}
