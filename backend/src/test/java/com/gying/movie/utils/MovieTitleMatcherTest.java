package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gying.movie.entity.MovieMetadata;
import org.junit.jupiter.api.Test;

class MovieTitleMatcherTest {

    @Test
    void fuzzyPrefixDoesNotCountAsExactTitle() {
        MovieMetadata movie = movie("福尔摩斯小姐3", "Enola Holmes 3", null);

        assertFalse(MovieTitleMatcher.isExactMatch(movie, "福尔摩斯"));
        assertTrue(MovieTitleMatcher.isExactMatch(movie, "福尔摩斯小姐3"));
    }

    @Test
    void individualAliasCanMatchExactly() {
        MovieMetadata movie = movie("神探夏洛克", "Sherlock", "新福尔摩斯 / 新世纪福尔摩斯");

        assertTrue(MovieTitleMatcher.isExactMatch(movie, "新福尔摩斯"));
        assertFalse(MovieTitleMatcher.isExactMatch(movie, "福尔摩斯"));
    }

    private MovieMetadata movie(String titleCn, String titleEn, String aliases) {
        MovieMetadata movie = new MovieMetadata();
        movie.setTitleCn(titleCn);
        movie.setTitleEn(titleEn);
        movie.setAliases(aliases);
        return movie;
    }
}
