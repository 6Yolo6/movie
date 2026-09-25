package com.gying.movie.utils;

import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class Sanitizer {

    private static final Safelist SAFE_WHITELIST = new Safelist()
            .addTags("b", "strong", "i", "em", "u", "p", "br", "ul", "ol", "li", "blockquote", "code", "pre", "a")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "https", "http", "mailto")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
            .addEnforcedAttribute("a", "target", "_blank");

    /**
     * Sanitize user input to prevent XSS attacks.
     * Only allows a small text-formatting whitelist; scripts, event handlers and embedded media are removed.
     */
    public String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        return org.jsoup.Jsoup.clean(input, SAFE_WHITELIST);
    }
}
