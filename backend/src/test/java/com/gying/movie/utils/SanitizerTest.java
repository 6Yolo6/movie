package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SanitizerTest {

    private final Sanitizer sanitizer = new Sanitizer();

    @Test
    void removesScriptsEventHandlersAndEmbeddedMedia() {
        String cleaned = sanitizer.sanitize(
                "<img src=x onerror=alert(1)><script>alert(1)</script><b>safe</b>");

        assertFalse(cleaned.contains("script"));
        assertFalse(cleaned.contains("onerror"));
        assertFalse(cleaned.contains("<img"));
        assertTrue(cleaned.contains("<b>safe</b>"));
    }

    @Test
    void rejectsJavascriptLinksAndHardensExternalLinks() {
        String cleaned = sanitizer.sanitize("<a href=\"javascript:alert(1)\" title=\"bad\">x</a>");

        assertFalse(cleaned.contains("javascript:"));
        assertTrue(cleaned.contains("nofollow noopener noreferrer") || !cleaned.contains("<a"));
        assertTrue(cleaned.contains("_blank") || !cleaned.contains("<a"));
    }
}
