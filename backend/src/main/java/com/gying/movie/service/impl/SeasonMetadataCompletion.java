package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.dto.TmdbListItem;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IMovieSourceIdentityService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.SeasonSearchUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.BeanUtils;

/** Metadata-only season completion. Never searches for, transfers or publishes external resources. */
final class SeasonMetadataCompletion {
    private final TmdbClient tmdb;
    private final PosterStorageService posters;
    private final IMovieMetadataService movies;
    private final IMovieSourceIdentityService identities;
    private final IResourceLinkService resources;
    private final IResourceDiscoveryResultService discoveries;

    SeasonMetadataCompletion(TmdbClient tmdb, PosterStorageService posters, IMovieMetadataService movies,
            IMovieSourceIdentityService identities, IResourceLinkService resources,
            IResourceDiscoveryResultService discoveries) {
        this.tmdb = tmdb; this.movies = movies; this.posters = posters;
        this.identities = identities; this.resources = resources; this.discoveries = discoveries;
    }

    synchronized Map<String, Object> complete(MovieMetadata movie,
            Function<Set<Integer>, List<MovieMetadata>> gyingLoader) {
        if (!Set.of("tv", "ac").contains(movie.getCategory())) {
            throw new IllegalArgumentException("只有剧集和动漫可以补齐剩余季");
        }
        Map<Integer, MovieMetadata> seasons = new LinkedHashMap<>();
        for (MovieMetadata row : safe(movies.list(new QueryWrapper<MovieMetadata>()
                .eq("category", movie.getCategory()).isNull("deleted_at")))) {
            if (sameSeries(movie, row) && row.getSeason() != null && row.getSeason() > 0) {
                seasons.putIfAbsent(row.getSeason(), row);
            }
        }
        if (movie.getSeason() != null) seasons.put(movie.getSeason(), movie);
        List<CollectionLink> collections = collectionLinks(movie);
        Set<Integer> expected = new LinkedHashSet<>();
        collections.forEach(link -> expected.addAll(link.seasons()));
        List<String> warnings = new ArrayList<>();
        boolean gyingUnavailable = false;
        int gyingAdded = 0, gyingRefreshed = 0, tmdbAdded = 0;
        try {
            for (MovieMetadata row : safe(gyingLoader.apply(Set.copyOf(seasons.keySet())))) {
                if (row != null && row.getSeason() != null && row.getSeason() > 0) {
                    if (seasons.containsKey(row.getSeason())) gyingRefreshed++; else gyingAdded++;
                    // Subsequent resource bindings must use refreshed source metadata, not a stale DB snapshot.
                    seasons.put(row.getSeason(), row);
                }
            }
        } catch (Exception error) {
            gyingUnavailable = true;
            warnings.add(error instanceof IllegalStateException
                    ? "GYING 未找到唯一可信的系列匹配或导入未完成，已尝试 TMDB"
                    : "GYING 请求超时或来源暂不可用，已尝试 TMDB");
        }
        boolean tmdbNeeded = expected.isEmpty() || !seasons.keySet().containsAll(expected);
        if (tmdbNeeded) {
            try {
                tmdbAdded = fillFromTmdb(movie, seasons);
            } catch (Exception error) {
                warnings.add("TMDB 未找到唯一匹配或暂不可用，未创建推测的季元数据");
            }
        }
        int bound = 0, existingBindings = 0, failed = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (MovieMetadata target : seasons.values()) {
            if (target.getId().equals(movie.getId())) continue;
            for (CollectionLink collection : collections) {
                if (!collection.seasons().contains(target.getSeason())) continue;
                try {
                    ResourceLink original = collection.link();
                    long count = resources.count(new QueryWrapper<ResourceLink>()
                            .eq("movie_id", target.getId()).eq("url", original.getUrl()).isNull("deleted_at"));
                    if (count > 0) { existingBindings++; continue; }
                    ResourceLink link = new ResourceLink();
                    BeanUtils.copyProperties(original, link);
                    link.setId(null); link.setMovieId(target.getId());
                    // Local association only: do not enqueue another external GYING publication.
                    link.setSource("COLLECTION_BINDING"); link.setSourceRef(String.valueOf(original.getId()));
                    link.setName(collectionName(movie, collection.title(), original.getQuality()));
                    link.setReportCount(0); link.setLastCheckError(null); link.setRejectReason(null);
                    link.setCreatedAt(LocalDateTime.now()); link.setUpdatedAt(link.getCreatedAt());
                    if (!resources.save(link)) throw new IllegalStateException("Resource binding not saved");
                    target.setResourceStatus("AVAILABLE"); target.setUpdatedAt(LocalDateTime.now());
                    movies.updateById(target);
                    bound++;
                    items.add(Map.of("movieId", target.getId(), "season", target.getSeason(),
                            "status", "BOUND_EXISTING_COLLECTION", "sourceResourceId", original.getId()));
                } catch (Exception error) {
                    failed++;
                    items.add(Map.of("movieId", target.getId(), "season", target.getSeason(), "status", "BIND_FAILED"));
                }
            }
        }
        Set<Integer> missing = new LinkedHashSet<>(expected);
        missing.removeAll(seasons.keySet());
        if (!missing.isEmpty()) warnings.add("合集中的部分季尚未找到可信元数据：" + missing);
        if (collections.isEmpty()) warnings.add("没有可确认季范围的可用合集链接；仅补元数据，不绑定单季或未知范围资源");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gyingUnavailable", gyingUnavailable);
        result.put("mode", "METADATA_AND_EXISTING_COLLECTION");
        result.put("status", failed > 0 || !missing.isEmpty() ? "PARTIAL"
                : gyingAdded + tmdbAdded + gyingRefreshed == 0 && bound == 0 ? "SKIPPED" : "COMPLETED");
        result.put("discovered", seasons.size()); result.put("completed", gyingAdded + tmdbAdded);
        result.put("metadataCreated", gyingAdded + tmdbAdded); result.put("gyingCreated", gyingAdded);
        result.put("tmdbCreated", tmdbAdded); result.put("metadataRefreshed", gyingRefreshed); result.put("bound", bound);
        result.put("existingBindings", existingBindings); result.put("failed", failed);
        result.put("missingSeasons", missing); result.put("items", items); result.put("warnings", warnings);
        result.put("reason", "优先 GYING，其次 TMDB；只补元数据并复用已确认的合集分享，不重复转存");
        return result;
    }

