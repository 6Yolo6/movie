package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.ResourceReportAdminDTO;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.ResourceReport;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IResourceReportService;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.service.IUserNotificationService;
import com.gying.movie.utils.AuthHelper;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/resource-reports")
public class AdminResourceReportController {
    private final IResourceReportService reportService;
    private final IResourceLinkService resourceService;
    private final IMovieMetadataService movieService;
    private final ISysUserService userService;
    private final IUserNotificationService notificationService;
    private final AuthHelper authHelper;

    public AdminResourceReportController(
            IResourceReportService reportService,
            IResourceLinkService resourceService,
            IMovieMetadataService movieService,
            ISysUserService userService,
            IUserNotificationService notificationService,
            AuthHelper authHelper) {
        this.reportService = reportService;
        this.resourceService = resourceService;
        this.movieService = movieService;
        this.userService = userService;
        this.notificationService = notificationService;
        this.authHelper = authHelper;
    }

    @GetMapping
    public ResponseEntity<?> listReports(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String linkStatus,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);

        QueryWrapper<ResourceReport> query = new QueryWrapper<>();
        if (hasText(status)) {
            query.eq("status", status);
        }

        List<Long> matchingResourceIds = findMatchingResourceIds(linkStatus, provider, keyword);
        boolean hasResourceFilter = hasText(linkStatus) || hasText(provider) || hasText(keyword);
        if (hasResourceFilter) {
            if (matchingResourceIds.isEmpty()) {
                return ResponseEntity.ok(new Page<ResourceReportAdminDTO>(Math.max(page, 1), Math.min(Math.max(size, 1), 100), 0));
            }
            query.in("resource_id", matchingResourceIds);
        }

        query.orderByDesc("created_at");
        Page<ResourceReport> result = reportService.page(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query);
        return ResponseEntity.ok(toAdminPage(result));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateReportStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        Set<String> allowed = Set.of("PENDING", "HANDLED", "FALSE_REPORT", "INVALID");
        if (!allowed.contains(status)) {
            return ResponseEntity.badRequest().body("Invalid report status");
        }
        ResourceReport report = reportService.getById(id);
        if (report == null) {
            return ResponseEntity.status(404).body("Report not found");
        }
        ResourceLink resource = resourceService.getById(report.getResourceId());
        String finalStatus = "INVALID".equals(status) ? "HANDLED" : status;
        report.setStatus(finalStatus);
        report.setHandledAt("PENDING".equals(finalStatus) ? null : LocalDateTime.now());
        reportService.updateById(report);

        if (resource != null && "INVALID".equals(status)) {
            resource.setLinkStatus("INVALID");
            resourceService.updateById(resource);
            notifyResourceLinkStatus(resource);
        }
        return ResponseEntity.ok(Map.of("status", finalStatus));
    }

    private List<Long> findMatchingResourceIds(String linkStatus, String provider, String keyword) {
        QueryWrapper<ResourceLink> query = new QueryWrapper<>();
        boolean filtered = false;
        if (hasText(linkStatus)) {
            query.eq("link_status", linkStatus);
            filtered = true;
        }
        if (hasText(provider)) {
            query.eq("provider", provider);
            filtered = true;
        }
        if (hasText(keyword)) {
            String kw = keyword.trim();
            List<String> movieIds = movieService.lambdaQuery()
                    .like(MovieMetadata::getTitleCn, kw)
                    .or()
                    .like(MovieMetadata::getTitleEn, kw)
                    .list()
                    .stream()
                    .map(MovieMetadata::getId)
                    .toList();
            List<Long> userIds = userService.lambdaQuery()
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
            filtered = true;
        }
        if (!filtered) {
            return List.of();
        }
        return resourceService.list(query).stream().map(ResourceLink::getId).toList();
    }

    private Page<ResourceReportAdminDTO> toAdminPage(Page<ResourceReport> source) {
        List<Long> resourceIds = source.getRecords().stream()
                .map(ResourceReport::getResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> reporterIds = source.getRecords().stream()
                .map(ResourceReport::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ResourceLink> resources = resourceIds.isEmpty()
                ? Collections.emptyMap()
                : resourceService.listByIds(resourceIds).stream().collect(Collectors.toMap(ResourceLink::getId, r -> r));
        List<String> movieIds = resources.values().stream()
                .map(ResourceLink::getMovieId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> uploaderIds = resources.values().stream()
                .map(ResourceLink::getUploaderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, MovieMetadata> movies = movieIds.isEmpty()
                ? Collections.emptyMap()
                : movieService.listByIds(movieIds).stream().collect(Collectors.toMap(MovieMetadata::getId, m -> m));
        List<Long> userIds = new java.util.ArrayList<>(reporterIds);
        uploaderIds.stream().filter(id -> !userIds.contains(id)).forEach(userIds::add);
        Map<Long, SysUser> users = userIds.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(userIds).stream().collect(Collectors.toMap(SysUser::getId, u -> u));

        Page<ResourceReportAdminDTO> page = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        page.setPages(source.getPages());
        page.setRecords(source.getRecords().stream().map(report -> {
            ResourceReportAdminDTO dto = new ResourceReportAdminDTO();
            BeanUtils.copyProperties(report, dto);
            ResourceLink resource = resources.get(report.getResourceId());
            if (resource != null) {
                dto.setMovieId(resource.getMovieId());
                dto.setResourceName(resource.getName());
                dto.setProvider(resource.getProvider());
                dto.setUrl(resource.getUrl());
                dto.setLinkStatus(resource.getLinkStatus());
                SysUser uploader = users.get(resource.getUploaderId());
                if (uploader != null) {
                    dto.setUploaderName(uploader.getUsername());
                }
                MovieMetadata movie = movies.get(resource.getMovieId());
                if (movie != null) {
                    dto.setMovieTitle(movie.getTitleCn());
                }
            }
            SysUser reporter = users.get(report.getUserId());
            if (reporter != null) {
                dto.setReporterName(reporter.getUsername());
            }
            return dto;
        }).toList());
        return page;
    }

    private void notifyResourceLinkStatus(ResourceLink resource) {
        if (resource.getUploaderId() == null) {
            return;
        }
        notificationService.notifyUser(
                resource.getUploaderId(),
                "RESOURCE_LINK_STATUS",
                "Resource link marked invalid",
                "Your submission \"" + (resource.getName() == null ? "resource" : resource.getName()) + "\" was marked invalid after review.",
                "RESOURCE",
                String.valueOf(resource.getId()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}