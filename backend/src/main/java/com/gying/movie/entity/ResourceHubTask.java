package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("resource_hub_task")
public class ResourceHubTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskType;
    private String movieId;
    private Long tmdbId;
    private String tmdbType;
    private String keyword;
    private String source;
    private String status;
    private Integer priority;
    private Integer attempts;
    private Integer maxAttempts;
    private String lastError;
    private String payload;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
