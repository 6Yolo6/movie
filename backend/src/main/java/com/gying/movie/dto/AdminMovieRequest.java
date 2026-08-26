package com.gying.movie.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class AdminMovieRequest {
    private String id;
    private Long tmdbId;
    private String tmdbType;
    private String titleCn;
    private String titleEn;
    private String seriesName;
    private Integer season;
    private Integer year;
    private String runtime;
    private List<String> directors;
    private List<String> actors;
    private List<String> genres;
    private List<String> regions;
    private List<String> languages;
    private String releaseDates;
    private String aliases;
    private String category;
    private String posterUrl;
    private BigDecimal doubanScore;
    private BigDecimal imdbScore;
    private BigDecimal tmdbPopularity;
    private BigDecimal tmdbVoteAverage;
    private String rtScore;
    private String summary;
    private String status;
    private String resourceStatus;
    private Integer popularity;
    private LocalDateTime tmdbLastSyncAt;
}
