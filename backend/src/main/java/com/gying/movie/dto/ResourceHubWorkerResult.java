package com.gying.movie.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ResourceHubWorkerResult {
    private boolean enabled;
    private boolean skipped;
    private String reason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private int taskLimit;
    private int quarkLimit;
    private int publishLimit;
    private int tasksProcessed;
    private int tasksSucceeded;
    private int tasksFailed;
    private int tasksSkipped;
    private List<TaskResult> tasks = new ArrayList<>();
    private QuarkTransferRunResult quarkTransfers;
    private ResourceHubPublishResult publishedResources;
    private List<String> errors = new ArrayList<>();

    @Data
    public static class TaskResult {
        private Long taskId;
        private String taskType;
        private String status;
        private String error;
    }
}
