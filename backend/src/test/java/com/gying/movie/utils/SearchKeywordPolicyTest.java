package com.gying.movie.utils;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SearchKeywordPolicyTest {
    @Test void blocksExplicitAdultTermsAndObfuscation() {
        for (String value : new String[]{"porn", "ＰＯＲＮ", "p o r n", "p.o-r_n", "po\u200brn", "成人视频合集"})
            assertTrue(SearchKeywordPolicy.blocked(value, null), value);
    }
    @Test void configuredWordsSupportChineseSeparatorsAndNewlines() {
        for (String value : new String[]{"禁词甲", "禁词乙", "禁词丙", "禁词丁"})
            assertTrue(SearchKeywordPolicy.blocked(value, "禁词甲，禁词乙\n禁词丙、禁词丁"));
    }
    @Test void ordinaryMoviesAreNotBlocked() {
        for (String value : new String[]{"破产姐妹", "复仇者联盟", "Avatar", "Sex Education", "1917"})
            assertFalse(SearchKeywordPolicy.blocked(value, ""), value);
    }
    @Test void builtInDictionaryCoversChineseTraditionalEnglishAndAdultLabels() {
        for (String value : new String[]{"成人電影合集", "日 本 ＡＶ", "裏番", "无码资源", "全裸写真", "adult video", "ｐ０ｒｎ", "JAV 1080p", "AV资源", "R-18 动画", "MissAV", "偷拍视频资源", "儿童色情"})
            assertTrue(SearchKeywordPolicy.blocked(value, ""), value);
    }
    @Test void ambiguousOrdinaryWordsDoNotTriggerTheBuiltInDictionary() {
        for (String value : new String[]{"成人高考", "人性", "艺术写真", "Java", "Java17", "Avatar", "Avengers", "Sex Education", "加勒比海盗"})
            assertFalse(SearchKeywordPolicy.blocked(value, ""), value);
    }
}
