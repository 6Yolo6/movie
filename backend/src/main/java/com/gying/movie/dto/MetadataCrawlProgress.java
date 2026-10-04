package com.gying.movie.dto;

/** Durable position for one provider/catalog, without credentials or raw errors. */
public record MetadataCrawlProgress(String provider, String source, int startPage, int endPage,
        int nextPage, int nextItem, String status, Long taskId) {
}
