package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.gying.movie.entity.BaiduTemporaryCleanupJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.mapper.BaiduTemporaryCleanupJobMapper;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

/** Only the explicitly marked fresh network-none/tmpfs database may execute these DDL/CAS tests. */
@EnabledIfEnvironmentVariable(named="QUERY_BOUNDARY_MYSQL_FIXTURE",matches="network-none-tmpfs")
class BaiduTemporaryCleanupMySqlTest {
    private static JdbcTemplate jdbc;
    private static BaiduTemporaryCleanupJobMapper mapper;
    private static final AtomicLong ids=new AtomicLong(1000);
    @BeforeAll static void initializeFreshFixture() throws Exception {
        String runId=System.getenv("QUERY_BOUNDARY_FIXTURE_ID");assertNotNull(runId);assertTrue(runId.matches("[0-9a-f]{12}"));
        String database="baidu_cleanup_fixture_"+runId;String url="jdbc:mysql://127.0.0.1:3306/"+database;
        assertEquals(url,System.getenv("BAIDU_CLEANUP_TEST_MYSQL_URL"));
        var source=new DriverManagerDataSource(url,"root","");var properties=new Properties();
        properties.setProperty("connectTimeout","3000");properties.setProperty("socketTimeout","15000");properties.setProperty("connectionTimeZone","Asia/Shanghai");properties.setProperty("useSSL","false");source.setConnectionProperties(properties);
        jdbc=new JdbcTemplate(source);
        assertEquals("gying-query-fixture",jdbc.queryForObject("SELECT @@hostname",String.class));assertEquals(database,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(List.of("fixture_marker"),jdbc.queryForList("SHOW TABLES",String.class));assertEquals(runId,jdbc.queryForObject("SELECT run_id FROM fixture_marker",String.class));assertTrue(jdbc.queryForObject("SELECT VERSION()",String.class).startsWith("8.0."));
        jdbc.execute("CREATE TABLE sys_config(config_key VARCHAR(200) PRIMARY KEY,config_value TEXT,description TEXT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        try(Connection connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration_baidu_temporary_cleanup.sql"));}
        jdbc.execute("""
            CREATE TABLE baidu_transfer_task(id BIGINT PRIMARY KEY,discovery_result_id BIGINT,scope_key VARCHAR(64),phase VARCHAR(32),
              share_confirmed_at DATETIME(6),cleanup_evidence LONGTEXT,share_url_hash CHAR(64),original_url_hash CHAR(64))
            ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        jdbc.execute("""
            CREATE TABLE resource_link(id BIGINT PRIMARY KEY,movie_id VARCHAR(64),provider VARCHAR(20),source VARCHAR(32),source_ref VARCHAR(100),
              url_hash CHAR(64),source_url VARCHAR(1000),status VARCHAR(20),link_status VARCHAR(20),deleted_at DATETIME(6),validated_at DATETIME(6),
              last_check_error VARCHAR(1000),updated_at DATETIME(6)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        jdbc.execute("""
            CREATE TABLE resource_discovery_result(id BIGINT PRIMARY KEY,movie_id VARCHAR(64),provider VARCHAR(20),source VARCHAR(32),source_ref VARCHAR(100),
              share_url_hash CHAR(64),original_url_hash CHAR(64),original_url VARCHAR(1000),resource_link_id BIGINT,status VARCHAR(32),failure_reason VARCHAR(1000),updated_at DATETIME(6))
            ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        var configuration=new MybatisConfiguration();GlobalConfigUtils.getGlobalConfig(configuration).setIdentifierGenerator(new DefaultIdentifierGenerator(0L,0L));
        configuration.setMapUnderscoreToCamelCase(true);configuration.setEnvironment(new Environment("baidu-cleanup-fixture",new SpringManagedTransactionFactory(),source));configuration.addMapper(BaiduTemporaryCleanupJobMapper.class);
        var factory=new MybatisSqlSessionFactoryBuilder().build(configuration);mapper=new SqlSessionTemplate(factory).getMapper(BaiduTemporaryCleanupJobMapper.class);
    }
    @Test void taskUniqueKeyPreservesExistingTerminalStateEvidenceAndOriginalDeadline() {
        var first=job();first.setStatus("SUCCEEDED");first.setFinishedAt(now());assertEquals(1,mapper.insert(first));
        var second=job();second.setTransferTaskId(first.getTransferTaskId());second.setDeleteAfter(now().plusHours(1));
        assertThrows(DataIntegrityViolationException.class,()->mapper.insert(second));
        var restored=mapper.findByTaskId(first.getTransferTaskId());assertEquals("SUCCEEDED",restored.getStatus());assertEquals(first.getDeleteAfter(),restored.getDeleteAfter());assertEquals(first.getEvidenceJson(),restored.getEvidenceJson());assertNotNull(restored.getFinishedAt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM baidu_temporary_cleanup_job WHERE transfer_task_id=?",Long.class,first.getTransferTaskId()));
    }
    @Test void mapperPersistsUnicodeMicrosecondTimestampAndIndependentRecycleEvidence() {
        var job=job();job.setTargetPath("/GYing QQ Temp/baidu-"+job.getTransferTaskId());job.setEvidenceJson(encoded(Map.of("文件","电影😀/视频.mkv","fsId","31","md5","opaque-web-value")));job.setRecycleEvidence(encoded(Map.of("accountUid","100","directoryId","20")));assertEquals(1,mapper.insert(job));
        var restored=mapper.selectById(job.getId());assertEquals(job.getEvidenceJson(),restored.getEvidenceJson());assertEquals(job.getRecycleEvidence(),restored.getRecycleEvidence());assertEquals(job.getShareConfirmedAt(),restored.getShareConfirmedAt());assertEquals(job.getDeleteAfter(),restored.getDeleteAfter());
        assertEquals("百度搜索临时目录精确彻底清理任务",jdbc.queryForObject("SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='baidu_temporary_cleanup_job'",String.class));
        assertEquals("utf8mb4_unicode_ci",jdbc.queryForObject("SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='baidu_temporary_cleanup_job'",String.class));
    }
    @Test void independentContendersCanClaimOnlyOneUnexpiredDatabaseLease() throws Exception {
        var job=job();assertEquals(1,mapper.insert(job));var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return mapper.claim(job.getId(),"PENDING","first",now(),now().plusMinutes(10));});
            var second=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return mapper.claim(job.getId(),"PENDING","second",now(),now().plusMinutes(10));});
            start.countDown();assertEquals(1,first.get(15,TimeUnit.SECONDS)+second.get(15,TimeUnit.SECONDS));
            var claimed=mapper.selectById(job.getId());assertEquals(1,claimed.getAttempts());assertTrue(List.of("first","second").contains(claimed.getLeaseOwner()));
            assertEquals(0,mapper.transition(job.getId(),"another","PENDING","TRASH_SUBMITTING",null,null,now(),now().plusMinutes(10)));assertEquals("PENDING",mapper.selectById(job.getId()).getStatus());
        } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
    @Test void deadlineStatusAndExpiredLeaseAreAllComparedBeforeClaimAndProgress() {
        var job=job();job.setDeleteAfter(now().plusMinutes(10));assertEquals(1,mapper.insert(job));assertEquals(0,mapper.claim(job.getId(),"PENDING","owner",now(),now().plusMinutes(10)));
        jdbc.update("UPDATE baidu_temporary_cleanup_job SET delete_after=?,lease_owner='stale',lease_until=? WHERE id=?",now().minusMinutes(1),now().minusMinutes(1),job.getId());
        assertEquals(0,mapper.claim(job.getId(),"TRASHED","owner",now(),now().plusMinutes(10)));assertEquals(1,mapper.claim(job.getId(),"PENDING","owner",now(),now().plusMinutes(10)));
        jdbc.update("UPDATE baidu_temporary_cleanup_job SET lease_until=? WHERE id=?",now().minusSeconds(1),job.getId());
        assertEquals(0,mapper.transition(job.getId(),"owner","PENDING","TRASH_SUBMITTING",null,null,now(),now().plusMinutes(10)));assertEquals("PENDING",mapper.selectById(job.getId()).getStatus());
    }
    @Test void durableTrashAndPurgeIntentsSurviveReleaseReclaimAndRejectStaleProgress() {
        var job=job();assertEquals(1,mapper.insert(job));assertEquals(1,mapper.claim(job.getId(),"PENDING","first",now(),now().plusMinutes(10)));
        assertEquals(1,mapper.transition(job.getId(),"first","PENDING","TRASH_SUBMITTING",null,null,now(),now().plusMinutes(10)));assertEquals(1,mapper.release(job.getId(),"first"));
        assertEquals("TRASH_SUBMITTING",mapper.selectById(job.getId()).getStatus());assertEquals(1,mapper.claim(job.getId(),"TRASH_SUBMITTING","restart",now(),now().plusMinutes(10)));
        assertEquals(0,mapper.transition(job.getId(),"first","TRASH_SUBMITTING","PENDING",null,null,now(),now().plusMinutes(10)));
        String recycle=encoded(Map.of("accountUid","100","destination",job.getTargetPath(),"directoryId","20"));
        assertEquals(1,mapper.transition(job.getId(),"restart","TRASH_SUBMITTING","TRASH_UNKNOWN","NETWORK_UNAVAILABLE",null,now(),now().plusMinutes(10)));
        assertEquals(1,mapper.transition(job.getId(),"restart","TRASH_UNKNOWN","TRASHED",null,recycle,now(),now().plusMinutes(10)));
        assertEquals(1,mapper.transition(job.getId(),"restart","TRASHED","PURGE_SUBMITTING",null,null,now(),now().plusMinutes(10)));
        assertEquals(recycle,mapper.selectById(job.getId()).getRecycleEvidence());assertEquals(1,mapper.release(job.getId(),"restart"));
        assertEquals(1,mapper.claim(job.getId(),"PURGE_SUBMITTING","third",now(),now().plusMinutes(10)));
        assertEquals(1,mapper.transition(job.getId(),"third","PURGE_SUBMITTING","PURGE_UNKNOWN","NETWORK_UNAVAILABLE",null,now(),now().plusMinutes(10)));
        assertEquals(1,mapper.transition(job.getId(),"third","PURGE_UNKNOWN","REMOTE_DELETED",null,null,now(),now().plusMinutes(10)));
        assertEquals(1,mapper.transition(job.getId(),"third","REMOTE_DELETED","SUCCEEDED",null,null,now(),now().plusMinutes(10)));
        var restored=mapper.selectById(job.getId());assertNotNull(restored.getFinishedAt());assertNull(restored.getLastError());assertEquals(job.getDeleteAfter(),restored.getDeleteAfter());assertEquals(recycle,restored.getRecycleEvidence());
    }
    @Test void localInvalidationCasTouchesOnlyExactTemporaryAssociationAndPreservesReboundPermanentLink() {
        var matched=job();matched.setResourceLinkId(ids.incrementAndGet());assertEquals(1,mapper.insert(matched));insertAssociation(matched);
        assertEquals(1,mapper.invalidateLink(matched,now()));assertEquals(1,mapper.invalidateDiscovery(matched,now()));
        assertEquals("INVALID",jdbc.queryForObject("SELECT link_status FROM resource_link WHERE id=?",String.class,matched.getResourceLinkId()));assertEquals("IGNORED",jdbc.queryForObject("SELECT status FROM resource_discovery_result WHERE id=?",String.class,matched.getDiscoveryResultId()));
        var rebound=job();rebound.setResourceLinkId(ids.incrementAndGet());assertEquals(1,mapper.insert(rebound));insertAssociation(rebound);
        jdbc.update("UPDATE resource_link SET source='LIBRARY',source_ref='PERMANENT',url_hash=? WHERE id=?","c".repeat(64),rebound.getResourceLinkId());
        assertEquals(0,mapper.invalidateLink(rebound,now()));assertEquals(0,mapper.invalidateDiscovery(rebound,now()));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM resource_link WHERE id=?",String.class,rebound.getResourceLinkId()));assertEquals("SAVED",jdbc.queryForObject("SELECT status FROM resource_discovery_result WHERE id=?",String.class,rebound.getDiscoveryResultId()));
        var scopeChanged=job();scopeChanged.setResourceLinkId(ids.incrementAndGet());assertEquals(1,mapper.insert(scopeChanged));insertAssociation(scopeChanged);jdbc.update("UPDATE baidu_transfer_task SET scope_key='PERMANENT' WHERE id=?",scopeChanged.getTransferTaskId());
        assertEquals(0,mapper.invalidateLink(scopeChanged,now()));assertEquals(0,mapper.invalidateDiscovery(scopeChanged,now()));
    }
    @Test void lateTerminalBindingRecordsOnlyCorrectLinkWithoutChangingStatusOrRetentionClock() {
        var job=job();job.setStatus("SUCCEEDED");job.setFinishedAt(now());assertEquals(1,mapper.insert(job));Long link=ids.incrementAndGet();
        assertEquals(1,mapper.bindResource(job.getTransferTaskId(),job.getDiscoveryResultId(),link,job.getShareUrlHash(),now()));
        assertEquals(0,mapper.bindResource(job.getTransferTaskId(),job.getDiscoveryResultId(),link+1,job.getShareUrlHash(),now()));
        assertEquals(0,mapper.bindResource(job.getTransferTaskId(),job.getDiscoveryResultId(),link,"d".repeat(64),now()));
        var restored=mapper.selectById(job.getId());assertEquals(link,restored.getResourceLinkId());assertEquals("SUCCEEDED",restored.getStatus());assertEquals(job.getDeleteAfter(),restored.getDeleteAfter());assertEquals(job.getShareConfirmedAt(),restored.getShareConfirmedAt());assertEquals(job.getFinishedAt(),restored.getFinishedAt());
    }
    @Test void changedCloneOriginalIdentityAndLinkedMovieRemainActiveAfterPhysicalDeletion() {
        var cloneChanged=job();cloneChanged.setResourceLinkId(ids.incrementAndGet());assertEquals(1,mapper.insert(cloneChanged));insertAssociation(cloneChanged);
        jdbc.update("UPDATE resource_discovery_result SET original_url_hash=?,original_url=? WHERE id=?","c".repeat(64),"https://pan.baidu.com/s/1replacement",cloneChanged.getDiscoveryResultId());
        assertEquals(0,mapper.invalidateLink(cloneChanged,now()));assertEquals(0,mapper.invalidateDiscovery(cloneChanged,now()));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM resource_link WHERE id=?",String.class,cloneChanged.getResourceLinkId()));
        assertEquals("SAVED",jdbc.queryForObject("SELECT status FROM resource_discovery_result WHERE id=?",String.class,cloneChanged.getDiscoveryResultId()));
        var movieChanged=job();movieChanged.setResourceLinkId(ids.incrementAndGet());assertEquals(1,mapper.insert(movieChanged));insertAssociation(movieChanged);
        jdbc.update("UPDATE resource_link SET movie_id='mv-replacement' WHERE id=?",movieChanged.getResourceLinkId());
        assertEquals(0,mapper.invalidateLink(movieChanged,now()));assertEquals(0,mapper.invalidateDiscovery(movieChanged,now()));
        assertEquals("SAVED",jdbc.queryForObject("SELECT status FROM resource_discovery_result WHERE id=?",String.class,movieChanged.getDiscoveryResultId()));
    }
    @Test void compensationQueryExcludesPermanentUnconfirmedAndAlreadyScheduledHistoricalTasks() {
        long confirmed=ids.incrementAndGet(),permanent=ids.incrementAndGet(),unknown=ids.incrementAndGet();
        insertTask(confirmed,"QQ_BOT:"+UUID.randomUUID(),"SHARED",now(),"{}");insertTask(permanent,"PERMANENT","SHARED",now(),"{}");insertTask(unknown,"QQ_BOT:"+UUID.randomUUID(),"SHARE_SUBMITTING",null,null);
        var result=mapper.findConfirmedMissingCleanup().stream().map(t->t.getId()).toList();assertTrue(result.contains(confirmed));assertFalse(result.contains(permanent));assertFalse(result.contains(unknown));
        var job=job();job.setTransferTaskId(confirmed);assertEquals(1,mapper.insert(job));assertFalse(mapper.findConfirmedMissingCleanup().stream().anyMatch(t->t.getId().equals(confirmed)));
    }
    private static BaiduTemporaryCleanupJob job() {
        var job=new BaiduTemporaryCleanupJob();job.setTransferTaskId(ids.incrementAndGet());job.setScopeKey("QQ_BOT:"+UUID.randomUUID());job.setMovieId("mv-"+job.getTransferTaskId());job.setDiscoveryResultId(ids.incrementAndGet());
        job.setOriginalUrl("https://pan.baidu.com/s/1source");job.setOriginalUrlHash("a".repeat(64));job.setShareUrlHash("b".repeat(64));job.setAccountUid("100");job.setTargetPath("/GYing QQ Temp/baidu-"+job.getTransferTaskId());job.setDirectoryId("20");job.setPlanJson(encoded(Map.of("destination",job.getTargetPath())));job.setEvidenceJson(encoded(Map.of("directoryId","20")));job.setDeleteMode("PERMANENT");job.setStatus("PENDING");job.setAttempts(0);job.setShareConfirmedAt(now().minusMinutes(11));job.setDeleteAfter(job.getShareConfirmedAt().plusMinutes(10));job.setCreatedAt(now());job.setUpdatedAt(now());return job;
    }
    private static String encoded(Object value){try{return new ObjectMapper().writeValueAsString(value);}catch(Exception error){throw new AssertionError(error);}}
    private static LocalDateTime now(){return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);}
    private static void insertTask(long id,String scope,String phase,LocalDateTime confirmed,String evidence){jdbc.update("INSERT INTO baidu_transfer_task(id,scope_key,phase,share_confirmed_at,cleanup_evidence) VALUES(?,?,?,?,?)",id,scope,phase,confirmed,evidence);}
    private static void insertAssociation(BaiduTemporaryCleanupJob job) {
        jdbc.update("INSERT INTO baidu_transfer_task(id,discovery_result_id,scope_key,phase,share_confirmed_at,cleanup_evidence,share_url_hash,original_url_hash) VALUES(?,?,?,?,?,?,?,?)",job.getTransferTaskId(),job.getDiscoveryResultId(),job.getScopeKey(),"SHARED",job.getShareConfirmedAt(),job.getEvidenceJson(),job.getShareUrlHash(),job.getOriginalUrlHash());
        jdbc.update("INSERT INTO resource_link(id,movie_id,provider,source,source_ref,url_hash,source_url,status,link_status) VALUES(?,?,?,?,?,?,?,?,?)",job.getResourceLinkId(),job.getMovieId(),"BAIDU","BAIDU_TEMP",job.getScopeKey(),job.getShareUrlHash(),job.getOriginalUrl(),"ACTIVE","NORMAL");
        jdbc.update("INSERT INTO resource_discovery_result(id,movie_id,provider,source,source_ref,share_url_hash,original_url_hash,original_url,resource_link_id,status) VALUES(?,?,?,?,?,?,?,?,?,?)",job.getDiscoveryResultId(),job.getMovieId(),"BAIDU","QQ_BOT","BAIDU_TEMP:"+job.getScopeKey(),job.getShareUrlHash(),job.getOriginalUrlHash(),job.getOriginalUrl(),job.getResourceLinkId(),"SAVED");
    }
}
