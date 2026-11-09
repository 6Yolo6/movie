package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MovieDuplicateScannerServiceTest {
    final IMovieMetadataService movies = mock(IMovieMetadataService.class);
    final IResourceLinkService resources = mock(IResourceLinkService.class);
    final MovieDuplicateScannerService scanner = new MovieDuplicateScannerService(movies, resources);
    private MovieMetadata movie(String id, String title) {
        var row = new MovieMetadata(); row.setId(id); row.setTitleEn(title); row.setCategory("tv");
        row.setSeason(1); row.setYear(2025); row.setStatus("ACTIVE"); return row;
    }
    private void catalog(List<MovieMetadata> rows) {
        rows = rows.stream().sorted(java.util.Comparator.comparing(MovieMetadata::getId)).toList();
        List<MovieMetadata> ordered = rows;
        AtomicInteger offset = new AtomicInteger();
        when(movies.list(any(QueryWrapper.class))).thenAnswer(invocation -> {
            int start = offset.getAndAdd(MovieDuplicateScannerService.READ_BATCH);
            return start >= ordered.size() ? List.of() : ordered.subList(start, Math.min(start + MovieDuplicateScannerService.READ_BATCH, ordered.size()));
        });
        when(resources.listMaps(any(QueryWrapper.class))).thenReturn(List.of());
    }
    @Test void scansBeyondTheOldTwoHundredRowWindowAndCountsCandidateResourcesInOneBatch() {
        List<MovieMetadata> rows = new ArrayList<>();
        for (int i = 0; i < 8500; i++) rows.add(movie(String.format("id_%05d", i), "Unique " + i));
        rows.add(movie("1YKW", " MobLand Season 1")); rows.add(movie("tmdb_tv_247718", "MobLand"));
        catalog(rows);
        when(resources.listMaps(any(QueryWrapper.class))).thenReturn(List.of(Map.of("movie_id", "1YKW", "resource_count", 8L)));
        var result = scanner.scan(1, 100);
        assertEquals(8502, result.get("scannedMovies")); assertEquals(1, result.get("candidates"));
        assertEquals(true, result.get("complete")); assertEquals(false, result.get("mergeEnabled")); assertEquals(0, result.get("merged"));
        var item = (Map<?, ?>)((List<?>)result.get("items")).get(0);
        assertEquals("1YKW", item.get("toMovieId")); assertEquals(8L, item.get("toResources")); assertEquals(0L, item.get("fromResources"));
        ArgumentCaptor<QueryWrapper<MovieMetadata>> reads = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(movies, times(18)).list(reads.capture());
        assertFalse(reads.getAllValues().get(0).getSqlSegment().contains("id LIKE"));
        assertTrue(reads.getAllValues().get(1).getSqlSegment().contains("id >"));
        assertTrue(reads.getAllValues().stream().allMatch(query -> query.getSqlSegment().endsWith("LIMIT 500")));
        assertFalse(reads.getAllValues().get(0).getSqlSelect().contains("summary"));
        ArgumentCaptor<QueryWrapper<com.gying.movie.entity.ResourceLink>> counts = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(resources, times(1)).listMaps(counts.capture());
        assertTrue(counts.getValue().getSqlSegment().contains("GROUP BY movie_id"));
        assertTrue(counts.getValue().getSqlSegment().contains("link_status")); assertTrue(counts.getValue().getSqlSegment().contains("source"));
        verifyNoMoreInteractions(movies, resources);
    }
    @Test void resultLimitAndPageNeverLimitCatalogCoverageAndAllCandidatesRemainReviewOnly() {
        catalog(List.of(movie("a", "Same"), movie("b", "Same"), movie("c", "Same")));
        var result = scanner.scan(2, 1);
        assertEquals(3, result.get("scannedMovies")); assertEquals(3, result.get("candidates"));
        assertEquals(2, result.get("page")); assertEquals(1, result.get("pageSize")); assertEquals(3, result.get("totalPages"));
        assertEquals(1, ((List<?>)result.get("items")).size());
        assertEquals(true, ((Map<?, ?>)((List<?>)result.get("items")).get(0)).get("reviewRequired"));
        verify(movies, times(1)).list(any(QueryWrapper.class)); verify(resources, times(1)).listMaps(any(QueryWrapper.class));
        verifyNoMoreInteractions(movies, resources);
    }
    @Test void emptyCatalogDoesNotQueryResourcesOrWriteAnything() {
        catalog(List.of()); var result = scanner.scan(-1, 5000);
        assertEquals(0, result.get("candidates")); assertEquals(1, result.get("page")); assertEquals(200, result.get("pageSize"));
        assertEquals(List.of(), result.get("items")); verify(movies).list(any(QueryWrapper.class)); verifyNoMoreInteractions(movies);
        verifyNoInteractions(resources);
    }
    @Test void failedCatalogReadReleasesTheScannerForASecondAttempt() {
        when(movies.list(any(QueryWrapper.class))).thenThrow(new IllegalStateException("fixture"));
        assertThrows(IllegalStateException.class, () -> scanner.scan(1, 100));
        when(movies.list(any(QueryWrapper.class))).thenReturn(List.of());
        assertEquals(true, scanner.scan(1, 100).get("complete")); verifyNoInteractions(resources);
    }
}
