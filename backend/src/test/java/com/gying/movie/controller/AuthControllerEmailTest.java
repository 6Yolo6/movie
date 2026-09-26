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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthControllerEmailTest {

    final ISysUserService users = mock(ISysUserService.class);
    final AuthHelper auth = mock(AuthHelper.class);
    final EmailVerificationService email = mock(EmailVerificationService.class);
    final HttpServletRequest request = mock(HttpServletRequest.class);
    final AuthController controller = new AuthController(users, auth, mock(RedisRateLimiter.class),
            mock(RegistrationService.class), email, mock(JwtUtils.class), mock(LoginDeviceService.class));

    @BeforeEach void setup() {
        when(auth.requireUser("token")).thenReturn(new AuthUser(2L, "fixture", "USER"));
        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername("fixture");
        user.setEmail("fixture@example.com");
        user.setRole("USER");
        when(users.getById(2L)).thenReturn(user);
    }

    @Test void resetPasswordRejectsInvalidCode() {
        doThrow(new IllegalArgumentException("Invalid email verification code"))
                .when(email).verify(eq("fixture@example.com"), eq("000000"), eq(EmailVerificationService.PURPOSE_RESET_PASSWORD));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.resetPassword(
                "token", Map.of("password", "a-very-long-password", "emailCode", "000000")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(users, never()).resetPassword(anyLong(), anyString());
    }

    @Test void resetPasswordResetsAfterCodeIsVerified() {
        Map<String, Object> result = controller.resetPassword(
                "token", Map.of("password", "a-very-long-password", "emailCode", "123456"));

        verify(email).verify("fixture@example.com", "123456", EmailVerificationService.PURPOSE_RESET_PASSWORD);
        verify(users).resetPassword(2L, "a-very-long-password");
        assertEquals("Password reset successful", result.get("message"));
    }

    @Test void resetPasswordRequiresBoundEmail() {
        when(auth.requireUser("token3")).thenReturn(new AuthUser(3L, "noemail", "USER"));
        SysUser noEmail = new SysUser();
        noEmail.setId(3L);
        noEmail.setUsername("noemail");
        noEmail.setRole("USER");
        when(users.getById(3L)).thenReturn(noEmail);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.resetPassword(
                "token3", Map.of("password", "a-very-long-password")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(email, never()).verify(anyString(), any(), anyString());
        verify(users, never()).resetPassword(anyLong(), anyString());
    }

    @Test void sendingResetCodeTargetsTheBoundEmail() {
        Map<String, Object> result = controller.sendResetPasswordCode(request, "token");

        verify(email).send(eq("fixture@example.com"), any(), eq(EmailVerificationService.PURPOSE_RESET_PASSWORD));
        assertEquals("fi***@example.com", result.get("email"));
    }

    @Test void updateEmailVerifiesTheNewAddressFirst() {
        SysUser updated = new SysUser();
        updated.setId(2L);
        updated.setUsername("fixture");
        updated.setRole("USER");
        updated.setEmail("new@example.com");
        updated.setEmailUpdatedAt(LocalDateTime.now());
        when(users.changeEmail(2L, "new@example.com")).thenReturn(updated);

        Map<String, Object> result = controller.updateEmail(
                "token", Map.of("email", " New@Example.COM ", "emailCode", "123456"));

        verify(email).verify("new@example.com", "123456", EmailVerificationService.PURPOSE_CHANGE_EMAIL);
        assertEquals("new@example.com", result.get("email"));
    }

    @Test void updateEmailRejectsBadCodeBeforeChanging() {
        doThrow(new IllegalArgumentException("Invalid email verification code"))
                .when(email).verify(eq("new@example.com"), eq("000000"), eq(EmailVerificationService.PURPOSE_CHANGE_EMAIL));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.updateEmail(
                "token", Map.of("email", "new@example.com", "emailCode", "000000")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(users, never()).changeEmail(anyLong(), anyString());
    }

    @Test void rejectingCurrentEmailBeforeSendingCode() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.sendEmailChangeCode(request, "token", Map.of("email", "FIXTURE@example.com")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(email, never()).send(anyString(), any(), anyString());
    }

    @Test void meExposesEmailMetadata() {
        when(email.enabled()).thenReturn(true);

        Map<String, Object> result = controller.me("token");

        assertEquals("fixture@example.com", result.get("email"));
        assertEquals(Boolean.TRUE, result.get("emailVerificationEnabled"));
        assertNull(result.get("emailChangeAvailableAt"));
    }
}
