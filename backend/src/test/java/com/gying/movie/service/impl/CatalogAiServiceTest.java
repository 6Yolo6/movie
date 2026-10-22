package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogAiServiceTest {
    @Test void naturalTitleSearchRemovesPrefixAndSeriesRequest() {
        assertEquals("蜘蛛侠",CatalogAiService.intent("我想看蜘蛛侠").keyword());
        assertEquals("蜘蛛侠",CatalogAiService.intent("蜘蛛侠系列电影").keyword());
        assertEquals("蜘蛛侠",CatalogAiService.intent("帮我搜索 《蜘蛛侠》").keyword());
        assertEquals("Spider-Man",CatalogAiService.intent("search Spider-Man").keyword());
        assertEquals("n",CatalogAiService.intent("Batman").keyword().substring(5));
    }
    @Test void preferenceConstraintsAndFollowupsKeepSeparateRoutes() {
        var intent=CatalogAiService.intent("推荐2023年的高分科幻动作电影");
        assertTrue(intent.recommend());assertEquals(2023,intent.constraints().year());
        assertEquals(Set.of("科幻","动作"),intent.constraints().genres());assertEquals(8,intent.constraints().minRating());
        assertTrue(CatalogAiService.intent("推荐剧情电影").recommend());
        assertFalse(CatalogAiService.intent("爱情公寓").recommend());
        assertNull(CatalogAiService.intent("这部电影有哪些资源"));
        assertNull(CatalogAiService.intent("删除所有蜘蛛侠"));
        assertNull(CatalogAiService.intent("为什么没有结果"));
    }
    @SuppressWarnings("unchecked")
    @Test void paginationPreservesCompleteCountAndEscapesWildcards() {
        var jdbc=mock(JdbcTemplate.class);var service=new CatalogAiService(jdbc,new ObjectMapper());
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(),any(),any(),any())).thenReturn(41L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var result=service.search("蜘蛛侠%_",3,20);
        assertEquals(41,result.path("catalog").path("total").asInt());
        assertEquals(3,result.path("catalog").path("pages").asInt());
        assertEquals(3,result.path("catalog").path("page").asInt());
        verify(jdbc).queryForObject(contains("deleted_at IS NULL"),eq(Long.class),eq("%蜘蛛侠!%!_%"),eq("%蜘蛛侠!%!_%"),eq("%蜘蛛侠!%!_%"),eq("%蜘蛛侠!%!_%"));
        assertThrows(ResponseStatusException.class,()->service.search(" ",1,20));
    }
    @SuppressWarnings("unchecked")
    @Test void actualUserQueriesNeverFallThroughToSentenceTitleLookup() {
        var jdbc=mock(JdbcTemplate.class);var mapper=new ObjectMapper();var service=new CatalogAiService(jdbc,mapper);
        var mystery=new CatalogRecommendationEngine.Film("m","悬疑样本",null,2024,"mv",null,null,List.of("悬疑"),List.of(),List.of("小罗伯特·唐尼"),8.5,1,0);
        when(jdbc.query(anyString(),any(RowMapper.class))).thenReturn(List.of(mystery));
        for(String query:List.of("想看几部高分悬疑片","小罗伯特唐尼演的片")) {
            var response=service.answer(1L,mapper.createObjectNode().put("message",query));
            var result=(com.fasterxml.jackson.databind.JsonNode)response.get("result");
            assertNotEquals("search",result.path("catalog").path("kind").asText());
            assertEquals(1,result.path("evidence").get(0).path("movies").size());
            assertEquals(0,result.path("evidence").get(0).path("movies").get(0).path("resourceCount").asInt());
            assertTrue(result.path("trace").isArray());
        }
        verify(jdbc,never()).queryForObject(contains("LIKE"),eq(Long.class),any(),any(),any(),any());
        assertNull(service.answer(1L,mapper.createObjectNode().put("message","这部电影剧情是什么")));
        assertNull(service.answer(1L,mapper.createObjectNode().put("message","联网查询星际穿越剧情")));
    }
}
