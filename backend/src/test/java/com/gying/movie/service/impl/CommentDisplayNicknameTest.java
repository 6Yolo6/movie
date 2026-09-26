package com.gying.movie.service.impl;

import com.gying.movie.dto.CommentDisplayDTO;
import com.gying.movie.entity.Comment;
import com.gying.movie.entity.SysUser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommentDisplayNicknameTest {

    private final CommentServiceImpl service = new CommentServiceImpl();

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = CommentServiceImpl.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    private Comment comment(Long userId, String snapshotNickname) {
        Comment comment = new Comment();
        comment.setId(1L);
        comment.setUserId(userId);
        comment.setNickname(snapshotNickname);
        comment.setContent("hello");
        return comment;
    }

    private SysUser user(String username, String nickname) {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername(username);
        user.setNickname(nickname);
        return user;
    }

    @Test void displayUsesCurrentNicknameInsteadOfUsername() throws Exception {
        Map<Long, SysUser> users = new HashMap<>();
        users.put(2L, user("fixture", "新昵称"));

        CommentDisplayDTO dto = (CommentDisplayDTO) invoke("toDisplayDTO",
                new Class<?>[]{Comment.class, Map.class}, comment(2L, "旧昵称"), users);

        assertEquals("新昵称", dto.getNickname());
        assertEquals("fixture", dto.getUsername());
    }

    @Test void displayFallsBackToUsernameWhenNicknameMissing() throws Exception {
        Map<Long, SysUser> users = new HashMap<>();
        users.put(2L, user("fixture", "   "));

        CommentDisplayDTO dto = (CommentDisplayDTO) invoke("toDisplayDTO",
                new Class<?>[]{Comment.class, Map.class}, comment(2L, null), users);

        assertEquals("fixture", dto.getNickname());
    }

    @Test void displayFallsBackToStoredSnapshotForDeletedUser() throws Exception {
        CommentDisplayDTO dto = (CommentDisplayDTO) invoke("toDisplayDTO",
                new Class<?>[]{Comment.class, Map.class}, comment(9L, "历史昵称"), Collections.emptyMap());

        assertEquals("历史昵称", dto.getNickname());
    }

    @Test void resolveNicknameUsesLiveUserThenSnapshotThenAnonymous() throws Exception {
        Map<Long, SysUser> users = new HashMap<>();
        users.put(2L, user("fixture", "新昵称"));

        assertEquals("新昵称", invoke("resolveNickname",
                new Class<?>[]{Comment.class, Map.class}, comment(2L, "旧昵称"), users));
        assertEquals("历史昵称", invoke("resolveNickname",
                new Class<?>[]{Comment.class, Map.class}, comment(9L, "历史昵称"), users));
        assertEquals("Anonymous", invoke("resolveNickname",
                new Class<?>[]{Comment.class, Map.class}, comment(null, null), users));
    }
}
