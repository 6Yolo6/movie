package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.BaiduTemporaryScope;
import com.gying.movie.utils.MovieDuplicateMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MovieDuplicateScannerService {
    static final int READ_BATCH = 500;
    static final int MAX_MOVIES = 100000;
    private final IMovieMetadataService movies;
    private final IResourceLinkService resources;
    private final AtomicBoolean scanning = new AtomicBoolean();

    public MovieDuplicateScannerService(IMovieMetadataService movies, IResourceLinkService resources) {
        this.movies = movies; this.resources = resources;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 60)
    public Map<String, Object> scan(int requestedPage, int requestedLimit) {
        if (!scanning.compareAndSet(false, true)) throw new ResponseStatusException(HttpStatus.CONFLICT, "A duplicate scan is already running");
        try {
            int limit = Math.max(1, Math.min(200, requestedLimit));
            List<MovieMetadata> catalog = new ArrayList<>();
            String cursor = null;
            boolean catalogComplete = true;
            while (true) {
                QueryWrapper<MovieMetadata> query = new QueryWrapper<MovieMetadata>()
                        .select("id", "tmdb_type", "title_cn", "title_en", "series_name", "season", "year", "actors", "directors", "aliases", "category", "status", "deleted_at")
                        .ne("status", "DELETED").isNull("deleted_at")
                        .orderByAsc("id").last("LIMIT " + READ_BATCH);
                if (cursor != null) query.gt("id", cursor);
                List<MovieMetadata> batch = movies.list(query);
                if (batch.isEmpty()) break;
                if (catalog.size() + batch.size() > MAX_MOVIES) { catalogComplete = false; break; }
                catalog.addAll(batch);
                String next = batch.get(batch.size() - 1).getId();
                if (next == null || next.equals(cursor)) throw new IllegalStateException("Duplicate scan cursor did not advance");
                cursor = next;
                if (batch.size() < READ_BATCH) break;
            }
            var matches = MovieDuplicateMatcher.find(catalog);
            List<String> ids = new ArrayList<>(matches.pairs().stream().flatMap(pair -> java.util.stream.Stream.of(pair.left().getId(), pair.right().getId()))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
            Map<String, Long> counts = countResources(ids);
            int total = matches.pairs().size();
            int totalPages = Math.max(1, (total + limit - 1) / limit);
            int page = Math.max(1, Math.min(totalPages, requestedPage));
            List<Map<String, Object>> items = new ArrayList<>();
            for (var pair : matches.pairs().subList((page - 1) * limit, Math.min(page * limit, total))) {
                MovieMetadata left = pair.left(), right = pair.right();
                // Prefer a record with resources, then the existing GYING record, solely as a review suggestion.
                if (prefer(left, right, counts)) { var temporary = left; left = right; right = temporary; }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fromMovieId", left.getId()); item.put("fromTitle", title(left));
                item.put("toMovieId", right.getId()); item.put("toTitle", title(right));
                item.put("fromResources", counts.getOrDefault(left.getId(), 0L));
                item.put("toResources", counts.getOrDefault(right.getId(), 0L));
                item.put("resources", counts.getOrDefault(left.getId(), 0L));
                item.put("category", pair.category()); item.put("year", left.getYear()); item.put("season", pair.season());
                item.put("matchedTitle", pair.title()); item.put("evidence", pair.peopleSupported() ? "NAME_AND_PEOPLE" : "NAME_ONLY");
                item.put("reviewRequired", true); items.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dryRun", true); result.put("scanMode", "FULL_LIBRARY");
            result.put("scannedMovies", catalog.size()); result.put("candidates", total);
            result.put("merged", 0); result.put("movedResources", 0); result.put("mergeEnabled", false);
            result.put("complete", catalogComplete && matches.complete());
            result.put("excludedMovies", matches.excludedMovies()); result.put("conflicts", matches.conflicts());
            result.put("crowdedBuckets", matches.crowdedBuckets());
            result.put("page", page); result.put("pageSize", limit); result.put("totalPages", totalPages); result.put("items", items);
            return result;
        } finally { scanning.set(false); }
    }

    private Map<String, Long> countResources(List<String> ids) {
        Map<String, Long> result = new HashMap<>();
        for (int offset = 0; offset < ids.size(); offset += READ_BATCH) {
            List<Map<String, Object>> rows = resources.listMaps(new QueryWrapper<ResourceLink>()
                    .select("movie_id", "COUNT(*) AS resource_count")
                    .in("movie_id", ids.subList(offset, Math.min(offset + READ_BATCH, ids.size())))
                    .eq("status", "ACTIVE").isNull("deleted_at")
                    .and(query -> query.isNull("link_status").or().eq("link_status", "NORMAL"))
                    .and(query -> query.isNull("source").or().ne("source", BaiduTemporaryScope.RESOURCE_SOURCE))
                    .groupBy("movie_id"));
            for (Map<String, Object> row : rows) {
                Object id = field(row, "movie_id"), count = field(row, "resource_count");
                if (id != null && count instanceof Number number) result.put(String.valueOf(id), number.longValue());
            }
        }
        return result;
    }
    private static Object field(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).findFirst().orElse(null);
    }
    private static boolean prefer(MovieMetadata left, MovieMetadata right, Map<String, Long> counts) {
        long first = counts.getOrDefault(left.getId(), 0L), second = counts.getOrDefault(right.getId(), 0L);
        if (first != second) return first > second;
        boolean leftGying = !left.getId().startsWith("tmdb_"), rightGying = !right.getId().startsWith("tmdb_");
        if (leftGying != rightGying) return leftGying;
        return left.getId().compareTo(right.getId()) < 0;
    }
    private static String title(MovieMetadata movie) {
        if (movie.getTitleCn() != null && !movie.getTitleCn().isBlank()) return movie.getTitleCn().trim();
        if (movie.getTitleEn() != null && !movie.getTitleEn().isBlank()) return movie.getTitleEn().trim();
        return movie.getId();
    }
}
