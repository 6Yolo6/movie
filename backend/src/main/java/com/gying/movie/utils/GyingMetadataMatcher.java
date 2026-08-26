package com.gying.movie.utils;

import com.gying.movie.entity.MovieMetadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GyingMetadataMatcher {

    private GyingMetadataMatcher() {
    }

    public static MatchEvidence score(MovieMetadata movie, SourceMetadata source) {
        if (movie == null || source == null || !MovieTitleMatcher.isExactMatch(movie, source.title())) {
            return new MatchEvidence(0, false, List.of("TITLE_MISMATCH"));
        }

        List<String> reasons = new ArrayList<>();
        int score = 50;
        reasons.add("EXACT_TITLE");

        if (typeCompatible(movie.getCategory(), source.typeCode())) {
            score += movie.getCategory() != null && movie.getCategory().equalsIgnoreCase(source.typeCode()) ? 15 : 10;
            reasons.add("TYPE");
        } else {
            return new MatchEvidence(0, false, List.of("TYPE_MISMATCH"));
        }

        Integer sourceSeason = source.season();
        Integer movieSeason = movie.getSeason();
        if (!"mv".equalsIgnoreCase(source.typeCode())
                && sourceSeason != null && movieSeason != null) {
            if (!sourceSeason.equals(movieSeason)) {
                return new MatchEvidence(0, false, List.of("SEASON_MISMATCH"));
            }
            score += 20;
            reasons.add("SEASON");
        }

        if (source.year() != null && movie.getYear() != null) {
            int difference = Math.abs(source.year() - movie.getYear());
            if (difference == 0) {
                score += 15;
                reasons.add("YEAR");
            } else if (difference == 1) {
                score += 8;
                reasons.add("YEAR_NEAR");
            } else if ("mv".equalsIgnoreCase(source.typeCode())) {
                return new MatchEvidence(0, false, List.of("YEAR_MISMATCH"));
            }
        }

        if (overlaps(movie.getDirectors(), source.directors())) {
            score += 10;
            reasons.add("DIRECTOR");
        }
        if (overlaps(movie.getActors(), source.actors())) {
            score += 5;
            reasons.add("ACTOR");
        }

        boolean hasAnchor = reasons.contains("YEAR")
                || reasons.contains("YEAR_NEAR")
                || reasons.contains("SEASON")
                || reasons.contains("DIRECTOR")
                || reasons.contains("ACTOR");
        return new MatchEvidence(Math.min(score, 100), score >= 75 && hasAnchor, List.copyOf(reasons));
    }

    public static boolean typeCompatible(String category, String typeCode) {
        if (!hasText(category) || !hasText(typeCode)) {
            return false;
        }
        String left = category.toLowerCase(Locale.ROOT);
        String right = typeCode.toLowerCase(Locale.ROOT);
        return left.equals(right) || Set.of(left, right).equals(Set.of("tv", "ac"));
    }

    private static boolean overlaps(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        Set<String> normalized = new HashSet<>();
        left.stream().filter(GyingMetadataMatcher::hasText).map(GyingMetadataMatcher::normalize).forEach(normalized::add);
        return right.stream().filter(GyingMetadataMatcher::hasText)
                .map(GyingMetadataMatcher::normalize)
                .anyMatch(normalized::contains);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、：；（）《》【】「」『』·]+", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record SourceMetadata(
            String typeCode,
            String title,
            Integer year,
            Integer season,
            List<String> directors,
            List<String> actors) {
    }

    public record MatchEvidence(int score, boolean autoMatch, List<String> reasons) {
    }
}
