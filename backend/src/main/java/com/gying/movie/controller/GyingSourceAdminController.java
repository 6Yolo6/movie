package com.gying.movie.controller;

import com.gying.movie.client.GyingSourceClient;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.impl.GyingSourceWorkflowService;
import com.gying.movie.utils.AuthHelper;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/gying-source")
public class GyingSourceAdminController {
    private final GyingSourceClient gyingSourceClient;
    private final IMovieMetadataService movieService;
    private final IResourceLinkService resourceService;
    private final AuthHelper authHelper;
    private final GyingSourceWorkflowService workflowService;
    private final ExecutorService workflowExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gying-source-workflow");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, WorkflowJob> workflowJobs = new ConcurrentHashMap<>();

    public GyingSourceAdminController(
            GyingSourceClient gyingSourceClient,
            IMovieMetadataService movieService,
            IResourceLinkService resourceService,
            AuthHelper authHelper,
            GyingSourceWorkflowService workflowService) {
        this.gyingSourceClient = gyingSourceClient;
        this.movieService = movieService;
        this.resourceService = resourceService;
        this.authHelper = authHelper;
        this.workflowService = workflowService;
    }

    @PreDestroy
    public void shutdownWorkflowExecutor() {
        workflowExecutor.shutdownNow();
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

    @GetMapping("/candidates/recent")
    public ResponseEntity<?> recentCandidates(
            @RequestParam(defaultValue = "30") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ResponseEntity.ok(workflowService.recentCandidates(limit));
    }

    @GetMapping("/candidates/trailers")
    public ResponseEntity<?> trailerCandidates(
            @RequestParam(defaultValue = "30") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ResponseEntity.ok(workflowService.trailerCandidates(limit));
    }

    @GetMapping("/account")
    public ResponseEntity<?> account(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ResponseEntity.ok(gyingSourceClient.get("/account"));
    }

    @PutMapping("/account")
    public ResponseEntity<?> updateAccount(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        if (request == null) {
            return ResponseEntity.badRequest().body("Account configuration is required");
        }
        return ResponseEntity.ok(gyingSourceClient.post("/account", request));
    }

    @PostMapping("/movies/{typeCode}/{mid}/ensure")
    public ResponseEntity<?> ensureMovieResource(
            @PathVariable String typeCode,
            @PathVariable String mid,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        return ResponseEntity.ok(startJob(
                "ENSURE_MOVIE",
                () -> workflowService.ensureMovieResource(typeCode, mid)));
    }

    @PostMapping("/trailers/ensure")
    public ResponseEntity<?> ensureTrailerResources(
            @RequestParam(defaultValue = "10") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        return ResponseEntity.ok(startJob(
                "ENSURE_TRAILERS",
                () -> workflowService.ensureTrailerResources(safeLimit)));
    }

    @PostMapping("/published-resources/check")
    public ResponseEntity<?> checkPublishedResources(
            @RequestParam(defaultValue = "200") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return ResponseEntity.ok(startJob(
                "CHECK_PUBLISHED",
                () -> workflowService.checkPublishedResources(safeLimit, true)));
    }

    @PostMapping("/published-resources/repair")
    public ResponseEntity<?> repairPublishedResources(
            @RequestParam(defaultValue = "200") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return ResponseEntity.ok(startJob(
                "REPAIR_PUBLISHED",
                () -> workflowService.repairPublishedResources(safeLimit)));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> workflowJob(
            @PathVariable String jobId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        WorkflowJob job = workflowJobs.get(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job.toResponse());
    }

    private Map<String, Object> startJob(String type, Supplier<Map<String, Object>> action) {
        WorkflowJob job = new WorkflowJob(UUID.randomUUID().toString(), type);
        workflowJobs.put(job.jobId, job);
        trimJobs();
        workflowExecutor.submit(() -> {
            try {
                job.result = action.get();
                job.status = "SUCCEEDED";
            } catch (Exception error) {
                job.status = "FAILED";
                job.errors.add(safeText(error.getMessage()));
            } finally {
                job.finishedAt = LocalDateTime.now();
            }
        });
        return job.toResponse();
    }

    private void trimJobs() {
        if (workflowJobs.size() <= 30) {
            return;
        }
        workflowJobs.values().stream()
                .filter(job -> !"RUNNING".equals(job.status))
                .sorted((left, right) -> right.startedAt.compareTo(left.startedAt))
                .skip(30)
                .map(job -> job.jobId)
                .toList()
                .forEach(workflowJobs::remove);
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

    private String safeText(String value) {
        String text = value == null || value.isBlank() ? "Unknown error" : value.trim();
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static class WorkflowJob {
        private final String jobId;
        private final String type;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private volatile String status = "RUNNING";
        private volatile LocalDateTime finishedAt;
        private volatile Map<String, Object> result = Map.of();
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        private WorkflowJob(String jobId, String type) {
            this.jobId = jobId;
            this.type = type;
        }

        private Map<String, Object> toResponse() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", jobId);
            response.put("type", type);
            response.put("status", status);
            response.put("startedAt", startedAt);
            response.put("finishedAt", finishedAt);
            response.put("result", result);
            response.put("errors", new ArrayList<>(errors));
            return response;
        }
    }
}
