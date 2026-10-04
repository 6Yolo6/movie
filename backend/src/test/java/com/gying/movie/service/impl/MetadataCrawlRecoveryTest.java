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
        verify(tasks).update(wrapper.capture());
        String sql = wrapper.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("$.crawl.mode"));
        assertTrue(sql.contains("started_at <"));
        assertTrue(sql.contains("task_type ="));
        assertTrue(sql.contains("source IN"));
        assertTrue(sql.contains("status ="));
        verifyNoMoreInteractions(tasks);
    }
}
