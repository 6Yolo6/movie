package com.gying.movie.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.dto.AdminMovieRequest;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.impl.PosterStorageService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.PosterUrlUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/movies")
public class AdminMovieController {
    private final IMovieMetadataService movieService;
    private final PosterStorageService posterStorageService;
    private final AuthHelper authHelper;
    private final ObjectMapper objectMapper;
    private final String minioUrlPrefix;

    public AdminMovieController(IMovieMetadataService movieService, PosterStorageService posterStorageService,
            AuthHelper authHelper, ObjectMapper objectMapper,
            @Value("${minio.url-prefix}") String minioUrlPrefix) {
        this.movieService = movieService;
        this.posterStorageService = posterStorageService;
        this.authHelper = authHelper;
        this.objectMapper = objectMapper;
        this.minioUrlPrefix = minioUrlPrefix;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        var query = movieService.lambdaQuery();
        if (!includeDeleted) query.ne(MovieMetadata::getStatus, "DELETED");
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(MovieMetadata::getTitleCn, keyword).or()
                    .like(MovieMetadata::getTitleEn, keyword).or().like(MovieMetadata::getSeriesName, keyword)
                    .or().like(MovieMetadata::getAliases, keyword).or().like(MovieMetadata::getId, keyword));
        }
        Page<MovieMetadata> result = query.orderByDesc(MovieMetadata::getUpdatedAt)
                .page(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)));
        result.getRecords().forEach(this::processPosterUrl);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        MovieMetadata movie = movieService.getById(id);
        if (movie == null) return ResponseEntity.notFound().build();
        processPosterUrl(movie);
        return ResponseEntity.ok(movie);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody AdminMovieRequest request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        String validationError = validateRequest(request, true);
        if (validationError != null) return ResponseEntity.badRequest().body(validationError);
        String id = hasText(request.getId()) ? request.getId().trim() : "manual_" + UUID.randomUUID();
        if (movieService.getById(id) != null) return ResponseEntity.status(409).body("Movie id already exists");
        LocalDateTime now = LocalDateTime.now();
        MovieMetadata movie = new MovieMetadata();
        movie.setId(id);
        applyRequest(movie, request);
        movie.setStatus(normalizeEnum(request.getStatus(), "ACTIVE"));
        movie.setResourceStatus(normalizeEnum(request.getResourceStatus(), "UNKNOWN"));
        movie.setDeletedAt("DELETED".equals(movie.getStatus()) ? now : null);
        movie.setCreatedAt(now);
        movie.setUpdatedAt(now);
        movieService.save(movie);
        processPosterUrl(movie);
        return ResponseEntity.ok(movie);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody AdminMovieRequest request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        MovieMetadata target = movieService.getById(id);
        if (target == null) return ResponseEntity.notFound().build();
        String validationError = validateRequest(request, false);
        if (validationError != null) return ResponseEntity.badRequest().body(validationError);

        LocalDateTime now = LocalDateTime.now();
        applyRequest(target, request);
        target.setStatus(normalizeEnum(request.getStatus(), "ACTIVE"));
        target.setResourceStatus(normalizeEnum(request.getResourceStatus(), "UNKNOWN"));
        target.setDeletedAt("DELETED".equals(target.getStatus())
                ? (target.getDeletedAt() == null ? now : target.getDeletedAt())
                : null);
        target.setUpdatedAt(now);
        updateAllMetadataFields(target);
        MovieMetadata updated = movieService.getById(id);
        processPosterUrl(updated);
        return ResponseEntity.ok(updated);
    }

    @PostMapping(value = "/{id}/poster", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPoster(@PathVariable String id, @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        MovieMetadata movie = movieService.getById(id);
        if (movie == null) return ResponseEntity.notFound().build();
        try {
            String posterObject = posterStorageService.storeUploadedPoster(id, file);
            movieService.lambdaUpdate()
                    .eq(MovieMetadata::getId, id)
                    .set(MovieMetadata::getPosterUrl, posterObject)
                    .set(MovieMetadata::getUpdatedAt, LocalDateTime.now())
                    .update();
            MovieMetadata updated = movieService.getById(id);
            processPosterUrl(updated);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        MovieMetadata movie = movieService.getById(id);
        if (movie == null) return ResponseEntity.notFound().build();
        movie.setStatus("DELETED");
        movie.setDeletedAt(LocalDateTime.now());
        movie.setUpdatedAt(movie.getDeletedAt());
        movieService.updateById(movie);
        return ResponseEntity.ok().build();
    }

    private void processPosterUrl(MovieMetadata movie) {
        if (movie == null) return;
        movie.setPosterUrl(PosterUrlUtils.toPublicUrl(movie.getPosterUrl(), minioUrlPrefix));
    }

    private String validateRequest(AdminMovieRequest request, boolean creating) {
        if (request == null || !hasText(request.getTitleCn())) return "titleCn is required";
        if (creating && hasText(request.getId()) && !request.getId().trim().matches("[A-Za-z0-9._:-]{1,64}")) {
            return "Movie id may only contain letters, numbers, dot, underscore, colon or hyphen";
        }
        if (request.getTmdbId() != null && request.getTmdbId() <= 0) return "tmdbId must be positive";
        if (request.getSeason() != null && request.getSeason() < 0) return "season cannot be negative";
        if (request.getYear() != null && (request.getYear() < 1800 || request.getYear() > 3000)) {
            return "year must be between 1800 and 3000";
        }
        if (!isAllowed(request.getCategory(), Set.of("mv", "tv", "ac"))) return "Invalid category";
        if (!isAllowed(request.getTmdbType(), Set.of("movie", "tv"))) return "Invalid tmdbType";
        if (!isAllowed(request.getStatus(), Set.of("ACTIVE", "DELETED"))) return "Invalid status";
        if (!isAllowed(request.getResourceStatus(), Set.of("UNKNOWN", "TRAILER", "AVAILABLE"))) {
            return "Invalid resourceStatus";
        }
        if (!validScore(request.getDoubanScore()) || !validScore(request.getImdbScore())
                || !validScore(request.getTmdbVoteAverage())) {
            return "Scores must be between 0 and 10";
        }
        if (request.getTmdbPopularity() != null && request.getTmdbPopularity().signum() < 0) {
            return "tmdbPopularity cannot be negative";
        }
        if (request.getPopularity() != null && request.getPopularity() < 0) return "popularity cannot be negative";
        String posterUrl = clean(request.getPosterUrl(), 500);
        if (posterUrl != null && posterUrl.contains("://")
                && !posterUrl.toLowerCase(Locale.ROOT).matches("^https?://.+")) {
            return "posterUrl must be an http(s) URL";
        }
        return null;
    }

    private void applyRequest(MovieMetadata movie, AdminMovieRequest request) {
        movie.setTmdbId(request.getTmdbId());
        movie.setTmdbType(normalizeLower(request.getTmdbType(), null));
        movie.setTitleCn(clean(request.getTitleCn(), 255));
        movie.setTitleEn(clean(request.getTitleEn(), 500));
        movie.setSeriesName(clean(request.getSeriesName(), 255));
        movie.setSeason(request.getSeason());
        movie.setYear(request.getYear());
        movie.setRuntime(clean(request.getRuntime(), 100));
        movie.setDirectors(cleanList(request.getDirectors()));
        movie.setActors(cleanList(request.getActors()));
        movie.setGenres(cleanList(request.getGenres()));
        movie.setRegions(cleanList(request.getRegions()));
        movie.setLanguages(cleanList(request.getLanguages()));
        movie.setReleaseDates(clean(request.getReleaseDates(), 500));
        movie.setAliases(clean(request.getAliases(), 2000));
        movie.setCategory(normalizeLower(request.getCategory(), "mv"));
        movie.setPosterUrl(clean(request.getPosterUrl(), 500));
        movie.setDoubanScore(request.getDoubanScore());
        movie.setImdbScore(request.getImdbScore());
        movie.setTmdbPopularity(request.getTmdbPopularity());
        movie.setTmdbVoteAverage(request.getTmdbVoteAverage());
        movie.setRtScore(clean(request.getRtScore(), 50));
        movie.setSummary(clean(request.getSummary(), 20000));
        movie.setPopularity(request.getPopularity() == null ? 0 : request.getPopularity());
        movie.setTmdbLastSyncAt(request.getTmdbLastSyncAt());
    }

    private void updateAllMetadataFields(MovieMetadata movie) {
        LambdaUpdateChainWrapper<MovieMetadata> update = movieService.lambdaUpdate()
                .eq(MovieMetadata::getId, movie.getId())
                .set(MovieMetadata::getTitleCn, movie.getTitleCn())
                .set(MovieMetadata::getDirectors, toJson(movie.getDirectors()))
                .set(MovieMetadata::getActors, toJson(movie.getActors()))
                .set(MovieMetadata::getGenres, toJson(movie.getGenres()))
                .set(MovieMetadata::getRegions, toJson(movie.getRegions()))
                .set(MovieMetadata::getLanguages, toJson(movie.getLanguages()))
                .set(MovieMetadata::getCategory, movie.getCategory())
                .set(MovieMetadata::getStatus, movie.getStatus())
                .set(MovieMetadata::getResourceStatus, movie.getResourceStatus())
                .set(MovieMetadata::getPopularity, movie.getPopularity())
                .set(MovieMetadata::getUpdatedAt, movie.getUpdatedAt());
        setNullable(update, MovieMetadata::getTmdbId, movie.getTmdbId(), "tmdb_id");
        setNullable(update, MovieMetadata::getTmdbType, movie.getTmdbType(), "tmdb_type");
        setNullable(update, MovieMetadata::getTitleEn, movie.getTitleEn(), "title_en");
        setNullable(update, MovieMetadata::getSeriesName, movie.getSeriesName(), "series_name");
        setNullable(update, MovieMetadata::getSeason, movie.getSeason(), "season");
        setNullable(update, MovieMetadata::getYear, movie.getYear(), "year");
        setNullable(update, MovieMetadata::getRuntime, movie.getRuntime(), "runtime");
        setNullable(update, MovieMetadata::getReleaseDates, movie.getReleaseDates(), "release_dates");
        setNullable(update, MovieMetadata::getAliases, movie.getAliases(), "aliases");
        setNullable(update, MovieMetadata::getPosterUrl, movie.getPosterUrl(), "poster_url");
        setNullable(update, MovieMetadata::getDoubanScore, movie.getDoubanScore(), "douban_score");
        setNullable(update, MovieMetadata::getImdbScore, movie.getImdbScore(), "imdb_score");
        setNullable(update, MovieMetadata::getTmdbPopularity, movie.getTmdbPopularity(), "tmdb_popularity");
        setNullable(update, MovieMetadata::getTmdbVoteAverage, movie.getTmdbVoteAverage(), "tmdb_vote_average");
        setNullable(update, MovieMetadata::getRtScore, movie.getRtScore(), "rt_score");
        setNullable(update, MovieMetadata::getSummary, movie.getSummary(), "summary");
        setNullable(update, MovieMetadata::getTmdbLastSyncAt, movie.getTmdbLastSyncAt(), "tmdb_last_sync_at");
        setNullable(update, MovieMetadata::getDeletedAt, movie.getDeletedAt(), "deleted_at");
        update.update();
    }

    private void setNullable(LambdaUpdateChainWrapper<MovieMetadata> update,
            SFunction<MovieMetadata, ?> column, Object value, String sqlColumn) {
        if (value == null) {
            update.setSql(sqlColumn + " = NULL");
        } else {
            update.set(column, value);
        }
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .map(value -> clean(value, 255))
                .filter(value -> value != null)
                .distinct()
                .limit(100)
                .toList();
    }

    private boolean isAllowed(String value, Set<String> allowed) {
        return !hasText(value) || allowed.stream().anyMatch(item -> item.equalsIgnoreCase(value.trim()));
    }

    private boolean validScore(BigDecimal score) {
        return score == null || (score.signum() >= 0 && score.compareTo(BigDecimal.TEN) <= 0);
    }

    private String normalizeEnum(String value, String fallback) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private String normalizeLower(String value, String fallback) {
        return hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : fallback;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid list metadata", e);
        }
    }

    private String clean(String value, int maxLength) {
        if (!hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
