package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.controller.CatalogAiController;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.security.RedisRateLimiter;
import com.gying.movie.utils.AuthHelper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QUERY_BOUNDARY_MYSQL_FIXTURE",matches="network-none-tmpfs")
class CatalogAiMySqlTest {
    private static JdbcTemplate jdbc;
    private static CatalogAiService catalog;
    private static final ObjectMapper mapper=new ObjectMapper();
    @BeforeAll static void freshMarkedFixtureOnly() {
        String id=System.getenv("QUERY_BOUNDARY_FIXTURE_ID");assertNotNull(id);assertTrue(id.matches("[0-9a-f]{12}"));
        String url="jdbc:mysql://127.0.0.1:3306/query_boundary_fixture_"+id;
        assertEquals(url,System.getenv("QUERY_BOUNDARY_MYSQL_URL"));
        var ds=new DriverManagerDataSource(url,"root","");
        var props=new Properties();props.setProperty("connectTimeout","3000");props.setProperty("socketTimeout","15000");props.setProperty("connectionTimeZone","Asia/Shanghai");ds.setConnectionProperties(props);
        jdbc=new JdbcTemplate(ds);
        assertEquals("gying-query-fixture",jdbc.queryForObject("SELECT @@hostname",String.class));
        assertEquals(List.of("fixture_marker"),jdbc.queryForList("SHOW TABLES",String.class));
        assertEquals(id,jdbc.queryForObject("SELECT run_id FROM fixture_marker",String.class));
        jdbc.execute("CREATE TABLE movie_metadata(id VARCHAR(64) PRIMARY KEY,title_cn VARCHAR(255),title_en VARCHAR(255),aliases JSON,series_name VARCHAR(255),year INT,season INT,category VARCHAR(8),poster_url VARCHAR(255),genres JSON,directors JSON,actors JSON,summary TEXT,regions JSON,languages JSON,runtime VARCHAR(100),douban_score DOUBLE,imdb_score DOUBLE,tmdb_vote_average DOUBLE,tmdb_popularity DOUBLE,popularity INT,status VARCHAR(20),deleted_at DATETIME) CHARACTER SET utf8mb4");
        jdbc.execute("CREATE TABLE resource_link(id BIGINT PRIMARY KEY,movie_id VARCHAR(64),status VARCHAR(20),audit_status INT,deleted_at DATETIME,link_status VARCHAR(20))");
        jdbc.execute("CREATE TABLE user_favorite(user_id BIGINT,movie_id VARCHAR(64),created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE resource_operation_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,movie_id VARCHAR(64),operation_type VARCHAR(20),status VARCHAR(20) DEFAULT 'SUCCESS',created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        for(int n=1;n<=45;n++)jdbc.update("INSERT INTO movie_metadata(id,title_cn,title_en,year,category,genres,directors,actors,douban_score,tmdb_popularity,status) VALUES(?,?,?,2020,'mv','[\"科幻\"]','[]','[]',8,20,'ACTIVE')","spider_"+n,"蜘蛛侠"+n,"Spider-Man "+n);
        jdbc.update("INSERT INTO movie_metadata(id,title_cn,category,genres,douban_score,status) VALUES('romance','爱情片','mv','[\"爱情\"]',9,'ACTIVE'),('gone','蜘蛛侠删除','mv','[]',9,'ACTIVE'),('inactive','蜘蛛侠停用','mv','[]',9,'INACTIVE'),('alias','小蜘蛛','tv','[\"科幻\"]',8,'ACTIVE'),('wild','片名%_','mv','[]',8,'ACTIVE')");
        jdbc.update("UPDATE movie_metadata SET deleted_at=NOW() WHERE id='gone'");jdbc.update("UPDATE movie_metadata SET aliases='[\"蜘蛛侠\"]' WHERE id='alias'");
        jdbc.update("INSERT INTO resource_link VALUES(1,'spider_1','ACTIVE',1,NULL,'NORMAL'),(2,'spider_1','ACTIVE',0,NULL,'NORMAL'),(3,'spider_1','ACTIVE',1,NULL,'INVALID')");
        jdbc.update("INSERT INTO user_favorite(user_id,movie_id) VALUES(1,'spider_1'),(2,'romance')");
        jdbc.update("UPDATE movie_metadata SET summary='宇航员穿越时空寻找人类新的家园',runtime='118分钟',regions='[\"美国\"]',languages='[\"英语\"]' WHERE id='spider_1'");
        jdbc.update("UPDATE movie_metadata SET summary='时间循环中的机器人',runtime='100分钟',year=2024 WHERE id='spider_2'");
        catalog=new CatalogAiService(jdbc,mapper);
    }
    @AfterAll static void removeOnlyThisTestClassTables() {
        if(jdbc==null)return;
        String id=System.getenv("QUERY_BOUNDARY_FIXTURE_ID");
        assertEquals("gying-query-fixture",jdbc.queryForObject("SELECT @@hostname",String.class));
        assertEquals("query_boundary_fixture_"+id,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(id,jdbc.queryForObject("SELECT run_id FROM fixture_marker",String.class));
        // Only this marked network-none fixture owns these tables; leave its marker intact.
        for(String table:List.of("resource_operation_log","user_favorite","resource_link","movie_metadata"))jdbc.execute("DROP TABLE "+table);
    }
    @Test void completeSearchHasNoThreeRowLimitAndPagesDoNotOverlap() {
        var first=catalog.search("蜘蛛侠",1,20);var last=catalog.search("蜘蛛侠",3,20);
        assertEquals(46,first.path("catalog").path("total").asInt());assertEquals(20,first.path("evidence").get(0).path("movies").size());
        assertEquals(6,last.path("evidence").get(0).path("movies").size());
        Set<String> ids=new HashSet<>();for(int page=1;page<=3;page++)catalog.search("蜘蛛侠",page,20).path("evidence").get(0).path("movies").forEach(m->assertTrue(ids.add(m.path("id").asText())));
        assertEquals(46,ids.size());assertFalse(ids.contains("gone"));assertFalse(ids.contains("inactive"));
    }
    @Test void wildcardIsLiteralAndResourceCountRequiresReviewedNormalRows() {
        assertEquals(1,catalog.search("%_",1,20).path("catalog").path("total").asInt());
        var films=catalog.search("蜘蛛侠1",1,30).path("evidence").get(0).path("movies");
        assertEquals(1,films.get(0).path("resourceCount").asInt());
    }
    @Test void profilesRemainPerUserAndColdStartIsExplicit() {
        var a=catalog.recommendations(1L,CatalogRecommendationEngine.Constraints.none(),12);
        var b=catalog.recommendations(2L,CatalogRecommendationEngine.Constraints.none(),12);
        var c=catalog.recommendations(999L,CatalogRecommendationEngine.Constraints.none(),12);
        assertTrue(a.path("catalog").path("personalized").asBoolean());assertFalse(c.path("catalog").path("personalized").asBoolean());
        assertTrue(a.path("evidence").get(0).path("movies").get(0).path("genres").toString().contains("科幻"));
        assertNotEquals(a.path("evidence").get(0).path("movies"),b.path("evidence").get(0).path("movies"));
        assertFalse(a.toString().contains("user_id"));assertFalse(a.toString().contains("collaborative"));
    }
    @Test void authenticatedViewsDeduplicateAndDoNotRecordUnknownOrInactiveMovies() {
        var auth=mock(AuthHelper.class);when(auth.requireUser("owner")).thenReturn(new AuthUser(77L,"fixture","USER"));
        var controller=new CatalogAiController(auth,catalog,jdbc,mock(RedisRateLimiter.class));
        var body=mapper.createObjectNode().put("movieId","spider_3");
        assertEquals(true,controller.view("owner",body).get("recorded"));assertEquals(false,controller.view("owner",body).get("recorded"));
        assertEquals(false,controller.view("owner",mapper.createObjectNode().put("movieId","inactive")).get("recorded"));
        assertEquals(false,controller.view("owner",mapper.createObjectNode().put("movieId","missing")).get("recorded"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM resource_operation_log WHERE user_id=77",Integer.class));
        assertTrue(catalog.recommendations(77L,CatalogRecommendationEngine.Constraints.none(),12).path("catalog").path("personalized").asBoolean());
    }
    @Test void relatedTitlesArePublicRelevantAndExcludeSelfDeletedAndInactive() {
        var service = new CatalogAiService(jdbc, mapper);
        var controller = new com.gying.movie.controller.MovieRecommendationController(service);
        var related = controller.recommendations("spider_1", 8);
        assertEquals(8, related.size());
        assertTrue(related.stream().allMatch(m -> m.path("category").asText().equals("mv") && m.path("genres").toString().contains("科幻")));
        assertTrue(related.stream().noneMatch(m -> Set.of("spider_1", "alias", "gone", "inactive", "romance").contains(m.path("id").asText())));
        assertEquals(12, controller.recommendations("spider_1", 100).size());
        assertTrue(controller.recommendations("romance", 8).isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.recommendations("gone", 8)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.recommendations("../bad", 8)).getStatusCode().value());
    }
    @Test void authenticationAndRequestValidationPrecedeRecording() {
        var auth=mock(AuthHelper.class);when(auth.requireUser(null)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        when(auth.requireUser("owner")).thenReturn(new AuthUser(78L,"fixture","USER"));
        var controller=new CatalogAiController(auth,catalog,jdbc,mock(RedisRateLimiter.class));
        assertThrows(ResponseStatusException.class,()->controller.search(null,"蜘蛛侠",1,20));
        assertThrows(ResponseStatusException.class,()->controller.recommendations(null));
        assertThrows(ResponseStatusException.class,()->controller.view(null,mapper.createObjectNode().put("movieId","spider_1")));
        assertThrows(ResponseStatusException.class,()->controller.view("owner",mapper.createObjectNode().put("movieId","spider_1").put("userId",1)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM resource_operation_log WHERE user_id=78",Integer.class));
    }

    @Test void plotSearchUsesRealSummaryAndStablePages() {
        var result=catalog.naturalSearch("找关于太空的科幻电影，不超过两小时",1,20);
        assertEquals("natural_search",result.path("catalog").path("kind").asText());
        assertEquals(1,result.path("catalog").path("total").asInt());
        var movie=result.path("evidence").get(0).path("movies").get(0);
        assertEquals("spider_1",movie.path("id").asText());assertTrue(movie.path("summary").asText().contains("宇航员"));
        assertFalse(movie.path("reasons").isEmpty());
        var first=catalog.naturalSearch("找高分科幻电影",1,7);
        Set<String> ids=new HashSet<>();int pages=first.path("catalog").path("pages").asInt();
        for(int n=1;n<=pages;n++)catalog.naturalSearch("找高分科幻电影",n,7).path("evidence").get(0).path("movies").forEach(m->assertTrue(ids.add(m.path("id").asText())));
        assertEquals(first.path("catalog").path("total").asInt(),ids.size());
    }
    @Test void recentMindBendingScenarioAndNoAnswerStayGrounded() {
        var result=catalog.answer(1L,mapper.createObjectNode().put("message","找近几年偏烧脑、时长不超过两小时的科幻电影"));
        var answer=(com.fasterxml.jackson.databind.JsonNode)result.get("result");
        assertEquals("spider_2",answer.path("evidence").get(0).path("movies").get(0).path("id").asText());
        assertEquals(0,catalog.naturalSearch("找关于量子海豚火山的电影",1,20).path("catalog").path("total").asInt());
        var auth=mock(AuthHelper.class);when(auth.requireUser(null)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        assertThrows(ResponseStatusException.class,()->new CatalogAiController(auth,catalog,jdbc,mock(RedisRateLimiter.class)).naturalSearch(null,"",1,20));
    }
    @Test void realSqlActorAndShortGenreQueriesReturnFilmsWithMissingResourceStatus() {
        jdbc.update("INSERT INTO movie_metadata(id,title_cn,category,genres,actors,douban_score,status) VALUES('actor_case','演员专用样本','mv','[\"悬疑\"]','[\"小罗伯特·唐尼\"]',8.8,'ACTIVE')");
        var service=new CatalogAiService(jdbc,mapper);
        for(String query:List.of("想看几部高分悬疑片","小罗伯特唐尼演的片")) {
            var response=service.answer(999L,mapper.createObjectNode().put("message",query));
            var result=(com.fasterxml.jackson.databind.JsonNode)response.get("result");
            assertEquals("actor_case",result.path("evidence").get(0).path("movies").get(0).path("id").asText());
            assertEquals(0,result.path("evidence").get(0).path("movies").get(0).path("resourceCount").asInt());
            assertNotEquals("search",result.path("catalog").path("kind").asText());
        }
    }
}
