package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gying.movie.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SysUserNicknameTest {

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername("fixture");
        user.setNickname("fixture");
        user.setRole("USER");
        return user;
    }

    private SysUserServiceImpl serviceFor(SysUser existing) {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        doReturn(existing).when(service).getById(existing.getId());
        return service;
    }

    @Test void updatesNicknameTrimmed() {
        SysUser existing = user();
        SysUserServiceImpl service = serviceFor(existing);
        doReturn(true).when(service).updateById(any());

        SysUser updated = service.updateNickname(2L, "  电影迷  ");

        assertEquals("电影迷", updated.getNickname());
        verify(service).updateById(existing);
    }

    @Test void rejectsBlankNickname() {
        SysUserServiceImpl service = serviceFor(user());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateNickname(2L, "   ")).getStatusCode());
        verify(service, never()).updateById(any());
    }

    @Test void rejectsNullNickname() {
        SysUserServiceImpl service = serviceFor(user());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateNickname(2L, null)).getStatusCode());
    }

    @Test void rejectsNicknameOverTwentyCharacters() {
        SysUserServiceImpl service = serviceFor(user());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateNickname(2L, "a".repeat(21))).getStatusCode());
        verify(service, never()).updateById(any());
    }

    @Test void acceptsTwentyCharacters() {
        SysUserServiceImpl service = serviceFor(user());
        doReturn(true).when(service).updateById(any());

        assertEquals("a".repeat(20), service.updateNickname(2L, "a".repeat(20)).getNickname());
    }

    @Test void rejectsControlCharacters() {
        SysUserServiceImpl service = serviceFor(user());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateNickname(2L, "bad\u0007name")).getStatusCode());
        verify(service, never()).updateById(any());
    }

    @Test void rejectsUnknownUser() {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        doReturn(null).when(service).getById(99L);

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.updateNickname(99L, "电影迷")).getStatusCode());
    }

    @Test void displayNamePrefersNicknameAndFallsBackToUsername() {
        SysUser user = user();
        assertEquals("fixture", user.displayName());

        user.setNickname("电影迷");
        assertEquals("电影迷", user.displayName());

        user.setNickname("   ");
        assertEquals("fixture", user.displayName());

        user.setUsername("only-user");
        user.setNickname(null);
        assertEquals("only-user", user.displayName());
    }

    @Test void registerDefaultsNicknameToUsername() {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any());

        SysUser created = service.register("newuser", "a-very-long-password", "new@example.com", null);

        assertEquals("newuser", created.getNickname());
        assertEquals("newuser", created.displayName());
    }

    @Test void registerRejectsUsernameContainingAt() {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.register("someone@example.com", "a-very-long-password", "new@example.com", null)).getStatusCode());
        verify(service, never()).save(any());
    }

    @Test void loginIdentifierFallsBackToEmail() {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        SysUser byEmail = user();
        byEmail.setEmail("fixture@example.com");
        doReturn(null, byEmail).when(service).getOne(any());

        assertSame(byEmail, service.findLoginUser("Fixture@Example.com"));
    }

    @Test void loginIdentifierMatchesUsernameWithoutEmailLookup() {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        SysUser byName = user();
        doReturn(byName).when(service).getOne(any());

        assertSame(byName, service.findLoginUser("fixture"));
        verify(service, times(1)).getOne(any());
    }

    @Test void loginIdentifierReturnsNullWhenUnknown() {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        doReturn(null).when(service).getOne(any());

        assertNull(service.findLoginUser("nobody"));
        verify(service, times(1)).getOne(any());
    }
}
