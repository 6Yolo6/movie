package com.gying.movie.controller;

import com.gying.movie.client.GyingSourceClient;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.AuthHelper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/gying-source")
public class GyingSourceAdminController {
    private final GyingSourceClient gyingSourceClient;
    private final IMovieMetadataService movieService;
    private final IResourceLinkService resourceService;
    private final AuthHelper authHelper;

    public GyingSourceAdminController(
            GyingSourceClient gyingSourceClient,
            IMovieMetadataService movieService,
            IResourceLinkService resourceService,
            AuthHelper authHelper) {
        this.gyingSourceClient = gyingSourceClient;
        this.movieService = movieService;
        this.resourceService = resourceService;
        this.authHelper = authHelper;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        String typeCode = text(request.get("typeCode"));
        String mid = text(request.get("mid"));
        if (!Set.of("mv", "tv", "ac").contains(typeCode) || mid == null) {
            return ResponseEntity.badRequest().body("typeCode and mid are required");
        }
        return ResponseEntity.ok(gyingSourceClient.post("/ingest", Map.of(
                "typeCode", typeCode,
                "mid", mid,
                "uploadPoster", !Boolean.FALSE.equals(request.get("uploadPoster")))));
    }

    @PostMapping("/resources/{resourceId}/publish")
    public ResponseEntity<?> publish(
            @PathVariable Long resourceId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        ResourceLink resource = requireResource(resourceId);
        MovieMetadata movie = requireMovie(resource.getMovieId());
        Map<String, Object> payload = resourcePayload(resource, movie);
        payload.put("resourceId", resourceId);
        return ResponseEntity.ok(gyingSourceClient.post("/publish", payload));
    }

    @PostMapping("/resources/{resourceId}/update")
    public ResponseEntity<?> update(
            @PathVariable Long resourceId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        ResourceLink resource = requireResource(resourceId);
        if (resource.getSourceRef() == null || resource.getSourceRef().isBlank()) {
            return ResponseEntity.badRequest().body("Resource has no Gying source id; publish it first");
        }
        MovieMetadata movie = requireMovie(resource.getMovieId());
        Map<String, Object> payload = resourcePayload(resource, movie);
        payload.put("sourceId", resource.getSourceRef());
        return ResponseEntity.ok(gyingSourceClient.post("/update", payload));
    }

    private ResourceLink requireResource(Long id) {
        ResourceLink resource = resourceService.getById(id);
        if (resource == null || !"ACTIVE".equalsIgnoreCase(resource.getStatus())) {
            throw new IllegalArgumentException("Resource not found");
        }
        if (!"DISK".equalsIgnoreCase(resource.getType())) {
            throw new IllegalArgumentException("Only cloud disk resources can be published to Gying");
        }
        return resource;
    }

    private MovieMetadata requireMovie(String id) {
        MovieMetadata movie = movieService.getById(id);
        if (movie == null || "DELETED".equalsIgnoreCase(movie.getStatus())) {
            throw new IllegalArgumentException("Movie not found");
        }
        return movie;
    }

    private Map<String, Object> resourcePayload(ResourceLink resource, MovieMetadata movie) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typeCode", Set.of("mv", "tv", "ac").contains(movie.getCategory()) ? movie.getCategory() : "mv");
        payload.put("mid", movie.getId());
        payload.put("title", firstText(resource.getName(), movie.getTitleCn(), movie.getTitleEn(), movie.getId()));
        payload.put("panurl", resource.getUrl());
        payload.put("panpw", firstText(resource.getCode(), ""));
        payload.put("is", 0);
        return payload;
    }

    private String text(Object value) {
        String result = value == null ? null : String.valueOf(value).trim();
        return result == null || result.isBlank() ? null : result;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }
}
