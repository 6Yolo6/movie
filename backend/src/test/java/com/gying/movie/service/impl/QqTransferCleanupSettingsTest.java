package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.utils.BaiduTemporaryPaths;
import java.util.List;
import org.junit.jupiter.api.Test;

class QqTransferCleanupSettingsTest {
    @Test void baiduDefaultsKeepTenMinutesAndExactPositiveTaskPath() {
        var config=mock(ISysConfigService.class);when(config.getConfigValue(anyString(),anyString())).thenAnswer(i->i.getArgument(1));
        var settings=new QqTransferCleanupSettings(config);
        assertTrue(settings.enabled());assertEquals(10,settings.delayMinutes());assertTrue(settings.baiduPermanentDeleteEnabled());
        assertEquals("/GYing QQ Temp/baidu-7",settings.baiduTarget(7L));assertEquals(7,BaiduTemporaryPaths.requireTarget(settings.baiduTarget(7L)));
        assertThrows(IllegalArgumentException.class,()->settings.baiduTarget(0L));assertThrows(IllegalArgumentException.class,()->settings.baiduTarget(null));
    }
    @Test void baiduRootRejectsTraversalAliasesAndPermanentDirectoriesInsteadOfNormalizing() {
        for(String root:List.of("/","GYing QQ Temp"," /GYing QQ Temp","/GYing QQ Temp/","/GYing//QQ Temp","/GYing\\QQ Temp","/GYing%20QQ Temp","/GYing/../QQ Temp","/mv","/GYing Resource Hub/Temporary","/Other/磁力种子","/Other/baidu-7")) {
            var config=mock(ISysConfigService.class);when(config.getConfigValue(QqTransferCleanupSettings.KEY_BAIDU_ROOT,BaiduTemporaryPaths.DEFAULT_ROOT)).thenReturn(root);
            assertThrows(IllegalArgumentException.class,()->new QqTransferCleanupSettings(config).baiduRoot());
        }
    }
    @Test void currentRootChangeGivesDifferentExactTargetAndUnsupportedModeFailsClosed() {
        var config=mock(ISysConfigService.class);when(config.getConfigValue(anyString(),anyString())).thenAnswer(i->i.getArgument(1));
        when(config.getConfigValue(QqTransferCleanupSettings.KEY_BAIDU_ROOT,BaiduTemporaryPaths.DEFAULT_ROOT)).thenReturn("/New/GYing QQ Temp");
        when(config.getConfigValue(QqTransferCleanupSettings.KEY_BAIDU_MODE,"PERMANENT")).thenReturn("unknown");
        var settings=new QqTransferCleanupSettings(config);assertEquals("/New/GYing QQ Temp/baidu-7",settings.baiduTarget(7L));assertFalse(settings.baiduPermanentDeleteEnabled());
    }
}
