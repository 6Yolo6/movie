package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.dto.TmdbListItem;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IMovieSourceIdentityService;
import com.gying.movie.utils.MovieTitleMatcher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

/** Movie installments are distinct TMDB IDs, never TV seasons or implicit resource bindings. */
final class MovieCollectionCompletion {
    private final TmdbClient tmdb;
    private final PosterStorageService posters;
    private final IMovieMetadataService movies;
    private final IMovieSourceIdentityService identities;
    MovieCollectionCompletion(TmdbClient tmdb, PosterStorageService posters,
            IMovieMetadataService movies, IMovieSourceIdentityService identities) {
        this.tmdb = tmdb; this.posters = posters; this.movies = movies; this.identities = identities;
    }

    synchronized Map<String, Object> complete(MovieMetadata anchor, Supplier<List<MovieMetadata>> gyingLoader) {
        List<String> warnings = new ArrayList<>();
        List<MovieMetadata> local = new ArrayList<>(movies.list(new QueryWrapper<MovieMetadata>()
                .in("category", List.of("mv", "ac")).isNull("deleted_at")));
        Set<String> before = new HashSet<>(); local.forEach(row -> before.add(row.getId()));
        before.add(anchor.getId());
        int gyingCreated = 0, gyingRefreshed = 0, tmdbCreated = 0;
        try {
            for (MovieMetadata row : gyingLoader.get()) {
                if (before.add(row.getId())) gyingCreated++; else gyingRefreshed++;
                local.removeIf(previous -> previous.getId().equals(row.getId()));
                local.add(row);
                if (anchor.getId().equals(row.getId())) org.springframework.beans.BeanUtils.copyProperties(row, anchor);
            }
        } catch (Exception unavailable) { warnings.add("GYING 系列暂不可用，已尝试 TMDB 合集"); }
        try {
            Long id = "movie".equals(anchor.getTmdbType()) ? anchor.getTmdbId() : null;
            if (id == null) {
                List<TmdbListItem> exact = tmdb.searchMulti(anchor.getTitleCn(), 20).stream()
                        .filter(item -> "movie".equals(item.getMediaType()) && item.getTmdbId() != null)
                        .filter(item -> MovieTitleMatcher.normalizedEquals(anchor.getTitleCn(), item.getTitle())
                                || anchor.getTitleEn() != null && MovieTitleMatcher.normalizedEquals(anchor.getTitleEn(), item.getOriginalTitle()))
                        .filter(item -> anchor.getYear() != null && item.getReleaseDate() != null
                                && item.getReleaseDate().startsWith(anchor.getYear().toString())).toList();
                if (exact.size() != 1) throw new IllegalStateException("No unique movie identity");
                id = exact.get(0).getTmdbId();
            }
            JsonNode details = tmdb.fetchDetails("movie", id);
            if (details == null || details.path("id").asLong() != id) throw new IllegalStateException("Identity mismatch");
            long collectionId = details.path("belongs_to_collection").path("id").asLong();
            if (collectionId <= 0) throw new IllegalStateException("Not in a collection");
            JsonNode collection = tmdb.fetchCollection(collectionId);
            if (collection == null || collection.path("id").asLong() != collectionId) throw new IllegalStateException("Collection mismatch");
            List<JsonNode> parts = new ArrayList<>(); collection.path("parts").forEach(parts::add);
            Long anchorId = id;
            if (parts.stream().noneMatch(part -> part.path("id").asLong() == anchorId)) throw new IllegalStateException("Anchor missing");
            parts.sort(Comparator.comparing(part -> part.path("release_date").asText("9999")));
            String family = text(anchor.getSeriesName()) ? anchor.getSeriesName() : collection.path("name").asText(anchor.getTitleCn());
            int ordinal = 0;
            for (JsonNode part : parts) {
                long partId = part.path("id").asLong();
                if (partId <= 0) continue;
                ordinal++;
                String date = part.path("release_date").asText("");
                if (!date.matches("\\d{4}-\\d{2}-\\d{2}") || LocalDate.parse(date).isAfter(LocalDate.now())) continue;
                MovieSourceIdentity mapped = identities.getOne(new QueryWrapper<MovieSourceIdentity>()
                        .eq("source", "TMDB").eq("source_type", "movie").eq("external_id", String.valueOf(partId))
                        .eq("season", 0).last("LIMIT 1"), false);
                MovieMetadata target = mapped == null ? null : movies.getById(mapped.getMovieId());
                if (mapped != null && (target == null || !active(target))) continue;
                if (target == null && partId == id) target = anchor;
                if (target == null) {
                    List<MovieMetadata> exact = local.stream().filter(MovieCollectionCompletion::active)
                            .filter(row -> row.getTmdbId() != null ? row.getTmdbId() == partId && "movie".equals(row.getTmdbType())
                                    : Objects.equals(row.getYear(), Integer.valueOf(date.substring(0, 4)))
                                      && MovieTitleMatcher.normalizedEquals(row.getTitleCn(), part.path("title").asText())).toList();
                    if (exact.size() > 1) continue;
                    if (exact.size() == 1) target = exact.get(0);
                }
                String canonicalId = "tmdb_movie_" + partId;
                if (target == null) target = movies.getById(canonicalId);
                if (target != null && !active(target)) continue;
                boolean inserted = target == null;
                if (inserted) {
                    target = new MovieMetadata(); target.setId(canonicalId);
                    target.setTitleCn(part.path("title").asText()); target.setTitleEn(part.path("original_title").asText(null));
                    target.setCategory(anchor.getCategory()); target.setYear(Integer.valueOf(date.substring(0, 4)));
                    target.setReleaseDates(date); target.setSummary(part.path("overview").asText(null));
                    target.setStatus("ACTIVE"); target.setResourceStatus("UNKNOWN"); target.setPopularity(0);
                    target.setCreatedAt(LocalDateTime.now());
                }
                target.setTmdbId(partId); target.setTmdbType("movie");
                target.setSeriesName(family); target.setSeason(ordinal); target.setUpdatedAt(LocalDateTime.now());
                if (!text(target.getPosterUrl())) target.setPosterUrl(posters.storeTmdbPoster("movie", partId, part.path("poster_path").asText(null)));
                if (inserted) {
                    if (!movies.save(target)) throw new IllegalStateException("Movie not saved");
                    local.add(target); tmdbCreated++;
                } else movies.updateById(target);
                if (mapped == null) {
                    MovieSourceIdentity identity = new MovieSourceIdentity();
                    identity.setMovieId(target.getId()); identity.setSource("TMDB"); identity.setSourceType("movie");
                    identity.setExternalId(String.valueOf(partId)); identity.setSeason(0);
                    identity.setConfidence(BigDecimal.valueOf(100)); identity.setMatchMethod("EXACT_COLLECTION_MEMBER");
                    identity.setMatchStatus("CONFIRMED"); identity.setCreatedAt(LocalDateTime.now()); identity.setUpdatedAt(identity.getCreatedAt());
                    identities.save(identity);
                }
            }
        } catch (Exception unavailable) { warnings.add("TMDB 未找到可信合集或暂不可用，未创建推测的续集"); }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("mode", "METADATA_ONLY_COLLECTION"); result.put("metadataCreated", gyingCreated + tmdbCreated);
        result.put("gyingCreated", gyingCreated); result.put("tmdbCreated", tmdbCreated); result.put("bound", 0);
        result.put("metadataRefreshed", gyingRefreshed);
        result.put("status", gyingCreated + tmdbCreated + gyingRefreshed > 0 ? "COMPLETED" : "SKIPPED");
        result.put("warnings", warnings); result.put("reason", String.join("；", warnings));
        return result;
    }
    private static boolean active(MovieMetadata row) { return row.getDeletedAt() == null && !"DELETED".equals(row.getStatus()); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
}
