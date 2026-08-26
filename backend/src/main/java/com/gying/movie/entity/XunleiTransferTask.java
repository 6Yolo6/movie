package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("xunlei_transfer_task")
public class XunleiTransferTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long discoveryResultId;
    private String movieId;
    private String originalUrl;
    private String originalUrlHash;
    private String savedPath;
    private String shareUrl;
    private String shareUrlHash;
    private String status;
    private Integer attempts;
    private String lastError;
    private String requestPayload;
    private String responsePayload;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
