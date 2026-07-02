package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("resource_discovery_result")
public class ResourceDiscoveryResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String movieId;
    private String source;
    private String sourceRef;
    private String title;
    private String provider;
    private String resourceType;
    private String originalUrl;
    private String originalUrlHash;
    private String shareUrl;
    private String shareUrlHash;
    private String code;
    private String quality;
    private String subtitle;
    private String fileSize;
    private String versionNote;
    private BigDecimal confidence;
    private String status;
    private String failureReason;
    private Long resourceLinkId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
