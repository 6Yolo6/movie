package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.CommentDisplayDTO;
import com.gying.movie.entity.Comment;
import com.gying.movie.entity.SysUser;
import com.gying.movie.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommentPaginationTest {
    private final CommentServiceImpl service = spy(new CommentServiceImpl());
    private final SysUserMapper users = mock(SysUserMapper.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "sysUserMapper", users);
        when(users.selectBatchIds(anyCollection())).thenReturn(List.of());
    }

    @Test
    void nestedRepliesKeepChronologicalOrderAndLiveReplyToNicknames() {
        Comment root = comment(1, 0, 11L, "root snapshot");
        Comment direct = comment(3, 1, 13L, "parent snapshot");
        Comment nested = comment(4, 3, 14L, "nested snapshot");
        Comment otherRoot = comment(2, 0, null, "other root");
        // Imported children can precede their parent; keep the database's display order.
        stubPage(List.of(root, otherRoot), List.of(nested, comment(5, 2, null, "other reply"), direct));
        when(users.selectBatchIds(anyCollection())).thenReturn(List.of(user(13, "live parent")));

        Page<CommentDisplayDTO> result = service.getCommentsPaged("movie", "GENERAL", 2, 2);

        assertEquals(List.of(1L, 2L), result.getRecords().stream().map(Comment::getId).toList());
        assertEquals(List.of(4L, 3L), result.getRecords().get(0).getReplies().stream().map(Comment::getId).toList());
        assertEquals("live parent", result.getRecords().get(0).getReplies().get(0).getReplyToNickname());
        assertNull(result.getRecords().get(0).getReplies().get(1).getReplyToNickname());
        assertEquals("nested snapshot", result.getRecords().get(0).getReplies().get(0).getNickname());
        assertEquals("other reply", result.getRecords().get(1).getReplies().get(0).getNickname());
        assertEquals(2, result.getCurrent());
        assertEquals(2, result.getSize());
        assertEquals(8, result.getTotal());
        verify(users).selectBatchIds(Set.of(11L, 13L, 14L));
    }

    @Test
    void onlyVisibleUsersAreLoadedAndOrphansAndCyclesStayHidden() {
        stubPage(List.of(comment(1, 0, 11L, "root")), List.of(
                comment(2, 1, 12L, "visible"),
                comment(3, 2, null, "anonymous visible"),
                comment(4, 900, 904L, "another page"),
                comment(5, 4, 905L, "nested on another page"),
                comment(6, 999, 906L, "orphan"),
                comment(7, 8, 907L, "cycle-a"),
                comment(8, 7, 908L, "cycle-b"),
                comment(9, 9, 909L, "self-cycle")));

        var result = service.getCommentsPaged("movie", "GENERAL", 1, 20);

        assertEquals(List.of(2L, 3L), result.getRecords().get(0).getReplies().stream().map(Comment::getId).toList());
        verify(users).selectBatchIds(Set.of(11L, 12L));
        verify(users, times(1)).selectBatchIds(anyCollection());
    }

    @Test
    void ancestorLookupRetainsTheExistingTwentyHopSafetyLimit() {
        List<Comment> replies = new ArrayList<>();
        for (long id = 2; id <= 25; id++) replies.add(comment(id, id - 1, null, "reply-" + id));
        stubPage(List.of(comment(1, 0, null, "root")), replies);

        var visible = service.getCommentsPaged("movie", null, 1, 20).getRecords().get(0).getReplies();

        assertEquals(21, visible.size());
        assertEquals(22L, visible.get(visible.size() - 1).getId());
        verifyNoInteractions(users);
    }

    @Test
    void nullParentAndMissingIntermediateParentDoNotBreakThePage() {
        Comment malformed = comment(4, 1, 14L, "malformed");
        malformed.setParentId(null);
        stubPage(List.of(comment(1, 0, null, "root")), List.of(
                comment(2, 1, null, "visible"), malformed, comment(5, 4, 15L, "orphan")));

        var visible = service.getCommentsPaged("movie", null, 1, 20).getRecords().get(0).getReplies();

        assertEquals(List.of(2L), visible.stream().map(Comment::getId).toList());
        verifyNoInteractions(users);
    }

    @Test
    void emptyRootPageSkipsReplyAndUserQueries() {
        doReturn(new Page<Comment>(3, 20, 40)).when(service).page(any(Page.class), any(Wrapper.class));

        var result = service.getCommentsPaged("movie", "GENERAL", 3, 20);

        assertTrue(result.getRecords().isEmpty());
        assertEquals(40, result.getTotal());
        verify(service, never()).list(any(Wrapper.class));
        verifyNoInteractions(users);
    }

    @Test
    void thousandsOfRepliesUseLinearIdLookupsInsteadOfRepeatedFullScans() {
        AtomicInteger idReads = new AtomicInteger();
        List<Comment> replies = new ArrayList<>();
        for (long id = 2; id < 4002; id += 2) {
            replies.add(countedComment(id, 1, idReads));
            replies.add(countedComment(id + 1, id, idReads));
        }
        stubPage(List.of(comment(1, 0, null, "root")), replies);

        var result = service.getCommentsPaged("movie", null, 1, 20);

        assertEquals(4000, result.getRecords().get(0).getReplies().size());
        System.out.printf("Comment pagination fixture: replies=4000, idReads=%d%n", idReads.get());
        assertTrue(idReads.get() <= replies.size() * 10,
                "Expected bounded ID reads; actual=" + idReads.get());
    }

    private void stubPage(List<Comment> roots, List<Comment> replies) {
        Page<Comment> page = new Page<>(2, 2, 8);
        page.setRecords(roots);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));
        doReturn(replies).when(service).list(any(Wrapper.class));
    }

    private Comment comment(long id, long parentId, Long userId, String nickname) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setParentId(parentId);
        comment.setUserId(userId);
        comment.setNickname(nickname);
        comment.setContent("fixture");
        return comment;
    }

    private SysUser user(long id, String nickname) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setNickname(nickname);
        return user;
    }

    private Comment countedComment(long id, long parentId, AtomicInteger idReads) {
        CountingComment comment = new CountingComment(idReads);
        comment.setId(id);
        comment.setParentId(parentId);
        return comment;
    }

    public static class CountingComment extends Comment {
        private final AtomicInteger idReads;

        CountingComment(AtomicInteger idReads) {
            this.idReads = idReads;
        }

        @Override
        public Long getId() {
            idReads.incrementAndGet();
            return super.getId();
        }
    }
}
