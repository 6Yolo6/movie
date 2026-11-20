package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduSessionVault;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.mapper.BaiduTransferTaskMapper;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** Real migration/mappers and durable lease writes; this class only accepts the marked disposable database. */
@EnabledIfEnvironmentVariable(named = "QUERY_BOUNDARY_MYSQL_FIXTURE", matches = "network-none-tmpfs")
class BaiduTransferTaskMySqlTest {
    private static JdbcTemplate jdbc;
    private static BaiduTransferTaskServiceImpl tasks;

    @BeforeAll static void initializeFreshFixture() throws Exception {
        String runId = System.getenv("QUERY_BOUNDARY_FIXTURE_ID");
        assertNotNull(runId); assertTrue(runId.matches("[0-9a-f]{12}"));
        String database = "baidu_transfer_fixture_" + runId;
        String url = "jdbc:mysql://127.0.0.1:3306/" + database;
        assertEquals(url, System.getenv("BAIDU_TEST_MYSQL_URL"));
        var source = new DriverManagerDataSource(url, "root", "");
        var properties = new Properties();
        properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "15000");
        properties.setProperty("connectionTimeZone", "Asia/Shanghai"); properties.setProperty("useSSL", "false");
        source.setConnectionProperties(properties); jdbc = new JdbcTemplate(source);
        // Identity and freshness checks precede all DDL. No application configuration is loaded.
        assertEquals("gying-query-fixture", jdbc.queryForObject("SELECT @@hostname", String.class));
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(List.of("fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class));
        assertEquals(runId, jdbc.queryForObject("SELECT run_id FROM fixture_marker", String.class));
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.0."));
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration_baidu_transfer_task.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration_baidu_search_temporary_scope.sql"));
        }
        var configuration = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(configuration).setIdentifierGenerator(new DefaultIdentifierGenerator(0L, 0L));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("baidu-fixture", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(BaiduTransferTaskMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        tasks = new BaiduTransferTaskServiceImpl();
        ReflectionTestUtils.setField(tasks, "baseMapper", new SqlSessionTemplate(factory).getMapper(BaiduTransferTaskMapper.class));
    }

    @Test void uniqueMovieAndSourceHashPreventsDuplicateTasksWithoutBlockingOtherSources() {
        String movie = "unique-" + UUID.randomUUID();
        BaiduTransferTask first = newTask(movie, "a".repeat(64)); assertTrue(tasks.save(first));
        assertThrows(DataIntegrityViolationException.class, () -> tasks.save(newTask(movie, "a".repeat(64))));
        assertTrue(tasks.save(newTask(movie, "b".repeat(64))));
        assertTrue(tasks.save(newTask(movie + "-other", "a".repeat(64))));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM baidu_transfer_task WHERE movie_id=?", Long.class, movie));
    }

    @Test void mapperRestoresPersistentPlanAccountRemoteTaskAndUnicodeProgress() {
        BaiduTransferTask task = leasedTask();
        task.setAccountUid("12345"); task.setSavedPath("/GYing Resource Hub/电影😀（test）");
        task.setRequestPayload("{\"来源\":\"百度\"}"); task.setResponsePayload("{\"计划\":\"视频😀\"}");
        task.setRemoteTaskId("fixture-remote"); task.setPhase("TRANSFER_SUBMITTING"); task.setStatus("WAITING_TRANSFER");
        task.setAttempts(2); task.setLastError("BAIDU_TRANSFER_UNCONFIRMED"); assertTrue(tasks.save(task));
        BaiduTransferTask restored = tasks.getById(task.getId());
        assertEquals(task.getSavedPath(), restored.getSavedPath()); assertEquals(task.getResponsePayload(), restored.getResponsePayload());
        assertEquals(task.getRequestPayload(), restored.getRequestPayload()); assertEquals("12345", restored.getAccountUid());
        assertEquals("fixture-remote", restored.getRemoteTaskId()); assertEquals("TRANSFER_SUBMITTING", restored.getPhase());
        assertEquals("WAITING_TRANSFER", restored.getStatus()); assertEquals(2, restored.getAttempts());
        assertEquals(task.getLeaseOwner(), restored.getLeaseOwner()); assertNotNull(restored.getLeaseUntil());
    }

    @Test void progressWriteWithAnotherLeaseOwnerCannotOverwriteDurableIntent() {
        BaiduTransferTask task = leasedTask(); assertTrue(tasks.save(task));
        task.setPhase("SHARED"); task.setStatus("SUCCEEDED");
        assertThrows(IllegalStateException.class, () -> persist(task, UUID.randomUUID().toString()));
        assertEquals("PREPARED", tasks.getById(task.getId()).getPhase());
        assertEquals("RUNNING", tasks.getById(task.getId()).getStatus());
    }

    @Test void expiredLeaseBlocksProgressEvenWhenTheOwnerMatches() {
        BaiduTransferTask task = leasedTask(); task.setLeaseUntil(LocalDateTime.now().minusMinutes(1)); assertTrue(tasks.save(task));
        task.setPhase("TRANSFER_SUBMITTING"); task.setRemoteTaskId("must-not-persist");
        assertThrows(IllegalStateException.class, () -> persist(task, task.getLeaseOwner()));
        BaiduTransferTask restored = tasks.getById(task.getId());
        assertEquals("PREPARED", restored.getPhase()); assertNull(restored.getRemoteTaskId());
    }

    @Test void ownedProgressClearsNullableErrorsAndRetainsTheRecoveryPlan() {
        BaiduTransferTask task = leasedTask(); task.setLastError("BAIDU_SHARE_UNCONFIRMED");
        task.setResponsePayload("{\"destination\":\"/电影😀\"}"); task.setRemoteTaskId("remote-previous"); assertTrue(tasks.save(task));
        task.setLastError(null); task.setShareUrl(null); task.setShareUrlHash(null); task.setStatus("WAITING_SHARE"); task.setPhase("TRANSFERRED");
        persist(task, task.getLeaseOwner());
        BaiduTransferTask restored = tasks.getById(task.getId());
        assertNull(restored.getLastError()); assertNull(restored.getShareUrl()); assertNull(restored.getShareUrlHash());
        assertEquals(task.getResponsePayload(), restored.getResponsePayload()); assertEquals("remote-previous", restored.getRemoteTaskId());
        assertEquals("TRANSFERRED", restored.getPhase()); assertTrue(restored.getLeaseUntil().isAfter(LocalDateTime.now()));
    }

    @Test void aRestartedRunnerCanReclaimAnExpiredLeaseWhileKeepingUncertainRemoteIntent() {
        BaiduTransferTask task = leasedTask(); task.setLeaseUntil(LocalDateTime.now().minusMinutes(1));
        task.setPhase("TRANSFER_SUBMITTING"); task.setStatus("WAITING_TRANSFER");
        task.setResponsePayload("{\"destination\":\"/电影\"}"); task.setRemoteTaskId("uncertain-remote"); assertTrue(tasks.save(task));
        var client = mock(BaiduWebClient.class); when(client.isConfigured()).thenReturn(true);
        var vault = mock(BaiduSessionVault.class); when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(false, null, null, 0));
        assertEquals(1, runner(client, vault).submitOne(task.getId()).getFailed());
        BaiduTransferTask restored = tasks.getById(task.getId());
        assertEquals("ACTION_REQUIRED", restored.getStatus()); assertEquals("TRANSFER_SUBMITTING", restored.getPhase());
        assertEquals(task.getResponsePayload(), restored.getResponsePayload()); assertEquals("uncertain-remote", restored.getRemoteTaskId());
        assertNull(restored.getLeaseOwner()); assertNull(restored.getLeaseUntil()); verify(client, never()).transfer(any());
    }

    @Test void independentRunnersCannotClaimTheSameUnexpiredDatabaseLease() throws Exception {
        BaiduTransferTask task = newTask("race-" + UUID.randomUUID(), "c".repeat(64)); assertTrue(tasks.save(task));
        var client = mock(BaiduWebClient.class); when(client.isConfigured()).thenReturn(true);
        var vault = mock(BaiduSessionVault.class); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(vault.metadata()).thenAnswer(invocation -> {
            entered.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS));
            return new BaiduSessionVault.Metadata(false, null, null, 0);
        });
        var first = runner(client, vault); var second = runner(client, vault); var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> first.submitOne(task.getId()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var skipped = second.submitOne(task.getId()); assertEquals(1, skipped.getSkipped()); assertEquals(0, skipped.getFailed());
            release.countDown(); assertEquals(1, pending.get(20, TimeUnit.SECONDS).getFailed());
            BaiduTransferTask restored = tasks.getById(task.getId());
            assertEquals("ACTION_REQUIRED", restored.getStatus()); assertNull(restored.getLeaseOwner()); assertNull(restored.getLeaseUntil());
            verify(vault, times(1)).metadata(); verify(client, never()).transfer(any());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test void migrationPreservesChineseCommentsAndUtf8mb4Storage() {
        var table = jdbc.queryForMap("SELECT TABLE_COMMENT,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='baidu_transfer_task'");
        assertEquals("百度网盘独立转存任务", table.get("TABLE_COMMENT")); assertEquals("utf8mb4_unicode_ci", table.get("TABLE_COLLATION"));
        assertEquals("执行计划和内容核验，不存原始上游响应", jdbc.queryForObject("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='baidu_transfer_task' AND COLUMN_NAME='response_payload'", String.class));
    }


    @Test void permanentAndIndependentTemporaryScopesCoexistForTheSameMovieSource() {
        String movie = "scoped-" + UUID.randomUUID(); String source = "d".repeat(64);
        BaiduTransferTask permanent = newTask(movie, source); assertTrue(tasks.save(permanent));
        BaiduTransferTask first = newTask(movie, source); first.setScopeKey("QQ_BOT:" + UUID.randomUUID()); assertTrue(tasks.save(first));
        BaiduTransferTask second = newTask(movie, source); second.setScopeKey("QQ_BOT:" + UUID.randomUUID()); assertTrue(tasks.save(second));
        assertEquals("PERMANENT", tasks.getById(permanent.getId()).getScopeKey());
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM baidu_transfer_task WHERE movie_id=? AND original_url_hash=?", Long.class, movie, source));
        BaiduTransferTask duplicate = newTask(movie, source); duplicate.setScopeKey(first.getScopeKey());
        assertThrows(DataIntegrityViolationException.class, () -> tasks.save(duplicate));
    }

    @Test void confirmationClockAndFullTreeEvidenceSurviveAMapperRoundTrip() {
        BaiduTransferTask task = newTask("confirmed-" + UUID.randomUUID(), "e".repeat(64));
        task.setScopeKey("QQ_BOT:" + UUID.randomUUID()); task.setSearchRequestHash("a".repeat(64));
        task.setShareConfirmedAt(LocalDateTime.of(2026, 11, 9, 20, 0));
        task.setCleanupEvidence("{\"accountUid\":\"100\",\"destination\":\"/GYing QQ Temp/baidu-1\",\"directoryId\":\"55\",\"files\":[]}");
        assertTrue(tasks.save(task));
        BaiduTransferTask restored = tasks.getById(task.getId());
        assertEquals(task.getScopeKey(), restored.getScopeKey()); assertEquals(task.getSearchRequestHash(), restored.getSearchRequestHash());
        assertEquals(task.getShareConfirmedAt(), restored.getShareConfirmedAt()); assertEquals(task.getCleanupEvidence(), restored.getCleanupEvidence());
    }

    @Test void scopeMigrationIsRepeatableAndRetainsTheLegacyPermanentRows() throws Exception {
        String movie = "migration-legacy-" + UUID.randomUUID();
        jdbc.update("INSERT INTO baidu_transfer_task(movie_id,original_url,original_url_hash) VALUES(?,?,?)", movie, "https://pan.baidu.com/s/1legacy", "f".repeat(64));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration_baidu_search_temporary_scope.sql"));
        }
        assertEquals("PERMANENT", jdbc.queryForObject("SELECT scope_key FROM baidu_transfer_task WHERE movie_id=?", String.class, movie));
        assertEquals("永久归档或独立临时搜索范围", jdbc.queryForObject("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='baidu_transfer_task' AND COLUMN_NAME='scope_key'", String.class));
    }

