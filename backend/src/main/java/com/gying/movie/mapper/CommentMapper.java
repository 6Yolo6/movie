package com.gying.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gying.movie.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}