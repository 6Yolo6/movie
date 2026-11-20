package com.gying.movie.dto;

/** Administration summary; no remote response bodies or credentials. */
public record TvmazeSyncProgress(Long taskId, String status, String checkedAt, String window,
        int attempted, int processed, int skipped, int pending, int deferred, int exhausted,
        boolean coverageGap) {}
