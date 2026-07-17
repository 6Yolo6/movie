package com.gying.movie.utils;

import java.net.URI;
import java.net.URISyntaxException;

public final class PosterUrlUtils {

    private PosterUrlUtils() {
    }

    public static String toPublicUrl(String posterUrl, String urlPrefix) {
        if (posterUrl == null || posterUrl.isBlank()) {
            return posterUrl;
        }
        String rawUrl = posterUrl.startsWith("http")
                ? posterUrl
                : joinUrl(urlPrefix, posterUrl);
        return replaceInternalHost(rawUrl);
    }

    private static String joinUrl(String prefix, String path) {
        if (prefix == null || prefix.isBlank()) {
            return path;
        }
        String normalizedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        return normalizedPrefix + normalizedPath;
    }

    private static String replaceInternalHost(String url) {
        try {
            URI uri = new URI(url);
            if (!"host.docker.internal".equalsIgnoreCase(uri.getHost())) {
                return url;
            }
            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    "localhost",
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()).toString();
        } catch (URISyntaxException ex) {
            return url.replace("://host.docker.internal", "://localhost");
        }
    }
}
