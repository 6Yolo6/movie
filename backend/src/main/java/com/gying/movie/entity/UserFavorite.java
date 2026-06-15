package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("user_favorite")
public class UserFavorite implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String movieId;

    private LocalDateTime createdAt;
}
