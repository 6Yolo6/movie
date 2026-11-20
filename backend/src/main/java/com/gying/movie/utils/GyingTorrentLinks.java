package com.gying.movie.utils;

import java.net.URI;
import java.util.Locale;

/** GYING's account-only download route, including mirror domains and rotating tickets. */
public final class GyingTorrentLinks {
    private GyingTorrentLinks() {}

    public static boolean isAccountDownload(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim()).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return false;
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            return path.equals("/dbt") || path.startsWith("/dbt/");
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return false;
        }
    }
}
