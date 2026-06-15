package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
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

    private Long userId;

    private String nickname;

    private String content;

    private Integer status;

    private Integer upvotes;

    private Long parentId;

    private String ipAddress;

    private LocalDateTime createdAt;
}
