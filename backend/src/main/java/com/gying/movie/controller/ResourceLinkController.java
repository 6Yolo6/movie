package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.ResourceSubmissionDTO;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resources")
public class ResourceLinkController {

    @Autowired
    private IResourceLinkService resourceLinkService;

    @Autowired
    private ISysUserService sysUserService;

    @Autowired
    private ISysConfigService sysConfigService;

    @Autowired
    private JwtUtils jwtUtils;

    // Track last submission time for rate limiting
    private Map<Long, LocalDateTime> lastSubmissionTime = new HashMap<>();

    @PostMapping
    public ResponseEntity<?> submitResource(@RequestBody ResourceSubmissionDTO dto,
            @RequestHeader(value = "Authorization", required = false) String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        // Extract user from token
        Claims claims = jwtUtils.validateToken(token.substring(7));
        if (claims == null) {
            return ResponseEntity.status(401).body("Invalid Token");
        }

        String username = claims.getSubject();
        String userRole = claims.get("role", String.class);
        SysUser user = sysUserService.getOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>().eq("username", username));

        if (user == null) {
            return ResponseEntity.status(401).body("User not found");
        }

        // Check role permission - only ADMIN and PUBLISHER can submit resources
        if (!"ADMIN".equals(userRole) && !"PUBLISHER".equals(userRole)) {
            return ResponseEntity.status(403).body("Publisher permission required");
        }

        // Check if audit is enabled
        String auditEnabled = sysConfigService.getConfigValue("resource.audit.enabled", "false");
        int auditStatus = "true".equals(auditEnabled) ? 0 : 1; // 0=pending, 1=auto-approved

        // Check resource count limit
        String maxResourcesStr = sysConfigService.getConfigValue("resource.max.per.user", "100");
        int maxResources = Integer.parseInt(maxResourcesStr);

        long userResourceCount = resourceLinkService.count(
                new QueryWrapper<ResourceLink>().eq("uploader_id", user.getId()));

        if (userResourceCount >= maxResources) {
            return ResponseEntity.status(403)
                    .body("Resource limit reached. Maximum " + maxResources + " resources per user.");
        }

        // Check rate limiting (prevent rapid successive submissions)
        LocalDateTime lastTime = lastSubmissionTime.get(user.getId());
        if (lastTime != null) {
            long secondsSinceLastSubmission = java.time.Duration.between(lastTime, LocalDateTime.now()).getSeconds();
            String minIntervalStr = sysConfigService.getConfigValue("resource.submit.interval.seconds", "60");
            int minInterval = Integer.parseInt(minIntervalStr);

            if (secondsSinceLastSubmission < minInterval) {
                return ResponseEntity.status(429).body("Please wait " + (minInterval - secondsSinceLastSubmission)
                        + " seconds before submitting another resource.");
            }
        }

        // Check for duplicate URL
        long duplicateCount = resourceLinkService.count(
                new QueryWrapper<ResourceLink>().eq("url", dto.getUrl()));

        if (duplicateCount > 0) {
            return ResponseEntity.status(409).body("This resource URL has already been submitted.");
        }

        ResourceLink link = new ResourceLink();
        link.setMovieId(dto.getMovieId());
        link.setName(dto.getName());
        link.setUrl(dto.getUrl());
        link.setCode(dto.getCode());
        link.setProvider(dto.getProvider());
        link.setType(dto.getType());
        link.setUploaderId(user.getId());
        link.setAuditStatus(auditStatus);
        link.setCreatedAt(LocalDateTime.now());

        resourceLinkService.addResource(link);

        // Update last submission time
        lastSubmissionTime.put(user.getId(), LocalDateTime.now());

