package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class MonitoringServiceLogPageTest {
    private record Query(String sql, List<Object> args) { }
    private static class CapturingJdbc extends JdbcTemplate {
        final List<Query> queries = new ArrayList<>();
        long total = 125;
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            queries.add(new Query(sql, Arrays.asList(args)));
            return type.cast(total);
        }
        @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
            queries.add(new Query(sql, Arrays.asList(args)));
            return List.of(Map.of("id", 81L));
        }
    }
    private final CapturingJdbc jdbc = new CapturingJdbc();
    private final MonitoringService service = new MonitoringService(jdbc, mock(StringRedisTemplate.class));

    @ParameterizedTest
    @CsvSource({"access,site_access_log", "searches,site_search_log", "resourceOperations,resource_operation_log",
            "qqSearches,qq_bot_search_log", "socialPosts,social_post_log"})
    void readsOnlyTheSelectedStreamAndPaginatesItsFullHistory(String type, String table) {
        var result = service.logPage(type, null, "all", 4, 20);
        assertEquals(125L, result.get("total"));
        assertEquals(4, result.get("page"));
        assertEquals(7L, result.get("pages"));
        assertEquals(2, jdbc.queries.size());
        assertEquals("SELECT COUNT(*) FROM " + table, jdbc.queries.get(0).sql());
        assertTrue(jdbc.queries.get(1).sql().contains(" FROM " + table + " ORDER BY id DESC LIMIT ? OFFSET ?"));
        assertEquals(List.of(20, 60L), jdbc.queries.get(1).args());
        assertFalse(jdbc.queries.get(1).sql().contains("visitor_hash"));
        assertFalse(jdbc.queries.get(1).sql().contains("user_agent_hash"));
    }

    @Test void searchIsBoundAndLikeWildcardsAreLiteral() {
        service.logPage("resourceOperations", "  O'Reilly%_!  ", "failure", 1, 20);
        assertFalse(jdbc.queries.get(0).sql().contains("O'Reilly"));
        assertTrue(jdbc.queries.get(0).sql().contains("ESCAPE '!'"));
        assertTrue(jdbc.queries.get(0).sql().contains("error_message LIKE ?"));
        assertEquals(5, jdbc.queries.get(0).args().size());
        assertTrue(jdbc.queries.get(0).args().stream().allMatch("%O'Reilly!%!_!!%"::equals));
        assertEquals(jdbc.queries.get(0).args(), jdbc.queries.get(1).args().subList(0, 5));
    }

    @ParameterizedTest
    @CsvSource({"access,success,status_code>=200 AND status_code<400", "access,failure,status_code>=400",
            "searches,success,result_count>0", "searches,failure,result_count=0", "socialPosts,attention,status='UNKNOWN'"})
    void filtersAreAppliedBeforeBothCountingAndPaging(String type, String status, String condition) {
        service.logPage(type, "", status, 1, 20);
        for (Query query : jdbc.queries) assertTrue(query.sql().contains("WHERE (" + condition + ")"));
    }

    @Test void publicationFailurePendingAndUncertainAreSeparate() {
        service.logPage("socialPosts", "", "failure", 1, 20);
        assertTrue(jdbc.queries.get(0).sql().contains("'FAILED','PREPARE_FAILED'"));
        assertFalse(jdbc.queries.get(0).sql().contains("UNKNOWN"));
        jdbc.queries.clear();
        service.logPage("socialPosts", "", "pending", 1, 20);
        assertTrue(jdbc.queries.get(0).sql().contains("'PENDING','PREPARING','PUBLISHING'"));
        assertFalse(jdbc.queries.get(0).sql().contains("UNKNOWN"));
    }

    @Test void successfulQqLibraryAndTransferOutcomesAreNotLost() {
        service.logPage("qqSearches", "", "success", 1, 20);
        String sql = jdbc.queries.get(0).sql();
        for (String status : List.of("SUCCEEDED", "LIBRARY_RESOURCE", "RESOURCE_SELECTION")) assertTrue(sql.contains("'" + status + "'"));
    }

    @Test void emptyResultDoesNotIssueAnUnnecessaryPageRead() {
        jdbc.total = 0;
        var result = service.logPage("access", "", "all", Integer.MAX_VALUE, 20);
        assertEquals(0L, result.get("total"));
        assertEquals(1, result.get("page"));
        assertEquals(List.of(), result.get("records"));
        assertEquals(1, jdbc.queries.size());
    }

    @Test void clampsPageAndSizeAndUsesLongOffsets() {
        jdbc.total = 5;
        var result = service.logPage("access", "", "all", 99, 0);
        assertEquals(1, result.get("size"));
        assertEquals(5, result.get("page"));
        assertEquals(List.of(1, 4L), jdbc.queries.get(1).args());
        jdbc.queries.clear();
        jdbc.total = 300_000_000_000L;
        result = service.logPage("access", "", "all", Integer.MAX_VALUE, 999);
        assertEquals(100, result.get("size"));
        assertEquals(List.of(100, 214_748_364_600L), jdbc.queries.get(1).args());
    }

    @ParameterizedTest @ValueSource(strings = {"", "site_access_log", "access; DELETE FROM site_access_log", "ACCESS"})
    void unknownTypesNeverReachJdbc(String type) {
        var error = assertThrows(ResponseStatusException.class, () -> service.logPage(type, "", "all", 1, 20));
        assertEquals(400, error.getStatusCode().value());
        assertTrue(jdbc.queries.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"", "failed OR 1=1", "pending", "attention"})
    void invalidOrInapplicableFiltersNeverReachJdbc(String status) {
        assertThrows(ResponseStatusException.class, () -> service.logPage("access", "", status, 1, 20));
        assertTrue(jdbc.queries.isEmpty());
    }

    @Test void rejectsOversizeSearchBeforeQuerying() {
        assertThrows(ResponseStatusException.class, () -> service.logPage("access", "x".repeat(101), "all", 1, 20));
        assertTrue(jdbc.queries.isEmpty());
    }
}
