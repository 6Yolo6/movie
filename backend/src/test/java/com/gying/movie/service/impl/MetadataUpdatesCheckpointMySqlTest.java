package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
        jdbc.execute("CREATE TABLE followup_metadata_fixture (id VARCHAR(64) PRIMARY KEY, syncs INT NOT NULL) ENGINE=InnoDB");
    }
    @BeforeEach void resetOnlyFixtureRows() { jdbc.execute("TRUNCATE TABLE resource_hub_task"); jdbc.execute("TRUNCATE TABLE followup_metadata_fixture"); }
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
    private static class ResourceFixture {
        final ResourceHubProperties properties = new ResourceHubProperties();
        final IMovieMetadataService movies = mock(IMovieMetadataService.class);
        final IMovieSourceIdentityService identities = mock(IMovieSourceIdentityService.class);
        final GyingSourceWorkflowService workflow = mock(GyingSourceWorkflowService.class);
        final P2pArchiveTaskService archives = mock(P2pArchiveTaskService.class);
        final TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        final Map<String, MovieMetadata> rows = new LinkedHashMap<>();
        final TmdbGyingResourceFollowUpService helper;
        ResourceFixture(IResourceHubTaskService store) {
            properties.setEnabled(true); properties.getWorker().setEnabled(true);
            properties.getTmdb().setAutoSyncEnabled(true); properties.getTmdb().setAutoSyncUpdatesOnly(true);
            properties.getGying().setAutoSyncEnabled(true); properties.getGying().setAutoSyncUpdatesOnly(true);
            properties.getXunlei().setDiscoveryEnabled(false); properties.getBaidu().setDiscoveryEnabled(false);
            helper = new TmdbGyingResourceFollowUpService(properties, store, movies, identities, workflow, archives,
                    mapper, jdbc, transactions, mock(IResourceHubConfigService.class));
            when(movies.getById(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
            when(movies.getOne(any(), eq(false))).thenAnswer(c -> {
                QueryWrapper<MovieMetadata> query = c.getArgument(0); query.getSqlSegment();
                return rows.values().stream().filter(row -> query.getParamNameValuePairs().containsValue(row.getId())).findFirst().orElse(null);
            });
            when(identities.getOne(any(), eq(false))).thenAnswer(c -> {
                QueryWrapper<MovieSourceIdentity> query = c.getArgument(0); query.getSqlSegment();
                MovieMetadata row = rows.values().stream().filter(movie -> query.getParamNameValuePairs()
                        .containsValue(String.valueOf(movie.getTmdbId()))).findFirst().orElse(null);
                if (row == null) return null;
                MovieSourceIdentity identity = new MovieSourceIdentity(); identity.setId(row.getTmdbId()); identity.setMovieId(row.getId());
                identity.setSource("TMDB"); identity.setSourceType("movie"); identity.setExternalId(String.valueOf(row.getTmdbId()));
                identity.setSeason(0); identity.setMatchStatus("CONFIRMED"); return identity;
            });
        }
        MovieMetadata movie(int number) {
            MovieMetadata row = new MovieMetadata(); row.setId("resource-movie-" + number); row.setTmdbId((long) number);
            row.setTmdbType("movie"); row.setCategory("mv"); row.setStatus("ACTIVE"); row.setSeason(3);
            row.setYear(2026); row.setTitleCn("Film " + number); row.setTmdbLastSyncAt(LocalDateTime.now()); rows.put(row.getId(), row); return row;
        }
        MovieMetadata persist(MovieMetadata row) {
            return helper.persistMetadataAndEnqueue(999L, () -> {
                jdbc.update("INSERT INTO followup_metadata_fixture(id,syncs) VALUES (?,1) ON DUPLICATE KEY UPDATE syncs=syncs+1", row.getId());
                return row;
            }, movie -> movie);
        }
        ResourceHubTask task(int number) {
            persist(movie(number));
            ResourceHubTask created = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TmdbGyingResourceFollowUpService.TYPE)
                    .eq("movie_id", "resource-movie-" + number), false);
            // DATETIME(0) rounds fractional enqueue times; all execution fixtures have an explicit past due time.
            jdbc.update("UPDATE resource_hub_task SET scheduled_at=? WHERE id=?", LocalDateTime.now().minusMinutes(1), created.getId());
            return tasks.getById(created.getId());
        }
        boolean claim(ResourceHubTask task) {
            return helper.withDatabaseLock("gying:tmdb-resource-budget:v1", () -> transactions.execute(tx -> helper.reserveAndClaim(task)));
        }
    }

    @Test void followupMetadataAndAcknowledgedIntentAreAtomicAndStableKeyIsDatabaseSerialized() throws Exception {
        ResourceFixture fixture = new ResourceFixture(tasks); var movie = fixture.movie(1);
        CountDownLatch start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return fixture.persist(movie); });
            var second = executor.submit(() -> { start.await(); return fixture.persist(movie); });
            start.countDown(); first.get(15, java.util.concurrent.TimeUnit.SECONDS); second.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='TMDB_GYING_RESOURCE'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT syncs FROM followup_metadata_fixture WHERE id=?", Integer.class, movie.getId()));
        var resource = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TmdbGyingResourceFollowUpService.TYPE), false);
        jdbc.update("UPDATE resource_hub_task SET status='FAILED', attempts=3 WHERE id=?", resource.getId());
        fixture.persist(movie);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task", Integer.class));
        assertEquals(3, tasks.getById(resource.getId()).getAttempts());
        assertEquals("FAILED", tasks.getById(resource.getId()).getStatus());
    }

    @Test void followupEnqueueAckFailureRollsBackAlreadyWrittenMetadataAndIntent() {
        ResourceHubTaskServiceImpl rejected = spy(tasks);
        doAnswer(c -> { ResourceHubTask row = c.getArgument(0); tasks.enqueue(row); return null; }).when(rejected).enqueue(any());
        ResourceFixture fixture = new ResourceFixture(rejected); var movie = fixture.movie(1);
        assertThrows(IllegalStateException.class, () -> fixture.persist(movie));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM followup_metadata_fixture", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task", Integer.class));
        verifyNoInteractions(fixture.workflow, fixture.archives);
    }

    @Test void followupActualJsonCasLimitsTenPer24HoursAndKeepsBudgetSucceededOutOfWorkQueue() throws Exception {
        ResourceFixture fixture = new ResourceFixture(tasks); List<ResourceHubTask> jobs = new ArrayList<>();
        for (int i = 1; i <= 11; i++) jobs.add(fixture.task(i));
        for (int i = 0; i < 10; i++) assertTrue(fixture.claim(jobs.get(i)));
        assertFalse(fixture.claim(jobs.get(10)));
        ResourceHubTask budget = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TmdbGyingResourceFollowUpService.BUDGET_TYPE), false);
        assertEquals("SUCCEEDED", budget.getStatus()); assertEquals(0, budget.getMaxAttempts());
        assertEquals(10, mapper.readTree(budget.getPayload()).path("used").asInt());
        assertEquals(0, tasks.getById(jobs.get(10).getId()).getAttempts());
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.windowStartedAt',?) WHERE id=?", LocalDateTime.now().minusHours(25).toString(), budget.getId());
        jdbc.update("UPDATE resource_hub_task SET scheduled_at=? WHERE id=?", LocalDateTime.now().minusMinutes(1), jobs.get(10).getId());
        assertTrue(fixture.claim(tasks.getById(jobs.get(10).getId())));
        assertEquals(1, mapper.readTree(tasks.getById(budget.getId()).getPayload()).path("used").asInt());
        verifyNoInteractions(fixture.workflow, fixture.archives);
    }

    @Test void followupLostClaimRollsBackBudgetReservationAndRunsNoRemoteWork() {
        ResourceFixture fixture = new ResourceFixture(tasks); var stale = fixture.task(1);
        jdbc.update("UPDATE resource_hub_task SET status='CANCELED' WHERE id=?", stale.getId());
        assertThrows(TmdbGyingResourceFollowUpService.CheckpointConflict.class, () -> fixture.claim(stale));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='TMDB_GYING_RESOURCE_BUDGET'", Integer.class));
        assertEquals("CANCELED", tasks.getById(stale.getId()).getStatus()); verifyNoInteractions(fixture.workflow, fixture.archives);
    }

    @Test void followupResourceFailureRetriesAcrossDaysThreeTimesWithoutCallingTmdbOrChangingMetadata() {
        ResourceFixture fixture = new ResourceFixture(tasks); var task = fixture.task(1);
        when(fixture.workflow.resolveTmdbUpdateGyingIdentity(task.getMovieId())).thenThrow(new IllegalStateException("GYING_NO_STRICT_MATCH"));
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) jdbc.update("UPDATE resource_hub_task SET scheduled_at=? WHERE id=?", LocalDateTime.now().minusMinutes(1), task.getId());
            fixture.helper.submitOne(task.getId()); task = tasks.getById(task.getId());
            assertEquals(attempt, task.getAttempts()); assertEquals("FAILED", task.getStatus());
            assertTrue(task.getScheduledAt().isAfter(LocalDateTime.now().plusHours(23)));
        }
        jdbc.update("UPDATE resource_hub_task SET scheduled_at=? WHERE id=?", LocalDateTime.now().minusMinutes(1), task.getId());
        fixture.helper.submitOne(task.getId()); verify(fixture.workflow, times(3)).resolveTmdbUpdateGyingIdentity(task.getMovieId());
        assertEquals(1, jdbc.queryForObject("SELECT syncs FROM followup_metadata_fixture WHERE id=?", Integer.class, task.getMovieId()));
        verifyNoInteractions(fixture.archives);
    }

    @Test void followupProtectedOldArchiveAndPausedWorkerDoNotConsumeBudgetOrMutateLegacyRows() {
        ResourceFixture fixture = new ResourceFixture(tasks); var task = fixture.task(1);
        ResourceHubTask legacy = new ResourceHubTask(); legacy.setTaskType("P2P_ARCHIVE"); legacy.setSource("QUARK"); legacy.setStatus("UNKNOWN");
        legacy.setMovieId(task.getMovieId()); legacy.setPayload("{\"immutable\":\"Quark2262\"}"); tasks.enqueue(legacy);
        String before = tasks.getById(legacy.getId()).getPayload(); fixture.helper.submitOne(task.getId());
        assertEquals(0, tasks.getById(task.getId()).getAttempts()); assertEquals(before, tasks.getById(legacy.getId()).getPayload());
        assertEquals("UNKNOWN", tasks.getById(legacy.getId()).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='TMDB_GYING_RESOURCE_BUDGET'", Integer.class));
        fixture.properties.getWorker().setEnabled(false); fixture.helper.runDue();
        verifyNoInteractions(fixture.workflow, fixture.archives);
        jdbc.update("UPDATE resource_hub_task SET status='RUNNING',attempts=2 WHERE id=?", task.getId());
        fixture.helper.recoverInterruptedFollowUps();
        assertEquals("FAILED", tasks.getById(task.getId()).getStatus()); assertEquals(2, tasks.getById(task.getId()).getAttempts());
        assertEquals(before, tasks.getById(legacy.getId()).getPayload()); assertEquals("UNKNOWN", tasks.getById(legacy.getId()).getStatus());
    }

    @Test void followupSuccessfulExactIdentityQueuesOnlyExistingArchiveStageAndCompletesAtomicAck() {
        ResourceFixture fixture = new ResourceFixture(tasks); var task = fixture.task(1);
        MovieSourceIdentity identity = new MovieSourceIdentity(); identity.setId(71L); identity.setMovieId(task.getMovieId());
        identity.setSource("GYING"); identity.setSourceType("mv"); identity.setExternalId("MiD1"); identity.setSeason(0); identity.setMatchStatus("AUTO");
        when(fixture.workflow.resolveTmdbUpdateGyingIdentity(task.getMovieId())).thenReturn(identity);
        ResourceLink resource = new ResourceLink(); resource.setId(20L); resource.setMovieId(task.getMovieId());
        when(fixture.workflow.collectMovieP2pResources(task.getMovieId(), 71L)).thenReturn(
                new GyingSourceWorkflowService.P2pCollection(task.getMovieId(), 71L, "mv", "MiD1", 1, List.of(resource)));
        doAnswer(c -> {
            ResourceHubTask child = new ResourceHubTask(); child.setTaskType("P2P_ARCHIVE"); child.setSource("QUARK"); child.setMovieId(task.getMovieId());
            String fingerprint = P2pArchiveTaskService.fingerprint(List.of(resource)); child.setKeyword(fingerprint);
            child.setPayload(mapper.createObjectNode().put("fingerprint", fingerprint).put("typeCode", "mv").put("mid", "MiD1")
                    .set("resourceIds", mapper.valueToTree(List.of(20L))).toString()); tasks.enqueue(child); return null;
        }).when(fixture.archives).enqueue(task.getMovieId(), "mv", "MiD1", List.of(resource));
        fixture.helper.submitOne(task.getId());
        assertEquals("SUCCEEDED", tasks.getById(task.getId()).getStatus());
        verify(fixture.archives).enqueue(task.getMovieId(), "mv", "MiD1", List.of(resource));
        assertEquals(1, jdbc.queryForObject("SELECT syncs FROM followup_metadata_fixture WHERE id=?", Integer.class, task.getMovieId()));
    }

    @Test void followupAdminDelayCapOrIdentityChangeRejectsStaleClaimAndRollsBackItsBudget() {
        ResourceFixture fixture = new ResourceFixture(tasks);
        for (int n = 1; n <= 3; n++) {
            var stale = fixture.task(n);
            if (n == 1) jdbc.update("UPDATE resource_hub_task SET scheduled_at=? WHERE id=?", LocalDateTime.now().plusDays(2), stale.getId());
            if (n == 2) jdbc.update("UPDATE resource_hub_task SET max_attempts=0 WHERE id=?", stale.getId());
            if (n == 3) jdbc.update("UPDATE resource_hub_task SET movie_id='changed-canonical' WHERE id=?", stale.getId());
            assertThrows(TmdbGyingResourceFollowUpService.CheckpointConflict.class, () -> fixture.claim(stale));
            assertEquals(0, tasks.getById(stale.getId()).getAttempts());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='TMDB_GYING_RESOURCE_BUDGET'", Integer.class));
        }
        verifyNoInteractions(fixture.workflow, fixture.archives);
    }

    @Test void followupBudgetRejectsOverflowAndChangedLedgerStatusBeforeAnyTaskClaim() {
        ResourceFixture fixture = new ResourceFixture(tasks); var first = fixture.task(1); var second = fixture.task(2);
        assertTrue(fixture.claim(first));
        ResourceHubTask budget = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TmdbGyingResourceFollowUpService.BUDGET_TYPE), false);
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.used',4294967297) WHERE id=?", budget.getId());
        assertThrows(IllegalStateException.class, () -> fixture.claim(second));
        assertEquals(0, tasks.getById(second.getId()).getAttempts());
        jdbc.update("UPDATE resource_hub_task SET payload=JSON_SET(payload,'$.used',1),status='PENDING' WHERE id=?", budget.getId());
        assertThrows(IllegalStateException.class, () -> fixture.claim(second));
        assertEquals(0, tasks.getById(second.getId()).getAttempts()); verifyNoInteractions(fixture.workflow, fixture.archives);
    }

    @Test void followupMissingYearPersistsMetadataAndIntentButLeavesResourceStageForReviewWithoutBudget() {
        ResourceFixture fixture = new ResourceFixture(tasks); var movie = fixture.movie(1); movie.setYear(null); fixture.persist(movie);
        ResourceHubTask task = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TmdbGyingResourceFollowUpService.TYPE), false);
        jdbc.update("UPDATE resource_hub_task SET scheduled_at=? WHERE id=?", LocalDateTime.now().minusMinutes(1), task.getId());
        fixture.helper.submitOne(task.getId());
        assertEquals(1, jdbc.queryForObject("SELECT syncs FROM followup_metadata_fixture WHERE id=?", Integer.class, movie.getId()));
        assertEquals(0, tasks.getById(task.getId()).getAttempts());
        assertEquals("TMDB_RESOURCE_LOCAL_METADATA_REVIEW_REQUIRED", tasks.getById(task.getId()).getLastError());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='TMDB_GYING_RESOURCE_BUDGET'", Integer.class));
        verifyNoInteractions(fixture.workflow, fixture.archives);
    }

    @Test void followupMissingArchiveAckCannotCompleteResourceTaskOrLoseIndependentRetry() {
        ResourceFixture fixture = new ResourceFixture(tasks); var task = fixture.task(1);
        MovieSourceIdentity identity = new MovieSourceIdentity(); identity.setId(71L); identity.setMovieId(task.getMovieId());
        identity.setSource("GYING"); identity.setSourceType("mv"); identity.setExternalId("MiD1"); identity.setSeason(0); identity.setMatchStatus("AUTO");
        when(fixture.workflow.resolveTmdbUpdateGyingIdentity(task.getMovieId())).thenReturn(identity);
        ResourceLink resource = new ResourceLink(); resource.setId(20L); resource.setMovieId(task.getMovieId());
        when(fixture.workflow.collectMovieP2pResources(task.getMovieId(), 71L)).thenReturn(
                new GyingSourceWorkflowService.P2pCollection(task.getMovieId(), 71L, "mv", "MiD1", 1, List.of(resource)));
        // A void enqueue returning without a durable child must never acknowledge completion.
        fixture.helper.submitOne(task.getId());
        assertEquals("FAILED", tasks.getById(task.getId()).getStatus()); assertEquals(1, tasks.getById(task.getId()).getAttempts());
        assertEquals("GYING_RESOURCE_ARCHIVE_ACK_MISSING", tasks.getById(task.getId()).getLastError());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_hub_task WHERE task_type='P2P_ARCHIVE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT syncs FROM followup_metadata_fixture WHERE id=?", Integer.class, task.getMovieId()));
    }

    @Test void followupWorkerPauseDuringSearchStopsCollectionAndNextJobWithoutNewBudget() throws Exception {
        ResourceFixture fixture = new ResourceFixture(tasks); var first = fixture.task(1); var second = fixture.task(2);
        when(fixture.workflow.resolveTmdbUpdateGyingIdentity(first.getMovieId())).thenAnswer(c -> {
            fixture.properties.getWorker().setEnabled(false); throw new IllegalStateException("GYING_RESOURCE_COLLECTION_PAUSED");
        });
        fixture.helper.runDue();
        verify(fixture.workflow).resolveTmdbUpdateGyingIdentity(first.getMovieId());
        verify(fixture.workflow, never()).resolveTmdbUpdateGyingIdentity(second.getMovieId());
        verify(fixture.workflow, never()).collectMovieP2pResources(anyString(), anyLong()); verifyNoInteractions(fixture.archives);
        assertEquals(0, tasks.getById(second.getId()).getAttempts());
        var budget = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TmdbGyingResourceFollowUpService.BUDGET_TYPE), false);
        assertEquals(1, mapper.readTree(budget.getPayload()).path("used").asInt());
    }

    @Test void followupCanonicalTitleNormalizationRunsAgainstActualMysql8RegexpEngine() {
        String sql = "SELECT LOWER(REGEXP_REPLACE(?, '[[:space:][:punct:]，。！？、：；（）《》【】「」『』·]+', ''))";
        assertEquals("strictfilm", jdbc.queryForObject(sql, String.class, " Strict-Film "));
        assertEquals("strictfilm", jdbc.queryForObject(sql, String.class, "Strict Film"));
        assertEquals("中文标题", jdbc.queryForObject(sql, String.class, "《中文 标题》"));
    }

}
