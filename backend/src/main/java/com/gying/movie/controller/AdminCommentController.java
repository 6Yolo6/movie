package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.entity.Comment;
import com.gying.movie.service.ICommentService;
import com.gying.movie.utils.AuthHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/comments")
public class AdminCommentController {

    private final ICommentService commentService;
    private final AuthHelper authHelper;

    public AdminCommentController(ICommentService commentService, AuthHelper authHelper) {
        this.commentService = commentService;
        this.authHelper = authHelper;
    }

    @GetMapping
    public ResponseEntity<?> listComments(
            @RequestParam(required = false) String relateId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);

        QueryWrapper<Comment> query = new QueryWrapper<>();
        if (relateId != null && !relateId.isBlank()) {
            query.eq("relate_id", relateId);
        }
        if (userId != null) {
            query.eq("user_id", userId);
        }
        if (status != null) {
            query.eq("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.like("content", keyword).or().like("nickname", keyword);
        }
        query.orderByDesc("created_at");
        return ResponseEntity.ok(commentService.page(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateCommentStatus(
            @PathVariable Long id,
            @RequestParam Integer status,
            @RequestHeader(value = "Authorization", required = false) String token) {
        authHelper.requireAdmin(token);
        if (status != 0 && status != 1 && status != 2) {
            return ResponseEntity.badRequest().body("Invalid status");
        }
        Comment comment = commentService.getById(id);
        if (comment == null) {
            return ResponseEntity.status(404).body("Comment not found");
        }
        comment.setStatus(status);
        commentService.updateById(comment);
        return ResponseEntity.ok(Map.of("status", status));
    }
}
