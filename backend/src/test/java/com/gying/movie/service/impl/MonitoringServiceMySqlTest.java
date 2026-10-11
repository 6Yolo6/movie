package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only runs against a fresh, explicitly named loopback fixture database, never application configuration. */
@EnabledIfEnvironmentVariable(named = "MONITORING_TEST_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/monitoring_fixture_[0-9]+.*")
class MonitoringServiceMySqlTest {
    static JdbcTemplate jdbc;
    static MonitoringService service;
    @BeforeAll static void initializeIsolatedFixture() {
        String url = System.getenv("MONITORING_TEST_MYSQL_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/monitoring_fixture_[0-9]+(?:\\?.*)?"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", System.getenv("MONITORING_TEST_MYSQL_PASSWORD")));
        assertTrue(jdbc.queryForList("SHOW TABLES").isEmpty(), "Fixture database must be brand-new and empty");
        jdbc.execute("CREATE TABLE site_access_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,request_path VARCHAR(500),method VARCHAR(10),status_code INT,duration_ms BIGINT,event_type VARCHAR(30),visitor_hash VARCHAR(100),created_at DATETIME)");
        jdbc.execute("CREATE TABLE site_search_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,keyword VARCHAR(100),source VARCHAR(30),result_count INT,created_at DATETIME)");
        jdbc.execute("CREATE TABLE resource_operation_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,movie_id VARCHAR(64),resource_link_id BIGINT,operation_type VARCHAR(30),provider VARCHAR(50),status VARCHAR(30),error_message VARCHAR(500),created_at DATETIME)");
        jdbc.execute("CREATE TABLE qq_bot_search_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,keyword VARCHAR(100),status VARCHAR(40),movie_id VARCHAR(64),resource_count INT,failure_reason VARCHAR(500),created_at DATETIME)");
        jdbc.execute("CREATE TABLE social_post_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,platform VARCHAR(30),title VARCHAR(200),status VARCHAR(40),error_message VARCHAR(500),posted_at DATETIME,created_at DATETIME)");
        for (int i = 1; i <= 85; i++) jdbc.update("INSERT INTO site_access_log(request_path,method,status_code,duration_ms,event_type,visitor_hash,created_at) VALUES (?,'GET',?,42,'API','api-visitor',NOW())", "/api/fixture/" + i, i == 1 ? 503 : i == 2 ? 404 : 200);
        for (String visitor : List.of("page-a", "page-a", "page-b")) jdbc.update("INSERT INTO site_access_log(request_path,method,status_code,duration_ms,event_type,visitor_hash,created_at) VALUES ('/','VIEW',200,0,'PAGE_VIEW',?,NOW())", visitor);
        jdbc.update("INSERT INTO site_access_log(request_path,method,status_code,duration_ms,event_type,visitor_hash,created_at) VALUES ('/yesterday','GET',200,1,'API','older',DATE_SUB(NOW(),INTERVAL 1 DAY))");
        jdbc.update("INSERT INTO site_search_log(keyword,source,result_count,created_at) VALUES ('fixture','WEB',2,NOW()),('missing','WEB',0,NOW())");
        jdbc.update("INSERT INTO resource_operation_log(movie_id,resource_link_id,operation_type,provider,status,error_message,created_at) VALUES ('literal%_!',1,'SHARE','QUARK','FAILED','fixture',NOW()),('literalXX',2,'SHARE','QUARK','SUCCESS',NULL,NOW())");
        jdbc.update("INSERT INTO qq_bot_search_log(keyword,status,movie_id,resource_count,created_at) VALUES ('fixture','LIBRARY_RESOURCE','fixture',2,NOW()),('missing','NO_RESOURCE',NULL,0,NOW())");
        for (String status : List.of("POSTED", "POSTED", "FAILED", "PREPARE_FAILED", "UNKNOWN", "PENDING", "PREPARING", "PUBLISHING")) jdbc.update("INSERT INTO social_post_log(platform,title,status,created_at) VALUES ('QQ_CHANNEL',?,?,NOW())", "Fixture " + status, status);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForZSet().reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(java.util.Set.of());
        service = new MonitoringService(jdbc, redis);
    }
    static long number(Map<String,Object> result, String key) { return ((Number) result.get(key)).longValue(); }

    @Test void realSqlSeparatesApiAndPageEventsAndReturnsCalendarDays() {
        var result = service.overview();
        assertEquals(85, number(result, "requestsToday"));
        assertEquals(3, number(result, "pageViewsToday"));
        assertEquals(2, number(result, "visitorsToday"));
        assertEquals(1, number(result, "clientErrorsToday"));
        assertEquals(1, number(result, "serverErrorsToday"));
        assertEquals(2, number(result, "socialPostedToday"));
        assertEquals(2, number(result, "socialFailedToday"));
        assertEquals(3, number(result, "socialPendingToday"));
        assertEquals(1, number(result, "socialUnknownToday"));
        var traffic = (List<?>) result.get("traffic");
        assertEquals(2, traffic.size());
        assertTrue(((Map<?,?>) traffic.get(0)).get("day") instanceof String);
        assertEquals(2, ((List<?>) result.get("hotSearches")).size());
    }
    @Test void realSqlReadsBeyondFiftyAndClampsOutOfRangePages() {
        var page = service.logPage("access", "", "all", 4, 20);
        assertEquals(89, number(page, "total"));
        assertEquals(20, ((List<?>) page.get("records")).size());
        page = service.logPage("access", "", "all", Integer.MAX_VALUE, 20);
        assertEquals(5, number(page, "page"));
        assertEquals(9, ((List<?>) page.get("records")).size());
    }
    @Test void realSqlUsesLiteralWildcardSearchAndResultFilters() {
        assertEquals(1, number(service.logPage("resourceOperations", "%_!", "all", 1, 20), "total"));
        assertEquals(1, number(service.logPage("searches", "", "failure", 1, 20), "total"));
        assertEquals(1, number(service.logPage("qqSearches", "", "success", 1, 20), "total"));
        assertEquals(2, number(service.logPage("access", "", "failure", 1, 20), "total"));
    }
    @Test void realSqlKeepsUncertainAndPendingPublicationsOutOfTheFailureFilter() {
        assertEquals(2, number(service.logPage("socialPosts", "", "failure", 1, 20), "total"));
        assertEquals(1, number(service.logPage("socialPosts", "", "attention", 1, 20), "total"));
        assertEquals(3, number(service.logPage("socialPosts", "", "pending", 1, 20), "total"));
    }
}
