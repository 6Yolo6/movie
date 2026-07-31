package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("movie_source_identity")
public class MovieSourceIdentity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String movieId;
    private String source;
    private String sourceType;
    private String externalId;
    private Integer season;
    private BigDecimal confidence;
    private String matchMethod;
    private String matchStatus;
    private String evidenceJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
