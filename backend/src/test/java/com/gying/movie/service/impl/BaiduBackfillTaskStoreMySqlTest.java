package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.client.P2pCloudArchiveClient;
import com.gying.movie.client.GyingSourceClient;
import com.gying.movie.entity.*;
import com.gying.movie.mapper.ResourceHubTaskMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
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
    static ResourceHubTaskServiceImpl taskService;
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
        String identities = new ClassPathResource("db/migration_movie_source_identity.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(identities.substring(0, identities.indexOf(';') + 1));
        store = new BaiduBackfillTaskStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), mapper);
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(configuration).setIdentifierGenerator(new DefaultIdentifierGenerator(0L, 0L));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(ResourceHubTaskMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        taskService = new ResourceHubTaskServiceImpl(); ReflectionTestUtils.setField(taskService, "baseMapper", session.getMapper(ResourceHubTaskMapper.class));
    }
    static DriverManagerDataSource dataSource(String url) {
        var source = new DriverManagerDataSource(url, "root", ""); var properties = new Properties();
        properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "15000");
        properties.setProperty("connectionTimeZone", "Asia/Shanghai"); properties.setProperty("useSSL", "false");
        source.setConnectionProperties(properties); return source;
    }
    @BeforeEach void emptyOnlyVerifiedDisposableTables() {
        for (String table : List.of("sys_config", "resource_hub_task", "baidu_transfer_task", "resource_discovery_result", "resource_link", "movie_metadata", "movie_source_identity")) jdbc.execute("DELETE FROM " + table);
        jdbc.execute("INSERT INTO movie_metadata(id,status,created_at) VALUES('movie1','ACTIVE',CURRENT_TIMESTAMP)");
        identity(2, "movie1", "mv", "CASE1", "CONFIRMED", 100);
    }
    static void identity(long id, String movie, String type, String mid, String status, int confidence) {
        jdbc.update("INSERT INTO movie_source_identity(id,movie_id,source,source_type,external_id,match_method,match_status,confidence) VALUES(?,?,'GYING',?,?, 'fixture',?,?)",
                id, movie, type, mid, status, confidence);
    }
    static BaiduBackfillTaskStore.State attach(BaiduBackfillTaskStore.State state, Long childId) {
        state.currentIdentityId = 2L; state.currentMovieId = "movie1"; state.currentGyingType = "mv"; state.currentGyingId = "CASE1";
        state.currentChildId = childId; state.currentResourceIds = List.of(31L); state.currentSeedNames = List.of("Movie seed"); state.currentSeedCount = 1;
        state.currentAction = "ARCHIVE_METADATA_FILES"; return state;
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
    @Test void fixedIdentityHighWaterNeverGrowsWhenNewIdsArrive() {
        identity(10, "movie1", "mv", "MORE", "AUTO", 50); var state = store.settings(false, 10, 1);
        identity(11, "movie1", "mv", "NEW", "CONFIRMED", 100); var saved = store.read();
        assertEquals(2, saved.version); assertEquals(10, saved.identityMaxId); assertEquals(state.movieCutoff, saved.movieCutoff); assertFalse(saved.enabled);
    }
    @Test void independentClaimersCannotShareAnUnexpiredLease() {
        store.settings(true, 10, 1); var first = store.claim(true); assertNotNull(first); assertNull(store.claim(true));
        var other = mapper.convertValue(first, BaiduBackfillTaskStore.State.class); other.leaseOwner = "wrong-owner";
        assertThrows(IllegalStateException.class, () -> store.checkpoint(other, null)); assertEquals(first.leaseOwner, store.read().leaseOwner);
    }
    @Test void revisionRejectsStaleProgressButPreservesDurableChildPointer() {
        store.settings(true, 10, 1); var first = store.claim(true); var stale = mapper.convertValue(first, BaiduBackfillTaskStore.State.class);
        var saved = store.checkpoint(attach(first, 71L), null);
        assertThrows(IllegalStateException.class, () -> store.checkpoint(stale, null)); assertEquals(71L, store.read().currentChildId);
        assertEquals("CASE1", store.read().currentGyingId); assertTrue(saved.revision > stale.revision);
    }
    @Test void pauseDuringRemoteWorkSurvivesCheckpointAndFinish() {
        store.settings(true, 10, 1); var working = store.claim(true); store.settings(false, 25, 2);
        working.processed = 1; working.succeeded = 1;
        working = store.checkpoint(working, null); assertFalse(working.enabled); assertEquals(25, working.intervalMinutes); assertEquals(2, working.batchLimit);
        var finished = store.finish(working); assertEquals("PAUSED", finished.status); assertNull(finished.nextRunAt); assertEquals(1, finished.succeeded);
    }
    @Test void childCreationAndParentAttachmentCommitOrRollBackTogether() {
        store.settings(true, 10, 1); var working = attach(store.claim(true), null);
        assertThrows(IllegalStateException.class, () -> store.checkpoint(working, saved -> {
            jdbc.execute("INSERT INTO resource_hub_task(id,task_type,source) VALUES(71,'P2P_ARCHIVE','BAIDU')"); saved.currentChildId = 71L; throw new IllegalStateException("fixture rollback");
        }));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='P2P_ARCHIVE'", Long.class)); assertNull(store.read().currentChildId);
        var saved = store.checkpoint(working, progress -> { jdbc.execute("INSERT INTO resource_hub_task(id,task_type,source) VALUES(71,'P2P_ARCHIVE','BAIDU')"); progress.currentChildId = 71L; });
        assertEquals(71L, store.read().currentChildId); assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='P2P_ARCHIVE'", Long.class));
        assertEquals(saved.revision, store.read().revision);
    }
    @Test void expiredLeaseIsReclaimedWithoutResettingAnUncertainChild() {
        store.settings(true, 10, 1); var first = store.checkpoint(attach(store.claim(true), 71L), null);
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.leaseUntil',?) WHERE id=?", LocalDateTime.now().minusMinutes(1).toString(), first.taskId);
        var expired = first; assertThrows(IllegalStateException.class, () -> store.checkpoint(expired, null));
        var reclaimed = store.claim(true); assertNotNull(reclaimed); assertNotEquals(first.leaseOwner, reclaimed.leaseOwner); assertEquals(71L, reclaimed.currentChildId);
        assertThrows(IllegalStateException.class, () -> store.finish(expired)); assertEquals(reclaimed.leaseOwner, store.read().leaseOwner);
    }
    @Test void selectionUsesOnlyVerifiedExactGyingIdsAndStableBestMappingInsideTheSnapshot() {
        jdbc.execute("DELETE FROM movie_source_identity"); jdbc.execute("DELETE FROM movie_metadata");
        jdbc.execute("INSERT INTO movie_metadata(id,status,created_at) VALUES('a-quark','ACTIVE',CURRENT_TIMESTAMP),('b-xunlei','ACTIVE',CURRENT_TIMESTAMP),('c-review','ACTIVE',CURRENT_TIMESTAMP),('d-future','ACTIVE',DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY))");
        identity(10, "a-quark", "mv", "LowCase", "AUTO", 90); identity(11, "a-quark", "mv", "CaSe", "CONFIRMED", 100);
        identity(12, "b-xunlei", "tv", "TvX", "AUTO", 100); identity(13, "c-review", "mv", "Review", "REVIEW", 100); identity(14, "d-future", "mv", "Future", "CONFIRMED", 100);
        jdbc.execute("INSERT INTO resource_link(movie_id,provider,type,status,audit_status,link_status) VALUES('a-quark','QUARK','DISK','ACTIVE',1,'NORMAL'),('b-xunlei','XUNLEI','DISK','ACTIVE',1,'NORMAL')");
        store.settings(true, 10, 1); var state = store.claim(true); identity(15, "a-quark", "mv", "NEW", "CONFIRMED", 100);
        var rows = jdbc.queryForList(BaiduBackfillService.NEXT_IDENTITY_SQL, state.movieCutoff, 0, state.identityMaxId, state.identityMaxId);
        assertEquals(1, rows.size()); assertEquals(11L, ((Number)rows.get(0).get("id")).longValue()); assertEquals("CaSe", rows.get(0).get("external_id"));
        rows = jdbc.queryForList(BaiduBackfillService.NEXT_IDENTITY_SQL, state.movieCutoff, 11, state.identityMaxId, state.identityMaxId);
        assertEquals(12L, ((Number)rows.get(0).get("id")).longValue()); assertEquals("b-xunlei", rows.get(0).get("movie_id"));
        assertTrue(jdbc.queryForList(BaiduBackfillService.NEXT_IDENTITY_SQL, state.movieCutoff, 12, state.identityMaxId, state.identityMaxId).isEmpty());
    }
    @Test void malformedPersistedStateIsRejectedBeforeAnyClaimOrProgressWrite() {
        store.settings(true, 10, 1); long id = store.read().taskId;
        String original = jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=?", String.class, id);
        for (String mutation : List.of("'$.enabled','true'", "'$.phase','SOURCE_SEARCH'", "'$.status','UNKNOWN'", "'$.batchLimit',1000", "'$.intervalMinutes',0", "'$.identityCursor',-1", "'$.processed',-1", "'$.leaseUntil','invalid'", "'$.currentAction','TRANSFER_VIDEO'", "'$.currentSeedCount',3", "'$.currentResourceIds',JSON_ARRAY('31')")) {
            jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload," + mutation + ") WHERE id=?", id);
            assertThrows(IllegalStateException.class, () -> store.claim(true)); jdbc.update("UPDATE resource_hub_task SET payload=? WHERE id=?", original, id);
        }
        assertNotNull(store.claim(true));
    }
    @Test void legacyUnknownParentAndOldPointerRemainVerbatimAndDoNotGateTheNewParent() {
        jdbc.update("INSERT INTO resource_hub_task(id,task_type,source,status,payload,last_error) VALUES(9000,'CLOUD_DRIVE_BACKFILL','BAIDU','BLOCKED',?,'CHILD_UNKNOWN')",
                "{\"version\":1,\"enabled\":false,\"currentGyingSubmitting\":true,\"currentChildId\":42,\"opaque\":\"KEEP\"}");
        jdbc.update("INSERT INTO sys_config(config_key,config_value) VALUES(?, '9000')", BaiduBackfillTaskStore.LEGACY_KEY);
        String legacy = jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=9000", String.class);
        assertNull(store.read().taskId); assertEquals(9000L, store.legacyTaskId());
        var created = store.settings(false, 10, 1); assertNotEquals(9000L, created.taskId); assertEquals(2, created.version);
        var claimed = store.claim(true); store.finish(claimed);
        assertEquals(legacy, jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=9000", String.class));
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT status FROM resource_hub_task WHERE id=9000", String.class));
        assertEquals("CHILD_UNKNOWN", jdbc.queryForObject("SELECT last_error FROM resource_hub_task WHERE id=9000", String.class));
        assertEquals("9000", jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?", String.class, BaiduBackfillTaskStore.LEGACY_KEY));
    }
    static BaiduBackfillTaskStore.State interruptedChild(boolean submitting, Long owner) {
        store.settings(true, 10, 1); var state = store.claim(true);
        long parent = owner == null ? state.taskId : owner;
        jdbc.update("INSERT INTO resource_hub_task(id,task_type,source,movie_id,status,attempts,max_attempts,payload) VALUES(2000,'P2P_ARCHIVE','BAIDU','movie1','RUNNING',2,3,?)",
                "{\"typeCode\":\"mv\",\"mid\":\"CASE1\",\"backfillParentId\":" + parent + ",\"baiduShareSubmitting\":" + submitting + ",\"stage\":\"UPLOAD_TORRENT\",\"folderId\":\"metadata-only\",\"uploadedFiles\":{\"a.torrent\":\"fid1\"}}");
        return store.checkpoint(attach(state, 2000L), null);
    }
    @Test void ownedInterruptedUploadRecoversWithFreshParentLeaseAndKeepsCheckpointsAndBudget() {
        var state = interruptedChild(false, null); String before = jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=2000", String.class);
        assertTrue(store.recoverInterruptedArchive(state)); assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM resource_hub_task WHERE id=2000", String.class));
        assertEquals(before, jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=2000", String.class)); assertEquals(2, jdbc.queryForObject("SELECT attempts FROM resource_hub_task WHERE id=2000", Integer.class));
        assertFalse(store.recoverInterruptedArchive(state));
    }
    @Test void unresolvedShareIntentIsNeverResetByInterruptedUploadRecovery() {
        var state = interruptedChild(true, null); String before = jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=2000", String.class);
        assertFalse(store.recoverInterruptedArchive(state)); assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM resource_hub_task WHERE id=2000", String.class));
        assertEquals(before, jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=2000", String.class));
    }
    @Test void recoveryRejectsForeignOwnerAndEveryStaleOrExpiredParentLease() {
        var state = interruptedChild(false, 99999L); assertFalse(store.recoverInterruptedArchive(state));
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.backfillParentId',?) WHERE id=2000", state.taskId);
        var foreign = mapper.convertValue(state, BaiduBackfillTaskStore.State.class); foreign.leaseOwner = "wrong-owner";
        assertThrows(IllegalStateException.class, () -> store.recoverInterruptedArchive(foreign));
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.leaseUntil',?) WHERE id=?", LocalDateTime.now().minusMinutes(1).toString(), state.taskId);
        assertThrows(IllegalStateException.class, () -> store.recoverInterruptedArchive(state));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM resource_hub_task WHERE id=2000", String.class));
        var reclaimed = store.claim(true); assertNotNull(reclaimed); assertTrue(store.recoverInterruptedArchive(reclaimed));
    }
    static List<ResourceLink> selection() {
        ResourceLink magnet = new ResourceLink(); magnet.setId(31L); magnet.setMovieId("movie1"); magnet.setType("MAGNET");
        magnet.setProvider("P2P"); magnet.setSource("GYING"); magnet.setSourceRef("BT1"); magnet.setQuality("1080P");
        magnet.setSubtitle("中文字幕（来源标注）"); magnet.setAuditStatus(1); magnet.setStatus("ACTIVE");
        magnet.setUrl("magnet:?xt=urn:btih:" + "a".repeat(40)); return List.of(magnet, P2pArchiveTaskService.torrentDescriptor(magnet));
    }
    static P2pArchiveTaskService archives(IResourceLinkService links, P2pCloudArchiveClient cloud) {
        ResourceHubProperties properties = new ResourceHubProperties(); properties.setEnabled(true); properties.getBaidu().setEnabled(true);
        properties.getBaidu().setShareEnabled(true); properties.getBaidu().setTorrentEnabled(true); properties.getQuark().setShareEnabled(true); properties.getQuark().setTorrentEnabled(true); when(cloud.isConfigured("BAIDU")).thenReturn(true);
        return new P2pArchiveTaskService(properties, taskService, links, mock(IMovieMetadataService.class), mock(IQuarkTransferTaskService.class), mock(IXunleiTransferTaskService.class),
                mock(IBaiduTransferTaskService.class), mock(GyingSourceClient.class), cloud, mapper);
    }
    @Test void ordinaryP2pChildAdoptionUsesRealJsonCasAndClosesTheGenericWorkerRace() throws Exception {
        var selected = selection(); var links = mock(IResourceLinkService.class); var cloud = mock(P2pCloudArchiveClient.class); var archive = archives(links, cloud);
        ResourceHubTask ordinary = new ResourceHubTask(); ordinary.setTaskType("P2P_ARCHIVE"); ordinary.setMovieId("movie1"); ordinary.setSource("BAIDU"); ordinary.setKeyword(P2pArchiveTaskService.fingerprint(selected));
        ordinary.setPayload("{\"version\":2,\"typeCode\":\"mv\",\"mid\":\"CASE1\",\"resourceIds\":[31],\"torrentMagnetIds\":[31],\"fingerprint\":\"" + ordinary.getKeyword() + "\"}"); ordinary = taskService.enqueue(ordinary);
        Long ordinaryId = ordinary.getId(); ResourceHubTask staleGenericSelection = taskService.getById(ordinaryId); store.settings(true, 10, 1); var state = attach(store.claim(true), null);
        var saved = store.checkpoint(state, parent -> parent.currentChildId = archive.enqueueForProvider("movie1", "mv", "CASE1", selected, "BAIDU", parent.taskId).getId());
        assertEquals(ordinary.getId(), saved.currentChildId); assertEquals(saved.taskId.longValue(), mapper.readTree(taskService.getById(ordinary.getId()).getPayload()).path("backfillParentId").asLong());
        archive.runTask(staleGenericSelection); assertEquals("PENDING", taskService.getById(ordinary.getId()).getStatus());
        verify(cloud, never()).upload(anyString(), anyString(), any()); verifyNoInteractions(links);
        assertFalse(taskService.list(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ResourceHubTask>().eq("task_type", "P2P_ARCHIVE").apply("JSON_EXTRACT(payload,'$.backfillParentId') IS NULL")).stream().anyMatch(task -> task.getId().equals(ordinaryId)));
    }
    @Test void existingUnknownSelectionIsReusedWithoutChangingAnyRemoteCheckpoint() throws Exception {
        var selected = selection(); var archive = archives(mock(IResourceLinkService.class), mock(P2pCloudArchiveClient.class));
        ResourceHubTask unknown = new ResourceHubTask(); unknown.setTaskType("P2P_ARCHIVE"); unknown.setMovieId("movie1"); unknown.setSource("BAIDU"); unknown.setStatus("UNKNOWN");
        unknown.setKeyword(P2pArchiveTaskService.fingerprint(selected)); unknown.setPayload("{\"typeCode\":\"mv\",\"mid\":\"CASE1\",\"baiduShareSubmitting\":true,\"folderId\":\"preserve\",\"resourceIds\":[31],\"torrentMagnetIds\":[31]}");
        unknown = taskService.enqueue(unknown); String before = taskService.getById(unknown.getId()).getPayload();
        store.settings(true, 10, 1); var state = store.claim(true);
        assertEquals(unknown.getId(), archive.protectedArchive("movie1", "BAIDU").getId());
        assertEquals(unknown.getId(), archive.enqueueForProvider("movie1", "mv", "CASE1", selected, "BAIDU", state.taskId).getId());
        assertEquals(before, taskService.getById(unknown.getId()).getPayload()); assertEquals("UNKNOWN", taskService.getById(unknown.getId()).getStatus());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='P2P_ARCHIVE'", Long.class));
    }
    static ResourceHubTask ordinary(String movie, String provider) {
        ResourceHubTask task = new ResourceHubTask(); task.setTaskType("P2P_ARCHIVE"); task.setMovieId(movie); task.setSource(provider);
        task.setPayload("{\"version\":2,\"typeCode\":\"mv\",\"mid\":\"CASE1\",\"resourceIds\":[31],\"torrentMagnetIds\":[31],\"fingerprint\":\"fixture-no-cloud\"}");
        return taskService.enqueue(task);
    }
    @Test void freshCurrentV2LeaseBlocksGenericBaiduClaimThroughoutCollectionButNotOwnedExactClaim() throws Exception {
        var archive = archives(mock(IResourceLinkService.class), mock(P2pCloudArchiveClient.class));
        store.settings(true, 10, 1); var parent = store.checkpoint(attach(store.claim(true), null), null); ResourceHubTask ordinary = ordinary("movie1", "BAIDU");
        archive.runTask(ordinary); assertEquals("PENDING", taskService.getById(ordinary.getId()).getStatus()); assertEquals(0, taskService.getById(ordinary.getId()).getAttempts());
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(ordinary.getPayload()); payload.put("backfillParentId", parent.taskId);
        jdbc.update("UPDATE resource_hub_task SET payload=? WHERE id=?", payload.toString(), ordinary.getId());
        parent = store.checkpoint(attach(parent, ordinary.getId()), null);
        archive.submitOne(ordinary.getId(), "BAIDU", parent.taskId);
        assertEquals(1, taskService.getById(ordinary.getId()).getAttempts());
    }
    @Test void genericClaimGuardAllowsOtherMoviesAndProvidersAndReleasesAfterLeaseExpiry() {
        var archive = archives(mock(IResourceLinkService.class), mock(P2pCloudArchiveClient.class));
        store.settings(true, 10, 1); var parent = store.checkpoint(attach(store.claim(true), null), null);
        ResourceHubTask otherMovie = ordinary("movie2", "BAIDU"); archive.runTask(otherMovie); assertEquals(1, taskService.getById(otherMovie.getId()).getAttempts());
        ResourceHubTask otherProvider = ordinary("movie1", "QUARK"); archive.runTask(otherProvider); assertEquals(1, taskService.getById(otherProvider.getId()).getAttempts());
        ResourceHubTask target = ordinary("movie1", "BAIDU"); archive.runTask(target); assertEquals(0, taskService.getById(target.getId()).getAttempts());
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.leaseUntil',?) WHERE id=?", LocalDateTime.now().minusMinutes(1).toString(), parent.taskId);
        archive.runTask(taskService.getById(target.getId())); assertEquals(1, taskService.getById(target.getId()).getAttempts());
    }
    @Test void oldPointerAndUnpointedV2RowsDoNotBlockOrdinaryBaiduJobs() {
        var archive = archives(mock(IResourceLinkService.class), mock(P2pCloudArchiveClient.class));
        store.settings(true, 10, 1); var parent = store.checkpoint(attach(store.claim(true), null), null);
        jdbc.update("UPDATE sys_config SET config_key=? WHERE config_key=?", BaiduBackfillTaskStore.LEGACY_KEY, BaiduBackfillTaskStore.KEY);
        ResourceHubTask ordinary = ordinary("movie1", "BAIDU"); archive.runTask(ordinary); assertEquals(1, taskService.getById(ordinary.getId()).getAttempts());
        assertEquals(parent.taskId.toString(), jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?", String.class, BaiduBackfillTaskStore.LEGACY_KEY));
    }

    @Test void genericGuardAcceptsTheMinutePrecisionIsoLeaseFormatWrittenByLocalDateTime() {
        var archive = archives(mock(IResourceLinkService.class), mock(P2pCloudArchiveClient.class));
        store.settings(true, 10, 1); var parent = store.checkpoint(attach(store.claim(true), null), null);
        String lease = LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0).toString();
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.leaseUntil',?) WHERE id=?", lease, parent.taskId);
        ResourceHubTask ordinary = ordinary("movie1", "BAIDU"); archive.runTask(ordinary);
        assertEquals(0, taskService.getById(ordinary.getId()).getAttempts()); assertEquals("PENDING", taskService.getById(ordinary.getId()).getStatus());
    }

}
