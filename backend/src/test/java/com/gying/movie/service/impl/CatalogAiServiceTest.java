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
}
