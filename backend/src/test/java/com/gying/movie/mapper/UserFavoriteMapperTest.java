package com.gying.movie.mapper;

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserFavoriteMapperTest {
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        new MapperAnnotationBuilder(configuration, UserFavoriteMapper.class).parse();
    }

    @Test
    void batchBindsRequestedIdsAsResultKeysAndKeepsTheOriginalEqualityCounts() {
        String suspiciousId = "fixture') OR 1=1 --";
        BoundSql bound = boundSql(List.of("movie-a", suspiciousId));
        String sql = normalized(bound);

        String singleCount = "SELECT ? AS movie_id, COUNT(*) AS cnt FROM user_favorite WHERE movie_id = ?";
        assertEquals(singleCount + " UNION ALL " + singleCount, sql);
        assertFalse(sql.contains(suspiciousId));
        assertEquals(List.of("movie-a", "movie-a", suspiciousId, suspiciousId), bound.getParameterMappings().stream()
                .map(parameter -> bound.getAdditionalParameter(parameter.getProperty())).toList());
    }

    @Test
    void emptyOrNullInputCannotTurnIntoAnUnboundedAggregate() {
        for (BoundSql bound : List.of(boundSql(List.of()), boundSql(null))) {
            assertEquals("SELECT NULL AS movie_id, 0 AS cnt WHERE 1 = 0", normalized(bound));
            assertTrue(bound.getParameterMappings().isEmpty());
        }
    }

    @Test
    void caseVariantIdsKeepSeparateBoundKeysRatherThanAGroupedDatabaseLabel() {
        BoundSql bound = boundSql(List.of("dWXo", "dwXo"));
        assertFalse(normalized(bound).contains("GROUP BY"));
        assertEquals(List.of("dWXo", "dWXo", "dwXo", "dwXo"), bound.getParameterMappings().stream()
                .map(parameter -> bound.getAdditionalParameter(parameter.getProperty())).toList());
    }

    private BoundSql boundSql(List<String> ids) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("movieIds", ids);
        return configuration.getMappedStatement(UserFavoriteMapper.class.getName() + ".countByMovieIds")
                .getBoundSql(parameters);
    }

    private String normalized(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").trim();
    }
}
