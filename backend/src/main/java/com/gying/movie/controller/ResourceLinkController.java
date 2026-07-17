package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.dto.ResourceAdminDTO;
import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceSubmissionDTO;
import com.gying.movie.client.PanSouClient;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.client.QuarkShareClient;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.ResourceReport;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IResourceDiscoveryService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IResourceReportService;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.service.IUserNotificationService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.ResourceHubHashUtils;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/resources")
public class ResourceLinkController {

    private final IResourceLinkService resourceLinkService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IQuarkTransferTaskService quarkTransferTaskService;
    private final IQuarkShareService quarkShareService;
    private final IResourceDiscoveryService resourceDiscoveryService;
    private final IQuarkTransferRunnerService quarkTransferRunnerService;
    private final IResourceHubPublishService resourceHubPublishService;
    private final QuarkShareClient quarkShareClient;
    private final PanSouClient panSouClient;
    private final IResourceReportService resourceReportService;
    private final ISysUserService sysUserService;
    private final ISysConfigService sysConfigService;
    private final IMovieMetadataService movieService;
    private final IUserNotificationService notificationService;
    private final AuthHelper authHelper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ExecutorService repairInvalidExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "resource-invalid-repair");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, RepairInvalidJob> repairInvalidJobs = new ConcurrentHashMap<>();
    private final AtomicBoolean repairInvalidRunning = new AtomicBoolean(false);

    public ResourceLinkController(
            IResourceLinkService resourceLinkService,
            IResourceDiscoveryResultService discoveryResultService,
            IQuarkTransferTaskService quarkTransferTaskService,
            IQuarkShareService quarkShareService,
            IResourceDiscoveryService resourceDiscoveryService,
            IQuarkTransferRunnerService quarkTransferRunnerService,
            IResourceHubPublishService resourceHubPublishService,
            QuarkShareClient quarkShareClient,
            PanSouClient panSouClient,
            IResourceReportService resourceReportService,
            ISysUserService sysUserService,
            ISysConfigService sysConfigService,
            IMovieMetadataService movieService,
            IUserNotificationService notificationService,
            AuthHelper authHelper,
            StringRedisTemplate stringRedisTemplate) {
        this.resourceLinkService = resourceLinkService;
        this.discoveryResultService = discoveryResultService;
        this.quarkTransferTaskService = quarkTransferTaskService;
        this.quarkShareService = quarkShareService;
        this.resourceDiscoveryService = resourceDiscoveryService;
        this.quarkTransferRunnerService = quarkTransferRunnerService;
        this.resourceHubPublishService = resourceHubPublishService;
        this.quarkShareClient = quarkShareClient;
        this.panSouClient = panSouClient;
        this.resourceReportService = resourceReportService;
        this.sysUserService = sysUserService;
        this.sysConfigService = sysConfigService;
        this.movieService = movieService;
        this.notificationService = notificationService;
        this.authHelper = authHelper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PreDestroy
    public void shutdownRepairInvalidExecutor() {
        repairInvalidExecutor.shutdownNow();
    }

    @PostMapping
    public ResponseEntity<?> submitResource(
            @RequestBody ResourceSubmissionDTO dto,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser authUser = authHelper.requireUser(token);

        if (dto.getMovieId() == null || dto.getMovieId().isBlank()
                || dto.getUrl() == null || dto.getUrl().isBlank()) {
            return ResponseEntity.badRequest().body("movieId and url are required");
        }

        String resourceUrl = dto.getUrl().trim();
        String type = dto.getType() == null || dto.getType().isBlank()
                ? "DISK"
                : dto.getType().trim().toUpperCase();
        Set<String> allowedTypes = Set.of("DISK", "MAGNET", "TORRENT", "ONLINE");
        if (!allowedTypes.contains(type)) {
            return ResponseEntity.badRequest().body("Invalid resource type");
        }
        String urlError = validateResourceUrl(type, resourceUrl);
        if (urlError != null) {
            return ResponseEntity.badRequest().body(urlError);
        }
        String provider = dto.getProvider() == null || dto.getProvider().isBlank()
                ? "OTHER"
                : dto.getProvider().trim().toUpperCase();
        if ("DISK".equals(type) && "OTHER".equals(provider)) {
            return ResponseEntity.badRequest().body("provider is required for cloud disk resources");
        }

        String auditEnabled = sysConfigService.getConfigValue("resource.audit.enabled", "true");
        int auditStatus = "true".equals(auditEnabled) ? 0 : 1;

        int maxResources = Integer.parseInt(sysConfigService.getConfigValue("resource.max.per.user", "100"));
        long userResourceCount = resourceLinkService.count(
                new QueryWrapper<ResourceLink>().eq("uploader_id", authUser.getId()).eq("status", "ACTIVE"));
        if (userResourceCount >= maxResources) {
            return ResponseEntity.status(403)
                    .body("Resource limit reached. Maximum " + maxResources + " resources per user.");
        }

        int minInterval = Integer.parseInt(sysConfigService.getConfigValue("resource.submit.interval.seconds", "60"));
        if (minInterval > 0) {
            String rateKey = "resource:submit:user:" + authUser.getId();
            Boolean allowed = stringRedisTemplate.opsForValue()
                    .setIfAbsent(rateKey, "1", minInterval, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(allowed)) {
                Long ttl = stringRedisTemplate.getExpire(rateKey, TimeUnit.SECONDS);
                return ResponseEntity.status(429)
                        .body("Please wait " + Math.max(ttl == null ? minInterval : ttl, 1)
                                + " seconds before submitting another resource.");
            }
        }

        long duplicateCount = resourceLinkService.count(
                new QueryWrapper<ResourceLink>().eq("url", resourceUrl).eq("status", "ACTIVE"));
        if (duplicateCount > 0) {
            return ResponseEntity.status(409).body("This resource URL has already been submitted.");
        }

        ResourceLink link = new ResourceLink();
        link.setMovieId(dto.getMovieId());
        link.setName(cleanOptional(dto.getName(), 255));
        link.setUrl(resourceUrl);
        link.setCode("DISK".equals(type) ? cleanOptional(dto.getCode(), 50) : null);
        link.setProvider(provider);
        link.setType(type);
        link.setUploaderId(authUser.getId());
        link.setAuditStatus(auditStatus);
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setReportCount(0);
        applyQualityFields(link, dto);
        link.setRejectReason(null);
        link.setCreatedAt(LocalDateTime.now());

        resourceLinkService.addResource(link);

        String message = auditStatus == 1 ? "Resource published successfully!" : "Resource submitted for review";
        return ResponseEntity.ok(message);
    }

    @PostMapping("/admin")
    public ResponseEntity<?> createAdminResource(
            @RequestBody ResourceSubmissionDTO dto,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser admin = authHelper.requireAdmin(token);
        if (dto == null || dto.getMovieId() == null || dto.getMovieId().isBlank()
                || dto.getUrl() == null || dto.getUrl().isBlank()) {
            return ResponseEntity.badRequest().body("movieId and url are required");
        }
        MovieMetadata movie = movieService.getById(dto.getMovieId().trim());
        if (movie == null || "DELETED".equalsIgnoreCase(movie.getStatus())) {
            return ResponseEntity.badRequest().body("Movie not found");
        }
        String type = dto.getType() == null || dto.getType().isBlank() ? "DISK" : dto.getType().trim().toUpperCase();
        if (!Set.of("DISK", "MAGNET", "TORRENT", "ONLINE").contains(type)) {
            return ResponseEntity.badRequest().body("Invalid resource type");
        }
        String url = dto.getUrl().trim();
        String urlError = validateResourceUrl(type, url);
        if (urlError != null) return ResponseEntity.badRequest().body(urlError);
        String provider = dto.getProvider() == null || dto.getProvider().isBlank()
                ? "OTHER" : dto.getProvider().trim().toUpperCase();
        if ("DISK".equals(type) && "OTHER".equals(provider)) {
            return ResponseEntity.badRequest().body("provider is required for cloud disk resources");
        }
        if (resourceLinkService.count(new QueryWrapper<ResourceLink>().eq("url", url).eq("status", "ACTIVE")) > 0) {
            return ResponseEntity.status(409).body("This resource URL has already been submitted.");
        }
        LocalDateTime now = LocalDateTime.now();
        ResourceLink link = new ResourceLink();
        link.setMovieId(movie.getId());
        link.setName(cleanOptional(dto.getName(), 255));
        link.setUrl(url);
        link.setUrlHash(ResourceHubHashUtils.sha256(url));
        link.setCode("DISK".equals(type) ? cleanOptional(dto.getCode(), 50) : null);
        link.setProvider(provider);
        link.setType(type);
        link.setUploaderId(admin.getId());
        link.setAuditStatus(1);
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setReportCount(0);
        link.setSource("ADMIN_MANUAL");
        link.setAutoCollected(false);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        applyQualityFields(link, dto);
        resourceLinkService.addResource(link);
        return ResponseEntity.ok(link);
    }
    @PostMapping("/{id}/report")
    public ResponseEntity<?> reportInvalidResource(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser authUser = authHelper.requireUser(token);
        ResourceLink resource = resourceLinkService.getById(id);
        if (resource == null || !"ACTIVE".equals(resource.getStatus())) {
            return ResponseEntity.status(404).body("Resource not found");
        }

        ResourceReport existing = resourceReportService.getOne(new QueryWrapper<ResourceReport>()
                .eq("resource_id", id)
                .eq("user_id", authUser.getId())
                .eq("status", "PENDING")
                .last("LIMIT 1"));
        if (existing == null) {
            ResourceReport report = new ResourceReport();
            report.setResourceId(id);
            report.setUserId(authUser.getId());
            report.setReason(cleanOptional(request == null ? null : request.get("reason"), 255));
            report.setStatus("PENDING");
            report.setCreatedAt(LocalDateTime.now());
            resourceReportService.save(report);
            resource.setReportCount(resource.getReportCount() == null ? 1 : resource.getReportCount() + 1);
        }
        if ("NORMAL".equals(resource.getLinkStatus())) {
            resource.setLinkStatus("SUSPECTED_INVALID");
        }
        resourceLinkService.updateById(resource);
        return ResponseEntity.ok(Map.of("linkStatus", resource.getLinkStatus(), "reportCount", resource.getReportCount()));
    }

    @GetMapping("/mine")
    public ResponseEntity<?> getMyResources(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String linkStatus,
            @RequestParam(required = false) String movieId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser authUser = authHelper.requireUser(token);

        QueryWrapper<ResourceLink> query = new QueryWrapper<>();
        query.eq("uploader_id", authUser.getId())
                .eq("status", "ACTIVE");
        if (status != null) {
            query.eq("audit_status", status);
        }
        if (linkStatus != null && !linkStatus.isBlank()) {
            query.eq("link_status", linkStatus);
        }
        if (movieId != null && !movieId.isBlank()) {
            query.eq("movie_id", movieId);
        }
        query.orderByDesc("created_at");

        Page<ResourceLink> result = resourceLinkService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                query);
        return ResponseEntity.ok(toAdminPage(result));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateOwnResource(
            @PathVariable Long id,
            @RequestBody ResourceSubmissionDTO dto,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser authUser = authHelper.requireUser(token);
        ResourceLink resource = resourceLinkService.getById(id);
        if (resource == null || "DELETED".equals(resource.getStatus())) {
            return ResponseEntity.status(404).body("Resource not found");
        }
        boolean isAdmin = "ADMIN".equalsIgnoreCase(authUser.getRole());
        boolean isOwner = resource.getUploaderId() != null && resource.getUploaderId().equals(authUser.getId());
        if (!isAdmin && !isOwner) {
            return ResponseEntity.status(403).body("Forbidden");
        }
        if (dto.getUrl() == null || dto.getUrl().isBlank()) {
            return ResponseEntity.badRequest().body("url is required");
        }

        String resourceUrl = dto.getUrl().trim();
        String type = dto.getType() == null || dto.getType().isBlank()
                ? resource.getType()
                : dto.getType().trim().toUpperCase();
        Set<String> allowedTypes = Set.of("DISK", "MAGNET", "TORRENT", "ONLINE");
        if (!allowedTypes.contains(type)) {
            return ResponseEntity.badRequest().body("Invalid resource type");
        }
        String urlError = validateResourceUrl(type, resourceUrl);
        if (urlError != null) {
            return ResponseEntity.badRequest().body(urlError);
        }
        String provider = dto.getProvider() == null || dto.getProvider().isBlank()
                ? "OTHER"
                : dto.getProvider().trim().toUpperCase();
        if ("DISK".equals(type) && "OTHER".equals(provider)) {
            return ResponseEntity.badRequest().body("provider is required for cloud disk resources");
        }
        long duplicateCount = Objects.equals(resourceUrl, resource.getUrl())
                ? 0
                : resourceLinkService.count(new QueryWrapper<ResourceLink>()
                        .eq("url", resourceUrl)
                        .eq("status", "ACTIVE")
                        .isNull("deleted_at")
                        .ne("id", id));
        if (duplicateCount > 0) {
            return ResponseEntity.status(409).body("This resource URL has already been submitted.");
        }

        if (isAdmin && dto.getMovieId() != null && !dto.getMovieId().isBlank()
                && !Objects.equals(resource.getMovieId(), dto.getMovieId().trim())) {
            MovieMetadata movie = movieService.getById(dto.getMovieId().trim());
            if (movie == null || "DELETED".equalsIgnoreCase(movie.getStatus())) {
                return ResponseEntity.badRequest().body("Movie not found");
            }
            resource.setMovieId(movie.getId());
        }
        resource.setName(cleanOptional(dto.getName(), 255));
        resource.setUrl(resourceUrl);
        resource.setUrlHash(ResourceHubHashUtils.sha256(resourceUrl));
        resource.setCode("DISK".equals(type) ? cleanOptional(dto.getCode(), 50) : null);
        resource.setProvider(provider);
        resource.setType(type);
        resource.setLinkStatus("NORMAL");
        resource.setReportCount(0);
        applyQualityFields(resource, dto);
        resource.setRejectReason(null);
        resource.setUpdatedAt(LocalDateTime.now());
        if (!isAdmin) {
            String auditEnabled = sysConfigService.getConfigValue("resource.audit.enabled", "true");
            resource.setAuditStatus("true".equals(auditEnabled) ? 0 : 1);
        }
        resourceLinkService.updateById(resource);
        return ResponseEntity.ok("Resource updated");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOwnResource(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser authUser = authHelper.requireUser(token);
        ResourceLink resource = resourceLinkService.getById(id);
        if (resource == null || "DELETED".equals(resource.getStatus())) {
            return ResponseEntity.status(404).body("Resource not found");
        }
        boolean isAdmin = "ADMIN".equalsIgnoreCase(authUser.getRole());
        boolean isOwner = resource.getUploaderId() != null && resource.getUploaderId().equals(authUser.getId());
        if (!isAdmin && !isOwner) {
            return ResponseEntity.status(403).body("Forbidden");
        }
        resource.setStatus("DELETED");
        resource.setDeletedAt(LocalDateTime.now());
        resourceLinkService.updateById(resource);
        return ResponseEntity.ok("Resource deleted");
    }

    @PutMapping("/{id}/audit")
    public ResponseEntity<?> auditResource(
            @PathVariable Long id,
            @RequestParam Integer status,
            @RequestParam(required = false) String reason,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        if (status != 1 && status != 2) {
            return ResponseEntity.badRequest().body("Status must be 1 (approve) or 2 (reject)");
        }
        ResourceLink resource = resourceLinkService.getById(id);
        if (resource == null || "DELETED".equals(resource.getStatus())) {
            return ResponseEntity.status(404).body("Resource not found");
        }
        resource.setAuditStatus(status);
        resource.setRejectReason(status == 2 ? cleanOptional(reason, 255) : null);
        resourceLinkService.updateById(resource);
        notifyResourceAudit(resource, status);
        return ResponseEntity.ok(status == 1 ? "Resource approved" : "Resource rejected");
    }

    @PutMapping("/batch/audit")
    public ResponseEntity<?> batchAuditResources(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        List<Long> ids = toLongIds(request.get("ids"));
        Integer status = (Integer) request.get("status");
        String reason = cleanOptional((String) request.get("reason"), 255);
        if (status != 1 && status != 2) {
            return ResponseEntity.badRequest().body("Status must be 1 (approve) or 2 (reject)");
        }
        for (Long id : ids) {
            ResourceLink resource = resourceLinkService.getById(id);
            if (resource != null && !"DELETED".equals(resource.getStatus())) {
                resource.setAuditStatus(status);
                resource.setRejectReason(status == 2 ? (reason == null ? "Batch rejected" : reason) : null);
                resourceLinkService.updateById(resource);
                notifyResourceAudit(resource, status);
            }
        }
        return ResponseEntity.ok(status == 1 ? ids.size() + " resources approved" : ids.size() + " resources rejected");
    }

    @GetMapping("/admin/all")
    public ResponseEntity<?> getAllResources(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String movieId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String linkStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);

        QueryWrapper<ResourceLink> query = new QueryWrapper<>();
        if (!includeDeleted) {
            query.eq("status", "ACTIVE");
        }
        if (status != null) {
            query.eq("audit_status", status);
        }
        if (movieId != null && !movieId.isBlank()) {
            query.eq("movie_id", movieId);
        }
        if (provider != null && !provider.isBlank()) {
            query.eq("provider", provider);
        }
        if (linkStatus != null && !linkStatus.isBlank()) {
            query.eq("link_status", linkStatus);
        }
        applyKeywordFilters(query, keyword);

        query.orderByDesc("created_at");
        Page<ResourceLink> result = resourceLinkService.page(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query);
        return ResponseEntity.ok(toAdminPage(result));
    }

    @PutMapping("/admin/{id}/link-status")
    public ResponseEntity<?> updateLinkStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        Set<String> allowed = Set.of("NORMAL", "SUSPECTED_INVALID", "INVALID");
        if (!allowed.contains(status)) {
            return ResponseEntity.badRequest().body("Invalid link status");
        }
        ResourceLink resource = resourceLinkService.getById(id);
        if (resource == null || "DELETED".equals(resource.getStatus())) {
            return ResponseEntity.status(404).body("Resource not found");
        }
        resource.setLinkStatus(status);
        resourceLinkService.updateById(resource);
        notifyResourceLinkStatus(resource, status);
        return ResponseEntity.ok(Map.of("linkStatus", status));
    }

    @DeleteMapping("/admin/{id}")
    public ResponseEntity<?> deleteResource(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        ResourceLink resource = resourceLinkService.getById(id);
        if (resource == null || "DELETED".equals(resource.getStatus())) {
            return ResponseEntity.status(404).body("Resource not found");
        }
        resource.setStatus("DELETED");
        resource.setDeletedAt(LocalDateTime.now());
        resourceLinkService.updateById(resource);
        return ResponseEntity.ok("Resource deleted");
    }

    @DeleteMapping("/admin/batch")
    public ResponseEntity<?> batchDeleteResources(
            @RequestBody List<Long> ids,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        for (Long id : ids) {
            ResourceLink resource = resourceLinkService.getById(id);
            if (resource != null && !"DELETED".equals(resource.getStatus())) {
                resource.setStatus("DELETED");
                resource.setDeletedAt(LocalDateTime.now());
                resourceLinkService.updateById(resource);
            }
        }
        return ResponseEntity.ok("Resources deleted: " + ids.size());
    }

    @PostMapping("/admin/repair-invalid")
    public ResponseEntity<?> repairInvalidResources(
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        RepairInvalidJob runningJob = findRunningRepairInvalidJob();
        if (!repairInvalidRunning.compareAndSet(false, true)) {
            return ResponseEntity.accepted().body(runningJob == null
                    ? Map.of("status", "RUNNING")
                    : runningJob.toResponse());
        }
        RepairInvalidJob job = new RepairInvalidJob(UUID.randomUUID().toString(), safeLimit);
        repairInvalidJobs.put(job.jobId, job);
        trimRepairInvalidJobs();
        repairInvalidExecutor.submit(() -> runRepairInvalidJob(job));
        return ResponseEntity.accepted().body(job.toResponse());
    }

    @GetMapping("/admin/repair-invalid/jobs/{jobId}")
    public ResponseEntity<?> getRepairInvalidJob(
            @PathVariable String jobId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        RepairInvalidJob job = repairInvalidJobs.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body("Repair job not found");
        }
        return ResponseEntity.ok(job.toResponse());
    }

    private void runRepairInvalidJob(RepairInvalidJob job) {
        try {
            doRepairInvalidResources(job);
            job.status = "SUCCEEDED";
        } catch (Exception e) {
            job.status = "FAILED";
            job.errors.add("job: " + safeText(e.getMessage()));
        } finally {
            job.finishedAt = LocalDateTime.now();
            repairInvalidRunning.set(false);
        }
    }

    private void doRepairInvalidResources(RepairInvalidJob job) {
        int safeLimit = job.limit;
        List<ResourceLink> links = resourceLinkService.list(invalidRepairQuery(safeLimit));
        for (ResourceLink link : links) {
            job.checked++;
            try {
                if (!shouldForceRefresh(link)) {
                    LinkCheckResult current = panSouClient.checkLink(link.getUrl());
                    if (current.checked() && current.valid()) {
                        markLinkNormal(link);
                        job.restored++;
                        continue;
                    }
                    if (!current.checked()) {
                        markLinkSuspected(link, "Link check did not return a clear result: " + safeText(current.message()));
                        job.skipped++;
                        continue;
                    }
                }
                ResourceLink refreshed = refreshShareLink(link);
                if (refreshed == null || refreshed.getUrl() == null || refreshed.getUrl().isBlank()) {
                    if (rediscoverResource(link, "Unable to recreate share link", job.errors)) {
                        job.rediscovered++;
                    } else {
                        markLinkInvalid(link, "Unable to recreate share link");
                        job.invalid++;
                    }
                    continue;
                }
                LinkCheckResult refreshedCheck = panSouClient.checkLink(refreshed.getUrl());
                if (refreshedCheck.checked() && refreshedCheck.valid()) {
                    markLinkNormal(refreshed);
                    job.reshared++;
                } else if (refreshedCheck.checked()) {
                    if (rediscoverResource(refreshed, "Refreshed link is still invalid", job.errors)) {
                        job.rediscovered++;
                    } else {
                        markLinkInvalid(refreshed, "Refreshed link is still invalid");
                        job.invalid++;
                    }
                } else {
                    markLinkSuspected(refreshed, "Unable to confirm refreshed link status: " + safeText(refreshedCheck.message()));
                    job.skipped++;
                }
            } catch (Exception e) {
                if (rediscoverResource(link, safeText(e.getMessage()), job.errors)) {
                    job.rediscovered++;
                } else {
                    markLinkSuspected(link, "Repair failed: " + safeText(e.getMessage()));
                    job.errors.add("resource " + link.getId() + ": " + safeText(e.getMessage()));
                }
            }
        }
    }

    private RepairInvalidJob findRunningRepairInvalidJob() {
        return repairInvalidJobs.values().stream()
                .filter(job -> "RUNNING".equals(job.status))
                .findFirst()
                .orElse(null);
    }

    private void trimRepairInvalidJobs() {
        if (repairInvalidJobs.size() <= 20) {
            return;
        }
        repairInvalidJobs.values().stream()
                .filter(job -> !"RUNNING".equals(job.status))
                .sorted((left, right) -> right.startedAt.compareTo(left.startedAt))
                .skip(20)
                .map(job -> job.jobId)
                .toList()
                .forEach(repairInvalidJobs::remove);
    }

    private static class RepairInvalidJob {
        private final String jobId;
        private final int limit;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private volatile LocalDateTime finishedAt;
        private volatile String status = "RUNNING";
        private volatile int checked;
        private volatile int restored;
        private volatile int reshared;
        private volatile int rediscovered;
        private volatile int invalid;
        private volatile int skipped;
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        private RepairInvalidJob(String jobId, int limit) {
            this.jobId = jobId;
            this.limit = limit;
        }

        private Map<String, Object> toResponse() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", jobId);
            response.put("status", status);
            response.put("limit", limit);
            response.put("checked", checked);
            response.put("restored", restored);
            response.put("reshared", reshared);
            response.put("rediscovered", rediscovered);
            response.put("invalid", invalid);
            response.put("skipped", skipped);
            response.put("startedAt", startedAt);
            response.put("finishedAt", finishedAt);
            response.put("errors", new ArrayList<>(errors));
            return response;
        }
    }

    @GetMapping("/admin/invalid-checks")
    public ResponseEntity<?> listInvalidResourceChecks(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<Map<String, Object>> rows = resourceLinkService.list(invalidRepairQuery(safeLimit)).stream()
                .map(this::toInvalidCheckRow)
                .toList();
        return ResponseEntity.ok(Map.of("records", rows, "total", rows.size()));
    }

    @PostMapping("/admin/invalid-checks/scan")
    public ResponseEntity<?> scanInvalidResourceChecks(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<ResourceLink> links = resourceLinkService.list(invalidScanQuery(safeLimit));
        Map<String, LinkCheckResult> checks = panSouClient.checkLinks(links.stream()
                .map(ResourceLink::getUrl)
                .filter(Objects::nonNull)
                .toList());
        int checked = 0;
        int normal = 0;
        int suspected = 0;
        int unclear = 0;
        for (ResourceLink link : links) {
            LinkCheckResult check = checks.get(link.getUrl() == null ? null : link.getUrl().trim());
            if (check == null) {
                unclear++;
                markLinkSuspected(link, "PanSou check did not return this link");
                continue;
            }
            checked++;
            if (check.checked() && check.valid()) {
                markLinkNormal(link);
                normal++;
            } else if (check.checked()) {
                markLinkSuspected(link, "PanSou detected invalid link: " + safeText(check.message()));
                suspected++;
            } else {
                markLinkSuspected(link, "PanSou check unclear: " + safeText(check.message()));
                unclear++;
            }
        }
        List<Map<String, Object>> rows = resourceLinkService.list(invalidRepairQuery(safeLimit)).stream()
                .map(this::toInvalidCheckRow)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", checked);
        result.put("normal", normal);
        result.put("suspected", suspected);
        result.put("unclear", unclear);
        result.put("records", rows);
        result.put("total", rows.size());
        return ResponseEntity.ok(result);
    }

    private QueryWrapper<ResourceLink> invalidRepairQuery(int limit) {
        return new QueryWrapper<ResourceLink>()
                .ne("status", "DELETED")
                .isNull("deleted_at")
                .eq("source", "RESOURCE_HUB")
                .eq("provider", "QUARK")
                .in("link_status", List.of("SUSPECTED_INVALID", "INVALID"))
                .orderByDesc("validated_at")
                .orderByDesc("created_at")
                .last("LIMIT " + limit);
    }

    private QueryWrapper<ResourceLink> invalidScanQuery(int limit) {
        return new QueryWrapper<ResourceLink>()
                .ne("status", "DELETED")
                .isNull("deleted_at")
                .eq("source", "RESOURCE_HUB")
                .eq("provider", "QUARK")
                .isNotNull("url")
                .orderByDesc("validated_at")
                .orderByDesc("created_at")
                .last("LIMIT " + limit);
    }

    private boolean shouldForceRefresh(ResourceLink link) {
        return "INVALID".equalsIgnoreCase(link.getLinkStatus())
                || "INACTIVE".equalsIgnoreCase(link.getStatus());
    }

    private Map<String, Object> toInvalidCheckRow(ResourceLink link) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        MovieMetadata movie = movieService.getById(link.getMovieId());
        QuarkTransferTask task = findTransferTask(link);
        row.put("id", link.getId());
        row.put("movieId", link.getMovieId());
        row.put("movieTitle", firstText(movie == null ? null : movie.getTitleCn(),
                movie == null ? null : movie.getTitleEn(), link.getName()));
        row.put("status", link.getStatus());
        row.put("linkStatus", link.getLinkStatus());
        row.put("url", link.getUrl());
        row.put("lastCheckError", link.getLastCheckError());
        row.put("validatedAt", link.getValidatedAt());
        row.put("transferStatus", task == null ? null : task.getStatus());
        row.put("savedPath", task == null ? null : task.getSavedPath());
        if (task == null || task.getSavedPath() == null || task.getSavedPath().isBlank()) {
            row.put("folderState", "NO_SAVED_PATH");
        } else {
            try {
                QuarkShareClient.FolderContentCheck check = quarkShareClient.checkFolderContent(task.getSavedPath());
                row.put("folderState", check.hasContent() ? "HAS_CONTENT" : "EMPTY");
                row.put("folderItemCount", check.itemCount());
            } catch (Exception e) {
                row.put("folderState", "CHECK_FAILED");
                row.put("folderError", safeText(e.getMessage()));
            }
        }
        row.put("nextAction", shouldForceRefresh(link) ? "RESHARE_OR_REDISCOVER" : "VERIFY_THEN_REPAIR");
        return row;
    }

    private boolean rediscoverResource(ResourceLink link, String reason, List<String> errors) {
        try {
            LocalDateTime startedAt = LocalDateTime.now();
            MovieMetadata movie = movieService.getById(link.getMovieId());
            ResourceDiscoveryRequest request = new ResourceDiscoveryRequest();
            request.setMovieId(link.getMovieId());
            request.setMovieTitle(firstText(movie == null ? null : movie.getTitleCn(),
                    movie == null ? null : movie.getTitleEn(), link.getName(), link.getMovieId()));
            request.setKeyword(request.getMovieTitle());
            request.setSource("PANSOU");
            request.setMaxResults(5);
            request.setRefresh(true);
            request.setRunNow(true);
            ResourceHubTask task = resourceDiscoveryService.enqueue(request);
            resourceDiscoveryService.runTask(task.getId());
            List<QuarkTransferTask> transfers = quarkTransferTaskService.list(new QueryWrapper<QuarkTransferTask>()
                    .eq("movie_id", link.getMovieId())
                    .eq("status", "PENDING")
                    .orderByDesc("created_at")
                    .last("LIMIT 3"));
            for (QuarkTransferTask transfer : transfers) {
                quarkTransferRunnerService.submitOne(transfer.getId());
            }
            List<ResourceDiscoveryResult> discoveries = discoveryResultService.list(new QueryWrapper<ResourceDiscoveryResult>()
                    .eq("movie_id", link.getMovieId())
                    .eq("status", "DISCOVERED")
                    .isNotNull("share_url")
                    .orderByDesc("updated_at")
                    .last("LIMIT 3"));
            for (ResourceDiscoveryResult discovery : discoveries) {
                resourceHubPublishService.publishDiscovery(discovery.getId());
            }
            if (!replaceInvalidLinkWithRediscoveredResource(link, startedAt)) {
                markLinkSuspected(link, "Re-discovery triggered: " + safeText(reason));
            }
            return true;
        } catch (Exception e) {
            errors.add("resource " + link.getId() + " rediscover: " + safeText(e.getMessage()));
            return false;
        }
    }

    private boolean replaceInvalidLinkWithRediscoveredResource(ResourceLink original, LocalDateTime startedAt) {
        if (original == null || original.getId() == null) {
            return false;
        }
        ResourceLink replacement = resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", original.getMovieId())
                .eq("source", "RESOURCE_HUB")
                .eq("provider", "QUARK")
                .eq("status", "ACTIVE")
                .eq("link_status", "NORMAL")
                .ne("id", original.getId())
                .and(w -> w.ge("created_at", startedAt).or().ge("updated_at", startedAt))
                .orderByDesc("updated_at")
                .orderByDesc("created_at")
                .last("LIMIT 1"), false);
        if (replacement == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        original.setName(firstText(replacement.getName(), original.getName()));
        original.setType(replacement.getType());
        original.setProvider(replacement.getProvider());
        original.setUrl(replacement.getUrl());
        original.setUrlHash(replacement.getUrlHash());
        original.setCode(replacement.getCode());
        original.setAuditStatus(replacement.getAuditStatus());
        original.setStatus("ACTIVE");
        original.setLinkStatus("NORMAL");
        original.setSource(replacement.getSource());
        original.setSourceRef(replacement.getSourceRef());
        original.setSourceUrl(replacement.getSourceUrl());
        original.setAutoCollected(replacement.getAutoCollected());
        original.setValidatedAt(now);
        original.setLastCheckError(null);
        original.setQuality(replacement.getQuality());
        original.setSubtitle(replacement.getSubtitle());
        original.setFileSize(replacement.getFileSize());
        original.setVersionNote(replacement.getVersionNote());
        original.setRejectReason(null);
        original.setDeletedAt(null);
        original.setUpdatedAt(now);
        resourceLinkService.updateById(original);

        List<ResourceDiscoveryResult> replacementDiscoveries = discoveryResultService.list(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("resource_link_id", replacement.getId()));
        for (ResourceDiscoveryResult discovery : replacementDiscoveries) {
            discovery.setResourceLinkId(original.getId());
            discovery.setUpdatedAt(now);
            discoveryResultService.updateById(discovery);
        }
        replacement.setStatus("DELETED");
        replacement.setDeletedAt(now);
        replacement.setUpdatedAt(now);
        replacement.setLastCheckError("Merged into repaired resource link " + original.getId());
        resourceLinkService.updateById(replacement);
        return true;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private void applyKeywordFilters(QueryWrapper<ResourceLink> query, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String kw = keyword.trim();
        List<String> movieIds = movieService.lambdaQuery()
                .like(MovieMetadata::getTitleCn, kw)
                .or()
                .like(MovieMetadata::getTitleEn, kw)
                .list()
                .stream()
                .map(MovieMetadata::getId)
                .toList();
        List<Long> userIds = sysUserService.lambdaQuery()
                .like(SysUser::getUsername, kw)
                .list()
                .stream()
                .map(SysUser::getId)
                .toList();
        query.and(w -> {
            w.like("movie_id", kw).or().like("provider", kw).or().like("url", kw).or().like("name", kw);
            if (!movieIds.isEmpty()) {
                w.or().in("movie_id", movieIds);
            }
            if (!userIds.isEmpty()) {
                w.or().in("uploader_id", userIds);
            }
        });
    }

    private ResourceLink refreshShareLink(ResourceLink link) {
        QuarkTransferTask task = findTransferTask(link);
        if (task == null || task.getSavedPath() == null || task.getSavedPath().isBlank()) {
            return null;
        }
        QuarkShareClient.FolderContentCheck contentCheck = quarkShareClient.checkFolderContent(task.getSavedPath());
        if (!contentCheck.hasContent()) {
            throw new IllegalStateException("Saved Quark folder is empty: " + task.getSavedPath());
        }
        ResourceDiscoveryResult discovery = findDiscovery(link);
        LocalDateTime now = LocalDateTime.now();
        task.setStatus("SUBMITTED");
        task.setShareUrl(null);
        task.setShareUrlHash(null);
        task.setLastError(null);
        task.setUpdatedAt(now);
        quarkTransferTaskService.updateById(task);
        if (discovery != null) {
            discovery.setShareUrl(null);
            discovery.setShareUrlHash(null);
            discovery.setUpdatedAt(now);
            discoveryResultService.updateById(discovery);
        }
        String shareUrl = quarkShareService.ensureShareUrl(task);
        if (shareUrl == null || shareUrl.isBlank()) {
            return null;
        }
        return resourceLinkService.getById(link.getId());
    }

    private ResourceDiscoveryResult findDiscovery(ResourceLink link) {
        if (link == null || link.getId() == null) {
            return null;
        }
        return discoveryResultService.getOne(new QueryWrapper<ResourceDiscoveryResult>()
                .eq("resource_link_id", link.getId())
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
    }

    private QuarkTransferTask findTransferTask(ResourceLink link) {
        ResourceDiscoveryResult discovery = findDiscovery(link);
        if (discovery != null && discovery.getId() != null) {
            QuarkTransferTask byDiscovery = quarkTransferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                    .eq("discovery_result_id", discovery.getId())
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"), false);
            if (byDiscovery != null) {
                return byDiscovery;
            }
        }
        if (link == null || link.getUrlHash() == null || link.getUrlHash().isBlank()) {
            return null;
        }
        return quarkTransferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                .eq("movie_id", link.getMovieId())
                .eq("share_url_hash", link.getUrlHash())
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
    }

    private void markLinkNormal(ResourceLink link) {
        LocalDateTime now = LocalDateTime.now();
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setValidatedAt(now);
        link.setLastCheckError(null);
        link.setUpdatedAt(now);
        resourceLinkService.updateById(link);
    }

    private void markLinkInvalid(ResourceLink link, String reason) {
        LocalDateTime now = LocalDateTime.now();
        link.setStatus("INACTIVE");
        link.setLinkStatus("INVALID");
        link.setValidatedAt(now);
        link.setLastCheckError(cleanOptional(reason, 1000));
        link.setUpdatedAt(now);
        resourceLinkService.updateById(link);
    }

    private void markLinkSuspected(ResourceLink link, String reason) {
        LocalDateTime now = LocalDateTime.now();
        link.setLinkStatus("SUSPECTED_INVALID");
        link.setValidatedAt(now);
        link.setLastCheckError(cleanOptional(reason, 1000));
        link.setUpdatedAt(now);
        resourceLinkService.updateById(link);
    }

    private Page<ResourceAdminDTO> toAdminPage(Page<ResourceLink> source) {
        List<String> movieIds = source.getRecords().stream()
                .map(ResourceLink::getMovieId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> userIds = source.getRecords().stream()
                .map(ResourceLink::getUploaderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, MovieMetadata> movies = movieIds.isEmpty()
                ? Collections.emptyMap()
                : movieService.listByIds(movieIds).stream().collect(Collectors.toMap(MovieMetadata::getId, m -> m));
        Map<Long, SysUser> users = userIds.isEmpty()
                ? Collections.emptyMap()
                : sysUserService.listByIds(userIds).stream().collect(Collectors.toMap(SysUser::getId, u -> u));

        Page<ResourceAdminDTO> page = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        page.setPages(source.getPages());
        page.setRecords(source.getRecords().stream().map(resource -> {
            ResourceAdminDTO dto = new ResourceAdminDTO();
            BeanUtils.copyProperties(resource, dto);
            MovieMetadata movie = movies.get(resource.getMovieId());
            if (movie != null) {
                dto.setMovieTitle(movie.getTitleCn());
            }
            SysUser uploader = users.get(resource.getUploaderId());
            if (uploader != null) {
                dto.setUploaderName(uploader.getUsername());
            }
            return dto;
        }).toList());
        return page;
    }

    private List<Long> toLongIds(Object rawIds) {
        if (!(rawIds instanceof List<?> ids)) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .map(id -> ((Number) id).longValue())
                .toList();
    }

    private String validateResourceUrl(String type, String url) {
        String lowerUrl = url.toLowerCase();
        return switch (type) {
            case "MAGNET" -> lowerUrl.startsWith("magnet:?xt=urn:btih:")
                    ? null
                    : "Magnet resources must start with magnet:?xt=urn:btih:";
            case "TORRENT" -> isHttpUrl(lowerUrl) && lowerUrl.contains(".torrent")
                    ? null
                    : "Torrent resources must be an http(s) .torrent URL";
            case "DISK", "ONLINE" -> isHttpUrl(lowerUrl)
                    ? null
                    : "Cloud disk and online resources must be http(s) URLs";
            default -> "Invalid resource type";
        };
    }

    private boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private void applyQualityFields(ResourceLink resource, ResourceSubmissionDTO dto) {
        resource.setQuality(cleanOptional(dto.getQuality(), 50));
        resource.setSubtitle(cleanOptional(dto.getSubtitle(), 50));
        resource.setFileSize(cleanOptional(dto.getFileSize(), 50));
        resource.setVersionNote(cleanOptional(dto.getVersionNote(), 255));
    }

    private String cleanOptional(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String safeText(String value) {
        String cleaned = cleanOptional(value, 200);
        return cleaned == null ? "未知错误" : cleaned;
    }

    private void notifyResourceAudit(ResourceLink resource, int auditStatus) {
        if (resource.getUploaderId() == null) {
            return;
        }
        String movieTitle = resolveMovieTitle(resource.getMovieId());
        String resourceName = resource.getName() == null || resource.getName().isBlank()
                ? "resource"
                : resource.getName();
        String title = auditStatus == 1 ? "Resource approved" : "Resource rejected";
        String rejectReason = resource.getRejectReason() == null ? "" : " Reason: " + resource.getRejectReason();
        String content = auditStatus == 1
                ? "Your submission \"" + resourceName + "\" for " + movieTitle + " has been approved."
                : "Your submission \"" + resourceName + "\" for " + movieTitle + " has been rejected." + rejectReason;
        notificationService.notifyUser(
                resource.getUploaderId(),
                "RESOURCE_AUDIT",
                title,
                content,
                "RESOURCE",
                String.valueOf(resource.getId()));
    }

    private void notifyResourceLinkStatus(ResourceLink resource, String linkStatus) {
        if (resource.getUploaderId() == null) {
            return;
        }
        String movieTitle = resolveMovieTitle(resource.getMovieId());
        String resourceName = resource.getName() == null || resource.getName().isBlank()
                ? "resource"
                : resource.getName();
        String readableStatus = switch (linkStatus) {
            case "INVALID" -> "invalid";
            case "SUSPECTED_INVALID" -> "suspected invalid";
            default -> "normal";
        };
        notificationService.notifyUser(
                resource.getUploaderId(),
                "RESOURCE_LINK_STATUS",
                "Resource link status updated",
                "Your submission \"" + resourceName + "\" for " + movieTitle + " was marked as " + readableStatus + ".",
                "RESOURCE",
                String.valueOf(resource.getId()));
    }

    private String resolveMovieTitle(String movieId) {
        if (movieId == null || movieId.isBlank()) {
            return "the movie";
        }
        MovieMetadata movie = movieService.getById(movieId);
        if (movie == null) {
            return movieId;
        }
        if (movie.getTitleCn() != null && !movie.getTitleCn().isBlank()) {
            return movie.getTitleCn();
        }
        if (movie.getTitleEn() != null && !movie.getTitleEn().isBlank()) {
            return movie.getTitleEn();
        }
        return movieId;
    }
}
