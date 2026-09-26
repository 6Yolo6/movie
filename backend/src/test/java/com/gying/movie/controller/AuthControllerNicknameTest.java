package com.gying.movie.controller;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.security.RedisRateLimiter;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.service.impl.EmailVerificationService;
import com.gying.movie.service.impl.LoginDeviceService;
import com.gying.movie.service.impl.RegistrationService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthControllerNicknameTest {

    final ISysUserService users = mock(ISysUserService.class);
    final AuthHelper auth = mock(AuthHelper.class);
    final AuthController controller = new AuthController(users, auth, mock(RedisRateLimiter.class),
            mock(RegistrationService.class), mock(EmailVerificationService.class), mock(JwtUtils.class),
            mock(LoginDeviceService.class));

    @BeforeEach void setup() {
        when(auth.requireUser("token")).thenReturn(new AuthUser(2L, "fixture", "USER"));
    }

    private SysUser entity() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername("fixture");
        user.setNickname("电影迷");
        user.setRole("USER");
        return user;
    }

    @Test void profileEndpointUpdatesNickname() {
        when(users.updateNickname(2L, "电影迷")).thenReturn(entity());

        Map<String, Object> result = controller.updateProfile("token", Map.of("nickname", "电影迷"));

        assertEquals("电影迷", result.get("nickname"));
        verify(users).updateNickname(2L, "电影迷");
    }

    @Test void profileEndpointRequiresLogin() {
        when(auth.requireUser(null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updateProfile(null, Map.of("nickname", "电影迷")));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verify(users, never()).updateNickname(anyLong(), any());
    }

    @Test void meReturnsNicknameAlongsideUsername() {
        when(users.getById(2L)).thenReturn(entity());

        Map<String, Object> result = controller.me("token");

        assertEquals("fixture", result.get("username"));
        assertEquals("电影迷", result.get("nickname"));
    }
}
