package com.gying.movie.controller;
import com.fasterxml.jackson.databind.*;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.security.RedisRateLimiter;
import com.gying.movie.service.impl.*;
import com.gying.movie.utils.AuthHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CatalogPaginationContextTest {
 @Test void rememberedSecondPageUsesItsOwnCandidatesAndKeepsOwnerBoundary(){
  var m=new ObjectMapper();var auth=mock(AuthHelper.class);var catalog=mock(CatalogAiService.class);var jobs=new AiAssistantService(m);
  when(auth.requireUser("owner")).thenReturn(new AuthUser(1L,"owner","USER"));var controller=new CatalogAiController(auth,catalog,mock(JdbcTemplate.class),mock(RedisRateLimiter.class));controller.setJobs(jobs);
  var answer=m.createObjectNode().put("answer","第二页");answer.putObject("catalog").put("kind","title_search").put("keyword","样片").put("page",2);answer.putArray("evidence").addObject().putArray("movies").addObject().put("id","page_2").put("titleCn","第二页影片");when(catalog.search("样片",2,20)).thenReturn(answer);
  var result=controller.search("owner","样片",2,20,true);String id=result.path("jobId").asText();assertThat(id).isNotBlank();
  var follow=m.createObjectNode().put("message","第一部谁主演").put("previousJobId",id);assertThat(jobs.prepareRequest(1L,follow).path("context").path("candidateIds").get(0).asText()).isEqualTo("page_2");
  assertThatThrownBy(()->jobs.prepareRequest(2L,follow)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
}
