package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("qq_transfer_cleanup_job")
public class QqTransferCleanupJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String provider;
    private Long transferTaskId;
    private Long discoveryResultId;
    private String movieId;
    private String savedPath;
    private String targetPath;
    private Long resourceLinkId;
    private String status;
    private Integer attempts;
    private String lastError;
    private LocalDateTime deleteAfter;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
