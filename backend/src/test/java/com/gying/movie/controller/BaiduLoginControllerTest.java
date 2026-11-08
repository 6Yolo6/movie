package com.gying.movie.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.service.impl.BaiduLoginService;
import com.gying.movie.utils.AuthHelper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class BaiduLoginControllerTest {
    final AuthHelper auth = mock(AuthHelper.class);
    final BaiduLoginService service = mock(BaiduLoginService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new BaiduLoginController(auth, service)).build();
        when(auth.requireAdmin("Bearer fixture-admin")).thenReturn(new AuthUser(1L, "fixture", "ADMIN"));
        when(auth.requireAdmin(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(auth.requireAdmin("Bearer fixture-user")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }
    @Test void adminPermissionIsCheckedBeforeLoginCanCreatePrivateState() throws Exception {
        for (String endpoint : List.of("/api/admin/resource-hub/baidu/session", "/api/admin/resource-hub/baidu/login")) {
            var anonymous = endpoint.endsWith("session") ? get(endpoint) : post(endpoint);
            mvc.perform(anonymous).andExpect(status().isUnauthorized());
            var ordinary = endpoint.endsWith("session") ? get(endpoint) : post(endpoint);
            mvc.perform(ordinary.header("Authorization", "Bearer fixture-user")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void loginRejectsUnexpectedFieldsDuplicateJsonAndNonObjects() throws Exception {
        for (String body : List.of("null", "[]", "{\"force\":true}", "{\"nonce\":\"x\",\"nonce\":\"y\"}", "{} {}")) {
            mvc.perform(post("/api/admin/resource-hub/baidu/login").header("Authorization", "Bearer fixture-admin")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void internalImportRejectsUnknownFieldsAndMalformedCookieTypesBeforeService() throws Exception {
        for (String body : List.of("{}", "{\"ticketId\":\"id\",\"nonce\":\"n\",\"cookies\":[]}",
                "{\"ticketId\":\"id\",\"nonce\":\"n\",\"cookies\":[{\"name\":\"BDUSS\",\"value\":\"fixture\",\"domain\":\".baidu.com\",\"path\":\"/\",\"expires\":-1,\"secure\":\"true\"}]}",
                "{\"ticketId\":\"id\",\"nonce\":\"n\",\"cookies\":[],\"allowAccountChange\":true}")) {
            mvc.perform(post("/api/internal/resource-hub/baidu/session").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void validAdminLoginReturnsOnlyServiceMetadata() throws Exception {
        when(service.start()).thenReturn(new BaiduLoginService.SessionStatus(false, "PENDING", "fixture-ticket", 1L, null, null, null));
        mvc.perform(post("/api/admin/resource-hub/baidu/login").header("Authorization", "Bearer fixture-admin")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PENDING"))
                .andExpect(jsonPath("$.data.nonce").doesNotExist()).andExpect(jsonPath("$.data.cookies").doesNotExist());
        verify(service).start();
    }
}
