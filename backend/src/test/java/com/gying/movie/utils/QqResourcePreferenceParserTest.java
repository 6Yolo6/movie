package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QqResourcePreferenceParserTest {

    @Test
    void parsesProviderFiltersWithoutTurningCountsIntoBatchSizes() {
        assertEquals(
                new QqResourcePreferenceParser.ResourcePreference("BAIDU", 5),
                QqResourcePreferenceParser.parse("\u7f51\u76d8 \u767e\u5ea6 3\u6761", 5, 10));
        assertEquals(
                new QqResourcePreferenceParser.ResourcePreference("QUARK", 5),
                QqResourcePreferenceParser.parse("\u5938\u514b 2", 5, 10));
        assertEquals(
                new QqResourcePreferenceParser.ResourcePreference("ALL", 5),
                QqResourcePreferenceParser.parse("\u8d44\u6e90 8", 5, 10));
        assertEquals(
                new QqResourcePreferenceParser.ResourcePreference("ALIYUN", 5),
                QqResourcePreferenceParser.parse("\u963f\u91cc\u4e91\u76d8 20", 5, 10));
        assertTrue(QqResourcePreferenceParser.hasExplicitCount("\u5938\u514b 10"));
        assertTrue(QqResourcePreferenceParser.hasExplicitCount("\u8d44\u6e90 4"));
        assertFalse(QqResourcePreferenceParser.hasExplicitCount("\u5938\u514b"));
        assertNull(QqResourcePreferenceParser.parse("\u5bc6\u5ba4\u5927\u9003\u8131\u7b2c\u4e03\u5b63", 5, 10));
    }
}
