package com.gying.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gying.movie.entity.UserFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {

    // One round trip for a bounded page. Keep each requested ID as the result key:
    // GROUP BY movie_id can merge/relabel IDs under a case-insensitive collation.
    // Each indexed equality count retains the same semantics as the original query.
    @Select("""
            <script>
            <choose>
                <when test="movieIds != null and !movieIds.isEmpty()">
                    <foreach collection="movieIds" item="movieId" separator=" UNION ALL ">
                        SELECT #{movieId} AS movie_id, COUNT(*) AS cnt FROM user_favorite
                        WHERE movie_id = #{movieId}
                    </foreach>
                </when>
                <otherwise>SELECT NULL AS movie_id, 0 AS cnt WHERE 1 = 0</otherwise>
            </choose>
            </script>
            """)
    List<Map<String, Object>> countByMovieIds(@Param("movieIds") Collection<String> movieIds);

    @Select("SELECT movie_id, COUNT(*) as cnt FROM user_favorite " +
            "WHERE created_at >= #{since} " +
            "GROUP BY movie_id ORDER BY cnt DESC LIMIT #{limit}")
    List<Map<String, Object>> countByMovieSince(@Param("since") String since, @Param("limit") int limit);

    @Select("SELECT movie_id, COUNT(*) as cnt FROM user_favorite " +
            "GROUP BY movie_id ORDER BY cnt DESC LIMIT #{limit}")
    List<Map<String, Object>> countByMovieAll(@Param("limit") int limit);
}
