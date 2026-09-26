package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gying.movie.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SysUserEmailChangeTest {

    private SysUserServiceImpl serviceFor(SysUser existing) {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        doReturn(existing).when(service).getById(existing.getId());
        return service;
    }

    private SysUser userWith(LocalDateTime lastChanged) {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername("fixture");
        user.setEmail("old@example.com");
        user.setRole("USER");
        user.setEmailUpdatedAt(lastChanged);
        return user;
    }

    @Test void rejectsChangeWithinNinetyDays() {
        SysUserServiceImpl service = serviceFor(userWith(LocalDateTime.now().minusDays(10)));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.changeEmail(2L, "new@example.com"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
        verify(service, never()).updateById(any());
    }

    @Test void allowsChangeAfterCooldownAndNormalizesEmail() {
        SysUser user = userWith(LocalDateTime.now().minusDays(91));
        SysUserServiceImpl service = serviceFor(user);
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());

        SysUser updated = service.changeEmail(2L, "  New@Example.COM ");

        assertEquals("new@example.com", updated.getEmail());
        assertNotNull(updated.getEmailUpdatedAt());
        verify(service).updateById(user);
    }

    @Test void allowsFirstEverChange() {
        SysUserServiceImpl service = serviceFor(userWith(null));
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());

        SysUser updated = service.changeEmail(2L, "first@example.com");

        assertEquals("first@example.com", updated.getEmail());
        assertNotNull(updated.getEmailUpdatedAt());
    }

    @Test void rejectsUnchangedEmail() {
        SysUserServiceImpl service = serviceFor(userWith(null));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.changeEmail(2L, "OLD@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(service, never()).updateById(any());
    }

    @Test void rejectsEmailAlreadyRegistered() {
        SysUserServiceImpl service = serviceFor(userWith(null));
        doReturn(1L).when(service).count(any(Wrapper.class));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.changeEmail(2L, "taken@example.com"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(service, never()).updateById(any());
    }

    @Test void rejectsMalformedEmail() {
        SysUserServiceImpl service = serviceFor(userWith(null));

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.changeEmail(2L, "not-an-email")).getStatusCode());
    }
}
