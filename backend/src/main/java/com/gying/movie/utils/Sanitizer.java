package com.gying.movie.utils;

import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class Sanitizer {

    private static final Safelist SAFE_WHITELIST = Safelist.relaxed()
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "https", "http", "mailto");

    /**
     * Sanitize user input to prevent XSS attacks.
     * Only allows safe HTML tags (b, i, em, strong, p, br, ul, ol, li, a).
     */
    public String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        return org.jsoup.Jsoup.clean(input, SAFE_WHITELIST);
    }
}
