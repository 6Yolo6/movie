package com.gying.movie.service;
import com.gying.movie.dto.CommentDisplayDTO;
import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.Comment;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;

public interface ICommentService extends IService<Comment> {
    Page<CommentDisplayDTO> getCommentsPaged(String relateId, int page, int size);
}