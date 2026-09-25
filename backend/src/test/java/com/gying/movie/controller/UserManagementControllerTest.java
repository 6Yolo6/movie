package com.gying.movie.controller;

import com.gying.movie.dto.AdminCreateUserRequest;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.conditions.Wrapper;

import org.springframework.http.HttpStatus;
import java.util.Map;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserManagementControllerTest {
    final ISysUserService users = mock(ISysUserService.class);
    final AuthHelper auth = mock(AuthHelper.class);
    final UserManagementController controller = new UserManagementController(users, auth, mock(JwtUtils.class));
    AdminCreateUserRequest request;
    @BeforeEach void setup() {
        request = new AdminCreateUserRequest(); request.setUsername("fixture");
        request.setEmail("FIXTURE@example.com"); request.setPassword("fixture-password");
        when(auth.requireAdmin("admin")).thenReturn(new AuthUser(1L, "admin", "ADMIN"));
        SysUser user = new SysUser(); user.setId(2L); user.setUsername("fixture");user.setEmail("fixture@example.com");user.setRole("USER");user.setPassword("must-never-be-returned");
        when(users.register("fixture", "fixture-password", "fixture@example.com", null)).thenReturn(user);
        when(users.updateById(any())).thenReturn(true);
    }
    @Test void createsUserWithoutInvitationAndDoesNotReturnPassword() {
        var result=controller.createUser(request,"admin");assertEquals(201,result.getStatusCode().value());
        assertFalse(result.getBody().toString().contains("password"));assertFalse(result.getBody().toString().contains("must-never"));
        verify(users).register("fixture","fixture-password","fixture@example.com",null);
        verify(users,never()).updateById(any());
    }
    @Test void createsPublisherAndRejectsAdminRole() {
        request.setRole("PUBLISHER");assertEquals(201,controller.createUser(request,"admin").getStatusCode().value());
        verify(users).updateById(argThat(user -> "PUBLISHER".equals(user.getRole())));
        clearInvocations(users);request.setRole("ADMIN");assertEquals(400,controller.createUser(request,"admin").getStatusCode().value());verifyNoInteractions(users);
    }
    @Test void unauthenticatedAndOrdinaryUsersCannotCreateAccounts() {
        for(String token:new String[]{null,"user"}){
            when(auth.requireAdmin(token)).thenThrow(new ResponseStatusException(token==null?HttpStatus.UNAUTHORIZED:HttpStatus.FORBIDDEN));
            assertThrows(ResponseStatusException.class,()->controller.createUser(request,token));
        }
        verifyNoInteractions(users);
    }
    @Test void duplicateConflictPreserved() {
        when(users.register(anyString(),anyString(),anyString(),isNull())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"duplicate"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.createUser(request,"admin")).getStatusCode().value());
    }
    @Test void validatesShortPasswordsAndInvalidRolesAtHttpBoundary() throws Exception {
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.gying.movie.exception.GlobalExceptionHandler()).build();
        mvc.perform(post("/api/admin/users").header("Authorization","admin").contentType("application/json")
                .content("{\"username\":\"fixture\",\"email\":\"fixture@example.com\",\"password\":\"short\",\"role\":\"USER\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    private SysUser managedUser() {
        SysUser u = new SysUser();
        u.setId(2L); u.setUsername("fixture"); u.setEmail("fixture@example.com");
        u.setRole("USER"); u.setEnabled(true);
        return u;
    }

    @SuppressWarnings("unchecked")
    private void noDuplicates() {
        when(users.count(any(Wrapper.class))).thenReturn(0L);
    }

    @Test void updatesAnotherUserProfileAndNormalizesEmail() {
        SysUser target = managedUser();
        when(users.getById(2L)).thenReturn(target);
        noDuplicates();
        var result = controller.updateUser(2L, Map.of("username", "renamed", "email", "New@Example.com"), "admin");
        assertEquals(200, result.getStatusCode().value());
        assertEquals("renamed", target.getUsername());
        assertEquals("new@example.com", target.getEmail());
        verify(users).updateById(target);
    }

    @Test void rejectsTakenUsernameOrEmail() {
        SysUser target = managedUser();
        when(users.getById(2L)).thenReturn(target);
        when(users.count(any(Wrapper.class))).thenReturn(1L);
        assertEquals(409, controller.updateUser(2L, Map.of("username", "taken"), "admin").getStatusCode().value());
        assertEquals(409, controller.updateUser(2L, Map.of("email", "taken@example.com"), "admin").getStatusCode().value());
        verify(users, never()).updateById(any());
    }

    @Test void rejectsShortUsernameAndInvalidRole() {
        when(users.getById(2L)).thenReturn(managedUser());
        noDuplicates();
        assertEquals(400, controller.updateUser(2L, Map.of("username", "ab"), "admin").getStatusCode().value());
        assertEquals(400, controller.updateUser(2L, Map.of("role", "ADMIN"), "admin").getStatusCode().value());
        verify(users, never()).updateById(any());
    }

    @Test void preventsChangingOwnRole() {
        SysUser self = managedUser(); self.setId(1L); self.setRole("ADMIN");
        when(users.getById(1L)).thenReturn(self);
        noDuplicates();
        assertEquals(400, controller.updateUser(1L, Map.of("role", "USER"), "admin").getStatusCode().value());
        verify(users, never()).updateById(any());
    }

    @Test void resetsPasswordAndRevokesSessions() {
        when(users.getById(2L)).thenReturn(managedUser());
        var result = controller.resetUserPassword(2L, Map.of("password", "a-very-long-password"), "admin");
        assertEquals(200, result.getStatusCode().value());
        assertTrue(result.getBody().toString().contains("sessionsRevoked=true"));
        verify(users).resetPassword(2L, "a-very-long-password");
    }

    @Test void flagsSelfPasswordReset() {
        SysUser self = managedUser(); self.setId(1L); self.setRole("ADMIN");
        when(users.getById(1L)).thenReturn(self);
        var result = controller.resetUserPassword(1L, Map.of("password", "a-very-long-password"), "admin");
        assertEquals(200, result.getStatusCode().value());
        assertTrue(result.getBody().toString().contains("self=true"));
    }

    @Test void rejectsShortResetPassword() {
        assertEquals(400, controller.resetUserPassword(2L, Map.of("password", "short"), "admin").getStatusCode().value());
        verify(users, never()).resetPassword(anyLong(), anyString());
    }

    @Test void missingUserReturnsNotFound() {
        when(users.getById(99L)).thenReturn(null);
        noDuplicates();
        assertEquals(404, controller.updateUser(99L, Map.of("username", "valid-name"), "admin").getStatusCode().value());
        assertEquals(404, controller.resetUserPassword(99L, Map.of("password", "a-very-long-password"), "admin").getStatusCode().value());
    }

    @Test void nonAdminCannotEditOrResetPassword() {
        when(auth.requireAdmin("user")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> controller.updateUser(2L, Map.of("username", "valid-name"), "user"));
        assertThrows(ResponseStatusException.class, () -> controller.resetUserPassword(2L, Map.of("password", "a-very-long-password"), "user"));
        verify(users, never()).updateById(any());
        verify(users, never()).resetPassword(anyLong(), anyString());
    }
}
