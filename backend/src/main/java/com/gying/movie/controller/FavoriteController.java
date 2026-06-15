package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.UserFavorite;
import com.gying.movie.mapper.UserFavoriteMapper;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IUserFavoriteService;
import com.gying.movie.utils.AuthHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final IUserFavoriteService favoriteService;
    private final IMovieMetadataService movieService;
    private final UserFavoriteMapper favoriteMapper;
    private final AuthHelper authHelper;

    @Value("${minio.url-prefix}")
    private String minioUrlPrefix;

    public FavoriteController(IUserFavoriteService favoriteService,
                              IMovieMetadataService movieService,
                              UserFavoriteMapper favoriteMapper,
                              AuthHelper authHelper) {
        this.favoriteService = favoriteService;
        this.movieService = movieService;
        this.favoriteMapper = favoriteMapper;
        this.authHelper = authHelper;
    }

    /**
     * Toggle favorite (add or remove).
     * POST /api/favorites/toggle?movieId=xxx
     * Returns: { "favorited": true/false, "count": 42 }
     */
    @PostMapping("/toggle")
    public Map<String, Object> toggle(
            @RequestHeader("Authorization") String token,
            @RequestParam String movieId) {
        AuthUser user = authHelper.requireUser(token);

        if (movieId == null || movieId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "movieId is required");
        }

        // Check if movie exists
        MovieMetadata movie = movieService.getById(movieId);
        if (movie == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found");
        }

        // Check existing favorite
        LambdaQueryWrapper<UserFavorite> wrapper = new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, user.getId())
                .eq(UserFavorite::getMovieId, movieId);
        UserFavorite existing = favoriteService.getOne(wrapper);

        boolean favorited;
        if (existing != null) {
            // Remove favorite
            favoriteService.removeById(existing.getId());
            favorited = false;
            // Decrease popularity
            if (movie.getPopularity() != null && movie.getPopularity() > 0) {
                movie.setPopularity(movie.getPopularity() - 1);
                movieService.updateById(movie);
            }
        } else {
            // Add favorite
            UserFavorite fav = new UserFavorite();
            fav.setUserId(user.getId());
            fav.setMovieId(movieId);
            fav.setCreatedAt(LocalDateTime.now());
            favoriteService.save(fav);
            favorited = true;
            // Increase popularity
            movie.setPopularity(movie.getPopularity() == null ? 1 : movie.getPopularity() + 1);
            movieService.updateById(movie);
        }

        // Get current count
        long count = favoriteService.count(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getMovieId, movieId));

        Map<String, Object> result = new HashMap<>();
        result.put("favorited", favorited);
        result.put("count", count);
        return result;
    }

    /**
     * Check if current user has favorited a movie.
     * GET /api/favorites/check?movieId=xxx
     */
    @GetMapping("/check")
    public Map<String, Object> check(
            @RequestHeader("Authorization") String token,
            @RequestParam String movieId) {
        AuthUser user = authHelper.requireUser(token);

        boolean isFavorited = favoriteService.count(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, user.getId())
                .eq(UserFavorite::getMovieId, movieId)) > 0;

        long count = favoriteService.count(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getMovieId, movieId));

        Map<String, Object> result = new HashMap<>();
        result.put("favorited", isFavorited);
        result.put("count", count);
        return result;
    }

    /**
     * Get favorite count for a movie (public, no auth needed).
     * GET /api/favorites/count?movieId=xxx
     */
    @GetMapping("/count")
    public Map<String, Object> getCount(@RequestParam String movieId) {
        long count = favoriteService.count(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getMovieId, movieId));
        return Map.of("count", count);
    }

    /**
     * Get hot/trending movies.
     * GET /api/favorites/hot?period=day|week|month|all&limit=30
     */
    @GetMapping("/hot")
    public List<Map<String, Object>> hot(
            @RequestParam(defaultValue = "all") String period,
            @RequestParam(defaultValue = "30") int limit) {

        int maxLimit = Math.min(Math.max(limit, 1), 60);
        List<Map<String, Object>> stats;

        switch (period.toLowerCase()) {
            case "day":
                String daySince = LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                stats = favoriteMapper.countByMovieSince(daySince, maxLimit);
                break;
            case "week":
                String weekSince = LocalDateTime.now().minusWeeks(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                stats = favoriteMapper.countByMovieSince(weekSince, maxLimit);
                break;
            case "month":
                String monthSince = LocalDateTime.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                stats = favoriteMapper.countByMovieSince(monthSince, maxLimit);
                break;
            default:
                stats = favoriteMapper.countByMovieAll(maxLimit);
                break;
        }

        if (stats.isEmpty()) {
            return Collections.emptyList();
        }

        // Fetch movie details for the results
        List<String> movieIds = stats.stream()
                .map(s -> (String) s.get("movie_id"))
                .collect(Collectors.toList());
        List<MovieMetadata> movies = movieService.listByIds(movieIds);
        Map<String, MovieMetadata> movieMap = movies.stream()
                .collect(Collectors.toMap(MovieMetadata::getId, m -> m));

        // Build result maintaining sort order
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> stat : stats) {
            String movieId = (String) stat.get("movie_id");
            MovieMetadata movie = movieMap.get(movieId);
            if (movie == null) continue;

            // Process poster URL
            if (movie.getPosterUrl() != null && !movie.getPosterUrl().startsWith("http")) {
                movie.setPosterUrl(minioUrlPrefix + movie.getPosterUrl());
            }

            Map<String, Object> item = new HashMap<>();
            item.put("movie", movie);
            item.put("favoriteCount", stat.get("cnt"));
            result.add(item);
        }

        return result;
    }

    /**
     * Get current user's favorites.
     * GET /api/favorites/mine?page=1&size=20
     */
    @GetMapping("/mine")
    public Page<Map<String, Object>> mine(
            @RequestHeader("Authorization") String token,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuthUser user = authHelper.requireUser(token);

        int currentPage = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), 60);

        // Get paginated favorites
        Page<UserFavorite> favPage = new Page<>(currentPage, pageSize);
        LambdaQueryWrapper<UserFavorite> wrapper = new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, user.getId())
                .orderByDesc(UserFavorite::getCreatedAt);
        Page<UserFavorite> paged = favoriteService.page(favPage, wrapper);

        if (paged.getRecords().isEmpty()) {
            Page<Map<String, Object>> emptyResult = new Page<>(paged.getCurrent(), paged.getSize(), paged.getTotal());
            emptyResult.setPages(paged.getPages());
            emptyResult.setRecords(Collections.emptyList());
            return emptyResult;
        }

        // Fetch movies
        List<String> movieIds = paged.getRecords().stream()
                .map(UserFavorite::getMovieId)
                .collect(Collectors.toList());
        List<MovieMetadata> movies = movieService.listByIds(movieIds);
        Map<String, MovieMetadata> movieMap = movies.stream()
                .collect(Collectors.toMap(MovieMetadata::getId, m -> m));

        // Get favorite counts for all movies
        Map<String, Long> countMap = new HashMap<>();
        for (String mid : movieIds) {
            countMap.put(mid, favoriteService.count(new LambdaQueryWrapper<UserFavorite>()
                    .eq(UserFavorite::getMovieId, mid)));
        }

        // Build result
        List<Map<String, Object>> records = new ArrayList<>();
        for (UserFavorite fav : paged.getRecords()) {
            MovieMetadata movie = movieMap.get(fav.getMovieId());
            if (movie == null) continue;

            if (movie.getPosterUrl() != null && !movie.getPosterUrl().startsWith("http")) {
                movie.setPosterUrl(minioUrlPrefix + movie.getPosterUrl());
            }

            Map<String, Object> item = new HashMap<>();
            item.put("movie", movie);
            item.put("favoriteCount", countMap.getOrDefault(fav.getMovieId(), 0L));
            item.put("favoritedAt", fav.getCreatedAt());
            records.add(item);
        }

        Page<Map<String, Object>> result = new Page<>(paged.getCurrent(), paged.getSize(), paged.getTotal());
        result.setPages(paged.getPages());
        result.setRecords(records);
        return result;
    }
}
