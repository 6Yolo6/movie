package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
@TableName(value = "movie_metadata", autoResultMap = true)
public class MovieMetadata implements Serializable {

    @TableId
    private String id;

    private String titleCn;

    private String titleEn;

    private String seriesName;

    private Integer season;

    private Integer year;

    private String runtime;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> directors;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> actors;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> genres;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> regions;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> languages;

    private String releaseDates;

    private String aliases;

    private String category;

    private String posterUrl;

    private BigDecimal doubanScore;

    private BigDecimal imdbScore;

    private String rtScore;

    private String summary;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
