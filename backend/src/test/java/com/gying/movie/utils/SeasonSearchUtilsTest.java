package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SeasonSearchUtilsTest {

    @Test
    void parsesChineseSeasonAndBuildsApiFriendlyVariants() {
        SeasonSearchUtils.SeasonQuery query = SeasonSearchUtils.parse("密室大逃脱第七季");

        assertEquals("密室大逃脱", query.baseTitle());
        assertEquals(7, query.season());
        assertEquals(
                List.of("密室大逃脱第七季", "密室大逃脱 第7季", "密室大逃脱7", "密室大逃脱 S07"),
                SeasonSearchUtils.searchVariants("密室大逃脱", "密室大逃脱第七季"));
    }

    @Test
    void matchesOnlyRequestedSeasonOrContainingRange() {
        assertTrue(SeasonSearchUtils.matchesRequestedSeason(
                "密室大逃脱 第7季 2025 1080P",
                "密室大逃脱第七季"));
        assertTrue(SeasonSearchUtils.matchesRequestedSeason(
                "密室大逃脱 1-7季合集",
                "密室大逃脱第七季"));
        assertFalse(SeasonSearchUtils.matchesRequestedSeason(
                "密室大逃脱 第6季 2024",
                "密室大逃脱第七季"));
    }
}
