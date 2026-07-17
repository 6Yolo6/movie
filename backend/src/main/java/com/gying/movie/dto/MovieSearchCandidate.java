package com.gying.movie.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovieSearchCandidate {
    private Long tmdbId;
    private String mediaType;
    private String title;
    private String originalTitle;
    private Integer year;
    private int score;
}
