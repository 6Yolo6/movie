package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduSessionVault;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.*;
import com.gying.movie.mapper.*;
import com.gying.movie.service.*;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Scope allocation, clone/resource isolation and rollback on a disposable marked MySQL only. */
@EnabledIfEnvironmentVariable(named = "QUERY_BOUNDARY_MYSQL_FIXTURE", matches = "network-none-tmpfs")
class BaiduTemporaryTransferMySqlTest {
    private static JdbcTemplate jdbc; private static TransactionTemplate transactions;
    private static BaiduTransferTaskServiceImpl tasks; private static ResourceDiscoveryResultServiceImpl discoveries;
    private static ResourceLinkServiceImpl links; private static final ObjectMapper mapper = new ObjectMapper();
    @BeforeAll static void initialize() throws Exception {
        String runId = System.getenv("QUERY_BOUNDARY_FIXTURE_ID"); assertNotNull(runId); assertTrue(runId.matches("[0-9a-f]{12}"));
        String baseUrl = "jdbc:mysql://127.0.0.1:3306/baidu_transfer_fixture_" + runId;
        assertEquals(baseUrl, System.getenv("BAIDU_TEST_MYSQL_URL"));
        DriverManagerDataSource base = dataSource(baseUrl); JdbcTemplate probe = new JdbcTemplate(base);
        assertEquals("gying-query-fixture", probe.queryForObject("SELECT @@hostname", String.class));
        assertEquals(runId, probe.queryForObject("SELECT run_id FROM fixture_marker", String.class));
        String database = "baidu_temp_selection_fixture_" + runId;
        assertEquals(0, probe.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?", Integer.class, database));
        probe.execute("CREATE DATABASE " + database);
        DriverManagerDataSource data = dataSource("jdbc:mysql://127.0.0.1:3306/" + database); jdbc = new JdbcTemplate(data);
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class)); assertTrue(jdbc.queryForList("SHOW TABLES", String.class).isEmpty());
        try (Connection connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration_baidu_transfer_task.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration_baidu_search_temporary_scope.sql"));
        }
        jdbc.execute("CREATE TABLE movie_metadata(id VARCHAR(64) PRIMARY KEY,status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',deleted_at DATETIME NULL)");
        String schema = new ClassPathResource("db/schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (String table : List.of("resource_discovery_result", "resource_link")) {
            var block = Pattern.compile("CREATE TABLE `" + table + "` \\([\\s\\S]*?;").matcher(schema);
            assertTrue(block.find()); jdbc.execute(block.group());
        }
        jdbc.execute("ALTER TABLE resource_link ADD COLUMN updated_at DATETIME NULL, ADD COLUMN deleted_at DATETIME NULL");
        var configuration = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(configuration).setIdentifierGenerator(new DefaultIdentifierGenerator(0L, 0L));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("baidu-temp-fixture", new SpringManagedTransactionFactory(), data));
        configuration.addMapper(BaiduTransferTaskMapper.class); configuration.addMapper(ResourceDiscoveryResultMapper.class); configuration.addMapper(ResourceLinkMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        tasks = new BaiduTransferTaskServiceImpl(); ReflectionTestUtils.setField(tasks, "baseMapper", session.getMapper(BaiduTransferTaskMapper.class));
        discoveries = new ResourceDiscoveryResultServiceImpl(); ReflectionTestUtils.setField(discoveries, "baseMapper", session.getMapper(ResourceDiscoveryResultMapper.class));
        links = new ResourceLinkServiceImpl(); ReflectionTestUtils.setField(links, "baseMapper", session.getMapper(ResourceLinkMapper.class));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(data));
    }
    private static DriverManagerDataSource dataSource(String url) {
        var source = new DriverManagerDataSource(url, "root", ""); var p = new Properties();
        p.setProperty("connectTimeout", "3000"); p.setProperty("socketTimeout", "15000"); p.setProperty("connectionTimeZone", "Asia/Shanghai"); p.setProperty("useSSL", "false");
        source.setConnectionProperties(p); return source;
    }
    private ResourceDiscoveryResult source() {
        String movie = "select-" + UUID.randomUUID(); jdbc.update("INSERT INTO movie_metadata(id) VALUES(?)", movie);
        ResourceDiscoveryResult source = new ResourceDiscoveryResult(); source.setMovieId(movie); source.setSource("PANSOU");
        source.setTitle("临时选择测试"); source.setProvider("BAIDU"); source.setResourceType("DISK");
        source.setOriginalUrl("https://pan.baidu.com/s/1SourceCase?pwd=abcd"); source.setOriginalUrlHash(ResourceHubHashUtils.sha256(source.getOriginalUrl()));
        source.setStatus("DISCOVERED"); assertTrue(discoveries.save(source)); return source;
    }
    private BaiduTemporaryTransferService service(IResourceDiscoveryResultService discoveryService,
            IBaiduTransferTaskService taskService, BaiduTemporaryCleanupService cleanup, IBaiduTransferRunnerService runner, BaiduWebClient client) {
        var properties = new ResourceHubProperties(); properties.setEnabled(true); properties.getBaidu().setEnabled(true);
        properties.getBaidu().setVideoEnabled(true);
        var vault = mock(BaiduSessionVault.class); when(vault.metadata()).thenReturn(new BaiduSessionVault.Metadata(true, "100", "fixture", 1));
        var settings = mock(QqTransferCleanupSettings.class); when(settings.baiduTarget(anyLong())).thenAnswer(i -> "/GYing QQ Temp/baidu-" + i.getArgument(0));
        when(settings.delayMinutes()).thenReturn(10);
        when(client.isConfigured()).thenReturn(true);
        return new BaiduTemporaryTransferService(properties, client, vault, taskService, discoveryService, links, runner, settings, cleanup, jdbc, transactions, mapper);
    }
    @Test void permanentAndTwoUsersAllocateIndependentScopesWithoutRewritingTheSource() {
        ResourceDiscoveryResult source = source(); var cleanup = mock(BaiduTemporaryCleanupService.class);
        var service = service(discoveries, tasks, cleanup, mock(IBaiduTransferRunnerService.class), mock(BaiduWebClient.class));
        BaiduTransferTask permanent = new BaiduTransferTask(); permanent.setMovieId(source.getMovieId());
        permanent.setOriginalUrl(source.getOriginalUrl()); permanent.setOriginalUrlHash(source.getOriginalUrlHash()); assertTrue(tasks.save(permanent));
        BaiduTransferTask first = service.findOrCreate(source, "web:1"); BaiduTransferTask second = service.findOrCreate(source, "web:2");
        assertNotEquals(first.getScopeKey(), second.getScopeKey()); assertNotEquals(first.getSearchRequestHash(), second.getSearchRequestHash());
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM baidu_transfer_task WHERE movie_id=?", Long.class, source.getMovieId()));
        assertNull(discoveries.getById(source.getId()).getShareUrl()); assertNull(discoveries.getById(source.getId()).getResourceLinkId());
        assertEquals("PERMANENT", tasks.getById(permanent.getId()).getScopeKey());
    }
    @Test void twoIndependentInstancesAllocateOnlyOneTaskForTheSameUserSelection() throws Exception {
        ResourceDiscoveryResult source = source(); var cleanup = mock(BaiduTemporaryCleanupService.class);
        var first = service(discoveries, tasks, cleanup, mock(IBaiduTransferRunnerService.class), mock(BaiduWebClient.class));
        var second = service(discoveries, tasks, cleanup, mock(IBaiduTransferRunnerService.class), mock(BaiduWebClient.class));
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return first.findOrCreate(source, "qq:same-user"); });
            var b = pool.submit(() -> { start.await(); return second.findOrCreate(source, "qq:same-user"); });
            start.countDown(); assertEquals(a.get(20, TimeUnit.SECONDS).getId(), b.get(20, TimeUnit.SECONDS).getId());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM baidu_transfer_task WHERE movie_id=?", Long.class, source.getMovieId()));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM resource_discovery_result WHERE movie_id=? AND source='QQ_BOT'", Long.class, source.getMovieId()));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    @Test void markerPersistenceFailureRollsBackBothTheTaskAndItsClone() {
        ResourceDiscoveryResult source = source(); var failingTasks = spy(tasks);
        doReturn(false).when(failingTasks).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var service = service(discoveries, failingTasks, mock(BaiduTemporaryCleanupService.class), mock(IBaiduTransferRunnerService.class), mock(BaiduWebClient.class));
        assertThrows(IllegalStateException.class, () -> service.findOrCreate(source, "web:rollback"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM baidu_transfer_task WHERE movie_id=?", Long.class, source.getMovieId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM resource_discovery_result WHERE movie_id=?", Long.class, source.getMovieId()));
    }
    @Test void unknownCleanupRetainsTheOldScopeAndOnlyConfirmedDeletionAllowsANewOne() {
        ResourceDiscoveryResult source = source(); var cleanup = mock(BaiduTemporaryCleanupService.class);
        var service = service(discoveries, tasks, cleanup, mock(IBaiduTransferRunnerService.class), mock(BaiduWebClient.class));
        BaiduTransferTask first = service.findOrCreate(source, "web:expired");
        when(cleanup.isExpired(first.getId())).thenReturn(true);
        assertEquals(first.getId(), service.findOrCreate(source, "web:expired").getId());
        when(cleanup.isCleaned(first.getId())).thenReturn(true);
        BaiduTransferTask second = service.findOrCreate(source, "web:expired");
        assertNotEquals(first.getId(), second.getId()); assertEquals(first.getSearchRequestHash(), second.getSearchRequestHash());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM baidu_transfer_task WHERE movie_id=?", Long.class, source.getMovieId()));
    }
    @Test void repeatedSuccessfulSelectionBindsOneExclusiveResourceAndKeepsTheOriginalUntouched() throws Exception {
        ResourceDiscoveryResult source = source(); var cleanup = mock(BaiduTemporaryCleanupService.class); var client = mock(BaiduWebClient.class);
        when(client.validateOwnShare(anyString())).thenReturn(true);
        when(client.inspectTemporaryDirectory(any())).thenReturn(BaiduWebClient.DestinationState.MATCHING);
        var runner = mock(IBaiduTransferRunnerService.class); var service = service(discoveries, tasks, cleanup, runner, client);
        BaiduTransferTask task = confirm(service.findOrCreate(source, "web:binding"));
        when(cleanup.expiresAt(task.getId())).thenReturn(task.getShareConfirmedAt().plusMinutes(10));
        var first = service.select(source.getId(), "web:binding"); var second = service.select(source.getId(), "web:binding");
        assertNotNull(first.resource()); assertEquals(first.resource().getId(), second.resource().getId());
        assertEquals("BAIDU_TEMP", first.resource().getSource()); assertEquals(task.getScopeKey(), first.resource().getSourceRef());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM resource_link WHERE movie_id=?", Long.class, source.getMovieId()));
        assertNull(discoveries.getById(source.getId()).getResourceLinkId()); assertNull(discoveries.getById(source.getId()).getShareUrl());
        verify(cleanup, times(2)).bindResource(task.getId(), task.getDiscoveryResultId(), first.resource().getId(), task.getShareUrlHash());
        when(client.inspectTemporaryDirectory(any())).thenReturn(BaiduWebClient.DestinationState.CHANGED);
        assertEquals("WAITING_SHARE", service.select(source.getId(), "web:binding").status());
        verify(cleanup, times(2)).bindResource(task.getId(), task.getDiscoveryResultId(), first.resource().getId(), task.getShareUrlHash());
        when(client.inspectTemporaryDirectory(any())).thenReturn(BaiduWebClient.DestinationState.MATCHING);
        doThrow(new IllegalStateException("CLEANUP_BINDING_EXPIRED")).when(cleanup)
                .bindResource(task.getId(), task.getDiscoveryResultId(), first.resource().getId(), task.getShareUrlHash());
        assertEquals("EXPIRED", service.select(source.getId(), "web:binding").status());
    }
    @Test void resourceBindingFailureRollsBackTheNewLinkAndDoesNotResubmitTheTransfer() throws Exception {
        ResourceDiscoveryResult source = source(); var cleanup = mock(BaiduTemporaryCleanupService.class); var client = mock(BaiduWebClient.class);
        when(client.validateOwnShare(anyString())).thenReturn(true);
        when(client.inspectTemporaryDirectory(any())).thenReturn(BaiduWebClient.DestinationState.MATCHING);
        var runner = mock(IBaiduTransferRunnerService.class); var normal = service(discoveries, tasks, cleanup, runner, client);
        BaiduTransferTask task = confirm(normal.findOrCreate(source, "web:link-rollback"));
        when(cleanup.expiresAt(task.getId())).thenReturn(task.getShareConfirmedAt().plusMinutes(10));
        var failingDiscoveries = spy(discoveries); doReturn(false).when(failingDiscoveries).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var service = service(failingDiscoveries, tasks, cleanup, runner, client);
        assertNull(service.select(source.getId(), "web:link-rollback").resource());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM resource_link WHERE movie_id=?", Long.class, source.getMovieId()));
        assertNull(discoveries.getById(task.getDiscoveryResultId()).getResourceLinkId());
        assertEquals("SUCCEEDED", tasks.getById(task.getId()).getStatus()); verify(client, never()).transfer(any()); verify(client, never()).createOwnShare(any());
    }
    private BaiduTransferTask confirm(BaiduTransferTask task) throws Exception {
        task.setAccountUid("100"); task.setSavedPath("/GYing QQ Temp/baidu-" + task.getId());
        task.setShareUrl("https://pan.baidu.com/s/1Own" + task.getId() + "?pwd=abcd"); task.setShareUrlHash(ResourceHubHashUtils.sha256(task.getShareUrl()));
        task.setPhase("SHARED"); task.setStatus("SUCCEEDED"); task.setShareConfirmedAt(LocalDateTime.now()); task.setFinishedAt(task.getShareConfirmedAt());
        task.setCleanupEvidence(mapper.writeValueAsString(new BaiduWebClient.DestinationEvidence("100", task.getSavedPath(), "55",
                List.of(new BaiduWebClient.DestinationFile("Movie.mkv", "56", 100, false, null)))));
        task.setResponsePayload(mapper.writeValueAsString(new BaiduWebClient.TransferPlan(task.getOriginalUrl(), null, task.getSavedPath(), "1", "200",
                List.of("11"), List.of(new BaiduWebClient.ExpectedFile("Movie.mkv", 100, false)))));
        assertTrue(tasks.updateById(task));
        ResourceDiscoveryResult clone = discoveries.getById(task.getDiscoveryResultId()); clone.setShareUrl(task.getShareUrl()); clone.setShareUrlHash(task.getShareUrlHash());
        assertTrue(discoveries.updateById(clone)); return task;
    }
}
