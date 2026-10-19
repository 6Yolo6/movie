package com.gying.movie.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.security.RedisRateLimiter;
import com.gying.movie.service.impl.AiAssistantService;
import com.gying.movie.utils.AuthHelper;
import com.gying.movie.utils.AiBridgeAuth;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiAssistantControllerTest {
 @Test void requiresUserSessionBeforeQueueingAndDedicatedBridgeTokenForInternalRoutes() {
  var auth=mock(AuthHelper.class);var limits=mock(RedisRateLimiter.class);var service=new AiAssistantService(new ObjectMapper());
  var internal=new AiBridgeAuth("fixture-only-dedicated-ai-credential-0001");
  var controller=new AiAssistantController(auth,internal,limits,service);
  when(auth.requireUser(null)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
  assertThrows(ResponseStatusException.class,()->controller.query(new ObjectMapper().createObjectNode().put("message","星际穿越"),null));
  assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.claim("wrong")).getStatusCode().value());
  assertTrue(controller.claim("fixture-only-dedicated-ai-credential-0001").isEmpty());
  when(auth.requireUser("owner")).thenReturn(new AuthUser(1L,"owner","USER"));
  var response=controller.query(new ObjectMapper().createObjectNode().put("message","星际穿越"),"owner");assertEquals(202,response.getStatusCode().value());
  assertNotNull(response.getBody().get("jobId"));
  verify(limits).require(eq("ai-user"),eq("1"),eq(6),any());
 }
}
