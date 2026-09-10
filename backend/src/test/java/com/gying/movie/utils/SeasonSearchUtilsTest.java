package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
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
        assertTrue(SeasonSearchUtils.hasSeasonMarker("密室大逃脱 全7季"));
        assertTrue(SeasonSearchUtils.hasSeasonMarker("密室大逃脱 第二季"));
        assertFalse(SeasonSearchUtils.hasSeasonMarker("达顿牧场 9集全"));
    }

    @Test
    void detectsCoverageAndBuildsNestedSeasonDirectoryPattern() {
        assertEquals(
                "\u83dc\u9e1f\u8001\u8b66 \u7b2c1\u5b63",
                SeasonSearchUtils.seasonQualifiedTitle("\u83dc\u9e1f\u8001\u8b66", 1));
        assertTrue(SeasonSearchUtils.coversSeason("\u83dc\u9e1f\u8001\u8b66 \u51681-7\u5b63", 1));
        assertTrue(SeasonSearchUtils.coversSeason("The Rookie Season 1", 1));
        assertFalse(SeasonSearchUtils.coversSeason("\u83dc\u9e1f\u8001\u8b66 \u7b2c\u4e03\u5b63", 1));
        assertTrue(SeasonSearchUtils.explicitlyMatchesSeason("S01\u30102018\u3011", 1));
        assertFalse(SeasonSearchUtils.explicitlyMatchesSeason("S08\u30102026\u3011", 1));
        assertTrue(SeasonSearchUtils.hasSeasonCollection("\u7b2c\u516b\u5b63 \u96441-7\u5b63"));
        assertTrue(SeasonSearchUtils.isCollectionResource("\u6743\u529b\u7684\u6e38\u620f 1-8\u5b63\u5408\u96c6"));
        assertTrue(SeasonSearchUtils.isCollectionResource("\u6743\u529b\u7684\u6e38\u620f \u5168\u96c6"));
        assertFalse(SeasonSearchUtils.isCollectionResource("\u6743\u529b\u7684\u6e38\u620f \u7b2c1\u5b63"));
        assertTrue(SeasonSearchUtils.canUseRootForFirstSeason("鬼灭之刃 更至63集 4K合集 最新"));
        assertFalse(SeasonSearchUtils.canUseRootForFirstSeason("鬼灭之刃 第二季 全11集"));

        Pattern pattern = Pattern.compile(SeasonSearchUtils.subdirectoryPattern(1));
        assertTrue(pattern.matcher("S01\u30102018\u3011").find());
        assertTrue(pattern.matcher("Season 1").find());
        assertTrue(pattern.matcher("\u7b2c\u4e00\u5b63").find());
        assertFalse(pattern.matcher("S07\u30102025\u3011").find());
    }

    @Test
    void matchesTitleAnchoredNumericSeasonDirectoriesWithoutTreatingMetadataAsSeason() {
        assertTrue(SeasonSearchUtils.matchesSeasonDirectory("wW问@@X心2", "问心", 2));
        assertTrue(SeasonSearchUtils.matchesSeasonDirectory("问心 第2季", "问心", 2));
        assertTrue(SeasonSearchUtils.matchesSeasonDirectory("S02", "问心", 2));
        assertTrue(SeasonSearchUtils.matchesSeasonDirectory("wW问@@X心2", "问心2", 2));

        assertFalse(SeasonSearchUtils.matchesSeasonDirectory("问心2023", "问心", 2));
        assertFalse(SeasonSearchUtils.matchesSeasonDirectory("问心2160P", "问心", 2));
        assertFalse(SeasonSearchUtils.matchesSeasonDirectory("问心12集", "问心", 2));
        assertFalse(SeasonSearchUtils.matchesSeasonDirectory("2", "问心", 2));
    }
}
