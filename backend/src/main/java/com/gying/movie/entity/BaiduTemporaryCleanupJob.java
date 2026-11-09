package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** Immutable task evidence and independent, durable trash/purge intents. */
@Data
@TableName("baidu_temporary_cleanup_job")
public class BaiduTemporaryCleanupJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long transferTaskId;
    private String scopeKey;
    private String movieId;
    private Long discoveryResultId;
    private Long resourceLinkId;
    private String originalUrl;
    private String originalUrlHash;
    private String shareUrlHash;
    private String accountUid;
    private String targetPath;
    private String directoryId;
    private String planJson;
    private String evidenceJson;
    private String recycleEvidence;
    private String deleteMode;
    private String status;
    private Integer attempts;
    private String lastError;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime shareConfirmedAt;
    private LocalDateTime deleteAfter;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
