package com.gying.movie.controller;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.service.IQqBotService;
import com.gying.movie.utils.AuthHelper;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebResourceSearchControllerTest {
    private final AuthHelper auth = mock(AuthHelper.class);
    private final IQqBotService service = mock(IQqBotService.class);
    private final WebResourceSearchController controller = new WebResourceSearchController(auth, service);
    @AfterEach void stop() { controller.shutdown(); }

    @Test void acceptsImmediatelyDeduplicatesAndIsolatesUsers() throws Exception {
        when(auth.requireUser("owner")).thenReturn(new AuthUser(1L, "fixture", "USER"));
        when(auth.requireUser("other")).thenReturn(new AuthUser(2L, "other", "USER"));
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(service.buildSearchReply(anyString(), eq("web:1"))).thenAnswer(i -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "https://pan.quark.cn/s/fixture";
        });
        var response = controller.query(Map.of("keyword", "movie"), "owner");
        assertEquals(202, response.getStatusCode().value());
        String id = (String) response.getBody().get("jobId");
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(id, controller.query(Map.of("keyword", "1"), "owner").getBody().get("jobId"));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.getJob(id, "other")).getStatusCode().value());
        release.countDown();
        Map<String, Object> job = completed(id);
        assertEquals("SUCCEEDED", job.get("status"));
        assertTrue(job.get("links").toString().contains("pan.quark.cn"));
        verify(service, times(1)).buildSearchReply(anyString(), anyString());
    }

    @Test void failureIsPollableAndDoesNotLeakUpstreamBody() throws Exception {
        when(auth.requireUser("owner")).thenReturn(new AuthUser(1L, "fixture", "USER"));
        when(service.buildSearchReply(anyString(), anyString())).thenThrow(new IllegalStateException("private upstream secret"));
        String id = (String) controller.query(Map.of("keyword", "movie"), "owner").getBody().get("jobId");
        Map<String, Object> job = completed(id);
        assertEquals("FAILED", job.get("status"));
        assertFalse(job.toString().contains("private upstream"));
    }

    private Map<String, Object> completed(String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            var result = controller.getJob(id, "owner");
            if ("SUCCEEDED".equals(result.get("status")) || "FAILED".equals(result.get("status"))) return result;
            Thread.sleep(10);
        }
        fail("Job did not finish"); return Map.of();
    }
}
