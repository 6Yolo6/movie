package com.gying.movie.controller;

import com.gying.movie.dto.AdminCreateUserRequest;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
}
