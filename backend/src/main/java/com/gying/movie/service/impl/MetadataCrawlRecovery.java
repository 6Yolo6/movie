package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.IResourceHubTaskService;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** The Resource Hub worker is a singleton deployment, not a distributed worker. */
@Component
public class MetadataCrawlRecovery {
    private final IResourceHubTaskService tasks;
    private final LocalDateTime processStartedAt = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()), ZoneId.systemDefault());

    public MetadataCrawlRecovery(IResourceHubTaskService tasks) {
        this.tasks = tasks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedCrawls() {
        // Do not replay ingestion here. Only release interrupted range tasks; the normal
        // enabled/interval guards decide if and when the same checkpoint is retried.
        // Legacy/manual tasks and resource transfers are deliberately excluded.
        tasks.update(new UpdateWrapper<ResourceHubTask>()
                .eq("task_type", "METADATA_SYNC").in("source", "TMDB", "GYING")
                .eq("status", "RUNNING").lt("started_at", processStartedAt)
                .apply("JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(payload), payload, '{}'), '$.crawl.mode')) = 'RANGE'")
                .set("status", "FAILED").set("finished_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now())
                .set("last_error", "Automatic metadata crawl interrupted by backend restart; checkpoint retained"));
    }
}
