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

    @Test
    void seasonKeywordOnlyMatchesRequestedSeason() {
        MovieMetadata first = movie("\u6743\u529b\u7684\u6e38\u620f \u7b2c\u4e00\u5b63", "Game of Thrones", null);
        first.setSeason(1);
        MovieMetadata eighth = movie("\u6743\u529b\u7684\u6e38\u620f \u7b2c\u516b\u5b63", "Game of Thrones", null);
        eighth.setSeason(8);

        String keyword = "\u6743\u529b\u7684\u6e38\u620f\u7b2c\u516b\u5b63";
        assertFalse(MovieTitleMatcher.isExactMatch(first, keyword));
        assertTrue(MovieTitleMatcher.isExactMatch(eighth, keyword));
    }

    private MovieMetadata movie(String titleCn, String titleEn, String aliases) {
        MovieMetadata movie = new MovieMetadata();
        movie.setTitleCn(titleCn);
        movie.setTitleEn(titleEn);
        movie.setAliases(aliases);
        return movie;
    }
}
