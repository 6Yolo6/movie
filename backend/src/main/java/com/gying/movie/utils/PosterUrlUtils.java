package com.gying.movie.utils;

import java.net.URI;
import java.net.URISyntaxException;

public final class PosterUrlUtils {

    private PosterUrlUtils() {
    }

    public static String toPublicUrl(String posterUrl, String urlPrefix) {
        if (!hasText(posterUrl)) {
            return posterUrl;
        }
        String value = normalizeSlashes(posterUrl);
        if (hasScheme(value)) {
            return replaceInternalHost(value);
        }

        String prefix = normalizedPrefix(urlPrefix);
        if (!hasText(prefix)) {
            return value;
        }

        String objectKey = stripRepeatedPrefix(value, prefix);
        if (objectKey == null) {
            objectKey = stripRepeatedPrefix(value, relativePrefix(prefix));
        }
        return joinUrl(prefix, objectKey == null ? value : objectKey);
    }

    /**
     * Converts either a stored object key or the public URL shown by the API back
     * to the canonical object key stored in movie_metadata.poster_url.
     */
    public static String toStoragePath(String posterUrl, String urlPrefix) {
        if (!hasText(posterUrl)) {
            return null;
        }
        String value = normalizeSlashes(posterUrl);
        String prefix = normalizedPrefix(urlPrefix);
        if (hasText(prefix)) {
            String objectKey = stripRepeatedPrefix(value, prefix);
            if (objectKey == null) {
                objectKey = stripRepeatedPrefix(value, relativePrefix(prefix));
            }
            if (objectKey != null) {
                return emptyToNull(normalizeObjectKey(objectKey));
            }
        }
        if (hasScheme(value)) {
            return value;
        }
        return emptyToNull(normalizeObjectKey(value));
    }

    private static String normalizedPrefix(String prefix) {
        if (!hasText(prefix)) {
            return "";
        }
        String value = normalizeSlashes(prefix);
        if (!hasScheme(value)) {
            while (value.contains("//")) {
                value = value.replace("//", "/");
            }
        }
        return value.endsWith("/") ? value : value + "/";
    }

    private static String relativePrefix(String prefix) {
        if (!hasText(prefix) || !hasScheme(prefix)) {
            return prefix;
        }
        try {
            String path = new URI(prefix).getPath();
            return hasText(path) ? path : "";
        } catch (URISyntaxException ignored) {
            int scheme = prefix.indexOf("://");
            int path = scheme < 0 ? -1 : prefix.indexOf('/', scheme + 3);
            return path < 0 ? "" : prefix.substring(path);
        }
    }

    private static String stripRepeatedPrefix(String value, String prefix) {
        if (!hasText(value) || !hasText(prefix)) {
            return null;
        }
        String relative = relativePrefix(prefix);
        String result = value;
        boolean stripped = false;
        while (true) {
            if (startsWithIgnoreCase(result, prefix)) {
                result = result.substring(prefix.length());
                stripped = true;
                continue;
            }
            String path = result.startsWith("/") ? result : "/" + result;
            if (hasText(relative) && startsWithIgnoreCase(path, relative)) {
                result = path.substring(relative.length());
                stripped = true;
                continue;
            }
            break;
        }
        return stripped ? normalizeObjectKey(result) : null;
    }
    private static String normalizeObjectKey(String value) {
        String normalized = value == null ? "" : normalizeSlashes(value);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String emptyToNull(String value) {
        return hasText(value) ? value : null;
    }

    private static String normalizeSlashes(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value != null && prefix != null && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean hasScheme(String value) {
        return value != null && value.matches("(?i)^[a-z][a-z0-9+.-]*://.*");
    }

    private static String joinUrl(String prefix, String path) {
        if (!hasText(prefix)) {
            return path;
        }
        String normalizedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        return normalizedPrefix + stripLeadingSlashes(path);
    }

    private static String stripLeadingSlashes(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
