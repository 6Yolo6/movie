package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gying.movie.dto.MovieSearchCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MovieSearchCandidateUtilsTest {

    @Test
    void formatsTypedFullTitleSuggestionsAndDeduplicatesSources() {
        MovieSearchCandidate local = candidate(1L, "movie", "福尔摩斯小姐3", 2026, 80);
        MovieSearchCandidate mrHolmes = candidate(2L, "movie", "福尔摩斯先生", 2015, 130);
        MovieSearchCandidate duplicateMrHolmes = candidate(2L, "movie", "福尔摩斯先生", 2015, 90);
        MovieSearchCandidate holmes = candidate(3L, "movie", "福尔摩斯", 1992, 160);
        MovieSearchCandidate elementary = candidate(4L, "tv", "福尔摩斯：基本演绎法", 2012, 130);

        List<MovieSearchCandidate> merged = MovieSearchCandidateUtils.merge(
                List.of(local),
                List.of(mrHolmes, duplicateMrHolmes, holmes, elementary),
                8);

        assertEquals(4, merged.size());
        String reply = MovieSearchCandidateUtils.formatReply("福尔摩斯", merged);
        assertTrue(reply.contains("电影 福尔摩斯先生 (2015)"));
        assertTrue(reply.contains("电影 福尔摩斯 (1992)"));
        assertTrue(reply.contains("剧集 福尔摩斯：基本演绎法 (2012)"));
        assertTrue(reply.contains("直接回复序号即可，例如：1"));
        assertEquals("福尔摩斯先生", MovieSearchCandidateUtils.selectionTitle(merged, 2));
        assertNull(MovieSearchCandidateUtils.selectionTitle(merged, 5));
    }

    @Test
    void balancesGyingWithFallbackCandidates() {
        List<MovieSearchCandidate> gying = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> sourcedCandidate(
                        "GYING", "GYING " + index, 2020 + index, 200 - index))
                .toList();
        List<MovieSearchCandidate> fallback = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> sourcedCandidate(
                        "TMDB", "TMDB " + index, 2020 + index, 180 - index))
                .toList();

        List<MovieSearchCandidate> merged = MovieSearchCandidateUtils.mergeBalanced(
                gying, fallback, 10, 4);

        assertEquals(10, merged.size());
        assertEquals(4, merged.stream().filter(item -> "GYING".equals(item.getSource())).count());
        assertEquals("GYING", merged.get(0).getSource());
        assertEquals("TMDB", merged.get(1).getSource());
    }

    private MovieSearchCandidate candidate(Long id, String type, String title, Integer year, int score) {
        return new MovieSearchCandidate(id, type, title, null, year, score);
    }

    private MovieSearchCandidate sourcedCandidate(String source, String title, Integer year, int score) {
        return new MovieSearchCandidate(null, "movie", title, null, year, score,
                source, "movie", title, null);
    }
}
