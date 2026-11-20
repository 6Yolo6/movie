package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MetadataCrawlRecoveryTest {
    @Test void onlyInterruptedAutomaticMetadataTasksAreReleasedWithoutReplay() {
        var tasks = mock(IResourceHubTaskService.class);
        new MetadataCrawlRecovery(tasks).recoverInterruptedCrawls();
        ArgumentCaptor<Wrapper<ResourceHubTask>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(tasks, times(2)).update(wrapper.capture());
        String sql = wrapper.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("$.crawl.mode"));
        assertTrue(sql.contains("started_at <"));
        assertTrue(sql.contains("task_type ="));
        assertTrue(sql.contains("source IN"));
        assertTrue(sql.contains("status ="));
        String updateSql = wrapper.getAllValues().get(1).getCustomSqlSegment();
        assertTrue(wrapper.getAllValues().get(0).getCustomSqlSegment().contains("'RANGE'"));
        assertTrue(updateSql.contains("'UPDATES'")); assertTrue(updateSql.contains("keyword ="));
        assertTrue(updateSql.contains("started_at <"));
        assertFalse(((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ResourceHubTask>) wrapper.getAllValues().get(1)).getSqlSet().contains("payload="));
        verifyNoMoreInteractions(tasks);
    }
}
