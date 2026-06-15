package com.gying.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gying.movie.entity.MovieMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MovieMetadataMapper extends BaseMapper<MovieMetadata> {

    @Select("<script>" +
            "SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(${column}, CONCAT('$[', numbers.n, ']'))) as val " +
            "FROM movie_metadata, " +
            "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 " +
            " UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) numbers " +
            "WHERE ${column} IS NOT NULL " +
            "<if test='category != null and category != \"\"'> AND category = #{category} </if>" +
            "AND JSON_UNQUOTE(JSON_EXTRACT(${column}, CONCAT('$[', numbers.n, ']'))) IS NOT NULL " +
            "AND JSON_UNQUOTE(JSON_EXTRACT(${column}, CONCAT('$[', numbers.n, ']'))) != '' " +
            "ORDER BY val" +
            "</script>")
    List<String> selectDistinctJsonValues(@Param("column") String column, @Param("category") String category);

    @Select("<script>" +
            "SELECT DISTINCT CAST(year AS CHAR) as year FROM movie_metadata WHERE year IS NOT NULL " +
            "<if test='category != null and category != \"\"'> AND category = #{category} </if>" +
            "ORDER BY year DESC" +
            "</script>")
    List<String> selectDistinctYears(@Param("category") String category);
}

