package com.gying.movie.dto;

import java.util.List;

/** Independent bounds for one provider catalog. */
public record MetadataCrawlRange(int startPage, int endPage) {
    public static int maxPages(String provider) {
        return switch (provider) {
            case "GYING" -> 50;
            case "TMDB" -> 500;
            default -> throw new IllegalArgumentException("Unknown metadata provider");
        };
    }

    public static List<String> sources(String provider) {
        return switch (provider) {
            case "TMDB" -> List.of("TRENDING_MOVIE_DAY", "TRENDING_TV_DAY", "POPULAR_MOVIE", "POPULAR_TV",
                    "TOP_RATED_MOVIE", "TOP_RATED_TV", "UPCOMING_MOVIE");
            case "GYING" -> List.of("HITS_MOVIE", "HITS_TV", "HITS_ANIME", "CSCORE_MOVIE", "CSCORE_TV", "CSCORE_ANIME",
                    "TIME_MOVIE", "TIME_TV", "TIME_ANIME", "SCORE_MOVIE", "SCORE_TV", "SCORE_ANIME");
            default -> throw new IllegalArgumentException("Unknown metadata provider");
        };
    }
}
