package com.gying.movie.utils;

import com.gying.movie.entity.MovieMetadata;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResourceTitleMatcher {

    private static final Pattern INTRO_MARKER = Pattern.compile(
            "(?:\\r?\\n|📜\\s*介绍|[【\\[]?简介[】\\]]?\\s*[：:]|[【\\[]?介绍[】\\]]?\\s*[：:])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_NUMBER = Pattern.compile("([0-9]+)$");

    private ResourceTitleMatcher() {
    }

    public static boolean isRelevant(MovieMetadata movie, String resourceTitle, String keyword) {
        String normalizedResourceTitle = normalizeTitle(extractHeadline(resourceTitle));
        if (!hasText(normalizedResourceTitle) || movie == null) {
            return false;
        }

        String normalizedKeyword = normalizeTitle(keyword);
        String sequelNumber = trailingNumber(normalizedKeyword);
        if (hasText(sequelNumber)
                && !containsSequencedTitle(normalizedResourceTitle, normalizedKeyword, sequelNumber)) {
            return false;
        }

        Set<String> expectedTitles = new LinkedHashSet<>();
        addCandidate(expectedTitles, normalizedKeyword);
        addCandidate(expectedTitles, normalizeTitle(movie.getTitleCn()));
        addCandidate(expectedTitles, normalizeTitle(movie.getTitleEn()));
        addCandidate(expectedTitles, normalizeTitle(movie.getSeriesName()));
        for (String alias : splitAliases(movie.getAliases())) {
            addCandidate(expectedTitles, normalizeTitle(alias));
        }
        for (String expected : expectedTitles) {
            if (expected.length() >= 2 && normalizedResourceTitle.contains(expected)) {
                return true;
            }
            if (normalizedResourceTitle.length() >= 4 && expected.contains(normalizedResourceTitle)) {
                return true;
            }
        }
        return false;
    }

    static String extractHeadline(String value) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        Matcher matcher = INTRO_MARKER.matcher(trimmed);
        if (matcher.find()) {
            trimmed = trimmed.substring(0, matcher.start());
        }
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180);
    }

    static String normalizeTitle(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[\\s\\p{Punct}，。！？、：；（）《》【】「」『』·]+", "");
    }

    private static String trailingNumber(String value) {
        if (!hasText(value)) {
            return null;
        }
        Matcher matcher = TRAILING_NUMBER.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean containsSequencedTitle(String resourceTitle, String keyword, String sequelNumber) {
        if (resourceTitle.contains(keyword)) {
            return true;
        }
        String baseTitle = keyword.substring(0, keyword.length() - sequelNumber.length());
        if (!hasText(baseTitle) || !resourceTitle.contains(baseTitle)) {
            return false;
        }
        int start = resourceTitle.indexOf(baseTitle) + baseTitle.length();
        int end = Math.min(resourceTitle.length(), start + 6);
        return resourceTitle.substring(start, end).contains(sequelNumber);
    }

    private static List<String> splitAliases(String aliases) {
        if (!hasText(aliases)) {
            return List.of();
        }
        return java.util.Arrays.stream(aliases.split("[/|,，;；]+"))
                .map(String::trim)
                .filter(ResourceTitleMatcher::hasText)
                .toList();
    }

    private static void addCandidate(Set<String> candidates, String value) {
        if (hasText(value)) {
            candidates.add(value);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
