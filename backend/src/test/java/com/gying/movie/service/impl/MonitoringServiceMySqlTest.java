package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.sql.Connection;
import com.gying.movie.security.ClientIpResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
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
        jdbc.execute("CREATE TABLE site_access_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,request_path VARCHAR(500),method VARCHAR(10),status_code INT,duration_ms BIGINT,event_type VARCHAR(30),visitor_hash VARCHAR(100),user_agent_hash CHAR(64),referer VARCHAR(500),created_at DATETIME DEFAULT CURRENT_TIMESTAMP) CHARACTER SET utf8mb4");
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
        // Execute the actual additive migration twice, after legacy rows already exist.
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            var migration = new ClassPathResource("db/migration_access_log_client_identity.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        } catch (Exception failure) {
            throw new AssertionError("Isolated identity migration failed", failure);
        }
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
    @Test void realMigrationPreservesLegacyRowsAndDoesNotInventIdentity() {
        assertEquals(89L, jdbc.queryForObject("SELECT COUNT(*) FROM site_access_log WHERE client_ip IS NULL AND user_agent IS NULL", Long.class));
        assertEquals(45L, jdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='site_access_log' AND COLUMN_NAME='client_ip'", Long.class));
        assertEquals(1024L, jdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='site_access_log' AND COLUMN_NAME='user_agent'", Long.class));
        var legacy = (Map<?,?>) ((List<?>) service.logPage("access", "/api/fixture/1", "all", 1, 20).get("records")).get(0);
        assertTrue(legacy.containsKey("client_ip"));
        assertTrue(legacy.containsKey("user_agent"));
        assertNull(legacy.get("client_ip"));
        assertNull(legacy.get("user_agent"));
    }

    @Test void realSqlRoundTripsRawIdentityAndSearchesLiteralIpAndAgent() {
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("::1");
            request.addHeader("X-Forwarded-For", "2001:db8::8");
            request.setAttribute(ClientIpResolver.ATTRIBUTE, new ClientIpResolver("::1/128").resolve(request));
            String agent = "Fixture%_! / 中文 😀 <img src=x>";
            request.addHeader("User-Agent", agent);
            request.addHeader("Referer", "https://fixture.invalid/movie/test?session=private");
            request.addHeader("Cookie", "fixture-private-cookie");
            request.addHeader("Authorization", "fixture-private-authorization");
            service.access("/api/raw-identity?token=private", "GET", 404, 5, request, "API");
            var ipPage = service.logPage("access", "2001:db8:0:0:0:0:0:8", "failure", 1, 20);
            assertEquals(1, number(ipPage, "total"));
            assertEquals(1, number(service.logPage("access", "Fixture%_!", "all", 1, 20), "total"));
            var row = (Map<?,?>) ((List<?>) ipPage.get("records")).get(0);
            assertEquals("/api/raw-identity", row.get("request_path"));
            assertEquals("2001:db8:0:0:0:0:0:8", row.get("client_ip"));
            assertEquals(agent, row.get("user_agent"));
            assertFalse(row.containsKey("visitor_hash"));
            assertFalse(row.containsKey("user_agent_hash"));
            assertFalse(row.toString().contains("private"));
            var legacy = (Map<?,?>) ((List<?>) service.logs("Fixture", 1, 20).get("access")).get(0);
            assertEquals(agent, legacy.get("user_agent"));
            assertEquals(row.get("client_ip"), legacy.get("client_ip"));
        });
    }

    @Test void realSqlStores1024UnicodeCharactersWithoutSplittingOrDroppingTheEvent() {
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("192.0.2.44");
            String expected = "a".repeat(1023) + "😀";
            request.addHeader("User-Agent", expected + "not-stored");
            service.access("/api/raw-identity-long-agent", "GET", 200, 1, request, "API");
            var result = service.logPage("access", "/api/raw-identity-long-agent", "all", 1, 20);
            assertEquals(1, number(result, "total"));
            assertEquals(expected, ((Map<?,?>) ((List<?>) result.get("records")).get(0)).get("user_agent"));
            assertEquals(1024L, jdbc.queryForObject("SELECT CHAR_LENGTH(user_agent) FROM site_access_log WHERE request_path='/api/raw-identity-long-agent'", Long.class));
        });
    }
}
