package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** Provider-specific durable intent. Authentication material is never stored here. */
@Data
@TableName("baidu_transfer_task")
public class BaiduTransferTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long discoveryResultId;
    private String movieId;
    private String originalUrl;
    private String originalUrlHash;
    private String accountUid;
    private String savedPath;
    private String shareUrl;
    private String shareUrlHash;
    private String status;
    private String phase;
    private Integer attempts;
    private String lastError;
    private String requestPayload;
    private String responsePayload;
    private String remoteTaskId;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
