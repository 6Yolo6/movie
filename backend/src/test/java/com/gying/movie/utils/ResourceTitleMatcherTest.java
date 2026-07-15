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
        assertTrue(ResourceTitleMatcher.isRelevant(
                seasonSeven,
                "密室大逃脱 第7季 2025 1080P",
                "密室大逃脱第七季"));
        assertFalse(ResourceTitleMatcher.isRelevant(
                seasonSeven,
                "密室大逃脱 第6季 2024 1080P",
                "密室大逃脱第七季"));
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

    private MovieMetadata movie(String titleCn, String titleEn, String aliases) {
        MovieMetadata movie = new MovieMetadata();
        movie.setTitleCn(titleCn);
        movie.setTitleEn(titleEn);
        movie.setAliases(aliases);
        return movie;
    }
}
