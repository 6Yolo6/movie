package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("social_publish_target")
public class SocialPublishTarget {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private String accountKey;
    private String name;
    private String targetRef;
    private String channelRef;
    private Boolean enabled;
    private Boolean autoPostEnabled;
    private String scheduleTime;
    private Integer postsPerRun;
    private Integer postIntervalSeconds;
    private String template;
    private LocalDateTime lastAutoRunAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
