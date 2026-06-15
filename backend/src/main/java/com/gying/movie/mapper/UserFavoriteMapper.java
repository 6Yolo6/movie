package com.gying.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gying.movie.entity.UserFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {

    @Select("SELECT movie_id, COUNT(*) as cnt FROM user_favorite " +
            "WHERE created_at >= #{since} " +
            "GROUP BY movie_id ORDER BY cnt DESC LIMIT #{limit}")
    List<Map<String, Object>> countByMovieSince(@Param("since") String since, @Param("limit") int limit);

    @Select("SELECT movie_id, COUNT(*) as cnt FROM user_favorite " +
            "GROUP BY movie_id ORDER BY cnt DESC LIMIT #{limit}")
    List<Map<String, Object>> countByMovieAll(@Param("limit") int limit);
}
