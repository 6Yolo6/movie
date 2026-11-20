package com.gying.movie.utils;

import com.gying.movie.entity.MovieMetadata;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovieDuplicateMatcherTest {
    private MovieMetadata movie(String id, String name, String category, Integer year, Integer season) {
        var movie = new MovieMetadata(); movie.setId(id); movie.setTitleEn(name); movie.setCategory(category);
        movie.setYear(year); movie.setSeason(season); movie.setStatus("ACTIVE"); return movie;
    }
    @Test void findsMoblandAcrossEnglishAndChineseSeasonSuffixesAndAlternateNames() {
        var gying = movie("1YKW", " MobLand Season 1", "tv", 2025, 1); gying.setTitleCn("黑帮领地 第一季");
        gying.setActors(List.of("Tom Hardy")); gying.setDirectors(List.of("盖·里奇"));
        var tmdb = movie("tmdb_tv_247718", "MobLand", "tv", 2025, 1); tmdb.setActors(List.of("Tom Hardy"));
        var result = MovieDuplicateMatcher.find(List.of(gying, tmdb));
        assertEquals(1, result.pairs().size()); assertTrue(result.pairs().get(0).peopleSupported()); assertTrue(result.complete());
    }
    @Test void normalizesWhitespaceCaseAndChineseSeasonNumbers() {
        assertEquals(1, MovieDuplicateMatcher.find(List.of(movie("a", "相同 剧名 第十二季", "tv", 2025, 12),
                movie("b", "相同剧名", "tv", 2025, 12))).pairs().size());
        assertEquals(1, MovieDuplicateMatcher.find(List.of(movie("a", "SAME Title S02", "tv", 2025, 2),
                movie("b", "same title Season 2", "series", 2025, 2))).pairs().size());
    }
    @Test void doesNotStripAnEnglishWordEndingInSAndADigit() {
        assertEquals(0, MovieDuplicateMatcher.find(List.of(movie("a", "News1", "tv", 2025, 1),
                movie("b", "New", "tv", 2025, 1))).pairs().size());
    }
    @Test void matchesAliasAsATitleRatherThanSubstring() {
        var left = movie("a", "Chinese title", "mv", 2025, null); left.setAliases("MobLand / Another title");
        assertEquals(1, MovieDuplicateMatcher.find(List.of(left, movie("b", "MobLand", "movie", 2025, null))).pairs().size());
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "Land", "movie", 2025, null))).pairs().size());
    }
    @Test void understandsJsonAliasesButNeverTreatsInvalidJsonAsAName() {
        var left = movie("a", "名称", "mv", 2025, null); left.setAliases("[\"Another Title\",\"MobLand\"]");
        assertEquals(1, MovieDuplicateMatcher.find(List.of(left, movie("b", "mobland", "mv", 2025, null))).pairs().size());
        left.setAliases("[broken MobLand");
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "MobLand", "mv", 2025, null))).pairs().size());
    }
    @Test void keepsCategoryYearAndSeasonBoundaries() {
        var left = movie("a", "Same", "tv", 2025, 1);
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "Same", "mv", 2025, null))).pairs().size());
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "Same", "tv", 2024, 1))).pairs().size());
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "Same", "tv", 2025, 2))).pairs().size());
    }
    @Test void doesNotGuessMissingYearOrSeasonOrIgnoreAnExplicitSeasonConflict() {
        var left = movie("a", "MobLand", "tv", 2025, 1);
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "MobLand", "tv", null, 1))).pairs().size());
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "MobLand", "tv", 2025, null))).pairs().size());
        var result = MovieDuplicateMatcher.find(List.of(left, movie("b", "MobLand Season 2", "tv", 2025, 1)));
        assertEquals(0, result.pairs().size()); assertEquals(1, result.excludedMovies());
    }
    @Test void infersOneExplicitSeasonWithoutInventingFirstSeason() {
        assertEquals(1, MovieDuplicateMatcher.find(List.of(movie("a", "Same Season 3", "tv", 2025, null),
                movie("b", "Same 第三季", "tv", 2025, 3))).pairs().size());
    }
    @Test void isolatesVersionConflictEvenWhenAnAliasIsShared() {
        var left = movie("a", "Same Director's Cut", "mv", 2025, null); left.setAliases("Same");
        var result = MovieDuplicateMatcher.find(List.of(left, movie("b", "Same", "mv", 2025, null)));
        assertEquals(0, result.pairs().size()); assertEquals(1, result.conflicts());
    }
    @Test void isolatesDisjointKnownPeopleButMarksMissingPeopleForReview() {
        var left = movie("a", "Same", "mv", 2025, null); left.setActors(List.of("Cast A"));
        var right = movie("b", "Same", "mv", 2025, null); right.setDirectors(List.of("Director B"));
        assertEquals(1, MovieDuplicateMatcher.find(List.of(left, right)).conflicts());
        right.setDirectors(null);
        var result = MovieDuplicateMatcher.find(List.of(left, right));
        assertEquals(1, result.pairs().size()); assertFalse(result.pairs().get(0).peopleSupported());
    }
    @Test void skipsDeletedRecordsAndMultiSeasonCollections() {
        var left = movie("a", "Same", "tv", 2025, 1); left.setDeletedAt(LocalDateTime.now());
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "Same", "tv", 2025, 1))).pairs().size());
        left.setDeletedAt(null); left.setTitleEn("Same Seasons 1-3"); left.setAliases("Same");
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, movie("b", "Same", "tv", 2025, 1))).pairs().size());
    }
    @Test void animeMoviesAndSeriesRemainSeparateAndTypeConflictsAreExcluded() {
        var left = movie("a", "Same", "ac", 2025, null); left.setTmdbType("movie");
        var right = movie("b", "Same", "anime", 2025, null); right.setTmdbType("movie");
        assertEquals(1, MovieDuplicateMatcher.find(List.of(left, right)).pairs().size());
        right.setTmdbType("tv"); right.setSeason(1);
        assertEquals(0, MovieDuplicateMatcher.find(List.of(left, right)).pairs().size());
        left.setCategory("mv"); left.setTmdbType("tv");
        assertEquals(2, MovieDuplicateMatcher.find(List.of(left, movie("c", "Same", "ac", 2025, null))).excludedMovies());
    }
    @Test void doesNotRepeatAPairMatchedByMultipleTitlesOrAliases() {
        var left = movie("a", "English", "mv", 2025, null); left.setTitleCn("中文"); left.setAliases("English / 中文");
        var right = movie("b", "English", "mv", 2025, null); right.setTitleCn("中文");
        assertEquals(1, MovieDuplicateMatcher.find(List.of(left, right)).pairs().size());
    }
    @Test void crowdedTitlesReturnAnExplicitIncompleteResultRatherThanUnboundedPairs() {
        List<MovieMetadata> movies = new ArrayList<>();
        for (int i = 0; i < 110; i++) movies.add(movie("id" + i, "Same", "mv", 2025, null));
        var result = MovieDuplicateMatcher.find(movies);
        assertFalse(result.complete()); assertTrue(result.crowdedBuckets() > 0); assertTrue(result.pairs().size() <= 5000);
    }
}
