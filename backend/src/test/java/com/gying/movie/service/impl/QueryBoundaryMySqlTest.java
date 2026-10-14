package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.mapper.ResourceLinkMapper;
import com.gying.movie.mapper.UserNotificationMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual mappers and MySQL; only a marked, fresh, network-none container is accepted. */
@EnabledIfEnvironmentVariable(named = "QUERY_BOUNDARY_MYSQL_FIXTURE", matches = "network-none-tmpfs")
class QueryBoundaryMySqlTest {
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static ResourceLinkServiceImpl resources;
    private static UserNotificationServiceImpl notifications;
    private static final Statements statements = new Statements();

    @BeforeAll
    static void initializeFreshFixture() throws Exception {
        String runId = System.getenv("QUERY_BOUNDARY_FIXTURE_ID");
        assertNotNull(runId);
        assertTrue(runId.matches("[0-9a-f]{12}"));
        String url = "jdbc:mysql://127.0.0.1:3306/query_boundary_fixture_" + runId;
        assertEquals(url, System.getenv("QUERY_BOUNDARY_MYSQL_URL"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "root", "");
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", "3000");
        properties.setProperty("socketTimeout", "15000");
        properties.setProperty("connectionTimeZone", "Asia/Shanghai");
        properties.setProperty("useSSL", "false");
        dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        // All checks precede DDL. Never load application.yml, .env or a production data source.
        assertEquals("gying-query-fixture", jdbc.queryForObject("SELECT @@hostname", String.class));
        assertEquals("query_boundary_fixture_" + runId, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(List.of("fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class));
        assertEquals(runId, jdbc.queryForObject("SELECT run_id FROM fixture_marker", String.class));
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.0."));
        try (Connection connection = dataSource.getConnection()) {
            // Reuse the real notification schema/indexes, only after proving this DB is fresh.
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration_notifications.sql"));
        }
        // Fixture-only resource table: all mapped columns, no application data or foreign references.
        jdbc.execute("""
            CREATE TABLE resource_link (
                id BIGINT PRIMARY KEY AUTO_INCREMENT, movie_id VARCHAR(64), name VARCHAR(200),
                type VARCHAR(32), provider VARCHAR(50), url VARCHAR(1000), url_hash VARCHAR(64),
                code VARCHAR(100), uploader_id BIGINT, audit_status INT, status VARCHAR(32),
                link_status VARCHAR(32), report_count INT, source VARCHAR(50), source_ref VARCHAR(200),
                source_url VARCHAR(1000), auto_collected BOOLEAN, validated_at DATETIME,
                last_check_error VARCHAR(500), remark VARCHAR(1000), quality VARCHAR(100),
                subtitle VARCHAR(100), file_size VARCHAR(100), version_note VARCHAR(200),
                reject_reason VARCHAR(500), created_at DATETIME, updated_at DATETIME, deleted_at DATETIME,
                KEY fixture_movie (movie_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            COMMENT='仅用于隔离查询边界测试'
            """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        // Fixed fixture node IDs avoid hostname/network discovery in the network-none namespace.
        GlobalConfigUtils.getGlobalConfig(configuration)
                .setIdentifierGenerator(new DefaultIdentifierGenerator(0L, 0L));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), dataSource));
        configuration.addInterceptor(statements);
        configuration.addMapper(ResourceLinkMapper.class);
        configuration.addMapper(UserNotificationMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        var session = new SqlSessionTemplate(factory);
        resources = new ResourceLinkServiceImpl();
        notifications = new UserNotificationServiceImpl();
        ReflectionTestUtils.setField(resources, "baseMapper", session.getMapper(ResourceLinkMapper.class));
        ReflectionTestUtils.setField(notifications, "baseMapper", session.getMapper(UserNotificationMapper.class));
        transactions = new DataSourceTransactionManager(dataSource);
    }

    @Test
    void actualResourceMapperEnforcesEveryVisibilityConditionAndLiteralMovieId() {
        List<Object[]> rows = new ArrayList<>();
        Map<String, Set<String>> expected = new HashMap<>();
        int sequence = 0;
        for (String movie : List.of("matrix-a", "matrix-b", "matrix-a' OR 1=1 --")) {
            expected.put(movie, new HashSet<>());
            for (Integer audit : Arrays.asList(1, 0, 2, null)) {
                for (String status : Arrays.asList("ACTIVE", "DELETED", null)) {
                    for (boolean deleted : List.of(false, true)) {
                        for (String link : Arrays.asList(null, "NORMAL", "SUSPECT", "INVALID")) {
                            for (String type : List.of("DISK", "TORRENT")) {
                                String name = "matrix-row-" + (++sequence);
                                rows.add(new Object[]{movie, name, audit, status,
                                        deleted ? Timestamp.valueOf("2026-01-01 00:00:00") : null, link, type});
                                if (Integer.valueOf(1).equals(audit) && "ACTIVE".equals(status)
                                        && !deleted && !"INVALID".equals(link)) expected.get(movie).add(name);
                            }
                        }
                    }
                }
            }
        }
        jdbc.batchUpdate("INSERT INTO resource_link(movie_id,name,audit_status,status,deleted_at,link_status,type) VALUES (?,?,?,?,?,?,?)", rows);
        assertEquals(576, rows.size());
        for (var entry : expected.entrySet()) {
            statements.clear();
            List<ResourceLink> found = resources.getResourcesByMovieId(entry.getKey());
            assertEquals(6, found.size());
            assertEquals(entry.getValue(), new HashSet<>(found.stream().map(ResourceLink::getName).toList()));
            assertTrue(found.stream().allMatch(row -> entry.getKey().equals(row.getMovieId())));
            assertEquals(List.of("SELECT"), statements.snapshot());
        }
        assertTrue(resources.getResourcesByMovieId("missing-movie").isEmpty());
    }

    @Test
    void actualUnreadUpdatePreservesOtherUsersNullFlagsAndNonReadColumns() {
        insertNotifications(100, 3, false);
        insertNotifications(100, 2, true);
        insertNotifications(100, 1, null);
        insertNotifications(101, 4, false);
        var before = jdbc.queryForList("SELECT id,user_id,title,content,target_id,created_at FROM user_notification WHERE user_id IN (100,101) ORDER BY id");
        statements.clear();
        assertEquals(3, notifications.markAllRead(100L));
        assertEquals(List.of("UPDATE"), statements.snapshot(), "Exactly one update, without entity reads");
        assertEquals(0, unread(100));
        assertEquals(4, unread(101));
        assertEquals(1, count("user_id=100 AND read_flag IS NULL"));
        assertEquals(before, jdbc.queryForList("SELECT id,user_id,title,content,target_id,created_at FROM user_notification WHERE user_id IN (100,101) ORDER BY id"));
        assertEquals(0, notifications.markAllRead(100L), "Already-read rows must not be counted a second time");
        assertEquals(0, notifications.markAllRead(999999L));
    }

    @Test
    void missingUserCannotBecomeAnUnscopedDatabaseUpdate() {
        statements.clear();
        assertEquals(0, notifications.markAllRead(null));
        assertTrue(statements.snapshot().isEmpty());
    }

    @Test
    void mapperParticipatesInRollbackWithoutLosingTheUnreadRows() {
        insertNotifications(200, 5, false);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        Integer updated = transaction.execute(status -> {
            int affected = notifications.markAllRead(200L);
            assertEquals(0, unread(200));
            status.setRollbackOnly();
            return affected;
        });
        assertEquals(5, updated);
        assertEquals(5, unread(200));
    }

    @Test
    void concurrentReadAllDoesNotDoubleCountOrTouchAnotherUser() throws Exception {
        insertNotifications(300, 400, false);
        insertNotifications(301, 7, false);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            statements.clear();
            java.util.concurrent.Callable<Integer> update = () -> {
                start.await(10, TimeUnit.SECONDS);
                return notifications.markAllRead(300L);
            };
            var first = executor.submit(update);
            var second = executor.submit(update);
            var counts = new ArrayList<>(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)));
            counts.sort(Integer::compareTo);
            assertEquals(List.of(0, 400), counts);
            assertEquals(List.of("UPDATE", "UPDATE"), statements.snapshot());
            assertEquals(0, unread(300));
            assertEquals(7, unread(301));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void databaseWriteFailurePropagatesInsteadOfReturningSuccess() {
        insertNotifications(400, 2, false);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        statements.clear();
        DataAccessException error = assertThrows(DataAccessException.class, () -> transaction.execute(status -> {
            // Let MySQL, not the driver's Connection.setReadOnly hint, reject the mapper's UPDATE.
            jdbc.execute("SET TRANSACTION READ ONLY");
            return notifications.markAllRead(400L);
        }));
        SQLException sql = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(1792, sql.getErrorCode());
        assertEquals("25006", sql.getSQLState());
        assertEquals(List.of("UPDATE"), statements.snapshot(), "The real mapper must attempt the rejected write");
        assertEquals(2, unread(400));
    }

    @Test
    void existingUserReadIndexIsAvailableToTheUpdatePlan() {
        insertNotifications(500, 8, false);
        List<Object[]> noise = new ArrayList<>();
        for (long user = 10000; user < 11000; user++) {
            for (int i = 0; i < 5; i++) noise.add(new Object[]{user, false});
        }
        jdbc.batchUpdate("INSERT INTO user_notification(user_id,read_flag,type,title) VALUES (?,?,'COMMENT_REPLY','隔离测试')", noise);
        var plan = jdbc.queryForMap("EXPLAIN UPDATE user_notification SET read_flag=TRUE WHERE user_id=? AND read_flag=FALSE", 500);
        assertEquals("user_notification", plan.get("table"));
        assertNotNull(plan.get("possible_keys"));
        assertTrue(plan.get("possible_keys").toString().contains("idx_user_read_created"));
        assertTrue(Set.of("idx_user_read_created", "idx_user_created").contains(plan.get("key")), "Use an existing user-prefixed index: " + plan);
        assertNotEquals("ALL", plan.get("type"));
        assertEquals(8, notifications.markAllRead(500L));
        assertEquals(5000, count("user_id >= 10000 AND read_flag=FALSE"));
    }

    private static void insertNotifications(long userId, int quantity, Boolean read) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < quantity; i++) rows.add(new Object[]{userId, read});
        jdbc.batchUpdate("INSERT INTO user_notification(user_id,read_flag,type,title,content,target_id) VALUES (?,?,'COMMENT_REPLY','隔离测试','fixture-only','fixture-target')", rows);
    }

    private static long unread(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE user_id=? AND read_flag=FALSE", Long.class, userId);
    }

    private static long count(String fixtureCondition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE " + fixtureCondition, Long.class);
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    static class Statements implements Interceptor {
        private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            commands.add(((MappedStatement) invocation.getArgs()[0]).getSqlCommandType().name());
            return invocation.proceed();
        }
        void clear() { commands.clear(); }
        List<String> snapshot() { return List.copyOf(commands); }
    }
}
