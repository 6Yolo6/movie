package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.ISysConfigService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class GyingWeeklyTransferSettingsTest {
    private final ISysConfigService configs = mock(ISysConfigService.class);
    private SysConfig stored;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GyingWeeklyTransferSettings settings = new GyingWeeklyTransferSettings(configs, mapper);
    GyingWeeklyTransferSettingsTest() {
        when(configs.getOne(any())).thenAnswer(call -> stored);
        when(configs.save(any())).thenAnswer(call -> { stored = call.getArgument(0); stored.setId(1L); return true; });
        when(configs.updateById(any())).thenAnswer(call -> { stored = call.getArgument(0); return true; });
    }
    @Test void defaultsAreThreeWeeklyChartsAndDoNotRunImmediately() {
        var all = settings.get(); assertEquals(3, all.size());
        for (var s : all) {
            assertTrue(s.isEnabled()); assertEquals(7, s.getIntervalDays()); assertEquals(5, s.getMaxItems());
            assertTrue(LocalDateTime.parse(s.getNextRunAt()).isAfter(LocalDateTime.now().plusDays(6)));
        }
        assertNull(settings.claimDue());
    }
    @Test void claimIsPersistedBeforeExecutionAndSurvivesServiceRestart() throws Exception {
        var all = settings.get(); all.get(1).setNextRunAt(LocalDateTime.now().minusMinutes(1).toString());
        stored.setConfigValue(mapper.writeValueAsString(all));
        assertEquals("tv", settings.claimDue().getTypeCode());
        assertNull(new GyingWeeklyTransferSettings(configs, mapper).claimDue());
        settings.finished("tv", "FAILED", 9L);
        assertNull(settings.claimDue());
        assertEquals("FAILED", settings.get().get(1).getLastStatus());
    }
    @Test void frequencyAndEnableChangesWaitFullIntervalAndIgnoreInjectedRuntimeFields() {
        var requested = settings.get(); requested.get(0).setIntervalDays(14);
        requested.get(0).setNextRunAt("2000-01-01T00:00:00"); requested.get(0).setLastStatus("SUCCEEDED");
        requested.get(1).setEnabled(false);
        settings.update(requested);
        var actual = settings.get();
        assertTrue(LocalDateTime.parse(actual.get(0).getNextRunAt()).isAfter(LocalDateTime.now().plusDays(13)));
        assertEquals("NOT_STARTED", actual.get(0).getLastStatus()); assertFalse(actual.get(1).isEnabled());
        assertNull(settings.claimDue());
    }
    @Test void invalidConfigDoesNotWriteAndCorruptPersistenceFailsClosed() {
        var all = settings.get(); String original = stored.getConfigValue();
        all.get(0).setIntervalDays(0);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> settings.update(all));
        assertEquals(original, stored.getConfigValue());
        stored.setConfigValue("bad-json");
        assertThrows(IllegalStateException.class, settings::claimDue);
    }
    @Test void disabledChartIsNotClaimedEvenWhenDue() throws Exception {
        var all = settings.get(); all.get(0).setEnabled(false); all.get(0).setNextRunAt("2000-01-01T00:00:00");
        stored.setConfigValue(mapper.writeValueAsString(all)); assertNull(settings.claimDue());
    }
    @Test void rejectsDuplicateChartsAndOversizedBatches() {
        var all = settings.get(); all.get(0).setMaxItems(21);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> settings.validate(all));
        all.get(0).setMaxItems(5); all.get(0).setTypeCode("tv");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> settings.validate(all));
    }
    @Test void nullScheduleEntryIsAValidationError() {
        var requested = new java.util.ArrayList<>(settings.get()); requested.set(0, null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> settings.validate(requested));
    }
    @Test void failedClaimPersistenceStopsExecution() throws Exception {
        var all = settings.get(); all.get(0).setNextRunAt("2000-01-01T00:00:00");
        stored.setConfigValue(mapper.writeValueAsString(all));
        doReturn(false).when(configs).updateById(any());
        assertThrows(IllegalStateException.class, settings::claimDue);
    }
    @Test void changingOnlyBatchSizePreservesNextRunTime() {
        var all = settings.get(); String scheduled = all.get(0).getNextRunAt();
        all.get(0).setMaxItems(2); settings.update(all);
        assertEquals(scheduled, settings.get().get(0).getNextRunAt());
    }
}
