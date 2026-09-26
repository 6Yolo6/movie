package com.gying.movie.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.dto.CommentDisplayDTO;
import com.gying.movie.entity.Comment;
import com.gying.movie.entity.SysUser;
import com.gying.movie.entity.CommentVote;
import com.gying.movie.mapper.CommentVoteMapper;
import com.gying.movie.security.RedisRateLimiter;
import com.gying.movie.service.ICommentService;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.service.IUserNotificationService;
import com.gying.movie.utils.AuthHelper;
import jakarta.servlet.http.HttpServletRequest;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private static final Set<String> ALLOWED_TYPES = Set.of("GENERAL", "REQUEST", "INVALID_RESOURCE", "SUGGESTION", "OTHER");
    private static final String COMMENT_RATE_LIMIT_CONFIG_KEY = "comment.rate_limit_per_minute";
    private static final int DEFAULT_COMMENT_RATE_LIMIT_PER_MINUTE = 5;
    private static final int MAX_COMMENT_RATE_LIMIT_PER_MINUTE = 120;

    private final ICommentService commentService;
    private final CommentVoteMapper commentVoteMapper;
    private final ISysUserService sysUserService;
    private final AuthHelper authHelper;
    private final IUserNotificationService notificationService;
    private final ISysConfigService configService;
    private final RedisRateLimiter rateLimiter;

    public CommentController(ICommentService commentService,
                             CommentVoteMapper commentVoteMapper,
                             ISysUserService sysUserService,
                             AuthHelper authHelper,
                             IUserNotificationService notificationService,
                             ISysConfigService configService,
                             RedisRateLimiter rateLimiter) {
        this.commentService = commentService;
        this.commentVoteMapper = commentVoteMapper;
        this.sysUserService = sysUserService;
        this.authHelper = authHelper;
        this.notificationService = notificationService;
        this.configService = configService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public Map<String, String> addComment(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestBody Comment comment,
            HttpServletRequest request) {
        AuthUser user = authHelper.requireUser(token);

        if (comment.getRelateId() == null || comment.getRelateId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relateId is required");
        }
        String relateId = comment.getRelateId().trim();
        if (relateId.length() > 64 || !relateId.matches("[A-Za-z0-9_:/.-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid relateId");
        }
        comment.setRelateId(relateId);
        if (comment.getContent() == null || comment.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        String content = comment.getContent().trim();
        if (content.length() > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must be at most 5000 characters");
        }
        comment.setContent(content);
        String type = comment.getType() == null || comment.getType().isBlank()
                ? "GENERAL"
                : comment.getType().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid comment type");
        }
        comment.setType(type);
        enforceCommentRateLimit(user);
        Comment parent = null;
        if (comment.getParentId() != null && comment.getParentId() > 0) {
            parent = commentService.getById(comment.getParentId());
            if (parent == null || parent.getStatus() == null || parent.getStatus() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent comment not found");
            }
            if (!comment.getRelateId().equals(parent.getRelateId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reply relateId does not match parent comment");
            }
        }

        SysUser commenter = sysUserService.getById(user.getId());
        String commenterName = commenter == null ? user.getUsername() : commenter.displayName();
        comment.setNickname(commenterName);
        comment.setUserId(user.getId());
        comment.setCreatedAt(LocalDateTime.now());
        comment.setIpAddress(request.getRemoteAddr());

        commentService.save(comment);
        notifyCommentReply(parent, comment, user, commenterName);

        return Map.of("message", "Comment added successfully");
    }

    @GetMapping("/{relateId}")
    public Page<CommentDisplayDTO> getCommentsByRelateId(
            @PathVariable String relateId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String type) {
        String normalizedType = type == null || type.isBlank() ? null : type.trim().toUpperCase(Locale.ROOT);
        if (normalizedType != null && !ALLOWED_TYPES.contains(normalizedType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid comment type");
        }
        return commentService.getCommentsPaged(relateId, normalizedType, Math.max(page, 1), Math.min(Math.max(size, 1), 50));
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

    private void enforceCommentRateLimit(AuthUser user) {
        int limit = commentRateLimitPerMinute();
        rateLimiter.require("comment-post", String.valueOf(user.getId()), limit, Duration.ofMinutes(1));
    }

    static int commentRateLimitPerMinute(String configured) {
        try {
            int limit = Integer.parseInt(configured == null ? "" : configured.trim());
            return Math.min(Math.max(limit, 1), MAX_COMMENT_RATE_LIMIT_PER_MINUTE);
        } catch (NumberFormatException error) {
            return DEFAULT_COMMENT_RATE_LIMIT_PER_MINUTE;
        }
    }

    private int commentRateLimitPerMinute() {
        return commentRateLimitPerMinute(configService.getConfigValue(
                COMMENT_RATE_LIMIT_CONFIG_KEY, String.valueOf(DEFAULT_COMMENT_RATE_LIMIT_PER_MINUTE)));
    }

    private void notifyCommentReply(Comment parent, Comment reply, AuthUser replier, String replierName) {
        if (parent == null || parent.getUserId() == null || parent.getUserId().equals(replier.getId())) {
            return;
        }
        notificationService.notifyUser(
                parent.getUserId(),
                "COMMENT_REPLY",
                "New comment reply",
                replierName + " replied to your comment: " + shorten(reply.getContent(), 80),
                "COMMENT",
                reply.getRelateId());
    }

    private String shorten(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

}
