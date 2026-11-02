package com.gying.movie.dto;

import java.util.List;

/** A validated TMDB catalog page, including the upstream bounds. */
public record TmdbListPage(int page, int totalPages, long totalResults, List<TmdbListItem> items) {
    public TmdbListPage {
        items = List.copyOf(items);
    }
}
