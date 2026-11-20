package com.gying.movie.utils;

import com.gying.movie.client.BaiduActionException;
import java.util.Locale;

/** New permanent Baidu folders share the canonical catalog category layout. */
public final class BaiduArchivePaths {
    private BaiduArchivePaths() { }

    public static String categoryRoot(String base, String category) {
        String segment = switch (category == null ? "" : category.trim().toLowerCase(Locale.ROOT)) {
            case "mv", "movie" -> "mv";
            case "tv" -> "tv";
            case "ac", "anime" -> "ac";
            default -> throw new BaiduActionException("CATEGORY_REQUIRED", false, true, false);
        };
        String root = base == null || base.isBlank() ? "/GYing Resource Hub" : base;
        String result = root.replaceAll("/+$", "") + "/" + segment;
        if (!safePath(result)) throw new BaiduActionException("PATH_INVALID", false, true, false);
        return result;
    }

    /** Reuse an existing movie folder only inside its current permanent category. */
    public static boolean belongsToCategory(String base, String category, String path) {
        return path != null && path.startsWith(categoryRoot(base, category) + "/") && safePath(path);
    }

    private static boolean safePath(String path) {
        if (!path.startsWith("/") || path.length() > 300 || path.contains("\\")
                || path.chars().anyMatch(Character::isISOControl)) return false;
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.isBlank() || segment.length() > 255 || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }
}
