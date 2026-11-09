package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.gying.movie.mapper.MovieMetadataMapper;
import com.gying.movie.mapper.ResourceLinkMapper;
import com.gying.movie.utils.BaiduTemporaryScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual scan SQL only on an independently created database in the marked disposable fixture. */
@EnabledIfEnvironmentVariable(named = "QUERY_BOUNDARY_MYSQL_FIXTURE", matches = "network-none-tmpfs")
class MovieDuplicateScannerMySqlTest {
    static JdbcTemplate jdbc;
    static TransactionTemplate readOnly;
    static MovieDuplicateScannerService scanner;
    @BeforeAll static void initialize() {
        String runId = System.getenv("QUERY_BOUNDARY_FIXTURE_ID");
        assertNotNull(runId); assertTrue(runId.matches("[0-9a-f]{12}"));
        String baseUrl = "jdbc:mysql://127.0.0.1:3306/baidu_transfer_fixture_" + runId;
        assertEquals(baseUrl, System.getenv("BAIDU_TEST_MYSQL_URL"));
        var base = new JdbcTemplate(dataSource(baseUrl));
        assertEquals("gying-query-fixture", base.queryForObject("SELECT @@hostname", String.class));
        assertEquals(runId, base.queryForObject("SELECT run_id FROM fixture_marker", String.class));
        assertTrue(base.queryForObject("SELECT VERSION()", String.class).startsWith("8.0."));
        String database = "duplicate_scan_fixture_" + runId;
        assertEquals(0, base.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?", Integer.class, database));
        base.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        var source = dataSource("jdbc:mysql://127.0.0.1:3306/" + database); jdbc = new JdbcTemplate(source);
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForList("SHOW TABLES", String.class).isEmpty());
        jdbc.execute("""
            CREATE TABLE movie_metadata (
              id VARCHAR(64) PRIMARY KEY, tmdb_type VARCHAR(20), title_cn VARCHAR(200), title_en VARCHAR(200),
              series_name VARCHAR(200), season INT, year INT, actors JSON, directors JSON, aliases TEXT,
              category VARCHAR(20), status VARCHAR(20), deleted_at DATETIME
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='隔离重复影片扫描夹具'
            """);
        jdbc.execute("""
            CREATE TABLE resource_link (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, movie_id VARCHAR(64), status VARCHAR(20), deleted_at DATETIME,
              link_status VARCHAR(20), source VARCHAR(50), KEY movie_resource (movie_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='隔离重复影片资源计数夹具'
            """);
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < 501; index++) rows.add(new Object[]{String.format("filler_%04d", index), "Unique title " + index});
        jdbc.batchUpdate("INSERT INTO movie_metadata(id,title_en,category,year,season,status) VALUES(?,?,'tv',2025,1,'ACTIVE')", rows);
        jdbc.update("INSERT INTO movie_metadata(id,title_cn,title_en,category,year,season,status,actors,directors) VALUES('1YKW','黑帮领地 第一季',' MobLand Season 1','tv',2025,1,'ACTIVE','[\"Tom Hardy\"]','[\"盖·里奇\"]')");
        jdbc.update("INSERT INTO movie_metadata(id,title_en,category,year,season,status,actors) VALUES('tmdb_tv_247718','MobLand','tv',2025,1,'ACTIVE','[\"Tom Hardy\"]')");
        jdbc.update("INSERT INTO movie_metadata(id,title_en,category,year,season,status) VALUES('season_two','MobLand Season 2','tv',2025,2,'ACTIVE')");
        jdbc.update("INSERT INTO movie_metadata(id,title_en,category,year,season,status) VALUES('missing_season','MobLand','tv',2025,NULL,'ACTIVE')");
        jdbc.update("INSERT INTO movie_metadata(id,title_en,category,year,status,aliases) VALUES('version_a','Other film Director''s Cut','mv',2025,'ACTIVE','Other film'),('version_b','Other film','mv',2025,'ACTIVE',NULL)");
        jdbc.update("INSERT INTO movie_metadata(id,title_en,category,year,season,status,deleted_at) VALUES('deleted_movie','MobLand','tv',2025,1,'ACTIVE',NOW())");
        for (int index = 0; index < 8; index++) jdbc.update("INSERT INTO resource_link(movie_id,status,link_status,source) VALUES('1YKW','ACTIVE','NORMAL','RESOURCE_HUB')");
        jdbc.update("INSERT INTO resource_link(movie_id,status,link_status,source) VALUES('1YKW','ACTIVE','INVALID','RESOURCE_HUB'),('1YKW','DELETED','NORMAL','RESOURCE_HUB')");
        jdbc.update("INSERT INTO resource_link(movie_id,status,link_status,source) VALUES('1YKW','ACTIVE','NORMAL',?)", BaiduTemporaryScope.RESOURCE_SOURCE);
        jdbc.update("INSERT INTO resource_link(movie_id,status,link_status,source,deleted_at) VALUES('1YKW','ACTIVE','NORMAL','RESOURCE_HUB',NOW())");
        var configuration = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(configuration).setIdentifierGenerator(new DefaultIdentifierGenerator(0L, 0L));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("duplicate-fixture", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(MovieMetadataMapper.class); configuration.addMapper(ResourceLinkMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var movies = new MovieMetadataServiceImpl(); ReflectionTestUtils.setField(movies, "baseMapper", session.getMapper(MovieMetadataMapper.class));
        var resources = new ResourceLinkServiceImpl(); ReflectionTestUtils.setField(resources, "baseMapper", session.getMapper(ResourceLinkMapper.class));
        scanner = new MovieDuplicateScannerService(movies, resources);
        readOnly = new TransactionTemplate(new DataSourceTransactionManager(source));
        readOnly.setReadOnly(true); readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private static DriverManagerDataSource dataSource(String url) {
        var source = new DriverManagerDataSource(url, "root", ""); var properties = new Properties();
        properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "15000");
        properties.setProperty("connectionTimeZone", "Asia/Shanghai"); properties.setProperty("useSSL", "false");
        source.setConnectionProperties(properties); return source;
    }
    @Test void actualKeysetAndJsonProjectionFindAnOldMoblandAndNormalResourcesAcrossBatches() {
        var result = readOnly.execute(status -> scanner.scan(1, 100)); assertNotNull(result);
        assertEquals(507, result.get("scannedMovies")); assertEquals(1, result.get("candidates"));
        assertEquals(true, result.get("complete")); assertEquals(1, result.get("conflicts")); assertEquals(1, result.get("excludedMovies"));
        var item = (Map<?, ?>)((List<?>)result.get("items")).get(0);
        assertEquals("1YKW", item.get("toMovieId")); assertEquals("tmdb_tv_247718", item.get("fromMovieId"));
        assertEquals(8L, item.get("toResources")); assertEquals(0L, item.get("fromResources"));
        assertEquals("NAME_AND_PEOPLE", item.get("evidence"));
    }
    @Test void paginatingReviewCandidatesCannotChangeAnyMovieOrResourceRow() {
        var beforeMovies = jdbc.queryForList("SELECT * FROM movie_metadata ORDER BY id");
        var beforeResources = jdbc.queryForList("SELECT * FROM resource_link ORDER BY id");
        var result = readOnly.execute(status -> scanner.scan(2, 1)); assertNotNull(result);
        assertEquals(507, result.get("scannedMovies")); assertEquals(1, result.get("candidates"));
        assertEquals(1, result.get("page")); assertEquals(false, result.get("mergeEnabled")); assertEquals(0, result.get("merged"));
        assertEquals(beforeMovies, jdbc.queryForList("SELECT * FROM movie_metadata ORDER BY id"));
        assertEquals(beforeResources, jdbc.queryForList("SELECT * FROM resource_link ORDER BY id"));
    }
}
