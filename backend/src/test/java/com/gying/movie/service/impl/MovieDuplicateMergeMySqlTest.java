package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL schema, triggers and transactions, only in a newly created marked disposable database. */
@EnabledIfEnvironmentVariable(named="QUERY_BOUNDARY_MYSQL_FIXTURE",matches="network-none-tmpfs")
class MovieDuplicateMergeMySqlTest {
    static JdbcTemplate jdbc; static DriverManagerDataSource source;
    static MovieDuplicateMergeService service,limitedService;
    static JdbcTemplate limited; static String applicationUser,database;
    static final ObjectMapper json=new ObjectMapper();
    static final List<String> DIRECT=List.of("resource_link","resource_discovery_result","quark_transfer_task","xunlei_transfer_task",
        "baidu_transfer_task","resource_hub_task","movie_source_identity","qq_bot_search_log","qq_channel_post_log","social_post_log","resource_operation_log");
    @BeforeAll static void initialize() throws Exception {
        String runId=System.getenv("QUERY_BOUNDARY_FIXTURE_ID"); assertNotNull(runId); assertTrue(runId.matches("[0-9a-f]{12}"));
        String baseUrl="jdbc:mysql://127.0.0.1:3306/baidu_transfer_fixture_"+runId;
        assertEquals(baseUrl,System.getenv("BAIDU_TEST_MYSQL_URL")); var base=new JdbcTemplate(dataSource(baseUrl));
        assertEquals("gying-query-fixture",base.queryForObject("SELECT @@hostname",String.class));
        assertEquals(runId,base.queryForObject("SELECT run_id FROM fixture_marker",String.class));
        assertTrue(base.queryForObject("SELECT VERSION()",String.class).startsWith("8.0."));
        database="duplicate_merge_fixture_"+runId;
        assertEquals(0,base.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?",Integer.class,database));
        base.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        source=dataSource("jdbc:mysql://127.0.0.1:3306/"+database); jdbc=new JdbcTemplate(source);
        assertEquals(database,jdbc.queryForObject("SELECT DATABASE()",String.class)); assertTrue(jdbc.queryForList("SHOW TABLES").isEmpty());
        String schema=new ClassPathResource("db/schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var tables=new ArrayList<>(DIRECT); tables.addAll(List.of("movie_metadata","comment","user_notification","user_favorite","qq_transfer_cleanup_job","baidu_temporary_cleanup_job"));
        String qq=new ClassPathResource("db/migration_qq_automation.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for(String table:tables) {
            String content=table.startsWith("qq_bot_")||table.equals("qq_channel_post_log")?qq:schema;
            var block=Pattern.compile("CREATE TABLE(?: IF NOT EXISTS)? `?"+table+"`? \\([\\s\\S]*?;").matcher(content);
            assertTrue(block.find(),table); jdbc.execute(block.group());
        }
        jdbc.execute("ALTER TABLE movie_metadata ADD COLUMN deleted_at DATETIME NULL");
        jdbc.execute("ALTER TABLE resource_link ADD COLUMN deleted_at DATETIME NULL, ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        try(Connection connection=source.getConnection()) { ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration_movie_duplicate_merge.sql")); }
        for(var definition:MovieDuplicateMergeService.triggerDefinitions()) jdbc.execute(definition.statement());
        assertEquals(34,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()",Integer.class));
        service=new MovieDuplicateMergeService(jdbc,json,new DataSourceTransactionManager(source));
        jdbc.execute(MovieDuplicateMergeService.guardMetadataDefinition().statement());
        applicationUser="merge_app_"+runId;
        jdbc.execute("CREATE USER '"+applicationUser+"'@'%' IDENTIFIED BY ''");
        jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+database+".* TO '"+applicationUser+"'@'%'");
        jdbc.execute("GRANT EXECUTE ON PROCEDURE "+database+".movie_merge_guard_metadata TO '"+applicationUser+"'@'%'");
        jdbc.execute("GRANT SHOW_ROUTINE ON *.* TO '"+applicationUser+"'@'%'");
        var applicationSource=dataSource(source.getUrl(),applicationUser); limited=new JdbcTemplate(applicationSource);
        limitedService=new MovieDuplicateMergeService(limited,json,new DataSourceTransactionManager(applicationSource));
    }
    static DriverManagerDataSource dataSource(String url) { return dataSource(url,"root"); }
    static DriverManagerDataSource dataSource(String url,String user) {
        var data=new DriverManagerDataSource(url,user,""); var properties=new Properties();
        properties.setProperty("connectTimeout","3000"); properties.setProperty("socketTimeout","25000"); properties.setProperty("connectionTimeZone","Asia/Shanghai"); properties.setProperty("useSSL","false"); data.setConnectionProperties(properties); return data;
    }
    static MovieDuplicateMergeService.Pair pair() {
        String suffix=UUID.randomUUID().toString().replace("-",""); String from="Old_"+suffix,to="Keep_"+suffix;
        jdbc.update("INSERT INTO movie_metadata(id,title_en,category,tmdb_type,year,status,actors) VALUES(?,?,'mv','movie',2025,'ACTIVE','[\"Same Actor\"]'),(?,?,'mv','movie',2025,'ACTIVE','[\"Same Actor\"]')",from,"Reviewed "+suffix,to,"Reviewed "+suffix);
        return new MovieDuplicateMergeService.Pair(from,to);
    }
    static Map<String,Object> preview(MovieDuplicateMergeService.Pair pair) { return service.preview(new MovieDuplicateMergeService.PreviewRequest(List.of(pair)),7L); }
    static MovieDuplicateMergeService.MergeRequest request(Map<String,Object> review) { return new MovieDuplicateMergeService.MergeRequest((String)review.get("reviewId"),UUID.randomUUID().toString(),true); }
    static boolean blocked(Map<String,Object> review,String code) {
        return ((List<Map<String,Object>>)review.get("items")).stream().flatMap(item->((List<Map<String,Object>>)item.get("blockers")).stream()).anyMatch(block->code.equals(block.get("code")));
    }
    static long insert(String table,Map<String,Object> supplied) {
        Map<String,Object> row=new LinkedHashMap<>(supplied);
        for(var column:jdbc.queryForList("SELECT COLUMN_NAME,DATA_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",table)) {
            String name=(String)column.get("COLUMN_NAME");
            if(row.containsKey(name)||!"NO".equals(column.get("IS_NULLABLE"))||column.get("COLUMN_DEFAULT")!=null||String.valueOf(column.get("EXTRA")).contains("auto_increment")) continue;
            String type=(String)column.get("DATA_TYPE");
            Object value;
            if(Set.of("bigint","int","mediumint","smallint","tinyint","decimal","float","double","bit").contains(type)) value=1;
            else if(Set.of("datetime","timestamp").contains(type)) value="2020-01-02 03:04:05";
            else if(type.equals("date")) value="2020-01-02";
            else if(type.equals("time")) value="03:04:05";
            else if(type.equals("json")) value="{}";
            else {
                String text=UUID.randomUUID().toString().replace("-","");
                if(column.get("CHARACTER_MAXIMUM_LENGTH") instanceof Number maximum) text=text.substring(0,(int)Math.min(text.length(),maximum.longValue()));
                value=text;
            }
            row.put(name,value);
        }
        var columns=new ArrayList<>(row.keySet()); String placeholders=String.join(",",Collections.nCopies(columns.size(),"?"));
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO "+table+"("+String.join(",",columns)+") VALUES("+placeholders+")",java.sql.Statement.RETURN_GENERATED_KEYS);
            for(int index=0;index<columns.size();index++) statement.setObject(index+1,row.get(columns.get(index)));
            return statement;
        },key);
        assertNotNull(key.getKey()); return key.getKey().longValue();
    }
    static Map<String,Long> seed(MovieDuplicateMergeService.Pair pair) {
        Map<String,Long> ids=new LinkedHashMap<>();
        for(String table:DIRECT) {
            Map<String,Object> row=new LinkedHashMap<>(); row.put("movie_id",pair.fromMovieId());
            if(table.equals("resource_hub_task")) { row.put("task_type","DISCOVER"); row.put("payload","{\"movieId\":\""+pair.fromMovieId()+"\",\"keyword\":\"unchanged\"}"); }
            if(table.equals("baidu_transfer_task")) { row.put("phase","SHARED"); row.put("original_url_hash",UUID.randomUUID().toString().replace("-","")); }
            if(table.equals("resource_discovery_result")) row.put("source","TEST");
            if(table.equals("social_post_log")) { row.put("target_id",ids.get("resource_link")); row.put("resource_link_id",ids.get("resource_link")); }
            if(table.equals("movie_source_identity")) { row.put("source","TMDB"); row.put("source_type","movie"); row.put("external_id",UUID.randomUUID().toString()); }
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='status'",Integer.class,table)>0) row.put("status",table.equals("resource_link")?"DELETED":table.equals("social_post_log")||table.equals("qq_channel_post_log")?"POSTED":"SUCCEEDED");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='updated_at'",Integer.class,table)>0) row.put("updated_at","2020-01-02 03:04:05");
            ids.put(table,insert(table,row));
        }
        ids.put("comment",insert("comment",Map.of("relate_id",pair.fromMovieId(),"comment_type","GENERAL")));
        ids.put("user_notification",insert("user_notification",Map.of("target_id",pair.fromMovieId(),"target_type","MOVIE")));
        insert("user_favorite",Map.of("movie_id",pair.fromMovieId(),"user_id",7,"created_at","2019-01-01 00:00:00"));
        insert("user_favorite",Map.of("movie_id",pair.toMovieId(),"user_id",7,"created_at","2020-01-01 00:00:00"));
        ids.put("user_favorite",insert("user_favorite",Map.of("movie_id",pair.fromMovieId(),"user_id",8)));
        return ids;
    }
    @Test void migratesEveryKnownReferencePreservesAllOtherFieldsAndArchivesFavoriteCollision() {
        var pair=pair(); var ids=seed(pair); Map<String,Map<String,Object>> before=new HashMap<>();
        jdbc.update("UPDATE movie_metadata SET tmdb_popularity=IF(id=?,99999999,99999996) WHERE id IN (?,?)",pair.fromMovieId(),pair.fromMovieId(),pair.toMovieId());
        var hidden=pair();
        jdbc.update("UPDATE movie_metadata SET status='DELETED',tmdb_popularity=99999998 WHERE id=?",hidden.fromMovieId());
        jdbc.update("UPDATE movie_metadata SET deleted_at='2020-01-02 03:04:05',tmdb_popularity=99999997 WHERE id=?",hidden.toMovieId());
        ids.forEach((table,id)->before.put(table,jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",id)));
        long forum=insert("comment",Map.of("relate_id",pair.fromMovieId(),"comment_type","REQUEST"));
        long unrelatedNotice=insert("user_notification",Map.of("target_id",pair.fromMovieId(),"target_type","RESOURCE"));
        var review=preview(pair); assertEquals(true,review.get("canMerge")); assertTrue(String.valueOf(review.get("expiresAt")).endsWith("Z"));
        var result=service.merge(request(review),7L); assertEquals(1,result.get("merged")); assertEquals("SUCCEEDED",result.get("status"));
        for(var entry:ids.entrySet()) {
            String table=entry.getKey(),column=table.equals("comment")?"relate_id":table.equals("user_notification")?"target_id":"movie_id";
            var actual=jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",entry.getValue());
            var expected=new LinkedHashMap<>(before.get(table)); expected.put(column,pair.toMovieId());
            if(table.equals("resource_hub_task")) { var payload=(com.fasterxml.jackson.databind.node.ObjectNode)readJson(String.valueOf(expected.get("payload"))); payload.put("movieId",pair.toMovieId()); expected.put("payload",payload.toString()); actual.put("payload",readJson(String.valueOf(actual.get("payload"))).toString()); }
            assertEquals(expected,actual,table);
        }
        assertEquals(pair.fromMovieId(),jdbc.queryForObject("SELECT relate_id FROM comment WHERE id=?",String.class,forum));
        assertEquals(pair.fromMovieId(),jdbc.queryForObject("SELECT target_id FROM user_notification WHERE id=?",String.class,unrelatedNotice));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite WHERE BINARY movie_id=BINARY ?",Long.class,pair.fromMovieId()));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM movie_merge_favorite_history WHERE review_id=? AND original_movie_id=? AND user_id=7 AND original_created_at='2019-01-01 00:00:00'",Long.class,review.get("reviewId"),pair.fromMovieId()));
        assertEquals("DELETED",jdbc.queryForObject("SELECT status FROM movie_metadata WHERE id=?",String.class,pair.fromMovieId()));
        assertEquals(pair.toMovieId(),new MovieMergeAliasResolver(jdbc).resolve(pair.fromMovieId()));
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration)
            .setIdentifierGenerator(new com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator(0L,0L));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("duplicate-fixture",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),source));
        configuration.addMapper(com.gying.movie.mapper.MovieMetadataMapper.class);
        var session=new org.mybatis.spring.SqlSessionTemplate(new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration));
        var movies=new MovieMetadataServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(movies,"baseMapper",session.getMapper(com.gying.movie.mapper.MovieMetadataMapper.class));
        var favorites=new com.gying.movie.controller.FavoriteController(null,movies,null,null);
        org.springframework.test.util.ReflectionTestUtils.setField(favorites,"minioUrlPrefix","https://images.example.test/movies");
        assertEquals(List.of(pair.toMovieId()),favorites.tmdbHot(1).stream().map(item->((com.gying.movie.entity.MovieMetadata)item.get("movie")).getId()).toList());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM movie_merge_reference_history WHERE review_id=?",Integer.class,review.get("reviewId"))>=14);
    }
    @Test void rejectsStaleReferenceAndEntireBatchRollsBackOnSecondAliasFailure() {
        var pair=pair(); long id=insert("resource_link",Map.of("movie_id",pair.fromMovieId())); var review=preview(pair); assertEquals(true,review.get("canMerge"));
        jdbc.update("UPDATE resource_link SET url='changed-after-review' WHERE id=?",id);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.merge(request(review),7L)).getStatusCode().value());
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM movie_metadata WHERE id=?",String.class,pair.fromMovieId()));
        var a=pair(); var b=pair(); long resource=insert("resource_link",Map.of("movie_id",a.fromMovieId()));
        var batch=service.preview(new MovieDuplicateMergeService.PreviewRequest(List.of(a,b)),7L); assertEquals(true,batch.get("canMerge"));
        JdbcTemplate failing=new JdbcTemplate(source) { @Override public int update(String sql,Object...args) { if(sql.startsWith("INSERT INTO movie_merge_alias")&&b.fromMovieId().equals(args[0])) throw new DataIntegrityViolationException("fixture injected alias failure"); return super.update(sql,args); } };
        var isolated=new MovieDuplicateMergeService(failing,json,new DataSourceTransactionManager(source));
        assertThrows(DataIntegrityViolationException.class,()->isolated.merge(request(batch),7L));
        assertEquals(a.fromMovieId(),jdbc.queryForObject("SELECT movie_id FROM resource_link WHERE id=?",String.class,resource));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM movie_merge_alias WHERE source_movie_id IN (?,?)",Long.class,a.fromMovieId(),b.fromMovieId()));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM movie_merge_reference_history WHERE review_id=?",Long.class,batch.get("reviewId")));
        assertEquals("PREVIEWED",jdbc.queryForObject("SELECT status FROM movie_duplicate_review WHERE id=?",String.class,batch.get("reviewId")));
    }
    @Test void rejectsUnknownTasksTemporaryScopesKnownIdentityAndSeriesOrPartConflicts() {
        var unknown=pair(); long task=insert("resource_hub_task",Map.of("movie_id",unknown.fromMovieId(),"task_type","P2P_ARCHIVE","status","UNKNOWN","payload","{}"));
        var blocked=preview(unknown); assertFalse((Boolean)blocked.get("canMerge")); assertTrue(blocked(blocked,"ACTIVE_OR_UNKNOWN_TASK"));
        assertThrows(ResponseStatusException.class,()->service.merge(request(blocked),7L));
        jdbc.update("UPDATE resource_hub_task SET status='SUCCEEDED' WHERE id=?",task);
        var temporary=pair(); insert("resource_link",Map.of("movie_id",temporary.fromMovieId(),"source","BAIDU_TEMP")); assertTrue(blocked(preview(temporary),"TEMPORARY_SCOPE"));
        var identity=pair(); jdbc.update("UPDATE movie_metadata SET tmdb_id=IF(id=?,1,2) WHERE id IN (?,?)",identity.fromMovieId(),identity.fromMovieId(),identity.toMovieId()); assertTrue(blocked(preview(identity),"TMDB_ID_CONFLICT"));
        var sequel=pair(); jdbc.update("UPDATE movie_metadata SET title_en=IF(id=?,'Sequel 2','Sequel 3'),series_name='Sequel',aliases='Sequel' WHERE id IN (?,?)",sequel.fromMovieId(),sequel.fromMovieId(),sequel.toMovieId()); assertTrue(blocked(preview(sequel),"PART_NUMBER_CONFLICT"));
        var series=pair(); jdbc.update("UPDATE movie_metadata SET title_en=IF(id=?,'Different first','Different second'),series_name='Shared franchise' WHERE id IN (?,?)",series.fromMovieId(),series.fromMovieId(),series.toMovieId()); assertTrue(blocked(preview(series),"SERIES_ONLY_MATCH"));
        jdbc.update("UPDATE movie_metadata SET aliases=IF(id=?,'Ｓｈａｒｅｄ　Ｆｒａｎｃｈｉｓｅ','[\"SHARED FRANCHISE\"]') WHERE id IN (?,?)",series.fromMovieId(),series.fromMovieId(),series.toMovieId());
        assertTrue(blocked(preview(series),"SERIES_ONLY_MATCH"));
        jdbc.update("UPDATE movie_metadata SET aliases=IF(id=?,'Ｓｈａｒｅｄ　Ｆｒａｎｃｈｉｓｅ / Verified alternate','[\"SHARED FRANCHISE\",\"Verified alternate\"]') WHERE id IN (?,?)",series.fromMovieId(),series.fromMovieId(),series.toMovieId());
        assertEquals(true,preview(series).get("canMerge"));
        var seasonal=pair();
        jdbc.update("UPDATE movie_metadata SET category='tv',tmdb_type='tv',season=1,title_en=IF(id=?,'Different A','Different B'),series_name='Shared franchise',aliases=IF(id=?,'Ｓｈａｒｅｄ　ｆｒａｎｃｈｉｓｅ Season 1','[\"Shared franchise S01\"]') WHERE id IN (?,?)",seasonal.fromMovieId(),seasonal.fromMovieId(),seasonal.fromMovieId(),seasonal.toMovieId());
        assertTrue(blocked(preview(seasonal),"SERIES_ONLY_MATCH"));
        jdbc.update("UPDATE movie_metadata SET aliases=IF(id=?,'Shared franchise Season 1 / Verified different title Season 1','[\"Shared franchise S01\",\"Verified different title S01\"]') WHERE id IN (?,?)",seasonal.fromMovieId(),seasonal.fromMovieId(),seasonal.toMovieId());
        assertEquals(true,preview(seasonal).get("canMerge"));
    }
    @Test void rejectsMissingOrModifiedGuardAndUnknownReferenceInventory() {
        var pair=pair(); var definition=MovieDuplicateMergeService.triggerDefinitions().get(0);
        jdbc.execute("DROP TRIGGER "+definition.name());
        try { assertTrue(blocked(preview(pair),"SCHEMA_NOT_READY")); } finally { jdbc.execute(definition.statement()); }
        jdbc.execute("CREATE TABLE unsupported_merge_reference(id BIGINT PRIMARY KEY,movie_id VARCHAR(64)) ENGINE=InnoDB");
        try { assertTrue(blocked(preview(pair),"UNKNOWN_REFERENCE")); } finally { jdbc.execute("DROP TABLE unsupported_merge_reference"); }
    }
    @Test void rejectsUnsafeReviewConstraintsColumnTypesNullabilityAndCollation() {
        var pair=pair(); var reviewed=preview(pair); assertEquals(true,reviewed.get("canMerge"));
        jdbc.execute("ALTER TABLE movie_duplicate_review DROP INDEX uk_duplicate_actor_key");
        try {
            assertTrue(blocked(preview(pair),"SCHEMA_NOT_READY"));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->service.merge(request(reviewed),7L)).getStatusCode().value());
        } finally { jdbc.execute("ALTER TABLE movie_duplicate_review ADD UNIQUE KEY uk_duplicate_actor_key(actor_id,idempotency_key)"); }
        jdbc.execute("ALTER TABLE movie_merge_alias MODIFY canonical_movie_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL");
        try { assertTrue(blocked(preview(pair),"SCHEMA_NOT_READY")); }
        finally { jdbc.execute("ALTER TABLE movie_merge_alias MODIFY canonical_movie_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"); }
        jdbc.execute("ALTER TABLE movie_merge_reference_history MODIFY original_fingerprint CHAR(65) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        try { assertTrue(blocked(preview(pair),"SCHEMA_NOT_READY")); }
        finally { jdbc.execute("ALTER TABLE movie_merge_reference_history MODIFY original_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"); }
        jdbc.execute("ALTER TABLE movie_duplicate_review MODIFY actor_id BIGINT NULL");
        try { assertTrue(blocked(preview(pair),"SCHEMA_NOT_READY")); }
        finally { jdbc.execute("ALTER TABLE movie_duplicate_review MODIFY actor_id BIGINT NOT NULL"); }
        jdbc.execute("ALTER TABLE movie_duplicate_review ADD UNIQUE KEY unsupported_duplicate_unique(id,actor_id)");
        try { assertTrue(blocked(preview(pair),"SCHEMA_NOT_READY")); }
        finally { jdbc.execute("ALTER TABLE movie_duplicate_review DROP INDEX unsupported_duplicate_unique"); }
        assertEquals(true,preview(pair).get("canMerge"));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM movie_metadata WHERE id=?",String.class,pair.fromMovieId()));
    }
    @Test void derivesAvailabilityWhenKeepingTheRecordWithNoResourcesAndPreservesItsMetadata() throws Exception {
        var pair=pair();
        jdbc.update("UPDATE movie_metadata SET resource_status='AVAILABLE' WHERE id=?",pair.fromMovieId());
        jdbc.update("UPDATE movie_metadata SET resource_status='UNKNOWN',summary='Retained review text',poster_url='/retained-poster',popularity=17,created_at='2019-02-03 04:05:06',updated_at='2020-02-03 04:05:06' WHERE id=?",pair.toMovieId());
        long link=insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","ACTIVE","audit_status",1,"link_status","NORMAL","source","RESOURCE_HUB"));
        long legacy=insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","ACTIVE","audit_status",1));
        jdbc.update("UPDATE resource_link SET link_status=NULL,source=NULL WHERE id=?",legacy);
        var original=jdbc.queryForMap("SELECT * FROM movie_metadata WHERE id=?",pair.toMovieId());
        var review=preview(pair); assertEquals(true,review.get("canMerge")); service.merge(request(review),7L);
        var expected=new LinkedHashMap<>(original); expected.put("resource_status","AVAILABLE");
        assertEquals(expected,jdbc.queryForMap("SELECT * FROM movie_metadata WHERE id=?",pair.toMovieId()));
        assertEquals(pair.toMovieId(),jdbc.queryForObject("SELECT movie_id FROM resource_link WHERE id=?",String.class,link));
        assertEquals(pair.toMovieId(),jdbc.queryForObject("SELECT movie_id FROM resource_link WHERE id=?",String.class,legacy));
        assertEquals(expectedFingerprint(original),jdbc.queryForObject("SELECT original_fingerprint FROM movie_merge_reference_history WHERE review_id=? AND reference_table='movie_metadata' AND reference_row_id=? AND original_movie_id=? AND canonical_movie_id=?",String.class,
            review.get("reviewId"),pair.toMovieId(),pair.toMovieId(),pair.toMovieId()));
    }
    @Test void unapprovedInvalidDeletedOrNonDiskResourcesCannotElevateTrailerOrUnknownStatus() {
        for(var statuses:List.of(List.of("TRAILER","UNKNOWN","TRAILER"),List.of("UNKNOWN","TRAILER","TRAILER"),List.of("AVAILABLE","AVAILABLE","UNKNOWN"))) {
            var pair=pair();
            jdbc.update("UPDATE movie_metadata SET resource_status=IF(id=?, ?, ?) WHERE id IN (?,?)",pair.fromMovieId(),statuses.get(0),statuses.get(1),pair.fromMovieId(),pair.toMovieId());
            insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","ACTIVE","audit_status",0,"link_status","NORMAL"));
            insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","ACTIVE","audit_status",1,"link_status","INVALID"));
            insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","ACTIVE","audit_status",1,"link_status","SUSPECTED_INVALID"));
            insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","DELETED","audit_status",1,"link_status","NORMAL"));
            insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","ACTIVE","audit_status",1,"link_status","NORMAL","deleted_at","2020-01-02 03:04:05"));
            insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","ACTIVE","audit_status",1,"link_status","NORMAL","type","TORRENT"));
            insert("resource_link",Map.of("movie_id",pair.fromMovieId(),"status","ACTIVE","audit_status",1,"link_status","NORMAL","type","MAGNET"));
            var original=jdbc.queryForMap("SELECT * FROM movie_metadata WHERE id=?",pair.toMovieId());
            var review=preview(pair); assertEquals(true,review.get("canMerge")); service.merge(request(review),7L);
            var expected=new LinkedHashMap<>(original); expected.put("resource_status",statuses.get(2));
            assertEquals(expected,jdbc.queryForMap("SELECT * FROM movie_metadata WHERE id=?",pair.toMovieId()));
        }
    }
    static String expectedFingerprint(Map<String,Object> row) throws Exception {
        Map<String,Object> ordered=new TreeMap<>();
        row.forEach((key,value)->ordered.put(key,value instanceof java.util.Date||value instanceof java.time.temporal.TemporalAccessor?value.toString():value instanceof byte[] bytes?Base64.getEncoder().encodeToString(bytes):value));
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(ordered)));
    }
    @Test void serializesConcurrentSameKeyAndCanReplayAfterExpiry() throws Exception {
        var pair=pair(); var review=preview(pair); var request=request(review); var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{ start.await(); return service.merge(request,7L); });
            var b=pool.submit(()->{ start.await(); return service.merge(request,7L); }); start.countDown();
            var first=a.get(20,TimeUnit.SECONDS); var second=b.get(20,TimeUnit.SECONDS);
            assertNotEquals(first.get("replayed"),second.get("replayed"));
            assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM movie_merge_alias WHERE source_movie_id=?",Long.class,pair.fromMovieId()));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
        jdbc.update("UPDATE movie_duplicate_review SET expires_at='2000-01-01' WHERE id=?",review.get("reviewId"));
        assertEquals(true,service.merge(request,7L).get("replayed"));
        assertThrows(ResponseStatusException.class,()->service.merge(request(review),7L));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.merge(request,8L)).getStatusCode().value());
    }
    @Test void flattensChainsAndCanonicalizesLateWritesFromAnOldRepeatableReadSnapshot() throws Exception {
        var first=pair(); var second=pair();
        jdbc.update("UPDATE movie_metadata SET title_en=(SELECT title_en FROM (SELECT title_en FROM movie_metadata WHERE id=?) original) WHERE id=?",first.toMovieId(),second.toMovieId());
        var ready=preview(first); assertEquals(true,ready.get("canMerge"));
        var snapshotStarted=new CountDownLatch(1); var committed=new CountDownLatch(1); var pool=Executors.newSingleThreadExecutor();
        try {
            var late=pool.submit(()->{ var tx=new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return tx.execute(status->{ jdbc.queryForObject("SELECT COUNT(*) FROM movie_merge_alias",Long.class); snapshotStarted.countDown(); try { assertTrue(committed.await(20,TimeUnit.SECONDS)); } catch(InterruptedException error) { throw new RuntimeException(error); }
                    return insert("resource_link",Map.of("movie_id",first.fromMovieId())); }); });
            assertTrue(snapshotStarted.await(5,TimeUnit.SECONDS)); service.merge(request(ready),7L); committed.countDown();
            long inserted=late.get(20,TimeUnit.SECONDS); assertEquals(first.toMovieId(),jdbc.queryForObject("SELECT movie_id FROM resource_link WHERE id=?",String.class,inserted));
        } finally { committed.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
        var next=new MovieDuplicateMergeService.Pair(first.toMovieId(),second.toMovieId()); var nextReview=preview(next); assertEquals(true,nextReview.get("canMerge")); service.merge(request(nextReview),7L);
        assertEquals(second.toMovieId(),new MovieMergeAliasResolver(jdbc).resolve(first.fromMovieId()));
        assertEquals(second.toMovieId(),new MovieMergeAliasResolver(jdbc).resolve(first.toMovieId()));
        long link=insert("resource_link",Map.of("movie_id",first.fromMovieId())); assertEquals(second.toMovieId(),jdbc.queryForObject("SELECT movie_id FROM resource_link WHERE id=?",String.class,link));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE movie_metadata SET status='ACTIVE',deleted_at=NULL WHERE id=?",first.fromMovieId()));
        long forum=insert("comment",Map.of("relate_id",first.fromMovieId(),"comment_type","REQUEST")); assertEquals(first.fromMovieId(),jdbc.queryForObject("SELECT relate_id FROM comment WHERE id=?",String.class,forum));
        long historical=insert("resource_link",Map.of("movie_id",first.fromMovieId().toLowerCase(Locale.ROOT))); assertEquals(first.fromMovieId().toLowerCase(Locale.ROOT),jdbc.queryForObject("SELECT movie_id FROM resource_link WHERE id=?",String.class,historical));
    }
    @Test void unrelatedBlockedGlobalParentCannotDisableEveryPairButAffectedRuntimeStillBlocks() {
        var pair=pair();
        long unrelated=insert("resource_hub_task",Map.of("task_type","MOVIE_VIDEO_BACKFILL","status","BLOCKED","payload","{\"currentMovieId\":\"other-unrelated-movie\"}"));
        long audit=insert("resource_hub_task",Map.of("task_type","MOVIE_VIDEO_BACKFILL","status","RUNNING","payload","{\"currentMovieId\":\"other-film\",\"deferredItems\":[{\"movieId\":\""+pair.fromMovieId()+"\",\"hash\":\"immutable\"}]}"));
        String original=jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=?",String.class,audit);
        try {
            var review=preview(pair); assertEquals(true,review.get("canMerge")); service.merge(request(review),7L);
            jdbc.update("UPDATE resource_hub_task SET attempts=attempts+1 WHERE id=?",audit);
            assertEquals(original,jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=?",String.class,audit));
            var protectedPair=pair();
            long affected=insert("resource_hub_task",Map.of("task_type","MOVIE_VIDEO_BACKFILL","status","RUNNING","payload","{\"currentMovieId\":\""+protectedPair.fromMovieId()+"\"}"));
            try { var blocked=preview(protectedPair); assertFalse((Boolean)blocked.get("canMerge")); assertTrue(blocked(blocked,"ACTIVE_OR_UNKNOWN_TASK")); assertTrue(blocked(blocked,"TASK_PAYLOAD_INVALID")); }
            finally { jdbc.update("UPDATE resource_hub_task SET status='SUCCEEDED' WHERE id=?",affected); }
        } finally { jdbc.update("UPDATE resource_hub_task SET status='SUCCEEDED' WHERE id IN (?,?)",unrelated,audit); }
    }
    @Test void taskGuardRewritesOnlyRootMovieIdAndRefusesUnknownRuntimePayload() {
        var pair=pair(); var review=preview(pair); service.merge(request(review),7L);
        long id=insert("resource_hub_task",Map.of("task_type","DISCOVER","status","SUCCEEDED","movie_id",pair.fromMovieId(),"payload","{\"movieId\":\""+pair.fromMovieId()+"\",\"keyword\":\"preserved\"}"));
        var row=jdbc.queryForMap("SELECT movie_id,payload FROM resource_hub_task WHERE id=?",id); assertEquals(pair.toMovieId(),row.get("movie_id")); assertEquals(pair.toMovieId(),readJson(String.valueOf(row.get("payload"))).path("movieId").asText());
        assertThrows(DataAccessException.class,()->insert("resource_hub_task",Map.of("task_type","MOVIE_VIDEO_BACKFILL","status","UNKNOWN","payload","{\"currentMovieId\":\""+pair.fromMovieId()+"\"}")));
    }
    static Map<String,Object> limitedPreview(MovieDuplicateMergeService.Pair pair) {
        return limitedService.preview(new MovieDuplicateMergeService.PreviewRequest(List.of(pair)),7L);
    }
    static void restoreMetadataRoutine() {
        jdbc.execute("DROP PROCEDURE IF EXISTS movie_merge_guard_metadata");
        jdbc.execute(MovieDuplicateMergeService.guardMetadataDefinition().statement());
        jdbc.execute("GRANT EXECUTE ON PROCEDURE "+database+".movie_merge_guard_metadata TO '"+applicationUser+"'@'%'");
    }
    @Test void limitedApplicationReadsLiveGuardsAndMergesWithoutTriggerOrDdlAuthority() {
        assertEquals(0,limited.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()",Integer.class));
        assertEquals(34,limited.queryForList("CALL movie_merge_guard_metadata()").size());
        assertEquals(MovieDuplicateMergeService.guardMetadataDefinition().body(),limited.queryForObject("SELECT ROUTINE_DEFINITION FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='movie_merge_guard_metadata'",String.class));
        assertEquals(0,limited.queryForObject("SELECT COUNT(*) FROM information_schema.PARAMETERS WHERE SPECIFIC_SCHEMA=DATABASE() AND SPECIFIC_NAME='movie_merge_guard_metadata'",Integer.class));
        String grants=limited.queryForList("SHOW GRANTS").toString().toUpperCase(Locale.ROOT);
        for(String forbidden:List.of("ALL PRIVILEGES","TRIGGER","CREATE","ALTER","DROP","GRANT OPTION")) assertFalse(grants.contains(forbidden),forbidden);
        assertTrue(grants.contains("SHOW_ROUTINE")); assertTrue(grants.contains("EXECUTE"));
        for(String forbidden:List.of("CREATE TABLE forbidden_fixture_ddl(id INT)","CREATE TRIGGER forbidden_fixture_trigger BEFORE INSERT ON movie_metadata FOR EACH ROW SET NEW.id=NEW.id",
            "DROP TRIGGER "+MovieDuplicateMergeService.triggerDefinitions().get(0).name(),"ALTER PROCEDURE movie_merge_guard_metadata SQL SECURITY INVOKER",
            "DROP PROCEDURE movie_merge_guard_metadata","CREATE PROCEDURE forbidden_fixture_routine() SELECT 1"))
            assertThrows(DataAccessException.class,()->limited.execute(forbidden));
        var pair=pair(); var rows=seed(pair); var reviewed=limitedPreview(pair); assertEquals(true,reviewed.get("canMerge"));
        assertEquals("SUCCEEDED",limitedService.merge(request(reviewed),7L).get("status"));
        assertEquals(pair.toMovieId(),jdbc.queryForObject("SELECT movie_id FROM resource_link WHERE id=?",String.class,rows.get("resource_link")));
    }
    @Test void missingMetadataRoutineOrUnreadableBodyOrMissingExecutePermissionBlocks() {
        var pair=pair(); jdbc.execute("DROP PROCEDURE movie_merge_guard_metadata");
        try { assertTrue(blocked(limitedPreview(pair),"SCHEMA_NOT_READY")); } finally { restoreMetadataRoutine(); }
        jdbc.execute("REVOKE SHOW_ROUTINE ON *.* FROM '"+applicationUser+"'@'%'");
        try {
            assertNull(limited.queryForObject("SELECT ROUTINE_DEFINITION FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='movie_merge_guard_metadata'",String.class));
            assertEquals(34,limited.queryForList("CALL movie_merge_guard_metadata()").size());
            assertTrue(blocked(limitedPreview(pair),"SCHEMA_NOT_READY"));
        } finally { jdbc.execute("GRANT SHOW_ROUTINE ON *.* TO '"+applicationUser+"'@'%'"); }
        jdbc.execute("REVOKE EXECUTE ON PROCEDURE "+database+".movie_merge_guard_metadata FROM '"+applicationUser+"'@'%'");
        try { assertTrue(blocked(limitedPreview(pair),"SCHEMA_NOT_READY")); } finally { restoreMetadataRoutine(); }
    }
    @Test void invokerModifiedBodyOrNonzeroParametersNeverExecuteUntrustedMetadataRoutine() {
        int[] calls={0};
        JdbcTemplate observed=new JdbcTemplate(limited.getDataSource()) {
            @Override public List<Map<String,Object>> queryForList(String sql) {
                if(sql.startsWith("CALL ")) calls[0]++;
                return super.queryForList(sql);
            }
        };
        var observedService=new MovieDuplicateMergeService(observed,json,new DataSourceTransactionManager(limited.getDataSource()));
        var pair=pair(); var selected=new MovieDuplicateMergeService.PreviewRequest(List.of(pair));
        jdbc.execute("ALTER PROCEDURE movie_merge_guard_metadata SQL SECURITY INVOKER");
        try { assertTrue(blocked(observedService.preview(selected,7L),"SCHEMA_NOT_READY")); } finally { restoreMetadataRoutine(); }
        jdbc.execute("CREATE TABLE guard_call_marker(calls INT NOT NULL)"); jdbc.update("INSERT INTO guard_call_marker VALUES(0)");
        try {
            jdbc.execute("DROP PROCEDURE movie_merge_guard_metadata");
            jdbc.execute("CREATE PROCEDURE movie_merge_guard_metadata() READS SQL DATA SQL SECURITY DEFINER UPDATE guard_call_marker SET calls=calls+1");
            jdbc.execute("GRANT EXECUTE ON PROCEDURE "+database+".movie_merge_guard_metadata TO '"+applicationUser+"'@'%'");
            assertTrue(blocked(observedService.preview(selected,7L),"SCHEMA_NOT_READY"));
            assertEquals(0,jdbc.queryForObject("SELECT calls FROM guard_call_marker",Integer.class));
        } finally { restoreMetadataRoutine(); jdbc.execute("DROP TABLE guard_call_marker"); }
        jdbc.execute("DROP PROCEDURE movie_merge_guard_metadata");
        jdbc.execute("CREATE PROCEDURE movie_merge_guard_metadata(IN unexpected INT) READS SQL DATA SQL SECURITY DEFINER "+MovieDuplicateMergeService.guardMetadataDefinition().body());
        jdbc.execute("GRANT EXECUTE ON PROCEDURE "+database+".movie_merge_guard_metadata TO '"+applicationUser+"'@'%'");
        try { assertTrue(blocked(observedService.preview(selected,7L),"SCHEMA_NOT_READY")); } finally { restoreMetadataRoutine(); }
        jdbc.execute("ALTER PROCEDURE movie_merge_guard_metadata CONTAINS SQL");
        try { assertTrue(blocked(observedService.preview(selected,7L),"SCHEMA_NOT_READY")); } finally { restoreMetadataRoutine(); }
        jdbc.execute("DROP PROCEDURE movie_merge_guard_metadata");
        jdbc.execute("CREATE PROCEDURE movie_merge_guard_metadata() DETERMINISTIC READS SQL DATA SQL SECURITY DEFINER "+MovieDuplicateMergeService.guardMetadataDefinition().body());
        jdbc.execute("GRANT EXECUTE ON PROCEDURE "+database+".movie_merge_guard_metadata TO '"+applicationUser+"'@'%'");
        try { assertTrue(blocked(observedService.preview(selected,7L),"SCHEMA_NOT_READY")); } finally { restoreMetadataRoutine(); }
        assertEquals(0,calls[0],"Invalid metadata must be rejected before CALL");
    }
    @Test void limitedApplicationRejectsTamperedGuardAndUnknownBeforeTriggerOnHistoryTable() {
        var pair=pair(); var definition=MovieDuplicateMergeService.triggerDefinitions().get(0);
        jdbc.execute("DROP TRIGGER "+definition.name());
        jdbc.execute("CREATE TRIGGER "+definition.name()+" BEFORE "+definition.event()+" ON "+definition.table()+" FOR EACH ROW SET NEW.id=NEW.id");
        try { assertTrue(blocked(limitedPreview(pair),"SCHEMA_NOT_READY")); }
        finally { jdbc.execute("DROP TRIGGER "+definition.name()); jdbc.execute(definition.statement()); }
        jdbc.execute("CREATE TRIGGER unexpected_history_before BEFORE INSERT ON movie_merge_favorite_history FOR EACH ROW SET NEW.user_id=NEW.user_id");
        try {
            assertEquals(35,limited.queryForList("CALL movie_merge_guard_metadata()").size());
            assertTrue(blocked(limitedPreview(pair),"UNKNOWN_REFERENCE"));
        } finally { jdbc.execute("DROP TRIGGER unexpected_history_before"); }
    }
    @Test void validRoutineMetadataChangeInvalidatesExistingAdministratorReview() {
        var pair=pair(); var reviewed=limitedPreview(pair); assertEquals(true,reviewed.get("canMerge"));
        jdbc.execute("ALTER PROCEDURE movie_merge_guard_metadata COMMENT 'fixture metadata changed after review'");
        try {
            assertEquals(true,limitedPreview(pair).get("canMerge"));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->limitedService.merge(request(reviewed),7L)).getStatusCode().value());
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM movie_metadata WHERE id=?",String.class,pair.fromMovieId()));
        } finally { restoreMetadataRoutine(); }
    }
    record QuarkFixture(long task,long discovery,long resource) { }
    static QuarkFixture completedQuarkFixture(MovieDuplicateMergeService.Pair pair, boolean sourceMovie) {
        String movieId=sourceMovie?pair.fromMovieId():pair.toMovieId();
        String own="https://pan.quark.cn/s/ownedFixture",original="https://pan.quark.cn/s/sourceFixture";
        String ownHash=com.gying.movie.utils.ResourceHubHashUtils.sha256(own);
        long resource=insert("resource_link",Map.ofEntries(
            Map.entry("movie_id",movieId),Map.entry("provider","QUARK"),Map.entry("type","DISK"),
            Map.entry("source","GYING_PUBLISHED"),Map.entry("status","ACTIVE"),Map.entry("audit_status",1),
            Map.entry("link_status","NORMAL"),Map.entry("url",own),Map.entry("url_hash",ownHash),
            Map.entry("validated_at","2026-09-19 15:16:22"),Map.entry("updated_at","2026-09-19 15:16:22")));
        long discovery=insert("resource_discovery_result",Map.ofEntries(
            Map.entry("movie_id",movieId),Map.entry("provider","QUARK"),Map.entry("resource_type","DISK"),
            Map.entry("source","GYING"),Map.entry("source_ref","upstream"),Map.entry("status","SAVED"),
            Map.entry("share_url",own),Map.entry("share_url_hash",ownHash),Map.entry("resource_link_id",resource),
            Map.entry("original_url",original),Map.entry("updated_at","2026-09-19 15:16:22")));
        long task=insert("quark_transfer_task",Map.ofEntries(
            Map.entry("movie_id",movieId),Map.entry("discovery_result_id",discovery),Map.entry("status","SUBMITTED"),
            Map.entry("attempts",1),Map.entry("saved_path","/GYing Resource Hub/tv/Fixture"),
            Map.entry("original_url",original),Map.entry("original_url_hash",com.gying.movie.utils.ResourceHubHashUtils.sha256(original)),
            Map.entry("share_url",own),Map.entry("share_url_hash",ownHash),
            Map.entry("started_at","2026-09-19 15:16:11"),Map.entry("finished_at","2026-09-19 15:16:20"),
            Map.entry("updated_at","2026-09-19 15:16:22"),
            Map.entry("request_payload","{\"taskname\":\"Fixture\",\"shareurl\":\""+original+"\",\"savepath\":\"/GYing Resource Hub/tv/Fixture\"}"),
            Map.entry("response_payload","{\"addTask\":{\"code\":0,\"data\":{\"taskname\":\"Fixture\"},\"success\":true,\"message\":\"ok\"},\"runNowOutput\":\"fixture completed\"}")));
        return new QuarkFixture(task,discovery,resource);
    }
    @Test void completedSubmittedQuarkEvidencePreservesAllReferenceFields() {
        for(boolean sourceMovie:List.of(false,true)) {
            var pair=pair(); var fixture=completedQuarkFixture(pair,sourceMovie);
            var beforeTask=jdbc.queryForMap("SELECT * FROM quark_transfer_task WHERE id=?",fixture.task());
            var beforeDiscovery=jdbc.queryForMap("SELECT * FROM resource_discovery_result WHERE id=?",fixture.discovery());
            var beforeResource=jdbc.queryForMap("SELECT * FROM resource_link WHERE id=?",fixture.resource());
            var reviewed=preview(pair); assertEquals(true,reviewed.get("canMerge"));
            assertEquals(1,service.merge(request(reviewed),7L).get("merged"));
            if(sourceMovie) {
                beforeTask.put("movie_id",pair.toMovieId()); beforeDiscovery.put("movie_id",pair.toMovieId()); beforeResource.put("movie_id",pair.toMovieId());
            }
            assertEquals(beforeTask,jdbc.queryForMap("SELECT * FROM quark_transfer_task WHERE id=?",fixture.task()));
            assertEquals(beforeDiscovery,jdbc.queryForMap("SELECT * FROM resource_discovery_result WHERE id=?",fixture.discovery()));
            assertEquals(beforeResource,jdbc.queryForMap("SELECT * FROM resource_link WHERE id=?",fixture.resource()));
            assertEquals("SUBMITTED",jdbc.queryForObject("SELECT status FROM quark_transfer_task WHERE id=?",String.class,fixture.task()));
        }
    }
    @Test void submittedQuarkRejectsAnyMissingContradictoryOrTemporaryEvidence() {
        Map<String,List<String>> changes=new LinkedHashMap<>();
        changes.put("quark_transfer_task",List.of(
            "finished_at=NULL","saved_path=NULL","saved_path=' '","last_error='failure'",
            "share_url=NULL","share_url_hash=NULL","share_url_hash=REPEAT('0',64)","discovery_result_id=NULL",
            "request_payload=NULL","request_payload='[]'","request_payload='{\"origin\":\"QQ_BOT\"}'",
            "request_payload='{\"nested\":{\"phase\":\"UNKNOWN\"}}'","response_payload=NULL",
            "response_payload='[{\"phase\":\"UNKNOWN\"}]'","response_payload='\"UNKNOWN\"'",
            "original_url='https://pan.quark.cn/s/ownedFixture?pwd=abcd'",
            "original_url='https://pan.quark.cn/s/ownedFixture/#/list/share/folder'",
            "original_url='https://pan.quark.cn/s/%6fwnedFixture'",
            "original_url='not-a-share'","status='RUNNING'","status='PENDING'","status='UNKNOWN'","status='WAITING_SHARE'",
            "response_payload='{\"nested\":{\"phase\":\"UNKNOWN\"}}'","response_payload='{\"nested\":{\"shareSubmitting\":true}}'"));
        changes.put("resource_discovery_result",List.of(
            "movie_id='other-unrelated-movie'","provider='XUNLEI'","resource_type='TORRENT'","status='FAILED'",
            "failure_reason='failure'","source='QQ_BOT'","source_ref='BAIDU_TEMP:fixture'",
            "share_url=NULL","share_url_hash=NULL","original_url='https://pan.quark.cn/s/another'",
            "resource_link_id=NULL"));
        changes.put("resource_link",List.of(
            "movie_id='other-unrelated-movie'","provider='XUNLEI'","type='TORRENT'","status='DELETED'",
            "deleted_at='2026-09-19 15:16:23'","audit_status=0","link_status='INVALID'","source='BAIDU_TEMP'",
            "source='QQ_BOT'","url='https://pan.quark.cn/s/other'","url_hash=REPEAT('0',64)",
            "validated_at=NULL","validated_at='2026-09-19 15:16:19'","last_check_error='failure'"));
        for(var table:changes.entrySet()) for(String change:table.getValue()) {
            var pair=pair(); var fixture=completedQuarkFixture(pair,false);
            long id=switch(table.getKey()) { case "quark_transfer_task"->fixture.task(); case "resource_discovery_result"->fixture.discovery(); default->fixture.resource(); };
            assertEquals(1,jdbc.update("UPDATE "+table.getKey()+" SET "+change+" WHERE id=?",id));
            var reviewed=preview(pair); assertFalse((Boolean)reviewed.get("canMerge"),table.getKey()+":"+change);
            assertTrue(blocked(reviewed,"ACTIVE_OR_UNKNOWN_TASK"),table.getKey()+":"+change);
        }
    }
    @Test void submittedQuarkRejectsSpoofedOwnedUrlEvenWhenEveryStoredHashMatches() {
        for(String own:List.of("http://pan.quark.cn/s/ownedFixture","https://pan.quark.cn.evil.invalid/s/ownedFixture",
            "https://fixture@pan.quark.cn/s/ownedFixture","https://pan.quark.cn:444/s/ownedFixture",
            "https://pan.quark.cn/s/ownedFixture#fragment","https://pan.quark.cn/s/ownedFixture?redirect=elsewhere",
            "https://pan.quark.cn/s/ownedFixture?pwd=abcd&pwd=efgh","https://pan.quark.cn/s/ownedFixture/")) {
            var pair=pair(); var fixture=completedQuarkFixture(pair,false);
            String hash=com.gying.movie.utils.ResourceHubHashUtils.sha256(own);
            jdbc.update("UPDATE quark_transfer_task SET share_url=?,share_url_hash=? WHERE id=?",own,hash,fixture.task());
            jdbc.update("UPDATE resource_discovery_result SET share_url=?,share_url_hash=? WHERE id=?",own,hash,fixture.discovery());
            jdbc.update("UPDATE resource_link SET url=?,url_hash=? WHERE id=?",own,hash,fixture.resource());
            assertTrue(blocked(preview(pair),"ACTIVE_OR_UNKNOWN_TASK"),own);
        }
    }
    @Test void sameQuarkSourceShareIdRejectsQueryFragmentTrailingSlashEncodingAndHostCaseVariants() {
        for(String original:List.of("https://pan.quark.cn/s/ownedFixture?pwd=abcd",
            "https://pan.quark.cn/s/ownedFixture#/list/share/folder","https://pan.quark.cn/s/ownedFixture/",
            "https://pan.quark.cn/s/ownedFixture/#/list/share/folder","https://pan.quark.cn/s/%6fwnedFixture",
            "https://PAN.QUARK.CN/s/ownedFixture?pwd=abcd")) {
            var pair=pair(); var fixture=completedQuarkFixture(pair,false);
            jdbc.update("UPDATE quark_transfer_task SET original_url=?,original_url_hash=? WHERE id=?",
                original,com.gying.movie.utils.ResourceHubHashUtils.sha256(original),fixture.task());
            jdbc.update("UPDATE resource_discovery_result SET original_url=? WHERE id=?",original,fixture.discovery());
            var reviewed=preview(pair); assertFalse((Boolean)reviewed.get("canMerge"),original);
            assertTrue(blocked(reviewed,"ACTIVE_OR_UNKNOWN_TASK"),original);
        }
    }
    @Test void submittedIsStillUncertainForOtherProvidersAndNestedTaskStates() {
        var pair=pair(); insert("xunlei_transfer_task",Map.of("movie_id",pair.toMovieId(),"status","SUBMITTED","finished_at","2026-09-19 15:16:20"));
        assertTrue(blocked(preview(pair),"ACTIVE_OR_UNKNOWN_TASK"));
        var nested=pair(); var fixture=completedQuarkFixture(nested,false);
        jdbc.update("UPDATE quark_transfer_task SET response_payload='{\"nested\":{\"status\":\"SUBMITTED\"}}' WHERE id=?",fixture.task());
        assertTrue(blocked(preview(nested),"ACTIVE_OR_UNKNOWN_TASK"));
    }
    @Test void quarkCompletionEvidenceRowsAreLockedAndAnyFullRowDriftInvalidatesReview() {
        var pair=pair(); var fixture=completedQuarkFixture(pair,false); List<String> locks=new ArrayList<>();
        JdbcTemplate observed=new JdbcTemplate(source) {
            @Override public List<Map<String,Object>> queryForList(String sql,Object... arguments) {
                if(sql.endsWith(" FOR UPDATE")) locks.add(sql);
                return super.queryForList(sql,arguments);
            }
        };
        var observedService=new MovieDuplicateMergeService(observed,json,new DataSourceTransactionManager(source));
        var reviewed=observedService.preview(new MovieDuplicateMergeService.PreviewRequest(List.of(pair)),7L);
        assertEquals(true,reviewed.get("canMerge")); observedService.merge(request(reviewed),7L);
        for(String table:List.of("quark_transfer_task","resource_discovery_result","resource_link"))
            assertTrue(locks.stream().anyMatch(sql->sql.contains(" FROM "+table+" WHERE ")&&sql.endsWith(" FOR UPDATE")),table);
        var drifting=pair(); var evidence=completedQuarkFixture(drifting,false);
        for(String table:List.of("quark_transfer_task","resource_discovery_result","resource_link")) {
            var before=preview(drifting); assertEquals(true,before.get("canMerge"));
            long id=table.equals("quark_transfer_task")?evidence.task():table.equals("resource_discovery_result")?evidence.discovery():evidence.resource();
            String change=table.equals("quark_transfer_task")?"attempts=attempts+1":table.equals("resource_discovery_result")?"version_note=UUID()":"remark=UUID()";
            jdbc.update("UPDATE "+table+" SET "+change+" WHERE id=?",id);
            assertEquals(true,preview(drifting).get("canMerge"));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->service.merge(request(before),7L)).getStatusCode().value(),table);
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM movie_metadata WHERE id=?",String.class,drifting.fromMovieId()));
        }
    }
    static MovieDuplicateMergeService.Pair p2pPair() {
        String suffix=UUID.randomUUID().toString().replace("-",""); String from="tmdb_tv_"+suffix,to="GY"+suffix;
        jdbc.update("INSERT INTO movie_metadata(id,title_en,category,tmdb_type,year,season,status,actors) VALUES(?,?,'tv','tv',2025,1,'ACTIVE','[\"Same Actor\"]'),(?,?,'tv','tv',2025,1,'ACTIVE','[\"Same Actor\"]')",
            from,"Reviewed "+suffix,to,"Reviewed "+suffix);
        return new MovieDuplicateMergeService.Pair(from,to);
    }
    static String p2pTargetPayload(MovieDuplicateMergeService.Pair pair,int version) {
        return """
            {"version":%d,"typeCode":"tv","mid":"%s","stage":"COMPLETE",
             "policy":"1080P_4K_AT_LEAST_ONE_CHINESE_SUBTITLE_METADATA_FILES_ONLY",
             "folderId":"fixture-folder","shareUrl":"https://pan.quark.cn/s/metadataFixture",
             "fingerprint":"fixture-fingerprint","resourceIds":[1],"uploadedFiles":{"magnet.txt":"fixture-file"},
             "resourceLinkId":2}
            """.formatted(version,pair.toMovieId());
    }
    @Test void retainedP2pUpstreamMidHistoryPreservesPayloadAndCurrentLateWriteGuards() {
        for(int version:List.of(1,2)) {
            var pair=p2pPair(); List<Long> ids=new ArrayList<>(); List<Map<String,Object>> before=new ArrayList<>();
            for(String provider:List.of("QUARK","XUNLEI")) {
                long id=insert("resource_hub_task",Map.of("task_type","P2P_ARCHIVE","movie_id",pair.toMovieId(),
                    "source",provider,"status","SUCCEEDED","payload",p2pTargetPayload(pair,version)));
                ids.add(id); before.add(jdbc.queryForMap("SELECT * FROM resource_hub_task WHERE id=?",id));
            }
            var reviewed=preview(pair); assertEquals(true,reviewed.get("canMerge")); service.merge(request(reviewed),7L);
            for(int i=0;i<ids.size();i++) {
                assertEquals(before.get(i),jdbc.queryForMap("SELECT * FROM resource_hub_task WHERE id=?",ids.get(i)));
                jdbc.update("UPDATE resource_hub_task SET attempts=attempts+1 WHERE id=?",ids.get(i));
                assertEquals(before.get(i).get("payload"),jdbc.queryForObject("SELECT payload FROM resource_hub_task WHERE id=?",String.class,ids.get(i)));
            }
            String sourceMid=p2pTargetPayload(pair,version).replace("\"mid\":\""+pair.toMovieId()+"\"","\"mid\":\""+pair.fromMovieId()+"\"");
            assertThrows(DataAccessException.class,()->insert("resource_hub_task",Map.of("task_type","P2P_ARCHIVE",
                "movie_id",pair.fromMovieId(),"source","QUARK","status","SUCCEEDED","payload",sourceMid)));
        }
    }
    @Test void p2pTargetMidExceptionRejectsUnknownNestedIdsMalformedIdentityOrReverseDirection() {
        for(String change:List.of("source-mid","source-row","nested-target","nested-source","version-string","version-float",
            "version-overflow","unsupported-version","wrong-type","unknown-provider","wrong-task-type","running","unknown-state")) {
            var pair=p2pPair(); var payload=(com.fasterxml.jackson.databind.node.ObjectNode)readJson(p2pTargetPayload(pair,2));
            String movie=pair.toMovieId(),type="P2P_ARCHIVE",provider="QUARK",status="SUCCEEDED";
            switch(change) {
                case "source-mid"->payload.put("mid",pair.fromMovieId());
                case "source-row"->movie=pair.fromMovieId();
                case "nested-target"->payload.putObject("runtime").put("movieId",pair.toMovieId());
                case "nested-source"->payload.putObject("runtime").put("movieId",pair.fromMovieId());
                case "version-string"->payload.put("version","2");
                case "version-float"->payload.put("version",2.0);
                case "version-overflow"->payload.put("version",4294967297L);
                case "unsupported-version"->payload.put("version",3);
                case "wrong-type"->payload.put("typeCode","TV");
                case "unknown-provider"->provider="OTHER";
                case "wrong-task-type"->type="RESOURCE_DISCOVERY";
                case "running"->status="RUNNING";
                case "unknown-state"->payload.putObject("runtime").put("phase","UNKNOWN");
            }
            insert("resource_hub_task",Map.of("task_type",type,"movie_id",movie,"source",provider,"status",status,"payload",payload.toString()));
            var reviewed=preview(pair); assertFalse((Boolean)reviewed.get("canMerge"),change);
            assertTrue(blocked(reviewed,change.equals("unknown-state")?"ACTIVE_OR_UNKNOWN_TASK":"TASK_PAYLOAD_INVALID"),change);
        }
        var pair=p2pPair();
        insert("resource_hub_task",Map.of("task_type","P2P_ARCHIVE","movie_id",pair.toMovieId(),"source","QUARK",
            "status","SUCCEEDED","payload",p2pTargetPayload(pair,2)));
        var reversed=new MovieDuplicateMergeService.Pair(pair.toMovieId(),pair.fromMovieId());
        assertTrue(blocked(preview(reversed),"TASK_PAYLOAD_INVALID"));
    }
    static com.fasterxml.jackson.databind.JsonNode readJson(String value) { try { return json.readTree(value); } catch(Exception error) { throw new IllegalStateException(error); } }
}
