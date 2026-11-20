package com.gying.movie.controller;

import com.gying.movie.utils.AuthHelper;
import com.gying.movie.service.impl.CatalogAiService;
import com.gying.movie.service.impl.CatalogRecommendationEngine.Constraints;
import com.gying.movie.security.RedisRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class CatalogAiController {
    private final AuthHelper auth;private final CatalogAiService catalog;private final JdbcTemplate jdbc;private final RedisRateLimiter limiter;
    public CatalogAiController(AuthHelper auth,CatalogAiService catalog,JdbcTemplate jdbc,RedisRateLimiter limiter){this.auth=auth;this.catalog=catalog;this.jdbc=jdbc;this.limiter=limiter;}
    private com.gying.movie.service.impl.AiAssistantService jobs;
    @org.springframework.beans.factory.annotation.Autowired
    public void setJobs(com.gying.movie.service.impl.AiAssistantService jobs){this.jobs=jobs;}
    private ObjectNode remember(Long user,String message,ObjectNode result,boolean remember){
        if(!remember)return result;
        String id=java.util.UUID.randomUUID().toString();var input=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("message",message);
        var saved=jobs.rememberDirect(user,input,Map.of("jobId",id,"status","SUCCEEDED","result",result));return result.put("jobId",saved.get("jobId").toString());
    }
    public ObjectNode search(String token,String keyword,int page,int size){return search(token,keyword,page,size,false);}
    public ObjectNode naturalSearch(String token,String query,int page,int size){return naturalSearch(token,query,page,size,false);}
    @GetMapping("/search")
    public ObjectNode search(@RequestHeader(value="Authorization",required=false)String token,@RequestParam String keyword,
                             @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean remember){
        var user=auth.requireUser(token);limiter.require("ai-search",user.getId().toString(),30,Duration.ofMinutes(1));return remember(user.getId(),keyword,catalog.search(keyword,page,size),remember);
    }
    @GetMapping("/natural-search")
    public ObjectNode naturalSearch(@RequestHeader(value="Authorization",required=false)String token,@RequestParam String query,
                                    @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean remember) {
        var user=auth.requireUser(token);limiter.require("ai-search",user.getId().toString(),30,Duration.ofMinutes(1));
        return remember(user.getId(),query,catalog.naturalSearch(query,page,size),remember);
    }
    @GetMapping("/recommendations")
    public ObjectNode recommendations(@RequestHeader(value="Authorization",required=false)String token){
        var user=auth.requireUser(token);limiter.require("ai-recommend",user.getId().toString(),10,Duration.ofMinutes(1));return catalog.recommendations(user.getId(),Constraints.none(),12);
    }
    @PostMapping("/views")
    public Map<String,Object> view(@RequestHeader(value="Authorization",required=false)String token,@RequestBody JsonNode body){
        var user=auth.requireUser(token);limiter.require("ai-view",user.getId().toString(),30,Duration.ofMinutes(1));
        if(body==null||!body.isObject())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid movie");
        String id=body.path("movieId").asText("");
        if(!body.isObject()||body.size()!=1||!id.matches("[A-Za-z0-9_-]{1,64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid movie");
        // Only authenticated actual detail views; never infer users from IP/visitor hashes.
        int written=jdbc.update("INSERT INTO resource_operation_log(user_id,movie_id,operation_type,status) SELECT ?,id,'VIEW','SUCCESS' FROM movie_metadata m WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM resource_operation_log l WHERE l.user_id=? AND l.movie_id=m.id AND l.operation_type='VIEW' AND l.created_at>=DATE_SUB(NOW(),INTERVAL 30 MINUTE))",user.getId(),id,user.getId());
        return Map.of("ok",true,"recorded",written>0);
    }
}
