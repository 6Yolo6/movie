package com.gying.movie.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SeasonSearchUtils {

    private static final String NUMBER_TOKEN = "[0-9〇零一二两三四五六七八九十]+";
    private static final Pattern SEASON_SUFFIX = Pattern.compile(
            "^(.*?)(?:第\\s*(" + NUMBER_TOKEN + ")\\s*季|(?:season|s)\\s*0*([0-9]{1,2}))\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SEASON = Pattern.compile(
            "第\\s*(" + NUMBER_TOKEN + ")\\s*季|(?:season|s)\\s*0*([0-9]{1,2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON_RANGE = Pattern.compile(
            "(?:第\\s*)?(" + NUMBER_TOKEN + ")\\s*(?:[-~～至到])\\s*(" + NUMBER_TOKEN + ")\\s*季",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLETE_SEASONS = Pattern.compile(
            "全\\s*(" + NUMBER_TOKEN + ")\\s*季",
            Pattern.CASE_INSENSITIVE);

    private SeasonSearchUtils() {
    }

    public static SeasonQuery parse(String value) {
        if (!hasText(value)) {
            return null;
        }
        Matcher matcher = SEASON_SUFFIX.matcher(value.trim());
        if (!matcher.matches() || !hasText(matcher.group(1))) {
            return null;
        }
        String token = firstText(matcher.group(2), matcher.group(3));
        Integer season = parseNumber(token);
        if (season == null || season < 1 || season > 99) {
            return null;
        }
        return new SeasonQuery(matcher.group(1).trim(), season);
    }

    public static String baseTitle(String value) {
        SeasonQuery query = parse(value);
        return query == null ? value : query.baseTitle();
    }

    public static List<String> searchVariants(String canonicalTitle, String requestedKeyword) {
        Set<String> variants = new LinkedHashSet<>();
        SeasonQuery query = parse(requestedKeyword);
        if (query == null) {
            add(variants, requestedKeyword);
            return List.copyOf(variants);
        }

        String title = hasText(canonicalTitle) ? baseTitle(canonicalTitle).trim() : query.baseTitle();
        add(variants, requestedKeyword);
        add(variants, title + " 第" + query.season() + "季");
        add(variants, title + query.season());
        add(variants, title + " S" + String.format("%02d", query.season()));
        return List.copyOf(variants);
    }

    public static boolean matchesRequestedSeason(String resourceTitle, String requestedKeyword) {
        SeasonQuery requested = parse(requestedKeyword);
        if (requested == null) {
            return true;
        }
        if (!hasText(resourceTitle)) {
            return false;
        }

        Matcher rangeMatcher = SEASON_RANGE.matcher(resourceTitle);
        while (rangeMatcher.find()) {
            Integer start = parseNumber(rangeMatcher.group(1));
            Integer end = parseNumber(rangeMatcher.group(2));
            if (start != null && end != null
                    && requested.season() >= Math.min(start, end)
                    && requested.season() <= Math.max(start, end)) {
                return true;
            }
        }

        Matcher completeMatcher = COMPLETE_SEASONS.matcher(resourceTitle);
        while (completeMatcher.find()) {
            Integer end = parseNumber(completeMatcher.group(1));
            if (end != null && requested.season() <= end) {
                return true;
            }
        }

        List<Integer> explicitSeasons = new ArrayList<>();
        Matcher seasonMatcher = EXPLICIT_SEASON.matcher(resourceTitle);
        while (seasonMatcher.find()) {
            Integer season = parseNumber(firstText(seasonMatcher.group(1), seasonMatcher.group(2)));
            if (season != null) {
                explicitSeasons.add(season);
            }
        }
        if (!explicitSeasons.isEmpty()) {
            return explicitSeasons.contains(requested.season());
        }

        String compactTitle = compact(resourceTitle);
        String compactBase = compact(requested.baseTitle());
        return hasText(compactBase) && compactTitle.contains(compactBase + requested.season());
    }

    private static Integer parseNumber(String value) {
        if (!hasText(value)) {
            return null;
        }
        String token = value.trim().replace('〇', '零').replace('两', '二');
        if (token.matches("[0-9]+")) {
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        int tenIndex = token.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : digit(token.charAt(tenIndex - 1));
            int units = tenIndex + 1 >= token.length() ? 0 : digit(token.charAt(tenIndex + 1));
            return tens < 0 || units < 0 ? null : tens * 10 + units;
        }
        return token.length() == 1 ? nullableDigit(token.charAt(0)) : null;
    }

    private static Integer nullableDigit(char value) {
        int result = digit(value);
        return result < 0 ? null : result;
    }

    private static int digit(char value) {
        return switch (value) {
            case '零' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static String compact(String value) {
        return hasText(value)
                ? value.toLowerCase().replaceAll("[\\s\\p{Punct}，。！？、：；（）《》【】「」『』·]+", "")
                : "";
    }

    private static void add(Set<String> values, String value) {
        if (hasText(value)) {
            values.add(value.trim());
        }
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

    public record SeasonQuery(String baseTitle, int season) {
    }
}
