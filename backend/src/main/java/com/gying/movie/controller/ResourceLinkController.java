package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.dto.ResourceAdminDTO;
import com.gying.movie.dto.ResourceSubmissionDTO;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.service.IUserNotificationService;
import com.gying.movie.utils.AuthHelper;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/resources")
public class ResourceLinkController {

    private final IResourceLinkService resourceLinkService;
    private final ISysUserService sysUserService;
    private final ISysConfigService sysConfigService;
    private final IMovieMetadataService movieService;
    private final IUserNotificationService notificationService;
    private final AuthHelper authHelper;
    private final StringRedisTemplate stringRedisTemplate;

    public ResourceLinkController(
            IResourceLinkService resourceLinkService,
            ISysUserService sysUserService,
            ISysConfigService sysConfigService,
            IMovieMetadataService movieService,
            IUserNotificationService notificationService,
            AuthHelper authHelper,
            StringRedisTemplate stringRedisTemplate) {
        this.resourceLinkService = resourceLinkService;
        this.sysUserService = sysUserService;
        this.sysConfigService = sysConfigService;
        this.movieService = movieService;
        this.notificationService = notificationService;
        this.authHelper = authHelper;
        this.stringRedisTemplate = stringRedisTemplate;
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
        link.setName(dto.getName());
        link.setUrl(resourceUrl);
        link.setCode(dto.getCode());
        link.setProvider(provider);
        link.setType(type);
        link.setUploaderId(authUser.getId());
        link.setAuditStatus(auditStatus);
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setReportCount(0);
        link.setCreatedAt(LocalDateTime.now());

        resourceLinkService.addResource(link);

        String message = auditStatus == 1 ? "Resource published successfully!" : "Resource submitted for review";
        return ResponseEntity.ok(message);
    }

    @PostMapping("/{id}/report")
    public ResponseEntity<?> reportInvalidResource(@PathVariable Long id) {
        ResourceLink resource = resourceLinkService.getById(id);
        if (resource == null || !"ACTIVE".equals(resource.getStatus())) {
            return ResponseEntity.status(404).body("Resource not found");
        }
        resource.setReportCount(resource.getReportCount() == null ? 1 : resource.getReportCount() + 1);
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
        query.orderByDesc("created_at");

        Page<ResourceLink> result = resourceLinkService.page(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                query);
        return ResponseEntity.ok(toAdminPage(result));
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
        resourceLinkService.updateById(resource);
        return ResponseEntity.ok("Resource deleted");
    }

    @PutMapping("/{id}/audit")
    public ResponseEntity<?> auditResource(
            @PathVariable Long id,
            @RequestParam Integer status,
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
        if (status != 1 && status != 2) {
            return ResponseEntity.badRequest().body("Status must be 1 (approve) or 2 (reject)");
        }
        for (Long id : ids) {
            ResourceLink resource = resourceLinkService.getById(id);
            if (resource != null && !"DELETED".equals(resource.getStatus())) {
                resource.setAuditStatus(status);
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
                resourceLinkService.updateById(resource);
            }
        }
        return ResponseEntity.ok("Resources deleted: " + ids.size());
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

    private void notifyResourceAudit(ResourceLink resource, int auditStatus) {
        if (resource.getUploaderId() == null) {
            return;
        }
        String movieTitle = resolveMovieTitle(resource.getMovieId());
        String resourceName = resource.getName() == null || resource.getName().isBlank()
                ? "resource"
                : resource.getName();
        String title = auditStatus == 1 ? "Resource approved" : "Resource rejected";
        String content = auditStatus == 1
                ? "Your submission \"" + resourceName + "\" for " + movieTitle + " has been approved."
                : "Your submission \"" + resourceName + "\" for " + movieTitle + " has been rejected.";
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
