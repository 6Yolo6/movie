package com.gying.movie.dto;

import lombok.Data;

@Data
public class TmdbListItem {
    private Long tmdbId;
    private String mediaType;
    private String title;
    private String originalTitle;
    private String releaseDate;
    private Double popularity;
}
