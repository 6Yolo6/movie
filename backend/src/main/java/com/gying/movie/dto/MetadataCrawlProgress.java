package com.gying.movie.dto;

/** Durable position for one provider/catalog, without credentials or raw errors. */
public record MetadataCrawlProgress(String provider, String source, int startPage, int endPage,
        int nextPage, int nextItem, String status, Long taskId,
        Integer observedTotalPages, String availability, String nextCheckAt) {
    public MetadataCrawlProgress(String provider, String source, int startPage, int endPage,
            int nextPage, int nextItem, String status, Long taskId) {
        this(provider, source, startPage, endPage, nextPage, nextItem, status, taskId, null, null, null);
    }
}