        String message = auditStatus == 1 ? "Resource published successfully!" : "Resource submitted for review";
        return ResponseEntity.ok(message);
    }

    // Get pending resources (admin only)
    @GetMapping("/pending")
    public ResponseEntity<?> getPendingResources(
            @RequestHeader(value = "Authorization", required = false) String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        Claims claims = jwtUtils.validateToken(token.substring(7));
        if (claims == null) {
            return ResponseEntity.status(401).body("Invalid Token");
        }

        String role = claims.get("role", String.class);
        if (!"ADMIN".equals(role)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        // Get all pending resources
        var pending = resourceLinkService.list(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ResourceLink>()
                        .eq("audit_status", 0)
                        .orderByDesc("created_at"));

        return ResponseEntity.ok(pending);
    }

    // Approve or reject resource (admin only)
    @PutMapping("/{id}/audit")
    public ResponseEntity<?> auditResource(
            @PathVariable Long id,
            @RequestParam Integer status, // 1=approve, 2=reject
            @RequestHeader(value = "Authorization", required = false) String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        Claims claims = jwtUtils.validateToken(token.substring(7));
        if (claims == null) {
            return ResponseEntity.status(401).body("Invalid Token");
        }

        String role = claims.get("role", String.class);
        if (!"ADMIN".equals(role)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        if (status != 1 && status != 2) {
            return ResponseEntity.badRequest().body("Status must be 1 (approve) or 2 (reject)");
        }

        ResourceLink resource = resourceLinkService.getById(id);
        if (resource == null) {
            return ResponseEntity.status(404).body("Resource not found");
        }

        resource.setAuditStatus(status);
        resourceLinkService.updateById(resource);

        return ResponseEntity.ok(status == 1 ? "Resource approved" : "Resource rejected");
    }

    // Batch approve/reject resources (admin only)
    @PutMapping("/batch/audit")
    public ResponseEntity<?> batchAuditResources(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String token) {

        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) request.get("ids");
        Integer status = (Integer) request.get("status");

        if (status != 1 && status != 2) {
            return ResponseEntity.badRequest().body("Status must be 1 (approve) or 2 (reject)");
        }

        for (Long id : ids) {
            ResourceLink resource = resourceLinkService.getById(id);
            if (resource != null) {
                resource.setAuditStatus(status);
                resourceLinkService.updateById(resource);
            }
        }

        return ResponseEntity.ok(status == 1 ? ids.size() + " resources approved" : ids.size() + " resources rejected");
    }

    // Get all resources with filters (admin only)
    @GetMapping("/admin/all")
    public ResponseEntity<?> getAllResources(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String movieId,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {

        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        Page<ResourceLink> resourcePage = new Page<>(page, size);
        var query = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ResourceLink>();

        if (status != null) {
            query.eq("audit_status", status);
        }
        if (movieId != null && !movieId.isEmpty()) {
            query.eq("movie_id", movieId);
        }
        if (provider != null && !provider.isEmpty()) {
            query.eq("provider", provider);
        }

        query.orderByDesc("created_at");

        Page<ResourceLink> result = resourceLinkService.page(resourcePage, query);
        return ResponseEntity.ok(result);
    }

    // Delete resource (admin only)
    @DeleteMapping("/admin/{id}")
    public ResponseEntity<?> deleteResource(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {

        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        boolean deleted = resourceLinkService.removeById(id);
        if (deleted) {
            return ResponseEntity.ok("Resource deleted");
        }
        return ResponseEntity.status(404).body("Resource not found");
    }

    // Batch delete resources (admin only)
    @DeleteMapping("/admin/batch")
    public ResponseEntity<?> batchDeleteResources(
            @RequestBody List<Long> ids,
            @RequestHeader(value = "Authorization", required = false) String token) {

        if (!isAdmin(token)) {
            return ResponseEntity.status(403).body("Admin access required");
        }

        boolean deleted = resourceLinkService.removeByIds(ids);
        if (deleted) {
            return ResponseEntity.ok("Resources deleted: " + ids.size());
        }
        return ResponseEntity.status(400).body("Failed to delete resources");
    }

    // Helper method to check admin role
    private boolean isAdmin(String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            return false;
        }

        Claims claims = jwtUtils.validateToken(token.substring(7));
        if (claims == null) {
            return false;
        }

        String role = claims.get("role", String.class);
        return "ADMIN".equals(role);
    }
}
