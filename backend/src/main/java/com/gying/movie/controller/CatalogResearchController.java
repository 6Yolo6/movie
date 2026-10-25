package com.gying.movie.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.service.impl.*;
import com.gying.movie.utils.AiBridgeAuth;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/internal/ai/catalog")
public class CatalogResearchController {
    private final AiBridgeAuth auth;private final AiAssistantService jobs;private final CatalogResearchService catalog;
    public CatalogResearchController(AiBridgeAuth auth,AiAssistantService jobs,CatalogResearchService catalog){this.auth=auth;this.jobs=jobs;this.catalog=catalog;}
    @GetMapping("/documents") public ObjectNode documents(@RequestHeader(value="X-AI-Bridge-Token",required=false)String token,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="100")int size){auth.require(token);if(page<1||page>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page");return catalog.documents(page,size);}
    @PostMapping("/query") public ObjectNode query(@RequestHeader(value="X-AI-Bridge-Token",required=false)String token,@RequestBody JsonNode body){
        auth.require(token);if(body==null||!body.isObject())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid query");
        body.fieldNames().forEachRemaining(k->{if(!java.util.Set.of("jobId","lease","plan","denseCandidates","message","candidateLimit").contains(k))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown query field");});
        JsonNode context=jobs.runningContext(body.path("jobId").asText(),body.path("lease").asText());
        try{return catalog.query(body,context);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
    }
}
