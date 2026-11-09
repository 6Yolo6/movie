package com.gying.movie.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.entity.MovieMetadata;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative name candidates for review; never an authorization to merge. */
public final class MovieDuplicateMatcher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SEASON = Pattern.compile(
            "^(.*?)(?:第\\s*([0-9〇零一二两三四五六七八九十]+)\\s*季|\\s+(?:season\\s*|s)0*([0-9]{1,2}))\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile(
            "导演剪辑版|加长版|剧场版|特别篇|重制版|复刻版|tv版|director['’]?s?\\s+cut|extended|remaster(?:ed)?|theatrical|special",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_BUCKET = 100;
    private static final int MAX_PAIRS = 5000;
    private static final int MAX_COMPARISONS = 50000;

    private MovieDuplicateMatcher() { }

    public static Result find(List<MovieMetadata> movies) {
        Map<Key, List<Entry>> index = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<Pair> pairs = new ArrayList<>();
        int excluded = 0, conflicts = 0, crowded = 0;
        boolean complete = true;
        scan: for (MovieMetadata movie : movies) {
            Entry entry = entry(movie);
            if (entry == null) { excluded++; continue; }
            for (String title : entry.titles) {
                Key key = new Key(entry.category, movie.getYear(), entry.season, title);
                List<Entry> bucket = index.computeIfAbsent(key, ignored -> new ArrayList<>());
                if (bucket.size() >= MAX_BUCKET) { crowded++; complete = false; continue; }
                for (Entry other : bucket) {
                    String pairKey = other.movie.getId().compareTo(movie.getId()) < 0
                            ? other.movie.getId() + "\u0000" + movie.getId()
                            : movie.getId() + "\u0000" + other.movie.getId();
                    if (seen.size() >= MAX_COMPARISONS) { complete = false; break scan; }
                    if (!seen.add(pairKey)) continue;
                    boolean peopleSupported = overlaps(entry.people, other.people);
                    if (!entry.versions.equals(other.versions)
                            || (!entry.people.isEmpty() && !other.people.isEmpty() && !peopleSupported)) {
                        conflicts++; continue;
                    }
                    if (pairs.size() >= MAX_PAIRS) { complete = false; break scan; }
                    pairs.add(new Pair(other.movie, movie, title, entry.category, entry.season, peopleSupported));
                }
                bucket.add(entry);
            }
        }
        pairs.sort(java.util.Comparator.comparing((Pair pair) -> pair.left.getId())
                .thenComparing(pair -> pair.right.getId()));
        return new Result(List.copyOf(pairs), excluded, conflicts, crowded, complete);
    }

    private static Entry entry(MovieMetadata movie) {
        if (movie == null || blank(movie.getId()) || "DELETED".equalsIgnoreCase(movie.getStatus())
                || movie.getDeletedAt() != null || movie.getYear() == null) return null;
        String category = category(movie.getCategory());
        if (category == null) return null;
        String type = category(movie.getTmdbType());
        if (type != null && !category.equals("ac") && !category.equals(type)) return null;
        List<String> names = new ArrayList<>();
        add(names, movie.getTitleCn()); add(names, movie.getTitleEn()); add(names, movie.getSeriesName());
        names.addAll(aliases(movie.getAliases()));
        boolean serial = "tv".equals(category) || ("ac".equals(category)
                && ("tv".equals(type) || (movie.getSeason() != null && movie.getSeason() > 0)
                        || names.stream().anyMatch(name -> SEASON.matcher(name.trim()).matches())));
        if ("ac".equals(category) && type == null && !serial) return null;
        if ("ac".equals(category) && "mv".equals(type) && serial) return null;
        Integer season = serial ? movie.getSeason() : 0;
        Set<String> titles = new LinkedHashSet<>();
        for (String name : names) {
            String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).trim();
            if (SeasonSearchUtils.isCollectionResource(normalized)) return null;
            var suffix = SEASON.matcher(normalized);
            if (suffix.matches() && !blank(suffix.group(1))) {
                var parsed = SeasonSearchUtils.parse(normalized);
                if (parsed == null) return null;
                if (!serial) return null;
                if (season != null && !season.equals(parsed.season())) return null;
                season = parsed.season();
                normalized = suffix.group(1);
            }
            String key = compact(normalized);
            if (key.codePointCount(0, key.length()) >= 2
                    && !Set.of("unknown", "暂无", "无", "null").contains(key)) titles.add(key);
        }
        // An unspecified TV/anime season must not be guessed to be season one.
        if (season == null || season < 0 || season > 99 || (serial && season == 0)
                || titles.isEmpty()) return null;
        Set<String> versions = new HashSet<>();
        for (String name : List.of(text(movie.getTitleCn()), text(movie.getTitleEn()))) {
            var version = VERSION.matcher(name);
            while (version.find()) versions.add(compact(version.group()));
        }
        Set<String> people = new HashSet<>();
        if (movie.getActors() != null) movie.getActors().stream().filter(value -> !blank(value)).map(MovieDuplicateMatcher::compact).forEach(people::add);
        if (movie.getDirectors() != null) movie.getDirectors().stream().filter(value -> !blank(value)).map(MovieDuplicateMatcher::compact).forEach(people::add);
        return new Entry(movie, category, season, titles, versions, people);
    }

    private static List<String> aliases(String value) {
        if (blank(value)) return List.of();
        List<String> aliases = new ArrayList<>();
        if (value.trim().startsWith("[")) {
            try {
                JsonNode node = JSON.readTree(value);
                if (node.isArray()) for (JsonNode item : node) if (item.isTextual()) add(aliases, item.asText());
                return aliases;
            } catch (Exception ignored) { return List.of(); }
        }
        for (String alias : value.split("[/|;；、\\r\\n]+")) add(aliases, alias);
        return aliases;
    }

    private static String category(String value) {
        return switch (text(value).trim().toLowerCase(Locale.ROOT)) {
            case "mv", "movie", "film" -> "mv";
            case "tv", "series", "show", "drama" -> "tv";
            case "ac", "anime", "animation" -> "ac";
            default -> null;
        };
    }
    private static boolean overlaps(Set<String> left, Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }
    private static String compact(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('’', '\'').replaceAll("[\\s\\p{Z}]+", "");
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void add(List<String> values, String value) { if (!blank(value)) values.add(value.trim()); }
    private record Key(String category, int year, int season, String title) { }
    private record Entry(MovieMetadata movie, String category, Integer season, Set<String> titles, Set<String> versions, Set<String> people) { }
    public record Pair(MovieMetadata left, MovieMetadata right, String title, String category, int season, boolean peopleSupported) { }
    public record Result(List<Pair> pairs, int excludedMovies, int conflicts, int crowdedBuckets, boolean complete) { }
}