    private static BaiduTransferTask newTask(String movie, String hash) {
        BaiduTransferTask task = new BaiduTransferTask(); task.setMovieId(movie); task.setOriginalUrl("https://pan.baidu.com/s/1fixture");
        task.setOriginalUrlHash(hash); task.setStatus("PENDING"); task.setPhase("NEW"); task.setAttempts(0); return task;
    }
    private static BaiduTransferTask leasedTask() {
        BaiduTransferTask task = newTask("leased-" + UUID.randomUUID(), UUID.randomUUID().toString().replace("-", "").repeat(2));
        task.setStatus("RUNNING"); task.setPhase("PREPARED"); task.setLeaseOwner(UUID.randomUUID().toString());
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(10)); return task;
    }
    private static BaiduTransferRunnerServiceImpl runner(BaiduWebClient client, BaiduSessionVault vault) {
        var config = new ResourceHubProperties(); config.setEnabled(true); config.getBaidu().setEnabled(true); config.getBaidu().setVideoEnabled(true);
        return new BaiduTransferRunnerServiceImpl(config, client, vault, tasks, mock(IResourceDiscoveryResultService.class),
                mock(IResourceLinkService.class), mock(IMovieMetadataService.class), new ObjectMapper());
    }
    private static void persist(BaiduTransferTask task, String owner) {
        ReflectionTestUtils.invokeMethod(runner(mock(BaiduWebClient.class), mock(BaiduSessionVault.class)), "persist", task, owner);
    }
}
