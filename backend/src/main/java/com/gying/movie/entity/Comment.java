package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("comment")
public class Comment implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String relateId;

    /** GENERAL for movie comments; REQUEST, INVALID_RESOURCE, SUGGESTION or OTHER for message-board posts. */
    @TableField("comment_type")
    private String type;

    private Long userId;

    private String nickname;

    private String content;

    private Integer status;

    private Integer upvotes;

    private Long parentId;

    private String ipAddress;

    private LocalDateTime createdAt;
}
