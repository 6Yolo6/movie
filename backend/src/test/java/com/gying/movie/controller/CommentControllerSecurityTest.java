package com.gying.movie.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.Comment;
import com.gying.movie.mapper.CommentVoteMapper;
import com.gying.movie.security.RedisRateLimiter;
import com.gying.movie.service.ICommentService;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.IUserNotificationService;
import com.gying.movie.utils.AuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CommentControllerSecurityTest {

    private ICommentService commentService;
    private CommentVoteMapper commentVoteMapper;
    private AuthHelper authHelper;
    private IUserNotificationService notificationService;
    private ISysConfigService configService;
    private RedisRateLimiter rateLimiter;
    private CommentController controller;

    @BeforeEach
    void setUp() {
        commentService = mock(ICommentService.class);
        commentVoteMapper = mock(CommentVoteMapper.class);
        authHelper = mock(AuthHelper.class);
        notificationService = mock(IUserNotificationService.class);
        configService = mock(ISysConfigService.class);
        rateLimiter = mock(RedisRateLimiter.class);
        controller = new CommentController(commentService, commentVoteMapper, authHelper,
                notificationService, configService, rateLimiter);
    }

    @Test
    void defaultsMessageBoardPostToGeneralAndAppliesConfiguredRateLimit() {
        when(authHelper.requireUser("Bearer token")).thenReturn(new AuthUser(5L, "user", "USER"));
        when(configService.getConfigValue("comment.rate_limit_per_minute", "5")).thenReturn("8");
        when(commentService.save(any(Comment.class))).thenReturn(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");

        Comment comment = new Comment();
        comment.setRelateId("message-board");
        comment.setContent("hello");
        controller.addComment("Bearer token", comment, request);

        assertEquals("GENERAL", comment.getType());
        verify(rateLimiter).require("comment-post", "5", 8, Duration.ofMinutes(1));
        verify(commentService).save(comment);
        verifyNoInteractions(notificationService);
    }

    @Test
    void rateLimitRejectionPreventsPersistence() {
        when(authHelper.requireUser("Bearer token")).thenReturn(new AuthUser(5L, "user", "USER"));
        when(configService.getConfigValue("comment.rate_limit_per_minute", "5")).thenReturn("5");
        doThrow(new RedisRateLimiter.RateLimitExceededException(1000))
                .when(rateLimiter).require("comment-post", "5", 5, Duration.ofMinutes(1));

        Comment comment = new Comment();
        comment.setRelateId("message-board");
        comment.setContent("hello");

        assertThrows(RedisRateLimiter.RateLimitExceededException.class,
                () -> controller.addComment("Bearer token", comment, mock(HttpServletRequest.class)));
        verify(commentService, never()).save(any(Comment.class));
    }

    @Test
    void invalidTypeIsRejectedBeforeRateLimitOrDatabaseWork() {
        when(authHelper.requireUser("Bearer token")).thenReturn(new AuthUser(5L, "user", "USER"));
        Comment comment = new Comment();
        comment.setRelateId("message-board");
        comment.setType("UNKNOWN");
        comment.setContent("hello");

        assertThrows(ResponseStatusException.class,
                () -> controller.addComment("Bearer token", comment, mock(HttpServletRequest.class)));
        verifyNoInteractions(rateLimiter);
        verify(commentService, never()).save(any(Comment.class));
    }

    @Test
    void malformedRateLimitConfigurationFallsBackToFive() {
        assertEquals(5, CommentController.commentRateLimitPerMinute(null));
        assertEquals(5, CommentController.commentRateLimitPerMinute("bad"));
        assertEquals(1, CommentController.commentRateLimitPerMinute("0"));
        assertEquals(120, CommentController.commentRateLimitPerMinute("999"));
    }
}
