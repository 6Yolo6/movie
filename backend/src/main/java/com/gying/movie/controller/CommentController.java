package com.gying.movie.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.dto.CommentDisplayDTO;
import com.gying.movie.entity.Comment;
import com.gying.movie.entity.CommentVote;
import com.gying.movie.mapper.CommentVoteMapper;
import com.gying.movie.service.ICommentService;
import com.gying.movie.utils.AuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/comments")
public class CommentController {

    @Autowired
    private ICommentService commentService;

    @Autowired
    private CommentVoteMapper commentVoteMapper;

    @Autowired
    private AuthHelper authHelper;

    @PostMapping
    public Map<String, String> addComment(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestBody Comment comment,
            HttpServletRequest request) {
        AuthUser user = authHelper.requireUser(token);

        if (comment.getRelateId() == null || comment.getRelateId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relateId is required");
        }
        if (comment.getContent() == null || comment.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        if (comment.getParentId() != null && comment.getParentId() > 0) {
            Comment parent = commentService.getById(comment.getParentId());
            if (parent == null || parent.getStatus() == null || parent.getStatus() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent comment not found");
            }
            if (!comment.getRelateId().equals(parent.getRelateId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reply relateId does not match parent comment");
            }
        }

        comment.setNickname(user.getUsername());
        comment.setUserId(user.getId());
        comment.setCreatedAt(LocalDateTime.now());
        comment.setIpAddress(request.getRemoteAddr());

        commentService.save(comment);

        return Map.of("message", "Comment added successfully");
    }

    @GetMapping("/{relateId}")
    public Page<CommentDisplayDTO> getCommentsByRelateId(
            @PathVariable String relateId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return commentService.getCommentsPaged(relateId, Math.max(page, 1), Math.min(Math.max(size, 1), 50));
    }

    @PostMapping("/{id}/upvote")
    public Map<String, Object> upvoteComment(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser user = authHelper.requireUser(token);
        Comment comment = commentService.getById(id);
        if (comment == null || comment.getStatus() == null || comment.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
        }
        CommentVote existing = commentVoteMapper.selectOne(new QueryWrapper<CommentVote>()
                .eq("comment_id", id)
                .eq("user_id", user.getId()));
        boolean upvoted;
        if (existing == null) {
            CommentVote vote = new CommentVote();
            vote.setCommentId(id);
            vote.setUserId(user.getId());
            vote.setCreatedAt(LocalDateTime.now());
            commentVoteMapper.insert(vote);
            comment.setUpvotes(comment.getUpvotes() == null ? 1 : comment.getUpvotes() + 1);
            upvoted = true;
        } else {
            commentVoteMapper.deleteById(existing.getId());
            comment.setUpvotes(Math.max((comment.getUpvotes() == null ? 0 : comment.getUpvotes()) - 1, 0));
            upvoted = false;
        }
        commentService.updateById(comment);
        return Map.of("upvotes", comment.getUpvotes(), "upvoted", upvoted);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> deleteComment(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        AuthUser user = authHelper.requireUser(token);
        Comment comment = commentService.getById(id);
        if (comment == null || comment.getStatus() == null || comment.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
        }
        boolean isAdmin = "ADMIN".equalsIgnoreCase(user.getRole());
        boolean isOwner = comment.getUserId() != null && comment.getUserId().equals(user.getId());
        if (!isAdmin && !isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        comment.setStatus(2);
        commentService.updateById(comment);
        return Map.of("message", "Comment deleted successfully");
    }

}
