package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gying.movie.entity.MovieMetadata;
import org.junit.jupiter.api.Test;

class ResourceTitleMatcherTest {

    @Test
    void acceptsCanonicalAliasAndSeasonResourceNames() {
        MovieMetadata movie = movie("达顿牧场 第一季", "Dutton Ranch Season 1", "达顿牧场 / Dutton Ranch");

        assertTrue(ResourceTitleMatcher.isRelevant(
                movie,
                "达顿牧场 Dutton Ranch (2026) 【9集全】【1080p+ 4K】",
                "达顿牧场 第一季"));

        assertTrue(ResourceTitleMatcher.isRelevant(
                movie("福尔摩斯：基本演绎法", "Elementary", null),
                "福尔摩斯 [1~7季合集][1080P/720P][中英字幕][61.7G]",
                "福尔摩斯：基本演绎法"));
        assertTrue(ResourceTitleMatcher.isRelevant(
                movie("福尔摩斯：基本演绎法", "Elementary", null),
                "福尔摩斯：基本演绎法 第一季（2012） - 全7季 1080P",
                "福尔摩斯：基本演绎法 2012"));

        MovieMetadata seasonSeven = movie("密室大逃脱", "Great Escape", null);
        seasonSeven.setCategory("tv");
        assertTrue(ResourceTitleMatcher.isRelevant(
                seasonSeven,
                "密室大逃脱 第7季 2025 1080P",
                "密室大逃脱第七季"));
        assertFalse(ResourceTitleMatcher.isRelevant(
                seasonSeven,
                "密室大逃脱 第6季 2024 1080P",
                "密室大逃脱第七季"));

        MovieMetadata firstSeason = movie("权力的游戏 第一季", "Game of Thrones Season 1", null);
        firstSeason.setCategory("tv");
        assertTrue(ResourceTitleMatcher.isRelevant(
                firstSeason,
                "权力的游戏 全8季 4K",
                "权力的游戏 第一季"));
        assertFalse(ResourceTitleMatcher.isRelevant(
                firstSeason,
                "权力的游戏 第二季 4K",
                "权力的游戏 第一季"));
    }

    @Test
    void rejectsUnrelatedHeadlineEvenWhenDescriptionIsLong() {
        MovieMetadata movie = movie("深水", "Deep Water", null);

        assertFalse(ResourceTitleMatcher.isRelevant(
                movie,
                "#剧集 瑞克和莫蒂 第九季 (2026) 📜介绍：一架飞机坠入深水后展开求生",
                "深水 2026"));
    }

    @Test
    void rejectsGenericCollectionsAndWrongSequels() {
        assertFalse(ResourceTitleMatcher.isRelevant(
                movie("你会心碎", null, null),
                "2026-06-12合辑：",
                "你会心碎 2026"));
        assertFalse(ResourceTitleMatcher.isRelevant(
                movie("复仇者联盟5", "Avengers 5", null),
                "乐高复仇者联盟：红色代码 (2023) 4K",
                "复仇者联盟5"));
    }

    @Test
    void enforcesSeriesSeasonWhenPublishingWithoutSearchKeyword() {
        MovieMetadata firstSeason = movie("\u83dc\u9e1f\u8001\u8b66", "The Rookie", null);
        firstSeason.setCategory("tv");
        firstSeason.setSeason(1);

        assertTrue(ResourceTitleMatcher.isRelevant(
                firstSeason,
                "\u83dc\u9e1f\u8001\u8b66 \u51681-7\u5b63 1080P",
                null));
        assertFalse(ResourceTitleMatcher.isRelevant(
                firstSeason,
                "\u83dc\u9e1f\u8001\u8b66 \u7b2c\u4e03\u5b63 1080P",
                null));
    }

    @Test
    void acceptsHistoricalTmdbTitleSamplesButRejectsUnrelatedCollections() {
        MovieMetadata movie = movie(
                "\u8096\u7533\u514b\u7684\u6551\u8d4e",
                " The Shawshank Redemption",
                null);
        movie.setCategory("mv");
        movie.setSeason(1);

        assertTrue(ResourceTitleMatcher.isRelevant(
                movie,
                "\u8096\u7533\u514b\u7684\u6551\u8d4e:The Shawshank Redemption",
                null));
        assertTrue(ResourceTitleMatcher.isRelevant(
                movie,
                "001.\u8096\u7533\u514b\u7684\u6551\u8d4e.The.Shawshank.Redemption.1994.UHD.BluRay.2160p",
                null));
        assertFalse(ResourceTitleMatcher.isRelevant(
                movie,
                "\u8c46\u74e3top\u524d\u5341\u7535\u5f71\u5408\u96c6",
                null));
    }

    private MovieMetadata movie(String titleCn, String titleEn, String aliases) {
        MovieMetadata movie = new MovieMetadata();
        movie.setTitleCn(titleCn);
        movie.setTitleEn(titleEn);
        movie.setAliases(aliases);
        return movie;
    }
}
