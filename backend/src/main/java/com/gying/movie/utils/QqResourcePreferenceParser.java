package com.gying.movie.utils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QqResourcePreferenceParser {

    public static final String ALL = "ALL";
    private static final Pattern TRAILING_COUNT = Pattern.compile("([0-9]{1,2})(?:条|个)?$");
    private static final Map<String, String> PROVIDER_ALIASES = aliases();
    private static final Map<String, String> PROVIDER_LABELS = Map.ofEntries(
            Map.entry("QUARK", "夸克"),
            Map.entry("BAIDU", "百度"),
            Map.entry("ALIYUN", "阿里"),
            Map.entry("UC", "UC"),
            Map.entry("XUNLEI", "迅雷"),
            Map.entry("115", "115"),
            Map.entry("123PAN", "123"),
            Map.entry("PIKPAK", "PikPak"),
            Map.entry("TIANYI", "天翼"),
            Map.entry("MOBILE", "移动"),
            Map.entry(ALL, "全部网盘"));

    private QqResourcePreferenceParser() {
    }

    public static ResourcePreference parse(String value, int defaultCount, int maxCount) {
        if (!hasText(value)) {
            return null;
        }
        String compact = normalize(value);
        Matcher countMatcher = TRAILING_COUNT.matcher(compact);
        if (countMatcher.find()) {
            compact = compact.substring(0, countMatcher.start());
        }
        compact = stripCommandWords(compact);
        String provider = PROVIDER_ALIASES.get(compact);
        if (provider == null) {
            return null;
        }
        int safeMax = Math.max(maxCount, 1);
        int count = Math.min(Math.max(defaultCount, 1), safeMax);
        return new ResourcePreference(provider, count);
    }

    public static boolean hasExplicitCount(String value) {
        if (!hasText(value)) {
            return false;
        }
        return TRAILING_COUNT.matcher(normalize(value)).find();
    }

    public static Set<String> supportedProviders() {
        return new LinkedHashSet<>(List.of(
                "QUARK", "BAIDU", "ALIYUN", "UC", "XUNLEI",
                "115", "123PAN", "PIKPAK", "TIANYI", "MOBILE"));
    }

    public static Set<String> fallbackProviders() {
        Set<String> providers = supportedProviders();
        providers.remove("QUARK");
        return providers;
    }

    public static String label(String provider) {
        return PROVIDER_LABELS.getOrDefault(provider, provider);
    }

    private static String stripCommandWords(String value) {
        String result = value;
        if (result.startsWith("网盘")) {
            result = result.substring(2);
        } else if (result.startsWith("云盘")) {
            result = result.substring(2);
        }
        if (result.length() > 2 && result.endsWith("链接")) {
            result = result.substring(0, result.length() - 2);
        } else if (result.length() > 2 && result.endsWith("资源")) {
            result = result.substring(0, result.length() - 2);
        }
        return result;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、：；（）《》【】「」『』·]+", "");
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        addAliases(aliases, "QUARK", "夸克", "夸克网盘", "quark");
        addAliases(aliases, "BAIDU", "百度", "百度网盘", "百度云", "baidu");
        addAliases(aliases, "ALIYUN", "阿里", "阿里云", "阿里云盘", "阿里网盘", "alipan", "aliyun");
        addAliases(aliases, "UC", "uc", "uc网盘", "uc云盘");
        addAliases(aliases, "XUNLEI", "迅雷", "迅雷网盘", "xunlei");
        addAliases(aliases, "115", "115", "115网盘", "115云盘");
        addAliases(aliases, "123PAN", "123", "123网盘", "123云盘", "123pan");
        addAliases(aliases, "PIKPAK", "pikpak");
        addAliases(aliases, "TIANYI", "天翼", "天翼网盘", "天翼云盘");
        addAliases(aliases, "MOBILE", "移动", "移动网盘", "移动云盘", "中国移动云盘");
        addAliases(aliases, ALL, "全部", "所有", "任意", "综合", "资源", "更多", "all");
        return Map.copyOf(aliases);
    }

    private static void addAliases(Map<String, String> aliases, String provider, String... values) {
        for (String value : values) {
            aliases.put(normalize(value), provider);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ResourcePreference(String provider, int count) {
    }
}
