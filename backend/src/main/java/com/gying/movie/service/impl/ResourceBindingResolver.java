package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.ResourceHubHashUtils;
import com.gying.movie.utils.SeasonSearchUtils;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Resolves actual shared-resource bindings, never every resource with a similar series title. */
public final class ResourceBindingResolver {
    private static final List<String> BINDING_SOURCES = List.of("COLLECTION_BINDING", "RESOURCE_BINDING");
    private static final int MAX_ROWS = 500;
    private final IResourceLinkService resources;
    private final IMovieMetadataService movies;

    public ResourceBindingResolver(IResourceLinkService resources, IMovieMetadataService movies) {
        this.resources = resources; this.movies = movies;
    }

    public Snapshot resolve(ResourceLink source, AuthUser user, boolean lock) {
        Map<Long, ResourceLink> group = new LinkedHashMap<>();
        ResourceLink root = source;
        Set<Long> ancestors = new HashSet<>();
        while (root != null && root.getId() != null && ancestors.add(root.getId())) {
            group.put(root.getId(), root);
            Long parent = parentId(root);
            if (parent == null) break;
            ResourceLink next = resources.getById(parent);
            if (next == null) break;
            root = next;
            if (ancestors.size() > MAX_ROWS) throw tooLarge();
        }
        Long rootId = root == null ? source.getId() : root.getId();
        // Earlier partial edits could create more than one root for the exact same share.
        // Recover every row for that share within the exact same series, even when its
        // provenance points elsewhere, so reopening the editor preselects all seasons.
        List<ResourceLink> sameShareRows = resources.list(new QueryWrapper<ResourceLink>()
                .eq("url", source.getUrl()).eq("type", source.getType())
                .eq("status", "ACTIVE").isNull("deleted_at").orderByAsc("id").last("LIMIT 501"));
        if (sameShareRows != null) {
            MovieMetadata primary = movies.getById(source.getMovieId());
            for (ResourceLink row : sameShareRows) {
                if (row.getId() == null || !sameShare(row, source)
                        || !sameSeries(primary, movies.getById(row.getMovieId()))) continue;
                group.put(row.getId(), row);
            }
        }
        Set<Long> visited = new HashSet<>();
        while (true) {
            List<String> parents = group.keySet().stream().filter(id -> !visited.contains(id)).map(String::valueOf).toList();
            if (parents.isEmpty()) break;
            parents.forEach(id -> visited.add(Long.valueOf(id)));
            List<ResourceLink> children = resources.list(new QueryWrapper<ResourceLink>()
                    .in("source", BINDING_SOURCES).in("source_ref", parents)
                    .orderByAsc("id").last("LIMIT 501"));
            if (children != null) for (ResourceLink child : children) {
                if (child.getId() != null && visited.contains(parentId(child))) group.put(child.getId(), child);
            }
            if (group.size() > MAX_ROWS) throw tooLarge();
        }
        if (group.size() > MAX_ROWS) throw tooLarge();
        List<ResourceLink> rows = group.values().stream().filter(row -> editable(row, user))
                .sorted(Comparator.comparing(ResourceLink::getId)).toList();
        if (lock && !rows.isEmpty()) {
            // Lock in deterministic ID order so simultaneous edits cannot overwrite a stale group snapshot.
            List<ResourceLink> locked = resources.list(new QueryWrapper<ResourceLink>()
                    .in("id", rows.stream().map(ResourceLink::getId).toList()).orderByAsc("id").last("FOR UPDATE"));
            if (locked == null || locked.size() != rows.size()) throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "绑定资源已变化，请关闭编辑窗口后重新打开");
            rows = locked.stream().filter(row -> editable(row, user)).toList();
        }
        String version = ResourceHubHashUtils.sha256(rows.stream().map(row ->
                row.getId() + ":" + fingerprint(row))
                .collect(Collectors.joining("\n")));
        return new Snapshot(rootId, rows, version);
    }

    private static String fingerprint(ResourceLink row) {
        try {
            return ResourceHubHashUtils.sha256(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Arrays.asList(row.getMovieId(), String.valueOf(row.getUpdatedAt()), row.getUrl(), row.getCode(),
                            row.getName(), row.getLinkStatus(), row.getType(), row.getProvider(), row.getAuditStatus(),
                            row.getQuality(), row.getSubtitle(), row.getFileSize(), row.getVersionNote(), row.getSource(), row.getSourceRef())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Unable to fingerprint binding", error);
        }
    }

    private static boolean sameShare(ResourceLink a, ResourceLink b) {
        return a != null && b != null && a.getDeletedAt() == null && b.getDeletedAt() == null
                && !"DELETED".equalsIgnoreCase(a.getStatus()) && !"DELETED".equalsIgnoreCase(b.getStatus())
                && Objects.equals(a.getUrl(), b.getUrl())
                && sameText(a.getType(), b.getType())
                && sameText(a.getProvider(), b.getProvider())
                && Objects.equals(a.getUploaderId(), b.getUploaderId());
    }

    private static boolean sameText(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    public static boolean editable(ResourceLink row, AuthUser user) {
        return row != null && row.getId() != null && row.getDeletedAt() == null
                && !"DELETED".equalsIgnoreCase(row.getStatus())
                && ("ADMIN".equalsIgnoreCase(user.getRole()) || Objects.equals(user.getId(), row.getUploaderId()));
    }

    private static Long parentId(ResourceLink resource) {
        if (resource == null || resource.getSource() == null || !BINDING_SOURCES.contains(resource.getSource()) || resource.getSourceRef() == null) return null;
        try { long value = Long.parseLong(resource.getSourceRef()); return value > 0 ? value : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private static boolean sameSeries(MovieMetadata a, MovieMetadata b) {
        if (a == null || b == null || a.getDeletedAt() != null || b.getDeletedAt() != null
                || "DELETED".equalsIgnoreCase(b.getStatus())) return false;
        if (Objects.equals(a.getId(), b.getId())) return true;
        if (!Objects.equals(a.getCategory(), b.getCategory()) || !Set.of("tv", "ac").contains(a.getCategory() == null ? "" : a.getCategory())) return false;
        if (a.getTmdbId() != null && b.getTmdbId() != null) {
            return a.getTmdbId().equals(b.getTmdbId()) && Objects.equals(a.getTmdbType(), b.getTmdbType());
        }
        String first = a.getSeriesName() == null ? SeasonSearchUtils.baseTitle(a.getTitleCn()) : a.getSeriesName();
        String second = b.getSeriesName() == null ? SeasonSearchUtils.baseTitle(b.getTitleCn()) : b.getSeriesName();
        return first != null && !first.isBlank() && first.equals(second);
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "绑定组超过安全编辑上限，请先检查重复绑定");
    }

    public record Snapshot(Long rootId, List<ResourceLink> resources, String version) {}
}
