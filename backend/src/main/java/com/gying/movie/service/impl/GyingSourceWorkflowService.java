package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.client.GyingSourceClient;
import com.gying.movie.client.PanSouClient;
import com.gying.movie.client.PanSouClient.LinkCheckResult;
import com.gying.movie.dto.QuarkTransferRunResult;
import com.gying.movie.dto.ResourceHubPublishResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferRunnerService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceHubPublishService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.MovieTitleMatcher;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class GyingSourceWorkflowService {
    private static final Set<String> TYPE_CODES = Set.of("mv", "tv", "ac");
    private static final Pattern QUALITY_PATTERN = Pattern.compile(
            "(?i)(8K|4K|2160P|1080P|720P|HDR10\\+?|HDR|DV|杜比视界|蓝光|WEB[- .]?DL)");
    private static final int MAX_TRANSFER_CANDIDATES = 5;

    private final GyingSourceClient gyingSourceClient;
    private final PanSouClient panSouClient;
    private final IMovieMetadataService movieService;
    private final IResourceLinkService resourceLinkService;
    private final IResourceDiscoveryResultService discoveryResultService;
    private final IQuarkTransferTaskService transferTaskService;
    private final IQuarkTransferRunnerService transferRunnerService;
    private final IResourceHubPublishService resourceHubPublishService;
    private final IQuarkShareService quarkShareService;

    public GyingSourceWorkflowService(
            GyingSourceClient gyingSourceClient,
            PanSouClient panSouClient,
            IMovieMetadataService movieService,
            IResourceLinkService resourceLinkService,
            IResourceDiscoveryResultService discoveryResultService,
            IQuarkTransferTaskService transferTaskService,
            IQuarkTransferRunnerService transferRunnerService,
            IResourceHubPublishService resourceHubPublishService,
            IQuarkShareService quarkShareService) {
        this.gyingSourceClient = gyingSourceClient;
        this.panSouClient = panSouClient;
        this.movieService = movieService;
        this.resourceLinkService = resourceLinkService;
        this.discoveryResultService = discoveryResultService;
        this.transferTaskService = transferTaskService;
        this.transferRunnerService = transferRunnerService;
        this.resourceHubPublishService = resourceHubPublishService;
        this.quarkShareService = quarkShareService;
    }

    public List<Map<String, Object>> recentCandidates(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 60);
        List<Map<String, Object>> items = mapList(gyingSourceClient.get("/recent?limit=" + safeLimit).get("items"));
        for (Map<String, Object> item : items) {
            enrichCandidate(item);
        }
        return items;
    }

    public List<Map<String, Object>> trailerCandidates(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<Map<String, Object>> recent = mapList(
                gyingSourceClient.get("/recent?limit=100").get("items"));
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> siteKeys = new LinkedHashSet<>();
        for (Map<String, Object> item : recent) {
            MovieMetadata movie = resolveLocalMovie(
                    stringValue(item.get("typeCode")),
                    stringValue(item.get("mid")),
                    stringValue(item.get("title")),
                    integerValue(item.get("year")));
            if (movie == null || !"TRAILER".equalsIgnoreCase(movie.getResourceStatus())) {
                continue;
            }
            enrichCandidate(item, movie);
            items.add(item);
            siteKeys.add(siteKey(stringValue(item.get("typeCode")), stringValue(item.get("mid"))));
            if (items.size() >= safeLimit) {
                return items;
            }
        }

        List<MovieMetadata> movies = movieService.list(new QueryWrapper<MovieMetadata>()
                .eq("resource_status", "TRAILER")
                .ne("status", "DELETED")
                .notLikeRight("id", "tmdb_")
                .orderByDesc("updated_at")
                .last("LIMIT " + safeLimit));
        for (MovieMetadata movie : movies) {
            if (!TYPE_CODES.contains(movie.getCategory())) {
                continue;
            }
            String key = siteKey(movie.getCategory(), movie.getId());
            if (siteKeys.contains(key)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("typeCode", movie.getCategory());
            item.put("mid", movie.getId());
            item.put("title", firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getId()));
            item.put("year", movie.getYear());
            enrichCandidate(item, movie);
            items.add(item);
            if (items.size() >= safeLimit) {
                break;
            }
        }
        return items;
    }

    public Map<String, Object> ensureMovieResource(String typeCode, String mid) {
        String safeType = normalizeTypeCode(typeCode);
        String safeMid = required(mid, "GYING movie id");
        Map<String, Object> snapshot = gyingSourceClient.get("/movie/" + safeType + "/" + safeMid);
        List<Map<String, Object>> allResources = mapList(snapshot.get("resources"));
        List<Map<String, Object>> ownResources = mapList(snapshot.get("ownResources"));

        MovieMetadata canonical = resolveLocalMovie(
                safeType,
                safeMid,
                stringValue(snapshot.get("title")),
                integerValue(snapshot.get("year")));
        String localMovieId = canonical == null ? safeMid : canonical.getId();
        gyingSourceClient.post("/ingest", Map.of(
                "typeCode", safeType,
                "mid", safeMid,
                "targetMovieId", localMovieId,
                "uploadPoster", true));
        MovieMetadata movie = movieService.getById(localMovieId);
        if (movie == null) {
            throw new IllegalStateException("Movie was not saved after GYING ingest: " + localMovieId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("typeCode", safeType);
        result.put("mid", safeMid);
        result.put("localMovieId", movie.getId());
        result.put("title", firstText(movie.getTitleCn(), stringValue(snapshot.get("title")), safeMid));
        result.put("siteResourceCount", allResources.size());
        result.put("ownSiteResourceCount", ownResources.size());

        if (!ownResources.isEmpty()) {
            ResourceLink own = findResourceBySourceIds(movie.getId(),
                    ownResources.stream().map(item -> stringValue(item.get("source_id"))).toList());
            result.put("status", "ALREADY_PUBLISHED");
            result.put("resourceId", own == null ? null : own.getId());
            result.put("sourceId", stringValue(ownResources.get(0).get("source_id")));
            markMovieAvailable(movie);
            return result;
        }

        ResourceLink local = findPublishableLocalResource(movie.getId());
        if (local == null) {
            List<Map<String, Object>> candidates = selectTransferCandidates(allResources);
            if (candidates.isEmpty()) {
                result.put("status", "NO_TRANSFERABLE_RESOURCE");
                markMovieTrailer(movie);
                return result;
            }
            List<String> transferErrors = new ArrayList<>();
            Map<String, Object> selected = null;
            for (Map<String, Object> candidate : candidates) {
                try {
                    local = transferAndPublishLocally(movie, candidate);
                    selected = candidate;
                    break;
                } catch (Exception error) {
                    transferErrors.add(firstText(stringValue(candidate.get("source_id")), "unknown")
                            + ": " + safeText(error.getMessage()));
                }
            }
            if (local == null || selected == null) {
                throw new IllegalStateException("All GYING transfer candidates failed: "
                        + String.join("; ", transferErrors));
            }
            result.put("sourceCandidateId", selected.get("source_id"));
            result.put("sourceCandidateUrl", selected.get("url"));
            result.put("transferAttempts", transferErrors.size() + 1);
            result.put("transferErrors", transferErrors);
            result.put("transferMode", true);
        } else {
            result.put("transferMode", false);
        }

        Map<String, Object> published = publishLocalResource(safeType, safeMid, movie, local);
        String publishedSourceId = required(stringValue(published.get("sourceId")), "published GYING source id");
        verifyPublishedUpdate(safeType, safeMid, publishedSourceId, local.getUrl());
        result.put("status", "PUBLISHED");
        result.put("resourceId", local.getId());
        result.put("sourceId", publishedSourceId);
        result.put("site", published.get("site"));
        markMovieAvailable(movie);
        return result;
    }

    public Map<String, Object> ensureLocalMovieResource(String movieId) {
        MovieMetadata movie = movieService.getById(required(movieId, "local movie id"));
        if (movie == null || "DELETED".equalsIgnoreCase(movie.getStatus())) {
            throw new IllegalArgumentException("Movie not found: " + movieId);
        }
        Map<String, Object> candidate = recentCandidates(60).stream()
                .filter(item -> movie.getId().equals(stringValue(item.get("localMovieId"))))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No exact GYING recent-update match found for "
                                + firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getId())));
        return ensureMovieResource(
                required(stringValue(candidate.get("typeCode")), "GYING type code"),
                required(stringValue(candidate.get("mid")), "GYING movie id"));
    }

    public Map<String, Object> ensureTrailerResources(int limit) {
        List<Map<String, Object>> candidates = trailerCandidates(limit);
        List<Map<String, Object>> items = new ArrayList<>();
        int published = 0;
        int alreadyPublished = 0;
        int noResource = 0;
        int failed = 0;
        for (Map<String, Object> candidate : candidates) {
            try {
                Map<String, Object> result = ensureMovieResource(
                        stringValue(candidate.get("typeCode")),
                        stringValue(candidate.get("mid")));
                items.add(result);
                String status = stringValue(result.get("status"));
                if ("PUBLISHED".equals(status)) {
                    published++;
                } else if ("ALREADY_PUBLISHED".equals(status)) {
                    alreadyPublished++;
                } else {
                    noResource++;
                }
            } catch (Exception error) {
                failed++;
                Map<String, Object> item = new LinkedHashMap<>(candidate);
                item.put("status", "FAILED");
                item.put("error", safeText(error.getMessage()));
                items.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", candidates.size());
        result.put("published", published);
        result.put("alreadyPublished", alreadyPublished);
        result.put("noResource", noResource);
        result.put("failed", failed);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> checkPublishedResources(int limit, boolean updateLocal) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        List<Map<String, Object>> items = mapList(
                gyingSourceClient.get("/my-resources?limit=" + safeLimit).get("items"));
        Map<String, String> linksByProvider = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String url = stringValue(item.get("url"));
            if (hasText(url)) {
                linksByProvider.putIfAbsent(url, firstText(stringValue(item.get("provider")), "QUARK"));
            }
        }
        Map<String, LinkCheckResult> checks = panSouClient.checkLinksByProvider(linksByProvider);
        int valid = 0;
        int invalid = 0;
        int unclear = 0;
        for (Map<String, Object> item : items) {
            String url = stringValue(item.get("url"));
            LinkCheckResult check = checks.get(url);
            String status;
            if (check != null && check.checked()) {
                status = check.valid() ? "VALID" : "INVALID";
            } else {
                status = "UNCLEAR";
            }
            item.put("checkStatus", status);
            item.put("checkMessage", check == null ? null : check.message());
            ResourceLink local = findLocalPublishedResource(item);
            QuarkTransferTask transfer = findRepairTransfer(local, item);
            boolean canRetransfer = hasText(stringValue(item.get("mid")))
                    && TYPE_CODES.contains(stringValue(item.get("type_code")).toLowerCase(Locale.ROOT));
            item.put("resourceId", local == null ? null : local.getId());
            item.put("localMovieId", local == null ? null : local.getMovieId());
            item.put("repairable", transfer != null || canRetransfer);
            item.put("repairMode", transfer != null ? "RESHARE" : canRetransfer ? "RETRANSFER" : "NONE");
            if ("VALID".equals(status)) {
                valid++;
            } else if ("INVALID".equals(status)) {
                invalid++;
            } else {
                unclear++;
            }
            if (updateLocal && local != null && check != null && check.checked()) {
                local.setLinkStatus(check.valid() ? "NORMAL" : "INVALID");
                local.setValidatedAt(LocalDateTime.now());
                local.setLastCheckError(check.valid() ? null : safeText(check.message()));
                local.setUpdatedAt(LocalDateTime.now());
                resourceLinkService.updateById(local);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", items.size());
        result.put("valid", valid);
        result.put("invalid", invalid);
        result.put("unclear", unclear);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> repairPublishedResources(int limit) {
        Map<String, Object> checked = checkPublishedResources(limit, true);
        List<Map<String, Object>> items = mapList(checked.get("items"));
        int repaired = 0;
        int reshared = 0;
        int retransferred = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (!"INVALID".equals(stringValue(item.get("checkStatus")))) {
                continue;
            }
            try {
                ResourceLink local = findLocalPublishedResource(item);
                QuarkTransferTask transfer = findRepairTransfer(local, item);
                MovieMetadata movie = local == null ? null : movieService.getById(local.getMovieId());
                if (movie == null && transfer != null) {
                    movie = movieService.getById(transfer.getMovieId());
                }
                TransferOutcome transferOutcome = null;
                String newUrl = null;
                String repairMode = null;
                String reshareError = null;

                if (transfer != null && hasText(transfer.getSavedPath())) {
                    try {
                        transfer.setShareUrl(null);
                        transfer.setShareUrlHash(null);
                        transfer.setStatus("SUBMITTED");
                        transfer.setLastError(null);
                        transfer.setUpdatedAt(LocalDateTime.now());
                        transferTaskService.updateById(transfer);
                        newUrl = quarkShareService.ensureShareUrl(transfer);
                        requireConfirmedValid(newUrl);
                        repairMode = "RESHARED";
                    } catch (Exception error) {
                        reshareError = safeText(error.getMessage());
                        newUrl = null;
                    }
                }

                if (!hasText(newUrl)) {
                    Map<String, Object> snapshot = gyingSourceClient.get("/movie/"
                            + normalizeTypeCode(stringValue(item.get("type_code")))
                            + "/" + required(stringValue(item.get("mid")), "GYING movie id"));
                    if (movie == null) {
                        movie = ingestSiteMovie(item, snapshot);
                        if (local == null) {
                            local = findLocalPublishedResource(item);
                        }
                    }
                    List<Map<String, Object>> candidates = selectTransferCandidates(mapList(snapshot.get("resources")));
                    List<String> candidateErrors = new ArrayList<>();
                    for (Map<String, Object> candidate : candidates) {
                        try {
                            transferOutcome = transferCandidate(movie, candidate);
                            requireConfirmedValid(transferOutcome.shareUrl());
                            newUrl = transferOutcome.shareUrl();
                            repairMode = "RETRANSFERRED";
                            break;
                        } catch (Exception error) {
                            candidateErrors.add(firstText(stringValue(candidate.get("source_id")), "unknown")
                                    + ": " + safeText(error.getMessage()));
                        }
                    }
                    if (!hasText(newUrl)) {
                        skipped++;
                        item.put("repairStatus", "SKIPPED");
                        item.put("repairError", firstText(
                                candidateErrors.isEmpty() ? null : String.join("; ", candidateErrors),
                                reshareError,
                                "No reusable saved path or transferable GYING resource"));
                        continue;
                    }
                }

                String sourceId = required(stringValue(item.get("source_id")), "GYING source id");
                String typeCode = normalizeTypeCode(stringValue(item.get("type_code")));
                String mid = required(stringValue(item.get("mid")), "GYING movie id");
                String title = transferOutcome == null
                        ? firstText(stringValue(item.get("title")), "GYING Resource")
                        : buildResourceTitle(movie, transferOutcome.discovery().getTitle(), "QUARK");
                gyingSourceClient.post("/update", Map.of(
                        "sourceId", sourceId,
                        "typeCode", typeCode,
                        "mid", mid,
                        "title", title,
                        "panurl", newUrl,
                        "panpw", "",
                        "is", integerValue(item.get("login_visible")) == null
                                ? 0 : integerValue(item.get("login_visible"))));
                verifyPublishedUpdate(typeCode, mid, sourceId, newUrl);
                ResourceLink updated = local == null ? new ResourceLink() : local;
                applyRepairedLink(updated, item, movie, title, newUrl);
                if (updated.getId() == null) {
                    resourceLinkService.save(updated);
                } else {
                    resourceLinkService.updateById(updated);
                }
                if (transferOutcome != null) {
                    bindDiscoveryToResource(transferOutcome.discovery(), updated);
                }
                item.put("resourceId", updated.getId());
                item.put("localMovieId", updated.getMovieId());
                item.put("newUrl", newUrl);
                item.put("repairStatus", "REPAIRED");
                item.put("repairMode", repairMode);
                repaired++;
                if ("RESHARED".equals(repairMode)) {
                    reshared++;
                } else {
                    retransferred++;
                }
            } catch (Exception error) {
                failed++;
                item.put("repairStatus", "FAILED");
                item.put("repairError", safeText(error.getMessage()));
                if (errors.size() < 20) {
                    errors.add(firstText(stringValue(item.get("source_id")), "unknown") + ": "
                            + safeText(error.getMessage()));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", checked.get("checked"));
        result.put("invalid", checked.get("invalid"));
        result.put("repaired", repaired);
        result.put("reshared", reshared);
        result.put("retransferred", retransferred);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("items", items);
        result.put("errors", errors);
        return result;
    }

    private ResourceLink transferAndPublishLocally(MovieMetadata movie, Map<String, Object> candidate) {
        TransferOutcome outcome = transferCandidate(movie, candidate);
        ResourceHubPublishResult publishResult = resourceHubPublishService.publishDiscovery(outcome.discovery().getId());
        if (publishResult.getFailed() > 0 || publishResult.getResourceIds().isEmpty()) {
            throw new IllegalStateException(firstText(
                    publishResult.getErrors().isEmpty() ? null : publishResult.getErrors().get(0),
                    "Local resource publish failed"));
        }
        return resourceLinkService.getById(publishResult.getResourceIds().get(0));
    }

    private TransferOutcome transferCandidate(MovieMetadata movie, Map<String, Object> candidate) {
        String originalUrl = required(stringValue(candidate.get("url")), "GYING source URL");
        String urlHash = ResourceHubHashUtils.sha256(originalUrl);
        ResourceDiscoveryResult discovery = discoveryResultService.getOne(
                new QueryWrapper<ResourceDiscoveryResult>()
                        .eq("movie_id", movie.getId())
                        .eq("original_url_hash", urlHash)
                        .orderByDesc("updated_at")
                        .last("LIMIT 1"),
                false);
        LocalDateTime now = LocalDateTime.now();
        String title = buildResourceTitle(movie, stringValue(candidate.get("title")),
                stringValue(candidate.get("provider")));
        if (discovery == null) {
            discovery = new ResourceDiscoveryResult();
            discovery.setMovieId(movie.getId());
            discovery.setSource("GYING");
            discovery.setSourceRef(trim(stringValue(candidate.get("source_id")), 100));
            discovery.setOriginalUrl(originalUrl);
            discovery.setOriginalUrlHash(urlHash);
            discovery.setCreatedAt(now);
        }
        discovery.setTitle(trim(title, 255));
        discovery.setProvider("QUARK");
        discovery.setResourceType("DISK");
        discovery.setCode(trim(stringValue(candidate.get("code")), 50));
        discovery.setQuality(trim(extractQuality(title), 50));
        discovery.setConfidence(BigDecimal.valueOf(95));
        discovery.setStatus("DISCOVERED");
        discovery.setFailureReason(null);
        discovery.setUpdatedAt(now);
        if (discovery.getId() == null) {
            discoveryResultService.save(discovery);
        } else {
            discoveryResultService.updateById(discovery);
        }

        QuarkTransferTask transfer = transferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                .eq("discovery_result_id", discovery.getId())
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
        if (transfer == null) {
            transfer = new QuarkTransferTask();
            transfer.setDiscoveryResultId(discovery.getId());
            transfer.setMovieId(movie.getId());
            transfer.setOriginalUrl(originalUrl);
            transfer.setOriginalUrlHash(urlHash);
            transfer.setStatus("PENDING");
            transfer.setAttempts(0);
            transfer.setCreatedAt(now);
            transfer.setUpdatedAt(now);
            transferTaskService.save(transfer);
        }
        if (!hasText(transfer.getShareUrl())) {
            QuarkTransferRunResult transferResult = transferRunnerService.submitOne(transfer.getId());
            if (transferResult.getFailed() > 0) {
                throw new IllegalStateException(firstText(
                        transferResult.getErrors().isEmpty() ? null : transferResult.getErrors().get(0),
                        "Quark transfer failed"));
            }
        }
        transfer = transferTaskService.getById(transfer.getId());
        if (transfer == null) {
            throw new IllegalStateException("Quark transfer task disappeared after submission");
        }
        String shareUrl = transfer.getShareUrl();
        if (!hasText(shareUrl)) {
            shareUrl = quarkShareService.ensureShareUrl(transfer);
        }
        if (!hasText(shareUrl)) {
            throw new IllegalStateException("Quark transfer did not create an own share URL");
        }
        return new TransferOutcome(discovery, transfer, shareUrl);
    }

    private Map<String, Object> publishLocalResource(
            String typeCode,
            String siteMid,
            MovieMetadata movie,
            ResourceLink resource) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", resource.getId());
        payload.put("typeCode", normalizeTypeCode(typeCode));
        payload.put("mid", required(siteMid, "GYING movie id"));
        payload.put("title", buildResourceTitle(movie, resource.getName(), resource.getProvider()));
        payload.put("panurl", resource.getUrl());
        payload.put("panpw", firstText(resource.getCode(), ""));
        payload.put("is", 0);
        return gyingSourceClient.post("/publish", payload);
    }

    private List<Map<String, Object>> selectTransferCandidates(List<Map<String, Object>> resources) {
        List<Map<String, Object>> ordered = resources.stream()
                .filter(item -> !Boolean.TRUE.equals(item.get("is_own")))
                .filter(item -> "QUARK".equalsIgnoreCase(stringValue(item.get("provider"))))
                .filter(item -> hasText(stringValue(item.get("url"))))
                .sorted((left, right) -> qualityScore(stringValue(right.get("title")))
                        - qualityScore(stringValue(left.get("title"))))
                .limit(20)
                .toList();
        Map<String, String> links = new LinkedHashMap<>();
        ordered.forEach(item -> links.put(stringValue(item.get("url")), "QUARK"));
        Map<String, LinkCheckResult> checks;
        try {
            checks = panSouClient.checkLinksByProvider(links);
        } catch (Exception ignored) {
            checks = Map.of();
        }
        Map<String, LinkCheckResult> resolvedChecks = checks;
        return ordered.stream()
                .filter(item -> {
                    LinkCheckResult check = resolvedChecks.get(stringValue(item.get("url")));
                    return check == null || !check.checked() || check.valid();
                })
                .limit(MAX_TRANSFER_CANDIDATES)
                .toList();
    }

    private ResourceLink findPublishableLocalResource(String movieId) {
        return resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("status", "ACTIVE")
                .eq("audit_status", 1)
                .eq("type", "DISK")
                .isNull("deleted_at")
                .and(query -> query.isNull("source").or().ne("source", "GYING"))
                .and(query -> query.isNull("link_status")
                        .or().in("link_status", List.of("NORMAL", "UNKNOWN")))
                .last("ORDER BY CASE WHEN provider='QUARK' THEN 0 ELSE 1 END, updated_at DESC LIMIT 1"), false);
    }

    private ResourceLink findResourceBySourceIds(String movieId, List<String> sourceIds) {
        List<String> ids = sourceIds.stream().filter(this::hasText).toList();
        if (ids.isEmpty()) {
            return null;
        }
        return resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .in("source_ref", ids)
                .eq("status", "ACTIVE")
                .last("LIMIT 1"), false);
    }

    private ResourceLink findLocalPublishedResource(Map<String, Object> item) {
        String sourceId = stringValue(item.get("source_id"));
        if (hasText(sourceId)) {
            ResourceLink bySource = resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                    .eq("source_ref", sourceId)
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"), false);
            if (bySource != null) {
                return bySource;
            }
        }
        String url = stringValue(item.get("url"));
        return hasText(url) ? resourceLinkService.getOne(new QueryWrapper<ResourceLink>()
                .eq("url", url)
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false) : null;
    }

    private QuarkTransferTask findRepairTransfer(ResourceLink local, Map<String, Object> item) {
        String url = stringValue(item.get("url"));
        if (hasText(url)) {
            QuarkTransferTask byUrl = transferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                    .eq("share_url", url)
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"), false);
            if (byUrl != null && hasText(byUrl.getSavedPath())) {
                return byUrl;
            }
        }
        if (local == null || !hasText(local.getMovieId())) {
            return null;
        }
        return transferTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                .eq("movie_id", local.getMovieId())
                .isNotNull("saved_path")
                .in("status", List.of("SUBMITTED", "FAILED"))
                .orderByDesc("updated_at")
                .last("LIMIT 1"), false);
    }

    private void applyRepairedLink(
            ResourceLink link,
            Map<String, Object> item,
            MovieMetadata movie,
            String title,
            String newUrl) {
        LocalDateTime now = LocalDateTime.now();
        boolean creating = link.getId() == null;
        if (creating || !hasText(link.getMovieId())) {
            link.setMovieId(movie == null
                    ? required(stringValue(item.get("mid")), "movie id")
                    : movie.getId());
        }
        link.setName(trim(firstText(title, stringValue(item.get("title")), "GYING Resource"), 255));
        link.setType("DISK");
        link.setProvider("QUARK");
        link.setUrl(newUrl);
        link.setUrlHash(ResourceHubHashUtils.sha256(newUrl));
        link.setCode(null);
        link.setAuditStatus(1);
        link.setStatus("ACTIVE");
        link.setLinkStatus("NORMAL");
        link.setReportCount(0);
        link.setSource("GYING_PUBLISHED");
        link.setSourceRef(trim(stringValue(item.get("source_id")), 100));
        link.setSourceUrl("https://www.xn--wcv59z.com/" + normalizeTypeCode(stringValue(item.get("type_code")))
                + "/" + required(stringValue(item.get("mid")), "GYING movie id"));
        link.setAutoCollected(true);
        link.setValidatedAt(now);
        link.setLastCheckError(null);
        link.setQuality(trim(extractQuality(link.getName()), 50));
        link.setUpdatedAt(now);
        link.setDeletedAt(null);
        if (creating) {
            link.setCreatedAt(now);
        }
    }

    private MovieMetadata ingestSiteMovie(Map<String, Object> item, Map<String, Object> snapshot) {
        String typeCode = normalizeTypeCode(stringValue(item.get("type_code")));
        String mid = required(stringValue(item.get("mid")), "GYING movie id");
        MovieMetadata movie = resolveLocalMovie(
                typeCode,
                mid,
                firstText(stringValue(snapshot.get("title")), stringValue(item.get("movie_title"))),
                integerValue(snapshot.get("year")));
        String targetMovieId = movie == null ? mid : movie.getId();
        gyingSourceClient.post("/ingest", Map.of(
                "typeCode", typeCode,
                "mid", mid,
                "targetMovieId", targetMovieId,
                "uploadPoster", true));
        MovieMetadata saved = movieService.getById(targetMovieId);
        if (saved == null) {
            throw new IllegalStateException("Movie was not saved after GYING ingest: " + targetMovieId);
        }
        return saved;
    }

    private void requireConfirmedValid(String url) {
        String safeUrl = required(url, "new Quark share URL");
        LinkCheckResult check = panSouClient.checkLinksByProvider(Map.of(safeUrl, "QUARK")).get(safeUrl);
        if (check == null || !check.checked()) {
            throw new IllegalStateException("Unable to confirm new Quark share: "
                    + (check == null ? "missing link check result" : safeText(check.message())));
        }
        if (!check.valid()) {
            throw new IllegalStateException("New Quark share is invalid: " + safeText(check.message()));
        }
    }

    private void verifyPublishedUpdate(String typeCode, String mid, String sourceId, String expectedUrl) {
        Map<String, Object> snapshot = gyingSourceClient.get("/movie/" + typeCode + "/" + mid);
        boolean matched = mapList(snapshot.get("ownResources")).stream()
                .anyMatch(item -> sourceId.equals(stringValue(item.get("source_id")))
                        && normalizedUrl(expectedUrl).equals(normalizedUrl(stringValue(item.get("url")))));
        if (!matched) {
            throw new IllegalStateException("GYING update could not be verified for source " + sourceId);
        }
    }

    private void bindDiscoveryToResource(ResourceDiscoveryResult discovery, ResourceLink resource) {
        discovery.setResourceLinkId(resource.getId());
        discovery.setShareUrl(resource.getUrl());
        discovery.setShareUrlHash(resource.getUrlHash());
        discovery.setStatus("SAVED");
        discovery.setFailureReason(null);
        discovery.setUpdatedAt(LocalDateTime.now());
        discoveryResultService.updateById(discovery);
    }

    private void enrichCandidate(Map<String, Object> item) {
        MovieMetadata movie = resolveLocalMovie(
                stringValue(item.get("typeCode")),
                stringValue(item.get("mid")),
                stringValue(item.get("title")),
                integerValue(item.get("year")));
        enrichCandidate(item, movie);
    }

    private void enrichCandidate(Map<String, Object> item, MovieMetadata movie) {
        item.put("localMovie", movie != null);
        item.put("localMovieId", movie == null ? null : movie.getId());
        item.put("resourceStatus", movie == null ? null : movie.getResourceStatus());
        item.put("activeResourceCount", movie == null ? 0 : activeResourceCount(movie.getId()));
    }

    private MovieMetadata resolveLocalMovie(String typeCode, String mid, String title, Integer year) {
        MovieMetadata byId = hasText(mid) ? movieService.getById(mid) : null;
        if (byId != null && !"DELETED".equalsIgnoreCase(byId.getStatus())) {
            return byId;
        }
        String normalizedType = stringValue(typeCode);
        normalizedType = normalizedType == null ? null : normalizedType.toLowerCase(Locale.ROOT);
        if (!TYPE_CODES.contains(normalizedType) || !hasText(title)) {
            return null;
        }
        List<MovieMetadata> candidates = movieService.list(new QueryWrapper<MovieMetadata>()
                .eq("category", normalizedType)
                .ne("status", "DELETED")
                .and(query -> query.eq("title_cn", title)
                        .or().eq("title_en", title)
                        .or().eq("series_name", title)
                        .or().like("aliases", title))
                .last("LIMIT 20"));
        List<MovieMetadata> exact = candidates.stream()
                .filter(movie -> MovieTitleMatcher.isExactMatch(movie, title))
                .toList();
        if (exact.isEmpty()) {
            return null;
        }
        List<MovieMetadata> compatibleYears = exact.stream()
                .filter(movie -> year == null || movie.getYear() == null || year.equals(movie.getYear()))
                .toList();
        if (year != null && compatibleYears.isEmpty()) {
            return null;
        }
        return (compatibleYears.isEmpty() ? exact : compatibleYears).stream()
                .sorted(Comparator
                        .comparing((MovieMetadata movie) -> year != null && year.equals(movie.getYear()) ? 0 : 1)
                        .thenComparing(movie -> "AVAILABLE".equalsIgnoreCase(movie.getResourceStatus()) ? 0 : 1)
                        .thenComparing(movie -> movie.getId().startsWith("tmdb_") ? 0 : 1))
                .findFirst()
                .orElse(null);
    }

    private long activeResourceCount(String movieId) {
        return resourceLinkService.count(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("status", "ACTIVE")
                .isNull("deleted_at"));
    }

    private void markMovieAvailable(MovieMetadata movie) {
        updateMovieResourceStatus(movie, "AVAILABLE");
    }

    private void markMovieTrailer(MovieMetadata movie) {
        updateMovieResourceStatus(movie, "TRAILER");
    }

    private void updateMovieResourceStatus(MovieMetadata movie, String status) {
        if (movie == null || status.equalsIgnoreCase(movie.getResourceStatus())) {
            return;
        }
        movie.setResourceStatus(status);
        movie.setUpdatedAt(LocalDateTime.now());
        movieService.updateById(movie);
    }

    private String buildResourceTitle(MovieMetadata movie, String currentTitle, String provider) {
        String movieTitle = firstText(movie.getTitleCn(), movie.getTitleEn(), movie.getId());
        String title = firstText(currentTitle, movieTitle);
        String quality = extractQuality(title);
        if (!title.toLowerCase(Locale.ROOT).contains(movieTitle.toLowerCase(Locale.ROOT))) {
            title = movieTitle + " " + title;
        }
        if (!hasText(quality) && "QUARK".equalsIgnoreCase(provider)) {
            title = title + " 夸克网盘";
        }
        return title.replaceAll("\\s+", " ").trim();
    }

    private String extractQuality(String title) {
        if (!hasText(title)) {
            return null;
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = QUALITY_PATTERN.matcher(title);
        while (matcher.find() && values.size() < 5) {
            String value = matcher.group(1).toUpperCase(Locale.ROOT);
            if (!values.contains(value)) {
                values.add(value);
            }
        }
        return values.isEmpty() ? null : String.join(" / ", values);
    }

    private int qualityScore(String title) {
        String upper = title == null ? "" : title.toUpperCase(Locale.ROOT);
        if (upper.contains("8K")) return 5;
        if (upper.contains("4K") || upper.contains("2160P")) return 4;
        if (upper.contains("1080P")) return 3;
        if (upper.contains("720P")) return 2;
        return 1;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> values)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (!hasText(stringValue(value))) {
            return null;
        }
        try {
            return Integer.valueOf(stringValue(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizedUrl(String value) {
        String url = value == null ? "" : value.trim();
        while (url.endsWith("#")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String siteKey(String typeCode, String mid) {
        String safeType = typeCode == null ? "" : typeCode.trim().toLowerCase(Locale.ROOT);
        String safeMid = mid == null ? "" : mid.trim();
        return safeType + ":" + safeMid;
    }

    private String normalizeTypeCode(String value) {
        String normalized = required(value, "type code").toLowerCase(Locale.ROOT);
        if (!TYPE_CODES.contains(normalized)) {
            throw new IllegalArgumentException("Invalid GYING type code: " + value);
        }
        return normalized;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String required(String value, String label) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private String trim(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String text = value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String safeText(String value) {
        return trim(firstText(value, "Unknown error"), 1000);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record TransferOutcome(
            ResourceDiscoveryResult discovery,
            QuarkTransferTask transfer,
            String shareUrl) {
    }
}
