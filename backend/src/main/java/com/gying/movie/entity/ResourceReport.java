package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("resource_report")
public class ResourceReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resourceId;
    private Long userId;
    private String reason;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
}