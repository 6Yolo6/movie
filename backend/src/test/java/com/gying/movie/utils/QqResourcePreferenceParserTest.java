package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class QqResourcePreferenceParserTest {

    @Test
    void parsesProviderAndCountCommands() {
        assertEquals(
                new QqResourcePreferenceParser.ResourcePreference("BAIDU", 3),
                QqResourcePreferenceParser.parse("网盘 百度 3条", 5, 10));
        assertEquals(
                new QqResourcePreferenceParser.ResourcePreference("QUARK", 2),
                QqResourcePreferenceParser.parse("夸克 2", 5, 10));
        assertEquals(
                new QqResourcePreferenceParser.ResourcePreference("ALL", 8),
                QqResourcePreferenceParser.parse("资源 8", 5, 10));
        assertEquals(
                new QqResourcePreferenceParser.ResourcePreference("ALIYUN", 10),
                QqResourcePreferenceParser.parse("阿里云盘 20", 5, 10));
        assertNull(QqResourcePreferenceParser.parse("密室大逃脱第七季", 5, 10));
    }
}
