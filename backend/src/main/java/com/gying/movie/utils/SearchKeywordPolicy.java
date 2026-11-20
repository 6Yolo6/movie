package com.gying.movie.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared website/QQ policy. Empty admin settings do not disable the built-in dictionary. */
public final class SearchKeywordPolicy {
    private static final List<String> BUILT_IN_WORDS;
    private static final List<Pattern> BUILT_IN_TOKENS;
    static {
        List<String> words = new ArrayList<>();
        List<Pattern> tokens = new ArrayList<>();
        try (var input = SearchKeywordPolicy.class.getResourceAsStream("/moderation/search-blocklist.txt")) {
            if (input == null) throw new IllegalStateException("Required search blocklist is missing");
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("@token ")) {
                        tokens.add(Pattern.compile("(?<![a-z])" + Pattern.quote(normalize(line.substring(7))) + "(?![a-z])"));
                    } else {
                        String term = normalize(line);
                        if (!term.isEmpty()) words.add(term);
                    }
                }
            }
        } catch (Exception error) {
            // Fail closed rather than silently dropping the production policy.
            throw new ExceptionInInitializerError(error);
        }
        BUILT_IN_WORDS = words.stream().distinct().toList();
        BUILT_IN_TOKENS = List.copyOf(tokens);
    }
    private SearchKeywordPolicy() {}

    public static boolean blocked(String value, String configured) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;
        if (BUILT_IN_WORDS.stream().anyMatch(normalized::contains)
                || BUILT_IN_TOKENS.stream().anyMatch(pattern -> pattern.matcher(normalized).find())) return true;
        for (String word : (configured == null ? "" : configured).split("[,，|;；、\\n\\r]+")) {
            String term = normalize(word);
            if (!term.isEmpty() && normalized.contains(term)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{Z}\\p{C}\\p{P}\\p{S}\\s]+", "");
    }
}
