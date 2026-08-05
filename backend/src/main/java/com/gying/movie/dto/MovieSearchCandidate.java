package com.gying.movie.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class MovieSearchCandidate {
    private Long tmdbId;
    private String mediaType;
    private String title;
    private String originalTitle;
    private Integer year;
    private int score;
    private String source;
    private String sourceType;
    private String sourceId;
    private String localMovieId;

    public MovieSearchCandidate(
            Long tmdbId,
            String mediaType,
            String title,
            String originalTitle,
            Integer year,
            int score) {
        this(tmdbId, mediaType, title, originalTitle, year, score, null, null, null, null);
    }

    public MovieSearchCandidate(
            Long tmdbId,
            String mediaType,
            String title,
            String originalTitle,
            Integer year,
            int score,
            String source,
            String sourceType,
            String sourceId,
            String localMovieId) {
        this.tmdbId = tmdbId;
        this.mediaType = mediaType;
        this.title = title;
        this.originalTitle = originalTitle;
        this.year = year;
        this.score = score;
        this.source = source;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.localMovieId = localMovieId;
    }
}