    private List<CollectionLink> collectionLinks(MovieMetadata movie) {
        List<CollectionLink> result = new ArrayList<>();
        for (ResourceLink link : safe(resources.list(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movie.getId()).eq("type", "DISK").eq("status", "ACTIVE")
                .eq("audit_status", 1).eq("link_status", "NORMAL").isNull("deleted_at")))) {
            // Check again to keep mocked/custom service implementations from widening the boundary.
            if (!"ACTIVE".equals(link.getStatus()) || !"NORMAL".equals(link.getLinkStatus())
                    || !Integer.valueOf(1).equals(link.getAuditStatus()) || link.getDeletedAt() != null
                    || !"DISK".equals(link.getType()) || !text(link.getUrl())) continue;
            String title = link.getName();
            Set<Integer> coverage = SeasonSearchUtils.collectionSeasons(title);
            if (coverage.isEmpty() && link.getId() != null) {
                ResourceDiscoveryResult source = discoveries.getOne(new QueryWrapper<ResourceDiscoveryResult>()
                        .eq("resource_link_id", link.getId()).eq("share_url", link.getUrl())
                        .orderByDesc("updated_at").last("LIMIT 1"), false);
                if (source != null && link.getId().equals(source.getResourceLinkId())
                        && link.getUrl().equals(source.getShareUrl())) {
                    title = source.getTitle(); coverage = SeasonSearchUtils.collectionSeasons(title);
                }
            }
            if (coverage.size() < 2) continue;
            // A URL explicitly narrowed to a folder does not prove whole-collection coverage.
            if (link.getUrl().contains("?fid=") || link.getUrl().contains("&fid=")) continue;
            result.add(new CollectionLink(link, title, coverage));
            String name = collectionName(movie, title, link.getQuality());
            if (!name.equals(link.getName())) {
                link.setName(name); link.setUpdatedAt(LocalDateTime.now()); resources.updateById(link);
            }
        }
        return result;
    }

    private int fillFromTmdb(MovieMetadata movie, Map<Integer, MovieMetadata> seasons) {
        Long tmdbId = movie.getTmdbId();
        if (tmdbId == null || !"tv".equals(movie.getTmdbType())) {
            List<TmdbListItem> exact = safe(tmdb.searchMulti(base(movie), 20)).stream()
                    .filter(item -> "tv".equals(item.getMediaType()) && item.getTmdbId() != null)
                    .filter(item -> normalized(base(movie)).equals(normalized(item.getTitle()))
                            || normalized(SeasonSearchUtils.baseTitle(movie.getTitleEn())).equals(normalized(item.getOriginalTitle()))
                                    && text(movie.getTitleEn()))
                    .filter(item -> movie.getSeason() == null || movie.getSeason() > 1 || movie.getYear() == null
                            || item.getReleaseDate() != null && item.getReleaseDate().startsWith(movie.getYear().toString()))
                    .toList();
            if (exact.size() != 1) throw new IllegalStateException("No unique TMDB series match");
            tmdbId = exact.get(0).getTmdbId();
        }
        JsonNode details = tmdb.fetchDetails("tv", tmdbId);
        if (details == null || details.path("id").asLong() != tmdbId) throw new IllegalStateException("TMDB identity mismatch");
        int created = 0;
        for (JsonNode season : details.path("seasons")) {
            int number = season.path("season_number").asInt();
            if (number < 1 || number > 99 || seasons.containsKey(number)) continue;
            // Do not invent unaired seasons or copy season one's year/plot into every row.
            String airDate = season.path("air_date").asText("");
            if (!airDate.matches("\\d{4}-\\d{2}-\\d{2}")
                    || java.time.LocalDate.parse(airDate).isAfter(java.time.LocalDate.now())) continue;
            MovieSourceIdentity mapped = identities.getOne(new QueryWrapper<MovieSourceIdentity>()
                    .eq("source", "TMDB").eq("source_type", "tv")
                    .eq("external_id", tmdbId.toString()).eq("season", number).last("LIMIT 1"), false);
            if (mapped != null) {
                MovieMetadata canonical = movies.getById(mapped.getMovieId());
                if (canonical != null && canonical.getDeletedAt() == null
                        && !"DELETED".equals(canonical.getStatus()) && Integer.valueOf(number).equals(canonical.getSeason())) {
                    seasons.put(number, canonical);
                }
                continue; // Keep canonical identity ownership, including intentionally deleted rows.
            }
            String id = "tmdb_tv_" + tmdbId + "_s" + number;
            MovieMetadata existing = movies.getById(id);
            if (existing != null) {
                if (existing.getDeletedAt() == null && sameSeries(movie, existing)) seasons.put(number, existing);
                continue; // Never revive a deleted canonical row implicitly.
            }
            MovieMetadata row = new MovieMetadata();
            row.setId(id); row.setTmdbId(tmdbId); row.setTmdbType("tv");
            row.setCategory(movie.getCategory()); row.setTitleCn(SeasonSearchUtils.seasonQualifiedTitle(base(movie), number));
            row.setSeriesName(base(movie)); row.setSeason(number);
            if (text(details.path("original_name").asText())) row.setTitleEn(details.path("original_name").asText() + " Season " + number);
            row.setYear(Integer.parseInt(airDate.substring(0, 4))); row.setReleaseDates(airDate);
            row.setSummary(season.path("overview").asText(null));
            row.setGenres(movie.getGenres()); row.setRegions(movie.getRegions()); row.setLanguages(movie.getLanguages());
            row.setPosterUrl(posters.storeTmdbSeasonPoster(tmdbId, number, season.path("poster_path").asText(null)));
            // Never reuse a different season's image. Missing artwork remains eligible for repair.
            row.setStatus("ACTIVE"); row.setResourceStatus("UNKNOWN"); row.setPopularity(0);
            row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(row.getCreatedAt()); row.setTmdbLastSyncAt(row.getCreatedAt());
            if (!movies.save(row)) throw new IllegalStateException("Season metadata not saved");
            MovieSourceIdentity identity = new MovieSourceIdentity();
            identity.setMovieId(id); identity.setSource("TMDB"); identity.setSourceType("tv");
            identity.setExternalId(tmdbId.toString()); identity.setSeason(number);
            identity.setConfidence(BigDecimal.valueOf(100)); identity.setMatchMethod("EXACT_SERIES_SEASON");
            identity.setMatchStatus("CONFIRMED"); identity.setCreatedAt(row.getCreatedAt()); identity.setUpdatedAt(row.getCreatedAt());
            if (identities.count(new QueryWrapper<MovieSourceIdentity>().eq("source", "TMDB")
                    .eq("source_type", "tv").eq("external_id", tmdbId.toString()).eq("season", number)) == 0) identities.save(identity);
            seasons.put(number, row); created++;
        }
        return created;
    }

    static String collectionName(MovieMetadata movie, String title, String quality) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(8K|4K|2160P|1080[PI]|720P)")
                .matcher(text(quality) ? quality : title == null ? "" : title);
        String name = base(movie) + " " + SeasonSearchUtils.collectionLabel(title)
                + (matcher.find() ? " " + matcher.group().toLowerCase(java.util.Locale.ROOT) : "");
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    static boolean sameSeries(MovieMetadata base, MovieMetadata row) {
        if (row == null || row.getDeletedAt() != null || "DELETED".equals(row.getStatus())) return false;
        if (base.getTmdbId() != null && row.getTmdbId() != null) {
            return base.getTmdbId().equals(row.getTmdbId()) && "tv".equals(row.getTmdbType())
                    && "tv".equals(base.getTmdbType());
        }
        String name = normalized(base(base));
        if (name.isEmpty() || !base.getCategory().equals(row.getCategory()) || !name.equals(normalized(base(row)))) return false;
        if (text(base.getTitleEn()) && text(row.getTitleEn())) {
            return normalized(SeasonSearchUtils.baseTitle(base.getTitleEn()))
                    .equals(normalized(SeasonSearchUtils.baseTitle(row.getTitleEn())));
        }
        return true;
    }
    private static String base(MovieMetadata movie) {
        return text(movie.getSeriesName()) ? movie.getSeriesName() : SeasonSearchUtils.baseTitle(
                text(movie.getTitleCn()) ? movie.getTitleCn() : movie.getTitleEn());
    }
    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static <T> List<T> safe(List<T> list) { return list == null ? List.of() : list; }
    private record CollectionLink(ResourceLink link, String title, Set<Integer> seasons) {}
}
