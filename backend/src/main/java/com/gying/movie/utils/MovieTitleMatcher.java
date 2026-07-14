package com.gying.movie.utils;

import com.gying.movie.entity.MovieMetadata;
import java.util.Arrays;

public final class MovieTitleMatcher {

    private MovieTitleMatcher() {
    }

    public static boolean isExactMatch(MovieMetadata movie, String keyword) {
        if (movie == null || !hasText(keyword)) {
            return false;
        }
        String expected = normalize(keyword);
        if (matches(expected, movie.getTitleCn())
                || matches(expected, movie.getTitleEn())
                || matches(expected, movie.getSeriesName())) {
            return true;
        }
        if (!hasText(movie.getAliases())) {
            return false;
        }
        return Arrays.stream(movie.getAliases().split("[/|,，;；]+"))
                .anyMatch(alias -> matches(expected, alias));
    }

    public static boolean normalizedEquals(String left, String right) {
        return hasText(left) && hasText(right) && normalize(left).equals(normalize(right));
    }

    private static boolean matches(String expected, String candidate) {
        return hasText(candidate) && expected.equals(normalize(candidate));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase()
                .replaceAll("[\\s\\p{Punct}，。！？、：；（）《》【】「」『』·]+", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
