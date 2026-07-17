package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("qq_bot_search_log")
public class QqBotSearchLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userKey;
    private String keyword;
    private String status;
    private String movieId;
    private Integer resourceCount;
    private String replyPreview;
    private String failureReason;
    private LocalDateTime createdAt;
}
