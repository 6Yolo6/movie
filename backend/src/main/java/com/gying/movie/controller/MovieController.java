package com.gying.movie.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.MovieDetailDTO;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/movies")
public class MovieController {

    @Autowired
    private IMovieMetadataService movieService;

    @Autowired
    private IResourceLinkService resourceService;

    @org.springframework.beans.factory.annotation.Value("${minio.url-prefix}")
    private String minioUrlPrefix;

    private void processMovieUrl(MovieMetadata movie) {
        if (movie != null && movie.getPosterUrl() != null && !movie.getPosterUrl().startsWith("http")) {
            movie.setPosterUrl(minioUrlPrefix + movie.getPosterUrl());
        }
    }

    @GetMapping("/list")
    public Page<MovieMetadata> getMovieList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String sort) {
        int currentPage = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), 60);
        Page<MovieMetadata> pageParam = new Page<>(currentPage, pageSize);
        var query = movieService.lambdaQuery()
                .eq(MovieMetadata::getStatus, "ACTIVE")
                .eq(category != null && !category.isEmpty(), MovieMetadata::getCategory, category);

        if (keyword != null && !keyword.isEmpty()) {
            query.and(w -> w
                    .like(MovieMetadata::getTitleCn, keyword)
                    .or()
                    .like(MovieMetadata::getTitleEn, keyword)
                    .or()
                    .like(MovieMetadata::getDirectors, keyword)
                    .or()
                    .like(MovieMetadata::getActors, keyword)
                    .or()
                    .like(MovieMetadata::getGenres, keyword)
                    .or()
                    .like(MovieMetadata::getRegions, keyword)
                    .or()
                    .like(MovieMetadata::getAliases, keyword));
        }

        query.like(genre != null && !genre.isEmpty(), MovieMetadata::getGenres, genre)
                .like(region != null && !region.isEmpty(), MovieMetadata::getRegions, region)
                .like(language != null && !language.isEmpty(), MovieMetadata::getLanguages, language)
                .eq(year != null, MovieMetadata::getYear, year);

        if ("rating".equals(sort)) {
            query.orderByDesc(MovieMetadata::getDoubanScore);
        } else if ("popular".equals(sort)) {
            query.orderByDesc(MovieMetadata::getPopularity);
        } else {
            query.orderByDesc(MovieMetadata::getCreatedAt);
        }

        Page<MovieMetadata> result = query.page(pageParam);
        result.getRecords().forEach(this::processMovieUrl);
        return result;
    }

    @GetMapping("/series")
    public List<MovieMetadata> getSeriesList(@RequestParam String name) {
        if (name == null || name.isEmpty())
            return List.of();

        List<MovieMetadata> list = movieService.lambdaQuery()
                .eq(MovieMetadata::getSeriesName, name)
                .orderByAsc(MovieMetadata::getSeason)
                .list();
        list.forEach(this::processMovieUrl);
        return list;
    }

    @GetMapping("/{id}")
    public MovieDetailDTO getMovieDetail(@PathVariable String id) {
        MovieMetadata movie = movieService.getById(id);
        if (movie == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found");
        }
        processMovieUrl(movie);

        List<ResourceLink> links = resourceService.getResourcesByMovieId(id);

        MovieDetailDTO dto = new MovieDetailDTO();
        dto.setMovie(movie);
        dto.setResources(links);

        return dto;
    }

    @GetMapping("/filters")
    public Map<String, List<String>> getFilterOptions(@RequestParam(required = false) String category) {
        Map<String, List<String>> result = new HashMap<>();
        result.put("genres", movieService.getDistinctStrings("genres", category));
        result.put("regions", movieService.getDistinctStrings("regions", category));
        result.put("languages", movieService.getDistinctStrings("languages", category));
        result.put("years", movieService.getDistinctYears(category));
        return result;
    }
}
