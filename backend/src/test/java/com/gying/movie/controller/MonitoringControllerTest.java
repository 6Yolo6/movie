package com.gying.movie.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.service.impl.MonitoringService;
import com.gying.movie.utils.AuthHelper;

class MonitoringControllerTest {
    final MonitoringService service = mock(MonitoringService.class);
    final AuthHelper auth = mock(AuthHelper.class);
    MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MonitoringController(service, auth)).build();
        when(auth.requireAdmin("Bearer fixture-admin")).thenReturn(new AuthUser(1L, "fixture", "ADMIN"));
        when(auth.requireAdmin(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(auth.requireAdmin("Bearer fixture-user")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }
    @Test void newEndpointPassesFiltersToOnlyTheSelectedStream() throws Exception {
        when(service.logPage("socialPosts", "fixture", "attention", 3, 50))
                .thenReturn(Map.of("records", List.of(), "total", 101, "page", 3, "size", 50));
        mvc.perform(get("/api/admin/monitoring/logs").header("Authorization", "Bearer fixture-admin")
                .param("type", "socialPosts").param("q", "fixture").param("status", "attention").param("page", "3").param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(101));
        verify(service).logPage("socialPosts", "fixture", "attention", 3, 50);
        verifyNoMoreInteractions(service);
    }
    @Test void legacyConsumersKeepTheirMultiStreamResponse() throws Exception {
        when(service.logs(null, 1, 20)).thenReturn(Map.of("access", List.of()));
        mvc.perform(get("/api/admin/monitoring/logs").header("Authorization", "Bearer fixture-admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access").isArray());
        verify(service).logs(null, 1, 20);
        verifyNoMoreInteractions(service);
    }
    @Test void administratorsCanReadRawIdentityAsDataNotMarkup() throws Exception {
        when(service.logPage("access", null, "all", 1, 20)).thenReturn(Map.of(
                "records", List.of(Map.of("id", 1L, "client_ip", "2001:db8::8", "user_agent", "Fixture <script>not markup</script>")),
                "total", 1, "page", 1, "size", 20));
        mvc.perform(get("/api/admin/monitoring/logs").param("type", "access")
                .header("Authorization", "Bearer fixture-admin"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.records[0].client_ip").value("2001:db8::8"))
                .andExpect(jsonPath("$.records[0].user_agent").value("Fixture <script>not markup</script>"));
    }

    @Test void anonymousAndOrdinaryUsersCannotReadEitherMode() throws Exception {
        for (String suffix : List.of("", "?type=access")) {
            mvc.perform(get("/api/admin/monitoring/logs" + suffix)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/monitoring/logs" + suffix).header("Authorization", "Bearer fixture-user"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/monitoring/overview")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
