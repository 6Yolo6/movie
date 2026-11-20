package com.gying.movie.controller;
import com.gying.movie.config.QqBotProperties;
import com.gying.movie.exception.GlobalExceptionHandler;
import com.gying.movie.service.IQqBotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class QqBotSecurityTest {
    private final String fixture="only-a-test-fixture-not-a-real-token-0001";
    private QqBotProperties properties; private IQqBotService service; private MockMvc mvc;
    private QqBotController controller;
    private com.gying.movie.service.impl.AiAssistantService ai;
    @BeforeEach void setup() {
        properties=new QqBotProperties(); properties.setWebhookToken(fixture);
        service=mock(IQqBotService.class);
        controller=new QqBotController(properties,service);
        ai=mock(com.gying.movie.service.impl.AiAssistantService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"aiAssistant",ai);
        mvc=MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void emptyServerTokenFailsClosed() throws Exception {
        properties.setWebhookToken("");
        mvc.perform(get("/api/qq-bot/search-reply").param("keyword","test")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }
    @Test void queryTokensAreNotCredentials() throws Exception {
        mvc.perform(get("/api/qq-bot/search-reply").param("keyword","test").param("token",fixture))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
    @Test void headerTokenWorksAndDoesNotBypassInputBounds() throws Exception {
        when(service.buildSearchReply("test","member")).thenReturn("fixture reply");
        mvc.perform(get("/api/qq-bot/search-reply").param("keyword","test").param("userKey","member")
                .header("X-QQ-Bot-Token",fixture)).andExpect(status().isOk());
        mvc.perform(get("/api/qq-bot/search-reply").param("keyword","x".repeat(101))
                .header("X-QQ-Bot-Token",fixture)).andExpect(status().isBadRequest());
    }
    @Test void naturalLanguagePreservesUserSelectionAndRejectsInventedTitles() throws Exception {
        when(ai.interpret("有没有星际穿越的资源")).thenReturn(java.util.Map.of("keyword","星际穿越"));
        when(service.buildSearchReply("星际穿越","member")).thenReturn("请选择影片和资源");
        mvc.perform(get("/api/qq-bot/search-reply").param("keyword","自然语言：有没有星际穿越的资源").param("userKey","member")
          .header("X-QQ-Bot-Token",fixture)).andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("请选择影片和资源"));
        verify(service).buildSearchReply("星际穿越","member");
        when(ai.interpret("帮我找电影")).thenReturn(java.util.Map.of("keyword","编造片名"));
        mvc.perform(get("/api/qq-bot/search-reply").param("keyword","自然语言：帮我找电影").header("X-QQ-Bot-Token",fixture)).andExpect(status().isOk());
        verify(service,never()).buildSearchReply(eq("编造片名"),any());
    }
    @Test void healthDoesNotDiscloseGroupIdentifiers() throws Exception {
        mvc.perform(get("/api/qq-bot/health")).andExpect(status().isOk()).andExpect(jsonPath("$.allowedGroups").doesNotExist());
    }
}
