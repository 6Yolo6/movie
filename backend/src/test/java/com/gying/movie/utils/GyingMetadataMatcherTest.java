package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gying.movie.entity.MovieMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;

class GyingMetadataMatcherTest {

    @Test
    void exactTitleYearAndDirectorCanAutoMatch() {
        MovieMetadata movie = movie("鬼上车", "mv", 2026, null);
        movie.setDirectors(List.of("林珍钊"));

        var evidence = GyingMetadataMatcher.score(movie,
                new GyingMetadataMatcher.SourceMetadata(
                        "mv", "鬼上车", 2026, null, List.of("林珍钊"), List.of()));

        assertTrue(evidence.autoMatch());
    }

    @Test
    void sameSeriesDifferentSeasonIsRejected() {
        MovieMetadata first = movie("权力的游戏 第一季", "tv", 2011, 1);

        var evidence = GyingMetadataMatcher.score(first,
                new GyingMetadataMatcher.SourceMetadata(
                        "tv", "权力的游戏 第二季", 2012, 2, List.of(), List.of()));

        assertFalse(evidence.autoMatch());
    }

    @Test
    void exactTitleWithoutIndependentEvidenceNeedsReview() {
        MovieMetadata movie = movie("同名作品", "mv", null, null);

        var evidence = GyingMetadataMatcher.score(movie,
                new GyingMetadataMatcher.SourceMetadata(
                        "mv", "同名作品", null, null, List.of(), List.of()));

        assertFalse(evidence.autoMatch());
    }

    @Test
    void ignoresHistoricalSeasonValueForMovies() {
        MovieMetadata movie = movie("揭秘日", "mv", 2026, 1);

        var evidence = GyingMetadataMatcher.score(movie,
                new GyingMetadataMatcher.SourceMetadata(
                        "mv", "揭秘日", 2026, 2, List.of(), List.of()));

        assertTrue(evidence.autoMatch());
    }

    private MovieMetadata movie(String title, String category, Integer year, Integer season) {
        MovieMetadata movie = new MovieMetadata();
        movie.setTitleCn(title);
        movie.setCategory(category);
        movie.setYear(year);
        movie.setSeason(season);
        return movie;
    }
}
