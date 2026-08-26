package com.gying.movie.utils;

import com.gying.movie.dto.MovieSearchCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MovieSearchCandidateUtils {

    private MovieSearchCandidateUtils() {
    }

    public static List<MovieSearchCandidate> merge(
            List<MovieSearchCandidate> localCandidates,
            List<MovieSearchCandidate> tmdbCandidates,
            int limit) {
        Map<String, MovieSearchCandidate> unique = new LinkedHashMap<>();
        addAll(unique, localCandidates);
        addAll(unique, tmdbCandidates);
        return unique.values().stream()
                .sorted(Comparator.comparingInt(MovieSearchCandidateUtils::sourcePriority).reversed()
                        .thenComparing(Comparator.comparingInt(MovieSearchCandidate::getScore).reversed())
                        .thenComparing(candidate -> firstText(candidate.getTitle(), candidate.getOriginalTitle(), "")))
                .limit(Math.max(limit, 1))
                .toList();
    }

    public static List<MovieSearchCandidate> mergeBalanced(
            List<MovieSearchCandidate> gyingCandidates,
            List<MovieSearchCandidate> fallbackCandidates,
            int limit,
            int maxGying) {
        int safeLimit = Math.max(limit, 1);
        int safeGyingLimit = Math.min(Math.max(maxGying, 0), safeLimit);
        Map<String, MovieSearchCandidate> unique = new LinkedHashMap<>();
        addAll(unique, gyingCandidates);
        addAll(unique, fallbackCandidates);
        Comparator<MovieSearchCandidate> ordering = Comparator
                .comparingInt(MovieSearchCandidate::getScore).reversed()
                .thenComparing(candidate -> firstText(candidate.getTitle(), candidate.getOriginalTitle(), ""));
        List<MovieSearchCandidate> gying = unique.values().stream()
                .filter(candidate -> "GYING".equalsIgnoreCase(candidate.getSource()))
                .sorted(ordering)
                .limit(safeGyingLimit)
                .toList();
        List<MovieSearchCandidate> fallback = unique.values().stream()
                .filter(candidate -> !"GYING".equalsIgnoreCase(candidate.getSource()))
                .sorted(Comparator.comparingInt(MovieSearchCandidateUtils::sourcePriority).reversed()
                        .thenComparing(ordering))
                .toList();
        List<MovieSearchCandidate> result = new ArrayList<>();
        int gyingIndex = 0;
        int fallbackIndex = 0;
        while (result.size() < safeLimit
                && (gyingIndex < gying.size() || fallbackIndex < fallback.size())) {
            if (gyingIndex < gying.size()) {
                result.add(gying.get(gyingIndex++));
            }
            if (result.size() < safeLimit && fallbackIndex < fallback.size()) {
                result.add(fallback.get(fallbackIndex++));
            }
        }
        return List.copyOf(result);
    }

    public static String formatReply(String keyword, List<MovieSearchCandidate> candidates) {
        StringBuilder reply = new StringBuilder("请选择要搜索的影片：");
        int index = 0;
        for (MovieSearchCandidate candidate : candidates) {
            String title = firstText(candidate.getTitle(), candidate.getOriginalTitle());
            if (!hasText(title)) {
                continue;
            }
            reply.append("\n").append(++index).append(". ")
                    .append("tv".equalsIgnoreCase(candidate.getMediaType()) ? "剧集 " : "电影 ")
                    .append(title);
            if (candidate.getYear() != null) {
                reply.append(" (").append(candidate.getYear()).append(")");
            }
            if ("GYING".equalsIgnoreCase(candidate.getSource())) {
                reply.append(" [GYING]");
            }
        }
        reply.append("\n\n直接回复序号即可，例如：1");
        return reply.toString();
    }

    public static String selectionTitle(List<MovieSearchCandidate> candidates, int oneBasedIndex) {
        if (candidates == null || oneBasedIndex < 1 || oneBasedIndex > candidates.size()) {
            return null;
        }
        MovieSearchCandidate candidate = candidates.get(oneBasedIndex - 1);
        return candidate == null ? null : firstText(candidate.getTitle(), candidate.getOriginalTitle());
    }

    public static List<String> searchableTitles(List<MovieSearchCandidate> candidates) {
        List<String> titles = new ArrayList<>();
        for (MovieSearchCandidate candidate : candidates) {
            addTitle(titles, candidate.getTitle());
            addTitle(titles, candidate.getOriginalTitle());
        }
        return titles;
    }

    private static void addAll(Map<String, MovieSearchCandidate> unique, List<MovieSearchCandidate> candidates) {
        if (candidates == null) {
            return;
        }
        for (MovieSearchCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String title = firstText(candidate.getTitle(), candidate.getOriginalTitle());
            if (!hasText(title)) {
                continue;
            }
            String key = normalize(title) + "|" + candidate.getYear() + "|" + candidate.getMediaType();
            MovieSearchCandidate existing = unique.get(key);
            if (existing == null
                    || sourcePriority(candidate) > sourcePriority(existing)
                    || (sourcePriority(candidate) == sourcePriority(existing)
                    && candidate.getScore() > existing.getScore())) {
                unique.put(key, candidate);
            }
        }
    }

    private static int sourcePriority(MovieSearchCandidate candidate) {
        if (candidate == null || !hasText(candidate.getSource())) {
            return 0;
        }
        return switch (candidate.getSource().trim().toUpperCase()) {
            case "GYING" -> 3;
            case "LOCAL" -> 2;
            case "TMDB" -> 1;
            default -> 0;
        };
    }

    private static void addTitle(List<String> titles, String value) {
        if (hasText(value) && titles.stream().noneMatch(existing -> normalize(existing).equals(normalize(value)))) {
            titles.add(value.trim());
        }
    }

    private static String normalize(String value) {
        return hasText(value)
                ? value.trim().toLowerCase().replaceAll("[\\s\\p{Punct}，。！？、：；（）《》【】「」『』·]+", "")
                : "";
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
