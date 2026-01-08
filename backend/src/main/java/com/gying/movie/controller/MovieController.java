package com.gying.movie.controller;

import com.gying.movie.dto.MovieDetailDTO;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

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
    public List<MovieMetadata> getMovieList(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String sort) {
        var query = movieService.lambdaQuery()
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
                    .like(MovieMetadata::getRegions, keyword));
        }

        query.like(genre != null && !genre.isEmpty(), MovieMetadata::getGenres, genre)
                .like(region != null && !region.isEmpty(), MovieMetadata::getRegions, region)
                .like(language != null && !language.isEmpty(), MovieMetadata::getLanguages, language)
                .eq(year != null, MovieMetadata::getYear, year);

        if ("rating".equals(sort)) {
            query.orderByDesc(MovieMetadata::getDoubanScore);
        } else {
            query.orderByDesc(MovieMetadata::getCreatedAt);
        }

        List<MovieMetadata> list = query.last("LIMIT 100").list();
        list.forEach(this::processMovieUrl);
        return list;
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
            throw new RuntimeException("Movie not found");
        }
        processMovieUrl(movie);

        List<ResourceLink> links = resourceService.getResourcesByMovieId(id);

        MovieDetailDTO dto = new MovieDetailDTO();
        dto.setMovie(movie);
        dto.setResources(links);

        return dto;
    }
}
