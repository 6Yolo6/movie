package com.gying.movie.dto;

import java.util.List;

/** Independent bounds for one provider catalog. */
public record MetadataCrawlRange(int startPage, int endPage) {
    public static List<String> sources(String provider) {
        return switch (provider) {
            case "TMDB" -> List.of("TRENDING_MOVIE_DAY", "TRENDING_TV_DAY", "POPULAR_MOVIE", "POPULAR_TV",
                    "TOP_RATED_MOVIE", "TOP_RATED_TV", "UPCOMING_MOVIE");
            case "GYING" -> List.of("HITS_MOVIE", "HITS_TV", "HITS_ANIME", "CSCORE_MOVIE", "CSCORE_TV", "CSCORE_ANIME");
            default -> throw new IllegalArgumentException("Unknown metadata provider");
        };
    }
}
