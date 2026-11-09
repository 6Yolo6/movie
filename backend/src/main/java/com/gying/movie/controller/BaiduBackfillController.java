package com.gying.movie.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.dto.ApiResponse;
import com.gying.movie.service.impl.BaiduBackfillService;
import com.gying.movie.utils.AuthHelper;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/resource-hub/baidu/backfill")
public class BaiduBackfillController {
    private static final ObjectMapper REQUESTS = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AuthHelper auth;
    private final BaiduBackfillService service;
    public BaiduBackfillController(AuthHelper auth, BaiduBackfillService service) { this.auth = auth; this.service = service; }
    @GetMapping
    public ApiResponse<BaiduBackfillService.Status> status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization); return ApiResponse.ok(service.status());
    }
    @PutMapping
    public ApiResponse<BaiduBackfillService.Status> settings(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) {
        auth.requireAdmin(authorization);
        JsonNode settings = object(body, Set.of("enabled", "intervalMinutes", "batchLimit"));
        if (!settings.has("enabled") || !settings.get("enabled").isBoolean()) throw invalid();
        int interval = integer(settings, "intervalMinutes", 1, 1440), batch = integer(settings, "batchLimit", 1, 5);
        return ApiResponse.ok(service.settings(settings.get("enabled").booleanValue(), interval, batch));
    }
    @PostMapping("/run")
    public ApiResponse<BaiduBackfillService.Status> run(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) {
        auth.requireAdmin(authorization);
        if (body != null && !body.isBlank()) object(body, Set.of());
        return ApiResponse.ok(service.runOnce());
    }
    @PostMapping("/defer-unknown")
    public ApiResponse<BaiduBackfillService.Status> deferUnknown(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) {
        var administrator = auth.requireAdmin(authorization);
        JsonNode request = object(body, Set.of("taskId", "childId", "revision"));
        return ApiResponse.ok(service.deferUnknown(longInteger(request, "taskId", 1), longInteger(request, "childId", 1),
                longInteger(request, "revision", 0), administrator.getId()));
    }
    private static JsonNode object(String body, Set<String> allowed) {
        if (body == null || body.length() > 2048) throw invalid();
        try {
            JsonNode value = REQUESTS.readTree(body);
            if (value == null || !value.isObject()) throw invalid();
            var fields = value.fieldNames(); while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid();
            return value;
        } catch (Exception malformed) { throw invalid(); }
    }
    private static int integer(JsonNode value, String field, int minimum, int maximum) {
        JsonNode number = value.get(field);
        if (number == null || !number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < minimum || number.intValue() > maximum) throw invalid();
        return number.intValue();
    }
    private static long longInteger(JsonNode value, String field, long minimum) {
        JsonNode number = value.get(field);
        if (number == null || !number.isIntegralNumber() || !number.canConvertToLong() || number.longValue() < minimum) throw invalid();
        return number.longValue();
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Baidu backfill request"); }
}
