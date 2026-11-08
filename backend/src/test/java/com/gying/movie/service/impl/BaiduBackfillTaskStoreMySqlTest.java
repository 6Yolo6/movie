package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.service.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable, verified MySQL only; never loads production Spring configuration. */
@EnabledIfEnvironmentVariable(named = "QUERY_BOUNDARY_MYSQL_FIXTURE", matches = "network-none-tmpfs")
class BaiduBackfillTaskStoreMySqlTest {
    static JdbcTemplate jdbc;
    static ObjectMapper mapper = new ObjectMapper();
    static BaiduBackfillTaskStore store;
    @BeforeAll static void initialize() throws Exception {
        String runId = System.getenv("QUERY_BOUNDARY_FIXTURE_ID"); assertNotNull(runId); assertTrue(runId.matches("[0-9a-f]{12}"));
        String original = "baidu_transfer_fixture_" + runId;
        String originalUrl = "jdbc:mysql://127.0.0.1:3306/" + original;
        assertEquals(originalUrl, System.getenv("BAIDU_TEST_MYSQL_URL"));
        var source = dataSource(originalUrl); var verified = new JdbcTemplate(source);
        assertEquals("gying-query-fixture", verified.queryForObject("SELECT @@hostname", String.class));
        assertEquals(original, verified.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(runId, verified.queryForObject("SELECT run_id FROM fixture_marker", String.class));
        assertTrue(verified.queryForObject("SELECT VERSION()", String.class).startsWith("8.0."));
        String database = "baidu_backfill_fixture_" + runId;
        assertEquals(0L, verified.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?", Long.class, database));
        verified.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        source = dataSource("jdbc:mysql://127.0.0.1:3306/" + database); jdbc = new JdbcTemplate(source);
        assertEquals(List.of(), jdbc.queryForList("SHOW TABLES", String.class));
        // Exercise the actual parent table definition, not a simplified mock of JSON/datetime storage.
        String migration = new ClassPathResource("db/migration_resource_hub.sql").getContentAsString(StandardCharsets.UTF_8);
        int start = migration.indexOf("CREATE TABLE IF NOT EXISTS `resource_hub_task`");
        assertTrue(start >= 0); jdbc.execute(migration.substring(start, migration.indexOf(';', start) + 1));
        String sys = new ClassPathResource("db/migration_sys_config.sql").getContentAsString(StandardCharsets.UTF_8);
        start = sys.indexOf("CREATE TABLE IF NOT EXISTS sys_config");
        jdbc.execute(sys.substring(start, sys.indexOf(';', start) + 1));
        jdbc.execute("CREATE TABLE baidu_transfer_task(id BIGINT AUTO_INCREMENT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE resource_discovery_result(id BIGINT AUTO_INCREMENT PRIMARY KEY,provider VARCHAR(30))");
        jdbc.execute("CREATE TABLE resource_link(id BIGINT AUTO_INCREMENT PRIMARY KEY,movie_id VARCHAR(64),provider VARCHAR(30),type VARCHAR(30),status VARCHAR(30),audit_status INT,link_status VARCHAR(30),deleted_at DATETIME)");
        jdbc.execute("CREATE TABLE movie_metadata(id VARCHAR(64) PRIMARY KEY,status VARCHAR(30),created_at DATETIME,deleted_at DATETIME)");
        store = new BaiduBackfillTaskStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), mapper);
    }
    static DriverManagerDataSource dataSource(String url) {
        var source = new DriverManagerDataSource(url, "root", ""); var properties = new Properties();
        properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "15000");
        properties.setProperty("connectionTimeZone", "Asia/Shanghai"); properties.setProperty("useSSL", "false");
        source.setConnectionProperties(properties); return source;
    }
    @BeforeEach void emptyOnlyVerifiedDisposableTables() {
        for (String table : List.of("sys_config", "resource_hub_task", "baidu_transfer_task", "resource_discovery_result", "resource_link", "movie_metadata")) jdbc.execute("DELETE FROM " + table);
    }
    @Test void concurrentFirstSettingsRequestsCreateExactlyOneParent() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> store.settings(true, 10, 1)); var second = pool.submit(() -> store.settings(false, 20, 2));
            assertEquals(first.get(10, TimeUnit.SECONDS).taskId, second.get(10, TimeUnit.SECONDS).taskId);
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='CLOUD_DRIVE_BACKFILL'", Long.class));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key=?", Long.class, BaiduBackfillTaskStore.KEY));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    @Test void snapshotBoundsNeverGrowAsNewLocalResourcesArrive() {
        jdbc.execute("INSERT INTO baidu_transfer_task VALUES(10)"); jdbc.execute("INSERT INTO resource_discovery_result VALUES(20,'BAIDU')");
        jdbc.execute("INSERT INTO resource_link(id,provider) VALUES(30,'BAIDU')"); jdbc.execute("INSERT INTO resource_hub_task(id,task_type,source) VALUES(40,'P2P_ARCHIVE','QUARK')");
        var state = store.settings(false, 10, 1);
        jdbc.execute("INSERT INTO baidu_transfer_task VALUES(11)"); jdbc.execute("INSERT INTO resource_discovery_result VALUES(21,'BAIDU')");
        jdbc.execute("INSERT INTO resource_link(id,provider) VALUES(31,'BAIDU')"); jdbc.execute("INSERT INTO resource_hub_task(task_type,source) VALUES('P2P_ARCHIVE','XUNLEI')");
        var saved = store.read(); assertEquals(10, saved.taskMaxId); assertEquals(20, saved.discoveryMaxId); assertEquals(30, saved.linkMaxId); assertEquals(40, saved.p2pMaxId);
        assertEquals(state.movieCutoff, saved.movieCutoff); assertFalse(saved.enabled);
    }
    @Test void independentClaimersCannotShareAnUnexpiredLease() {
        store.settings(true, 10, 1); var first = store.claim(true); assertNotNull(first); assertNull(store.claim(true));
        var other = mapper.convertValue(first, BaiduBackfillTaskStore.State.class); other.leaseOwner = "wrong-owner";
        assertThrows(IllegalStateException.class, () -> store.checkpoint(other, null)); assertEquals(first.leaseOwner, store.read().leaseOwner);
    }
    @Test void revisionRejectsStaleProgressButPreservesDurableChildPointer() {
        store.settings(true, 10, 1); var first = store.claim(true); var stale = mapper.convertValue(first, BaiduBackfillTaskStore.State.class);
        first.phase = "SOURCE_SEARCH"; first.currentKind = "SOURCE_MOVIE"; first.currentChildId = 71L; first.currentMovieId = "电影😀";
        var saved = store.checkpoint(first, null);
        assertThrows(IllegalStateException.class, () -> store.checkpoint(stale, null)); assertEquals(71L, store.read().currentChildId);
        assertEquals("电影😀", store.read().currentMovieId); assertTrue(saved.revision > stale.revision);
    }
    @Test void pauseDuringRemoteWorkSurvivesTheNextCheckpointAndFinish() {
        store.settings(true, 10, 1); var working = store.claim(true); store.settings(false, 25, 2);
        working.processed = 1; working.succeeded = 1;
        working = store.checkpoint(working, null); assertFalse(working.enabled); assertEquals(25, working.intervalMinutes); assertEquals(2, working.batchLimit);
        var finished = store.finish(working); assertEquals("PAUSED", finished.status); assertNull(finished.nextRunAt); assertEquals(1, finished.succeeded);
    }
    @Test void childCreationAndParentAttachmentCommitOrRollBackTogether() {
        store.settings(true, 10, 1); var working = store.claim(true); working.phase = "SOURCE_SEARCH"; working.currentKind = "SOURCE_MOVIE"; working.currentMovieId = "movie1";
        assertThrows(IllegalStateException.class, () -> store.checkpoint(working, saved -> {
            jdbc.execute("INSERT INTO baidu_transfer_task VALUES(71)"); saved.currentChildId = 71L; throw new IllegalStateException("fixture rollback");
        }));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM baidu_transfer_task", Long.class)); assertNull(store.read().currentChildId);
        var saved = store.checkpoint(working, progress -> { jdbc.execute("INSERT INTO baidu_transfer_task VALUES(71)"); progress.currentChildId = 71L; });
        assertEquals(71L, store.read().currentChildId); assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM baidu_transfer_task", Long.class));
        assertEquals(saved.revision, store.read().revision);
    }
    @Test void expiredLeaseIsReclaimedWithoutResettingAnUncertainChild() {
        store.settings(true, 10, 1); var first = store.claim(true); first.currentChildId = 71L; first.phase = "SOURCE_SEARCH"; first.currentKind = "SOURCE_MOVIE"; first.currentMovieId = "movie1";
        first = store.checkpoint(first, null);
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.leaseUntil',?) WHERE id=?", LocalDateTime.now().minusMinutes(1).toString(), first.taskId);
        var expired = first; assertThrows(IllegalStateException.class, () -> store.checkpoint(expired, null));
        var reclaimed = store.claim(true); assertNotNull(reclaimed); assertNotEquals(first.leaseOwner, reclaimed.leaseOwner); assertEquals(71L, reclaimed.currentChildId);
        assertThrows(IllegalStateException.class, () -> store.finish(expired)); assertEquals(reclaimed.leaseOwner, store.read().leaseOwner);
    }
    @Test void sourceGapStillIncludesMoviesAlreadyAvailableOnQuarkAndXunlei() {
        jdbc.execute("INSERT INTO movie_metadata(id,status,created_at) VALUES('a-quark','ACTIVE',CURRENT_TIMESTAMP),('b-xunlei','ACTIVE',CURRENT_TIMESTAMP),('c-baidu','ACTIVE',CURRENT_TIMESTAMP)");
        jdbc.execute("INSERT INTO resource_link(movie_id,provider,type,status,audit_status,link_status) VALUES('a-quark','QUARK','DISK','ACTIVE',1,'NORMAL'),('b-xunlei','XUNLEI','DISK','ACTIVE',1,'NORMAL'),('c-baidu','BAIDU','TORRENT','ACTIVE',1,'NORMAL')");
        store.settings(true, 10, 1); var state = store.claim(true); state.phase = "SOURCE_SEARCH";
        ResourceHubProperties properties = new ResourceHubProperties(); properties.getBaidu().setEnabled(true); properties.getBaidu().setTorrentEnabled(true);
        var service = new BaiduBackfillService(store, jdbc, properties, mock(IResourceHubConfigService.class), mock(IBaiduTransferTaskService.class),
                mock(IBaiduTransferRunnerService.class), mock(IResourceDiscoveryResultService.class), mock(IResourceDiscoveryService.class),
                mock(IResourceHubTaskService.class), mock(IResourceHubPublishService.class), mock(GyingSourceWorkflowService.class), mock(IResourceLinkService.class), mock(IMovieMetadataService.class), mock(P2pArchiveTaskService.class), mock(BaiduWebClient.class), mapper);
        var selected = (BaiduBackfillTaskStore.State) ReflectionTestUtils.invokeMethod(service, "select", state); assertEquals("a-quark", selected.currentMovieId);
        selected.currentKind = null; selected.currentMovieId = null; selected.movieCursor = "a-quark";
        selected = ReflectionTestUtils.invokeMethod(service, "select", selected); assertEquals("b-xunlei", selected.currentMovieId);
        selected.currentKind = null; selected.currentMovieId = null; selected.movieCursor = "b-xunlei";
        selected = ReflectionTestUtils.invokeMethod(service, "select", selected); assertEquals("COMPLETE", selected.phase);
    }
    @Test void malformedPersistedStateIsRejectedBeforeAnyClaimOrProgressWrite() {
        store.settings(true, 10, 1); long id = store.read().taskId;
        String original = jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=?", String.class, id);
        for (String mutation : List.of("'$.enabled','true'", "'$.phase','UNSUPPORTED'", "'$.status','UNKNOWN'", "'$.batchLimit',1000", "'$.intervalMinutes',0", "'$.taskCursor',-1", "'$.processed',-1", "'$.leaseUntil','invalid'", "'$.currentKind','TRANSFER_EVERYTHING'")) {
            jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload," + mutation + ") WHERE id=?", id);
            assertThrows(IllegalStateException.class, () -> store.claim(true));
            jdbc.update("UPDATE resource_hub_task SET payload=? WHERE id=?", original, id);
        }
        assertNotNull(store.claim(true));
    }

}
