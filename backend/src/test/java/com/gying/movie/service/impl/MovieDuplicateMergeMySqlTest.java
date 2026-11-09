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
    static MovieDuplicateMergeService service;
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
        String database="duplicate_merge_fixture_"+runId;
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
    }
    static DriverManagerDataSource dataSource(String url) {
        var data=new DriverManagerDataSource(url,"root",""); var properties=new Properties();
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
    static com.fasterxml.jackson.databind.JsonNode readJson(String value) { try { return json.readTree(value); } catch(Exception error) { throw new IllegalStateException(error); } }
}
