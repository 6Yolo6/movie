package com.gying.movie.utils;

import java.util.Locale;
import java.util.Set;

/** Exact task paths; aliases and permanent archive roots are rejected instead of normalized. */
public final class BaiduTemporaryPaths {
    public static final String DEFAULT_ROOT = "/GYing QQ Temp";
    private static final Set<String> RESERVED = Set.of("mv", "tv", "ac", "磁力种子", "gying resource hub");
    private BaiduTemporaryPaths() { }

    public static String requireRoot(String value) {
        if (value == null || !value.startsWith("/") || value.length() > 250 || "/".equals(value)
                || !value.equals(value.trim()) || value.endsWith("/") || value.contains("//")
                || value.contains("\\") || value.contains("%") || value.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("BAIDU_TEMP_ROOT_INVALID");
        }
        for (String segment : value.substring(1).split("/", -1)) {
            if (segment.isBlank() || !segment.equals(segment.trim()) || ".".equals(segment) || "..".equals(segment)
                    || RESERVED.contains(segment.toLowerCase(Locale.ROOT)) || segment.matches("baidu-[0-9]+")) {
                throw new IllegalArgumentException("BAIDU_TEMP_ROOT_INVALID");
            }
        }
        return value;
    }

    public static String target(String root, Long taskId) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("BAIDU_TEMP_TASK_INVALID");
        return requireRoot(root) + "/baidu-" + taskId;
    }

    public static long requireTarget(String path) {
        if (path == null) throw new IllegalArgumentException("BAIDU_TEMP_PATH_INVALID");
        int slash = path.lastIndexOf('/');
        if (slash < 1 || !path.substring(slash + 1).matches("baidu-[1-9][0-9]*")) {
            throw new IllegalArgumentException("BAIDU_TEMP_PATH_INVALID");
        }
        requireRoot(path.substring(0, slash));
        try { return Long.parseLong(path.substring(slash + 7)); }
        catch (NumberFormatException ignored) { throw new IllegalArgumentException("BAIDU_TEMP_TASK_INVALID"); }
    }
}
