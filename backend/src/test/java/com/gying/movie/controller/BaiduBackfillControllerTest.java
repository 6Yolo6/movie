package com.gying.movie.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.service.impl.BaiduBackfillService;
import com.gying.movie.utils.AuthHelper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class BaiduBackfillControllerTest {
    static final String BASE = "/api/admin/resource-hub/baidu/backfill";
    AuthHelper auth = mock(AuthHelper.class);
    BaiduBackfillService service = mock(BaiduBackfillService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new BaiduBackfillController(auth, service)).build();
        when(auth.requireAdmin("Bearer fixture-admin")).thenReturn(new AuthUser(1L, "fixture", "ADMIN"));
        when(auth.requireAdmin(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(auth.requireAdmin("Bearer fixture-user")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }
    @Test void getPutAndRunRequireAnAdministratorBeforeReadingOrMutatingAnything() throws Exception {
        for (var request : List.of(get(BASE), put(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"), post(BASE + "/run"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.header("Authorization", "Bearer fixture-user")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void settingsRejectCoercionsMissingUnknownDuplicateAndOutOfRangeFields() throws Exception {
        for (String body : List.of("", "null", "[]", "{}", "{} {}",
                "{\"enabled\":\"true\",\"intervalMinutes\":10,\"batchLimit\":1}",
                "{\"enabled\":1,\"intervalMinutes\":10,\"batchLimit\":1}",
                "{\"enabled\":true,\"intervalMinutes\":\"10\",\"batchLimit\":1}",
                "{\"enabled\":true,\"intervalMinutes\":10.0,\"batchLimit\":1}",
                "{\"enabled\":true,\"intervalMinutes\":0,\"batchLimit\":1}",
                "{\"enabled\":true,\"intervalMinutes\":1441,\"batchLimit\":1}",
                "{\"enabled\":true,\"intervalMinutes\":10,\"batchLimit\":6}",
                "{\"enabled\":true,\"intervalMinutes\":10,\"batchLimit\":0}",
                "{\"enabled\":true,\"enabled\":false,\"intervalMinutes\":10,\"batchLimit\":1}",
                "{\"enabled\":true,\"intervalMinutes\":10,\"batchLimit\":1,\"force\":true}")) {
            mvc.perform(put(BASE).header("Authorization", "Bearer fixture-admin").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void runRejectsInputsThatCouldOverrideThePersistedLimitProviderOrFilePolicy() throws Exception {
        for (String body : List.of("null", "[]", "true", "{} {}", "{\"force\":true}", "{\"batchLimit\":999}", "{\"provider\":\"QUARK\"}", "{\"a\":1,\"a\":2}")) {
            mvc.perform(post(BASE + "/run").header("Authorization", "Bearer fixture-admin").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void validBasePutAndEmptyRunReturnTheSamePublicStateShape() throws Exception {
        var state = new BaiduBackfillService.Status(7L, false, "PAUSED", "GYING_TORRENTS", 10, 1, 0, 0, 0, 0, null, null, null, null, null, null, null, List.of(), "IDLE", List.of(), 0, null, null, null, 17580L);
        when(service.settings(false, 10, 1)).thenReturn(state); when(service.runOnce()).thenReturn(state); when(service.status()).thenReturn(state);
        mvc.perform(put(BASE).header("Authorization", "Bearer fixture-admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"intervalMinutes\":10,\"batchLimit\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taskId").value(7)).andExpect(jsonPath("$.data.batchLimit").value(1)).andExpect(jsonPath("$.data.currentAction").value("IDLE"))
                .andExpect(jsonPath("$.data.currentResourceIds").isEmpty()).andExpect(jsonPath("$.data.legacyTaskId").value(17580));
        mvc.perform(get(BASE).header("Authorization", "Bearer fixture-admin")).andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("GYING_TORRENTS"));
        mvc.perform(post(BASE + "/run").header("Authorization", "Bearer fixture-admin").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAUSED"));
        verify(service).settings(false, 10, 1); verify(service).runOnce(); verify(service).status();
    }
}
