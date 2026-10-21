package com.gying.movie.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.gying.movie.security.RedisRateLimiter;
import com.gying.movie.service.impl.AiAssistantService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.AiBridgeAuth;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AiAssistantController {
    private final AuthHelper auth; private final AiBridgeAuth internal;
    private final RedisRateLimiter limits; private final AiAssistantService assistant;
    private final com.gying.movie.service.impl.CatalogAiService catalog;
    public AiAssistantController(AuthHelper auth, AiBridgeAuth internal, RedisRateLimiter limits, AiAssistantService assistant, com.gying.movie.service.impl.CatalogAiService catalog) {
        this.auth=auth; this.internal=internal; this.limits=limits; this.assistant=assistant; this.catalog=catalog;
    }
    @GetMapping("/api/ai/status")
    public Map<String, Object> status(@RequestHeader(value="Authorization", required=false) String token) {
        auth.requireUser(token); var status=new java.util.HashMap<String,Object>(assistant.availability());
        status.put("catalogAvailable",true); return status;
    }
    @PostMapping("/api/ai/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody JsonNode body, @RequestHeader(value="Authorization", required=false) String token) {
        var user=auth.requireUser(token);
        limits.require("ai-user", user.getId().toString(), 6, Duration.ofMinutes(1));
        JsonNode request=assistant.validatedRequest(body);
        Map<String,Object> result=catalog.answer(user.getId(),request);
        if(result!=null)return ResponseEntity.ok(result);
        return ResponseEntity.accepted().body(assistant.submit(user.getId(), body));
    }
    @GetMapping("/api/ai/jobs/{id}")
    public Map<String, Object> job(@PathVariable String id, @RequestHeader(value="Authorization", required=false) String token) {
        return assistant.get(auth.requireUser(token).getId(), id);
    }
    @PostMapping("/api/internal/ai/heartbeat")
    public Map<String, Object> heartbeat(@RequestHeader(value="X-AI-Bridge-Token", required=false) String token) {
        internal.require(token); assistant.heartbeat(); return Map.of("ok",true);
    }
    @PostMapping("/api/internal/ai/claim")
    public Map<String, Object> claim(@RequestHeader(value="X-AI-Bridge-Token", required=false) String token) {
        internal.require(token); return assistant.claim();
    }
    @PostMapping("/api/internal/ai/jobs/{id}")
    public Map<String, Object> complete(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value="X-AI-Bridge-Token", required=false) String token) {
        internal.require(token);
        assistant.complete(id, body.path("lease").asText(), body.path("result")); return Map.of("ok", true);
    }
}
