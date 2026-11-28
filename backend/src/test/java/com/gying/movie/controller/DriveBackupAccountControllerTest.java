package com.gying.movie.controller;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gying.movie.client.XunleiBackupAccounts;
import com.gying.movie.service.impl.BaiduLoginService;
import com.gying.movie.utils.AuthHelper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class DriveBackupAccountControllerTest {
    private static final String BASE = "/api/admin/resource-hub/backup-accounts";
    private static final String AUTH = "Authorization";
    private static final String ADMIN = "Bearer fixture-admin";
    private final AuthHelper auth = mock(AuthHelper.class);
    private final XunleiBackupAccounts xunlei = mock(XunleiBackupAccounts.class);
    private final BaiduLoginService baidu = mock(BaiduLoginService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DriveBackupAccountController(auth, xunlei, baidu)).build();

    @Test void onlyAdminsCanReadSlotsOrEnrollAccounts() throws Exception {
        when(auth.requireAdmin(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(auth.requireAdmin("Bearer fixture-user")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        mvc.perform(get(BASE + "/xunlei/1")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/baidu/2").header(AUTH, "Bearer fixture-user")).andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/xunlei/1").header(AUTH, "Bearer fixture-user")
                .contentType(MediaType.APPLICATION_JSON).content("{\"authorization\":\"fixture-session\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/baidu/2/login")).andExpect(status().isUnauthorized());
        verifyNoInteractions(xunlei, baidu);
    }

    @Test void enrollmentMatchesTheRequestedSlotAndReturnsOnlyStatus() throws Exception {
        when(xunlei.save(3, "fixture-session")).thenReturn(new XunleiBackupAccounts.Status(true, 123L, false));
        when(baidu.startBackup(2)).thenReturn(new BaiduLoginService.SessionStatus(false, "PENDING", "fixture-ticket",
                123L, null, null, null));
        mvc.perform(put(BASE + "/xunlei/3").header(AUTH, ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorization\":\"fixture-session\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.authorization").doesNotExist()).andExpect(jsonPath("$.data.accessToken").doesNotExist());
        mvc.perform(post(BASE + "/baidu/2/login").header(AUTH, ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PENDING"))
                .andExpect(jsonPath("$.data.nonce").doesNotExist()).andExpect(jsonPath("$.data.cookies").doesNotExist());
    }

    @Test void requestsCannotOverrideTheSlotOrSupplyBaiduCredentials() throws Exception {
        mvc.perform(get(BASE + "/xunlei/4").header(AUTH, ADMIN)).andExpect(status().isBadRequest());
        for (String body : List.of("{\"backupSlot\":1}", "{\"cookies\":[]}", "{} {}")) {
            mvc.perform(post(BASE + "/baidu/2/login").header(AUTH, ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(xunlei, baidu);
    }
}
