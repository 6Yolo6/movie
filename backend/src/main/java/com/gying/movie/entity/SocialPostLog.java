package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("social_post_log")
public class SocialPostLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long targetId;
    private String platform;
    private Long resourceLinkId;
    private String movieId;
    private String title;
    private String status;
    private String externalUrl;
    private String errorMessage;
    private LocalDateTime postedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
