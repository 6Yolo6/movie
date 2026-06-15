package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("resource_link")
public class ResourceLink implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String movieId;

    private String name;

    private String type; // MAGNET, DISK, ONLINE

    private String provider; // BAIDU, XUNLEI, etc.

    private String url;

    private String code;

    private Long uploaderId;

    private Integer auditStatus;

    private String status;

    private String linkStatus;

    private Integer reportCount;

    private String remark;

    private LocalDateTime createdAt;
}
