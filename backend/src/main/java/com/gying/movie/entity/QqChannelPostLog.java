package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("qq_channel_post_log")
public class QqChannelPostLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resourceLinkId;
    private String movieId;
    private String title;
    private String linkUrl;
    private String channelType;
    private String channelId;
    private String status;
    private String errorMessage;
    private LocalDateTime postedAt;
    private LocalDateTime createdAt;
}
