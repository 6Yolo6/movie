package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.mapper.ResourceHubTaskMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real MyBatis CAS only in a positively identified disposable, network-none database. */
@EnabledIfEnvironmentVariable(named = "METADATA_UPDATES_MYSQL_FIXTURE", matches = "network-none-tmpfs")
class MetadataUpdatesCheckpointMySqlTest {
    static JdbcTemplate jdbc;
    static ResourceHubTaskServiceImpl tasks;
    static final ObjectMapper mapper = new ObjectMapper();
    static MetadataUpdatesCheckpoint checkpoints;

    @BeforeAll static void initializeFreshFixture() {
        String id = System.getenv("METADATA_UPDATES_FIXTURE_ID");
        assertNotNull(id); assertTrue(id.matches("[0-9a-f]{12}"));
        String database = "metadata_updates_fixture_" + id;
        String url = "jdbc:mysql://127.0.0.1:3306/" + database;
        assertEquals(url, System.getenv("METADATA_UPDATES_MYSQL_URL"));
        var source = new DriverManagerDataSource(url, "root", "");
        Properties options = new Properties(); options.setProperty("connectTimeout", "3000");
        options.setProperty("socketTimeout", "15000"); options.setProperty("useSSL", "false"); source.setConnectionProperties(options);
        jdbc = new JdbcTemplate(source);
        // Prove hostname, fresh table set and marker before issuing any DDL.
        assertEquals("gying-updates-fixture", jdbc.queryForObject("SELECT @@hostname", String.class));
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(List.of("fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class));
        assertEquals(id, jdbc.queryForObject("SELECT run_id FROM fixture_marker", String.class));
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.0."));
        jdbc.execute("""
            CREATE TABLE resource_hub_task (
                id BIGINT PRIMARY KEY AUTO_INCREMENT, task_type VARCHAR(32), movie_id VARCHAR(64), tmdb_id BIGINT,
                tmdb_type VARCHAR(32), keyword VARCHAR(200), source VARCHAR(32), status VARCHAR(32), priority INT,
                attempts INT, max_attempts INT, last_error VARCHAR(1000), payload JSON,
                scheduled_at DATETIME, started_at DATETIME, finished_at DATETIME, created_at DATETIME, updated_at DATETIME
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        assertEquals("json", jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='resource_hub_task' AND COLUMN_NAME='payload'", String.class, database));
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(configuration).setIdentifierGenerator(new DefaultIdentifierGenerator(0L, 0L));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(ResourceHubTaskMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        tasks = new ResourceHubTaskServiceImpl(); ReflectionTestUtils.setField(tasks, "baseMapper", session.getMapper(ResourceHubTaskMapper.class));
        checkpoints = new MetadataUpdatesCheckpoint(tasks, mapper);
    }
    @BeforeEach void resetOnlyFixtureRows() { jdbc.execute("TRUNCATE TABLE resource_hub_task"); }
    ResourceHubTask started() {
        ResourceHubTask task = checkpoints.enqueue("TMDB", List.of("TIME", "POPULAR"), 20);
        assertTrue(checkpoints.start(task)); return task;
    }

    @Test void actualMapperPersistsPayloadOnlyAndKeepsSeparateRangeHistory() throws Exception {
        ResourceHubTask range = new ResourceHubTask(); range.setTaskType("METADATA_SYNC"); range.setSource("TMDB");
        range.setKeyword("POPULAR_MOVIE"); range.setMovieId("retainedRange"); range.setPayload("{\"crawl\":{\"mode\":\"RANGE\",\"page\":500,\"offset\":7}}");
        tasks.enqueue(range); String rangeBefore = tasks.getById(range.getId()).getPayload();
        var task = started(); String schedule = jdbc.queryForObject("SELECT CAST(scheduled_at AS CHAR) FROM resource_hub_task WHERE id=?", String.class, task.getId());
        var item = new MetadataUpdatesCheckpoint.Candidate("TIME", "movie/1", mapper.createObjectNode().put("tmdbId", 1));
        assertEquals(1, checkpoints.run(task, List.of(item), Set.of("TIME"), 10, c -> false, c -> {}).processed());
        checkpoints.finish(task, "SUCCEEDED", null);
        ResourceHubTask saved = tasks.getById(task.getId());
        assertEquals("SUCCEEDED", saved.getStatus()); assertEquals(1, saved.getAttempts()); assertEquals("UPDATES", saved.getKeyword());
        assertEquals(schedule, jdbc.queryForObject("SELECT CAST(scheduled_at AS CHAR) FROM resource_hub_task WHERE id=?", String.class, task.getId()));
        assertEquals(1, mapper.readTree(saved.getPayload()).path("crawl").path("completed").size());
        var next = checkpoints.enqueue("TMDB", List.of("TIME"), 10);
        assertEquals(1, mapper.readTree(next.getPayload()).path("crawl").path("completed").size());
        assertEquals(rangeBefore, tasks.getById(range.getId()).getPayload());
    }

    @Test void cancelledTaskCannotBeResurrectedByStalePersistOrFinish() {
        var task = started(); String payload = tasks.getById(task.getId()).getPayload();
        jdbc.update("UPDATE resource_hub_task SET status='CANCELED', last_error='user cancelled' WHERE id=?", task.getId());
        assertThrows(MetadataUpdatesCheckpoint.CheckpointConflict.class, () -> checkpoints.run(task, List.of(), Set.of("TIME"), 10, c -> false, c -> fail()));
        assertThrows(MetadataUpdatesCheckpoint.CheckpointConflict.class, () -> checkpoints.finish(task, "FAILED", "stale worker"));
        var saved = tasks.getById(task.getId()); assertEquals("CANCELED", saved.getStatus()); assertEquals("user cancelled", saved.getLastError());
        assertEquals(payload, saved.getPayload()); assertEquals(1, saved.getAttempts());
    }

    @Test void payloadCompareIsByteSensitiveDespiteCaseInsensitiveDatabaseCollation() {
        var task = started(); String changed = task.getPayload().replace("UPDATES", "updates");
        jdbc.update("UPDATE resource_hub_task SET payload=? WHERE id=?", changed, task.getId());
        assertThrows(MetadataUpdatesCheckpoint.CheckpointConflict.class, () -> checkpoints.run(task, List.of(), Set.of("TIME"), 10, c -> false, c -> fail()));
        try { assertEquals(mapper.readTree(changed), mapper.readTree(tasks.getById(task.getId()).getPayload())); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    @Test void changedAttemptOwnershipRejectsTheOldWriter() {
        var task = started(); jdbc.update("UPDATE resource_hub_task SET attempts=attempts+1 WHERE id=?", task.getId());
        assertThrows(MetadataUpdatesCheckpoint.CheckpointConflict.class, () -> checkpoints.run(task, List.of(), Set.of("TIME"), 10, c -> false, c -> fail()));
        assertEquals(2, tasks.getById(task.getId()).getAttempts());
    }

    @Test void durableRoundBudgetSurvivesSameJobRetryAndOnlyNewJobResetsIt() {
        var task = started(); List<MetadataUpdatesCheckpoint.Candidate> head = new ArrayList<>();
        for (int i = 1; i <= 14; i++) head.add(new MetadataUpdatesCheckpoint.Candidate("TIME", "movie/" + i, mapper.createObjectNode().put("tmdbId", i)));
        assertEquals(10, checkpoints.run(task, head, Set.of("TIME"), 20, c -> false, c -> {}).processed());
        task = tasks.getById(task.getId());
        assertEquals(0, checkpoints.run(task, List.of(), Set.of("TIME"), 20, c -> false, c -> fail()).processed());
        checkpoints.finish(task, "SUCCEEDED", null);
        var next = checkpoints.enqueue("TMDB", List.of("TIME"), 20); assertTrue(checkpoints.start(next));
        assertEquals(4, checkpoints.run(next, List.of(), Set.of("TIME"), 20, c -> false, c -> {}).processed());
    }
}
