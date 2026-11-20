package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.utils.MovieDuplicateMatcher;
import com.gying.movie.utils.QqTransferMarker;
import com.gying.movie.utils.ResourceHubHashUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Explicit reviewed pairs only. Original records and reference provenance remain available. */
@Service
public class MovieDuplicateMergeService {
    public record Pair(String fromMovieId, String toMovieId) { }
    public record PreviewRequest(List<Pair> pairs) { }
    public record MergeRequest(String reviewId, String idempotencyKey, Boolean confirmed) { }
    private record Reference(String table, String column, String filter, String action) { }
    private record Snapshot(Map<String,Object> view, Map<String,List<Map<String,Object>>> rows, String fingerprint, TmdbBinding binding) { }
    record TmdbBinding(Long tmdbId, String tmdbType, int season, Object sourceIdentityId, Object gyingIdentityId, boolean changesMetadata) { }
    private record Schema(Map<String,Set<String>> columns, List<Map<String,Object>> blockers, String fingerprint) { }
    public record TriggerDefinition(String name, String table, String event, String statement, String body) { }
    public record GuardMetadataDefinition(String statement, String body) { }
    private record GuardMetadata(List<Map<String,Object>> triggers, List<Map<String,Object>> routine) { }
    private static final String GUARD_METADATA_ROUTINE = "movie_merge_guard_metadata";
    private static final int MAX_PAIRS = 20;
    private static final int MAX_REFERENCE_ROWS = 10000;
    private static final List<Reference> REFERENCES = List.of(
        ref("baidu_temporary_cleanup_job"), ref("baidu_transfer_task"), ref("movie_source_identity"),
        ref("qq_bot_search_log"), ref("qq_channel_post_log"), ref("qq_transfer_cleanup_job"),
        ref("quark_transfer_task"), ref("resource_discovery_result"),
        new Reference("resource_hub_task","movie_id","","MOVE_TASK_PAYLOAD"), ref("resource_link"),
        ref("resource_operation_log"), ref("social_post_log"),
        new Reference("user_favorite","movie_id","","MERGE_FAVORITES_PRESERVE_HISTORY"), ref("xunlei_transfer_task"),
        new Reference("comment","relate_id"," AND (comment_type='GENERAL' OR comment_type IS NULL)","MOVE_COMMENTS"),
        new Reference("user_notification","target_id"," AND target_type='MOVIE'","MOVE_MOVIE_NOTIFICATIONS"));
    private static final Set<String> TASK_TABLES = Set.of("resource_hub_task","quark_transfer_task","xunlei_transfer_task",
        "baidu_transfer_task","qq_transfer_cleanup_job","baidu_temporary_cleanup_job","social_post_log","qq_channel_post_log");
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED","SUCCESS","PUBLISHED","POSTED","SENT","FAILED","CANCELED","CANCELLED","IGNORED");
    private static final Set<String> UNCERTAIN = Set.of("RUNNING","UNKNOWN","SUBMITTING","SUBMITTED","WAITING_SHARE","ACTION_REQUIRED","REVIEW_REQUIRED","PROCESSING","SENDING","PUBLISHING");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public MovieDuplicateMergeService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; transactions=new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); transactions.setTimeout(60);
    }
    private static Reference ref(String table) { return new Reference(table,"movie_id","","MOVE_REFERENCES"); }

    public Map<String,Object> preview(PreviewRequest request, Long actor) {
        List<Pair> pairs=validatePairs(request == null ? null : request.pairs());
        return transactions.execute(transaction -> {
            Schema schema=schema(); List<Map<String,Object>> items=new ArrayList<>(); List<String> hashes=new ArrayList<>();
            for (Pair pair:pairs) { Snapshot state=snapshot(pair,schema,false); items.add(state.view()); hashes.add(state.fingerprint()); }
            boolean eligible=items.stream().allMatch(item -> Boolean.TRUE.equals(item.get("eligible")));
            String id=UUID.randomUUID().toString(); Instant now=Instant.now(); Instant expiry=now.plusSeconds(600);
            jdbc.update("INSERT INTO movie_duplicate_review(id,actor_id,plan_json,fingerprint,eligible,status,created_at,expires_at) VALUES(?,?,?,?,?,'PREVIEWED',?,?)",
                id,actor,write(pairs),hash(hashes),eligible,utc(now),utc(expiry));
            return Map.of("reviewId",id,"expiresAt",expiry.toString(),"canMerge",eligible,"items",items);
        });
    }

    public Map<String,Object> merge(MergeRequest request, Long actor) {
        if (request==null || !Boolean.TRUE.equals(request.confirmed()) || !uuid(request.reviewId()) || !uuid(request.idempotencyKey()))
            throw bad("Explicit confirmation, review ID and idempotency key are required");
        try { return transactions.execute(transaction -> {
            List<Map<String,Object>> reviews=jdbc.queryForList("SELECT * FROM movie_duplicate_review WHERE id=? FOR UPDATE",request.reviewId());
            if(reviews.size()!=1) throw conflict("Review is missing; preview again");
            Map<String,Object> review=reviews.get(0);
            if(!Objects.equals(number(review.get("actor_id")),actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Review belongs to another administrator");
            if("SUCCEEDED".equals(review.get("status"))) {
                if(!request.idempotencyKey().equals(review.get("idempotency_key"))) throw conflict("Review already used with another idempotency key");
                Map<String,Object> result=readMap(text(review.get("result_json"))); result.put("replayed",true); return result;
            }
            if(jdbc.queryForObject("SELECT COUNT(*) FROM movie_duplicate_review WHERE actor_id=? AND idempotency_key=?",Long.class,actor,request.idempotencyKey())!=0)
                throw conflict("Idempotency key already belongs to another review");
            if(!Boolean.TRUE.equals(review.get("eligible")) && !(review.get("eligible") instanceof Number value && value.intValue()==1))
                throw conflict("Review contains blocked pairs; preview again");
            if(jdbc.queryForObject("SELECT IF(expires_at > UTC_TIMESTAMP(6),1,0) FROM movie_duplicate_review WHERE id=?",Integer.class,request.reviewId())!=1)
                throw conflict("Review expired; preview again");
            List<Pair> pairs=readPairs(text(review.get("plan_json"))); validatePairs(pairs);
            List<String> ids=pairs.stream().flatMap(pair -> java.util.stream.Stream.of(pair.fromMovieId(),pair.toMovieId())).sorted().toList();
            for(String id:ids) jdbc.queryForList("SELECT id FROM movie_metadata WHERE id=? AND BINARY id=BINARY ? FOR UPDATE",id,id);
            Schema schema=schema(); List<Snapshot> states=new ArrayList<>(); List<String> hashes=new ArrayList<>();
            for(Pair pair:pairs) { Snapshot state=snapshot(pair,schema,true); states.add(state); hashes.add(state.fingerprint()); }
            if(states.stream().anyMatch(state -> !Boolean.TRUE.equals(state.view().get("eligible"))) || !hash(hashes).equals(review.get("fingerprint")))
                throw conflict("References or eligibility changed; preview again");
            for(int index=0;index<pairs.size();index++) apply(pairs.get(index),states.get(index),request.reviewId(),actor);
            List<Map<String,Object>> resultItems=pairs.stream().map(pair -> Map.<String,Object>of("fromMovieId",pair.fromMovieId(),"toMovieId",pair.toMovieId())).toList();
            Map<String,Object> result=new LinkedHashMap<>(); result.put("reviewId",request.reviewId()); result.put("idempotencyKey",request.idempotencyKey());
            result.put("status","SUCCEEDED"); result.put("merged",pairs.size()); result.put("items",resultItems); result.put("replayed",false);
            if(jdbc.update("UPDATE movie_duplicate_review SET status='SUCCEEDED',idempotency_key=?,result_json=?,completed_at=? WHERE id=? AND status='PREVIEWED'",
                    request.idempotencyKey(),write(result),utc(Instant.now()),request.reviewId())!=1) throw conflict("Review changed");
            return result;
        }); } catch(org.springframework.dao.DuplicateKeyException collision) {
            throw conflict("Idempotency key or reference uniqueness changed; preview again");
        }
    }

    public List<Map<String,Object>> history(int requestedLimit, Long actor) {
        int limit=Math.max(1,Math.min(100,requestedLimit));
        return jdbc.queryForList("SELECT id AS reviewId,status,created_at AS createdAt,completed_at AS completedAt FROM movie_duplicate_review WHERE actor_id=? ORDER BY created_at DESC LIMIT ?",actor,limit);
    }

    private Schema schema() {
        var data=jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,COLLATION_NAME,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION");
        Map<String,Set<String>> columns=new HashMap<>(); List<Map<String,Object>> blockers=new ArrayList<>();
        for(var row:data) columns.computeIfAbsent(text(row.get("TABLE_NAME")),ignored->new HashSet<>()).add(text(row.get("COLUMN_NAME")));
        Set<String> known=new HashSet<>(REFERENCES.stream().map(Reference::table).toList());
        for(var entry:columns.entrySet()) if(entry.getValue().contains("movie_id") && !known.contains(entry.getKey())) blockers.add(block("UNKNOWN_REFERENCE",entry.getKey(),1));
        Map<String,Set<String>> required=new LinkedHashMap<>();
        for(Reference ref:REFERENCES) required.put(ref.table(),new HashSet<>(Set.of("id",ref.column())));
        required.get("movie_source_identity").addAll(Set.of("source","source_type","external_id","season","match_status"));
        required.get("resource_hub_task").addAll(Set.of("payload","status")); required.get("user_favorite").addAll(Set.of("user_id","created_at"));
        required.get("resource_link").addAll(Set.of("status","deleted_at","audit_status","link_status","source","type","provider","url","url_hash","validated_at","last_check_error"));
        required.get("quark_transfer_task").addAll(Set.of("status","finished_at","saved_path","last_error","share_url","share_url_hash","original_url","discovery_result_id","request_payload","response_payload"));
        required.get("resource_discovery_result").addAll(Set.of("provider","share_url","share_url_hash","original_url","resource_link_id","resource_type","status","failure_reason","source","source_ref"));
        required.get("comment").add("comment_type"); required.get("user_notification").add("target_type");
        required.get("baidu_transfer_task").addAll(Set.of("scope_key","original_url_hash","status"));
        required.put("movie_metadata",Set.of("id","status","deleted_at","updated_at","title_cn","title_en","year","category","season","tmdb_id","tmdb_type","actors","directors","aliases","series_name","resource_status"));
        required.put("movie_duplicate_review",Set.of("id","actor_id","plan_json","fingerprint","eligible","status","idempotency_key","result_json","created_at","expires_at","completed_at"));
        required.put("movie_merge_alias",Set.of("source_movie_id","canonical_movie_id","review_id","actor_id","created_at"));
        required.put("movie_merge_favorite_history",Set.of("review_id","original_favorite_id","user_id","original_movie_id","canonical_movie_id","original_created_at","retained_favorite_id","recorded_at"));
        required.put("movie_merge_reference_history",Set.of("review_id","reference_table","reference_row_id","original_movie_id","canonical_movie_id","original_fingerprint","recorded_at"));
        for(var entry:required.entrySet()) if(!columns.getOrDefault(entry.getKey(),Set.of()).containsAll(entry.getValue())) blockers.add(block("SCHEMA_NOT_READY",entry.getKey(),1));
        for(var row:jdbc.queryForList("SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"))
            if(required.containsKey(text(row.get("TABLE_NAME"))) && !"InnoDB".equalsIgnoreCase(text(row.get("ENGINE")))) blockers.add(block("SCHEMA_NOT_READY",text(row.get("TABLE_NAME")),1));
        validateReviewSchema(data,blockers);
        GuardMetadata metadata=guardMetadata(blockers);
        var triggers=metadata.triggers();
        Map<String,Map<String,Object>> actual=new HashMap<>(); for(var trigger:triggers) actual.put(text(trigger.get("TRIGGER_NAME")),trigger);
        List<TriggerDefinition> expected=triggerDefinitions(); Set<String> names=new HashSet<>();
        for(var definition:expected) {
            names.add(definition.name()); var found=actual.get(definition.name());
            if(found==null || !definition.table().equals(found.get("EVENT_OBJECT_TABLE")) || !definition.event().equals(found.get("EVENT_MANIPULATION"))
                || !"BEFORE".equals(found.get("ACTION_TIMING")) || !normalize(definition.body()).equals(normalize(text(found.get("ACTION_STATEMENT")))))
                blockers.add(block("SCHEMA_NOT_READY",definition.name(),1));
        }
        for(var trigger:triggers) if(required.containsKey(text(trigger.get("EVENT_OBJECT_TABLE"))) && "BEFORE".equals(trigger.get("ACTION_TIMING")) && !names.contains(text(trigger.get("TRIGGER_NAME"))))
            blockers.add(block("UNKNOWN_REFERENCE",text(trigger.get("TRIGGER_NAME")),1));
        return new Schema(columns,blockers,hash(List.of(data,triggers,metadata.routine())));
    }

    private GuardMetadata guardMetadata(List<Map<String,Object>> blockers) {
        List<Map<String,Object>> routine=List.of();
        try {
            routine=jdbc.queryForList("SELECT ROUTINE_NAME,ROUTINE_TYPE,SQL_DATA_ACCESS,SECURITY_TYPE,IS_DETERMINISTIC,DEFINER,ROUTINE_DEFINITION,ROUTINE_COMMENT,SQL_MODE,CHARACTER_SET_CLIENT,COLLATION_CONNECTION,DATABASE_COLLATION FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME=?",GUARD_METADATA_ROUTINE);
            Long parameters=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.PARAMETERS WHERE SPECIFIC_SCHEMA=DATABASE() AND SPECIFIC_NAME=?",Long.class,GUARD_METADATA_ROUTINE);
            var found=routine.size()==1?routine.get(0):Map.<String,Object>of();
            String definer=text(found.get("DEFINER")); int separator=definer.lastIndexOf('@');
            boolean validDefiner=separator>0 && separator<definer.length()-1 && definer.codePoints().noneMatch(Character::isISOControl);
            // Reject an already modified routine before CALL. DBA routine DDL must be serialized with merge operations.
            if(!"PROCEDURE".equals(found.get("ROUTINE_TYPE")) || !"READS SQL DATA".equals(found.get("SQL_DATA_ACCESS"))
                || !"DEFINER".equals(found.get("SECURITY_TYPE")) || !"NO".equals(found.get("IS_DETERMINISTIC")) || !validDefiner
                || !Long.valueOf(0).equals(parameters) || !guardMetadataDefinition().body().equals(found.get("ROUTINE_DEFINITION"))) {
                blockers.add(block("SCHEMA_NOT_READY",GUARD_METADATA_ROUTINE,1));
                return new GuardMetadata(List.of(),routine);
            }
            return new GuardMetadata(jdbc.queryForList("CALL "+GUARD_METADATA_ROUTINE+"()"),routine);
        } catch(DataAccessException error) {
            blockers.add(block("SCHEMA_NOT_READY",GUARD_METADATA_ROUTINE,1));
            return new GuardMetadata(List.of(),routine);
        }
    }

    private void validateReviewSchema(List<Map<String,Object>> data,List<Map<String,Object>> blockers) {
        try {
            String ddl=new ClassPathResource("db/migration_movie_duplicate_merge.sql").getContentAsString(StandardCharsets.UTF_8);
            var tables=Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\(([\\s\\S]*?)\\) ENGINE",Pattern.DOTALL).matcher(ddl);
            Map<String,Map<String,Object>> actual=new HashMap<>();
            for(var row:data) actual.put(text(row.get("TABLE_NAME"))+"."+text(row.get("COLUMN_NAME")),row);
            while(tables.find()) {
                String table=tables.group(1),body=tables.group(2);
                var lines=Pattern.compile("^\\s*(\\w+)\\s+(VARCHAR|CHAR|LONGTEXT|BIGINT|BOOLEAN|DATETIME)(\\([0-9]+\\))?(.*)$",Pattern.MULTILINE).matcher(body);
                while(lines.find()) {
                    String name=lines.group(1),kind=lines.group(2),width=lines.group(3)==null?"":lines.group(3),tail=lines.group(4);
                    String type=kind.equals("BOOLEAN")?"tinyint(1)":kind.toLowerCase(Locale.ROOT)+width;
                    String collation=Set.of("VARCHAR","CHAR","LONGTEXT").contains(kind)?tail.contains("COLLATE ascii_bin")?"ascii_bin":"utf8mb4_bin":null;
                    String nullable=tail.contains("NOT NULL")||tail.contains("PRIMARY KEY")?"NO":"YES";
                    var found=actual.get(table+"."+name);
                    if(found==null || !type.equals(text(found.get("COLUMN_TYPE"))) || !Objects.equals(collation,found.get("COLLATION_NAME")) || !nullable.equals(found.get("IS_NULLABLE")))
                        blockers.add(block("SCHEMA_NOT_READY",table+"."+name,1));
                }
            }
            Map<String,List<String>> expected=Map.of(
                "movie_duplicate_review.PRIMARY",List.of("id"),
                "movie_duplicate_review.uk_duplicate_actor_key",List.of("actor_id","idempotency_key"),
                "movie_merge_alias.PRIMARY",List.of("source_movie_id"),
                "movie_merge_favorite_history.PRIMARY",List.of("review_id","original_favorite_id"),
                "movie_merge_reference_history.PRIMARY",List.of("review_id","reference_table","reference_row_id"));
            var indexes=jdbc.queryForList("SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME,SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('movie_duplicate_review','movie_merge_alias','movie_merge_favorite_history','movie_merge_reference_history') AND NON_UNIQUE=0 ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX");
            Map<String,List<String>> keys=new HashMap<>();
            for(var index:indexes) {
                String name=text(index.get("TABLE_NAME"))+"."+text(index.get("INDEX_NAME"));
                keys.computeIfAbsent(name,ignored->new ArrayList<>()).add(text(index.get("COLUMN_NAME")));
                if(index.get("SUB_PART")!=null) blockers.add(block("SCHEMA_NOT_READY",name,1));
            }
            if(!expected.equals(keys)) blockers.add(block("SCHEMA_NOT_READY","movie_merge_unique_constraints",1));
        } catch(java.io.IOException error) { throw new IllegalStateException("Missing review schema definition",error); }
    }

    private Snapshot snapshot(Pair pair, Schema schema, boolean locking) {
        List<Map<String,Object>> blockers=new ArrayList<>(schema.blockers()); Map<String,List<Map<String,Object>>> all=new LinkedHashMap<>();
        List<Map<String,Object>> movies=jdbc.queryForList("SELECT * FROM movie_metadata WHERE id IN (?,?) AND BINARY id IN (?,?) ORDER BY BINARY id"+(locking?" FOR UPDATE":""),pair.fromMovieId(),pair.toMovieId(),pair.fromMovieId(),pair.toMovieId());
        all.put("movie_metadata",movies); var source=find(movies,"id",pair.fromMovieId()); var target=find(movies,"id",pair.toMovieId());
        if(source==null || target==null || !active(source) || !active(target)) blockers.add(block("MOVIE_NOT_ACTIVE","movie_metadata",1));
        var aliases=jdbc.queryForList("SELECT * FROM movie_merge_alias WHERE source_movie_id IN (?,?) OR canonical_movie_id IN (?,?) ORDER BY source_movie_id"+(locking?" FOR UPDATE":""),pair.fromMovieId(),pair.toMovieId(),pair.fromMovieId(),pair.toMovieId());
        all.put("movie_merge_alias",aliases);
        if(aliases.stream().anyMatch(row -> pair.fromMovieId().equals(row.get("source_movie_id")) || pair.toMovieId().equals(row.get("source_movie_id")))) blockers.add(block("ALREADY_MERGED","movie_merge_alias",1));
        if(source!=null && target!=null && active(source) && active(target)) checkIdentity(source,target,blockers);
        List<Map<String,Object>> impact=new ArrayList<>();
        for(Reference ref:REFERENCES) {
            if(!schema.columns().getOrDefault(ref.table(),Set.of()).containsAll(Set.of("id",ref.column()))) continue;
            if(ref.table().equals("comment") && !schema.columns().get(ref.table()).contains("comment_type")) continue;
            if(ref.table().equals("user_notification") && !schema.columns().get(ref.table()).contains("target_type")) continue;
            long neighbours=jdbc.queryForObject("SELECT COUNT(*) FROM "+ref.table()+" WHERE "+ref.column()+" IN (?,?) AND BINARY "+ref.column()+" NOT IN (?,?)"+ref.filter(),Long.class,
                pair.fromMovieId(),pair.toMovieId(),pair.fromMovieId(),pair.toMovieId());
            if(neighbours>0) blockers.add(block("REFERENCE_KEY_CONFLICT",ref.table(),neighbours));
            String predicate="("+ref.column()+" IN (?,?) AND BINARY "+ref.column()+" IN (?,?))";
            List<Object> arguments=new ArrayList<>(List.of(pair.fromMovieId(),pair.toMovieId(),pair.fromMovieId(),pair.toMovieId()));
            if(ref.table().equals("resource_hub_task") && schema.columns().get(ref.table()).contains("payload")) {
                predicate="("+predicate+" OR INSTR(BINARY IF(JSON_VALID(payload),JSON_REMOVE(payload,'$.deferredItems'),payload),BINARY ?)>0 OR INSTR(BINARY IF(JSON_VALID(payload),JSON_REMOVE(payload,'$.deferredItems'),payload),BINARY ?)>0)";
                arguments.add(write(pair.fromMovieId())); arguments.add(write(pair.toMovieId()));
            }
            String query="SELECT * FROM "+ref.table()+" WHERE "+predicate+ref.filter()+" ORDER BY id LIMIT "+(MAX_REFERENCE_ROWS+1)+(locking?" FOR UPDATE":"");
            List<Map<String,Object>> rows=jdbc.queryForList(query,arguments.toArray()); all.put(ref.table(),rows);
            if(rows.size()>MAX_REFERENCE_ROWS) blockers.add(block("REFERENCE_LIMIT",ref.table(),rows.size()));
            long from=rows.stream().filter(row -> pair.fromMovieId().equals(text(row.get(ref.column())))).count();
            long to=rows.stream().filter(row -> pair.toMovieId().equals(text(row.get(ref.column())))).count();
            impact.add(Map.of("reference",ref.table(),"fromCount",from,"toCount",to,"action",ref.action()));
        }
        for(Reference ref:REFERENCES) checkReferences(pair,ref,all.getOrDefault(ref.table(),List.of()),all,blockers);
        TmdbBinding binding=plannedTmdbBinding(pair,source,target,all.getOrDefault("movie_source_identity",List.of()),blockers);
        if(binding!=null) checkTmdbBindingOwners(pair,binding,all,blockers,locking);
        impact.add(Map.of("reference","movie_metadata","fromCount",source==null?0:1,"toCount",target==null?0:1,"action","PRESERVE_SOURCE_WITH_ALIAS"));
        Map<String,Object> item=new LinkedHashMap<>(); item.put("fromMovieId",pair.fromMovieId()); item.put("toMovieId",pair.toMovieId());
        item.put("fromTitle",title(source,pair.fromMovieId())); item.put("toTitle",title(target,pair.toMovieId()));
        item.put("eligible",blockers.isEmpty()); item.put("blockers",blockers); item.put("impact",impact);
        if(binding!=null) item.put("tmdbBinding",Map.of("tmdbId",binding.tmdbId(),"tmdbType",binding.tmdbType(),"season",binding.season(),
            "sourceIdentityId",binding.sourceIdentityId(),"gyingIdentityId",binding.gyingIdentityId(),"changesMetadata",binding.changesMetadata()));
        return new Snapshot(item,all,hash(List.of(schema.fingerprint(),all)),binding);
    }

    static TmdbBinding plannedTmdbBinding(Pair pair, Map<String,Object> source, Map<String,Object> target,
            List<Map<String,Object>> identities, List<Map<String,Object>> blockers) {
        if(source==null || target==null || !active(source) || !active(target)) return null;
        Long tmdbId=number(source.get("tmdb_id")); String type=text(source.get("tmdb_type"));
        if(tmdbId==null || tmdbId<=0 || !Set.of("movie","tv").contains(type)) return null;
        Long sourceSeason="movie".equals(type)?Long.valueOf(0):number(source.get("season"));
        Long targetSeason="movie".equals(type)?Long.valueOf(0):number(target.get("season"));
        if(sourceSeason==null || targetSeason==null || sourceSeason<0 || sourceSeason>Integer.MAX_VALUE
            || !sourceSeason.equals(targetSeason) || ("tv".equals(type) && sourceSeason==0)) return null;
        String gyingType=text(target.get("category"));
        if(!Set.of("mv","tv","ac").contains(gyingType) || ("tv".equals(type) && "mv".equals(gyingType))
            || ("movie".equals(type) && "tv".equals(gyingType))) return null;
        // 使用真实来源记录和已确认的同季 TMDB 身份，不依据本地 ID 前缀推断来源。
        var gying=identities.stream().filter(row -> pair.toMovieId().equals(row.get("movie_id")) && "GYING".equals(row.get("source"))
            && Set.of("AUTO","CONFIRMED").contains(text(row.get("match_status"))) && gyingType.equals(row.get("source_type"))
            && text(row.get("external_id")).matches("[A-Za-z0-9]{1,100}") && sourceSeason.equals(number(row.get("season")))
            && row.get("id")!=null).findFirst().orElse(null);
        if(gying==null) return null;
        var confirmed=identities.stream().filter(row -> pair.fromMovieId().equals(row.get("movie_id"))
            && "TMDB".equals(row.get("source")) && "CONFIRMED".equals(row.get("match_status"))).toList();
        if(confirmed.isEmpty()) return null;
        if(confirmed.stream().anyMatch(row -> !type.equals(row.get("source_type")) || !String.valueOf(tmdbId).equals(row.get("external_id"))
            || !sourceSeason.equals(number(row.get("season"))) || row.get("id")==null)) {
            blockers.add(block("TMDB_BINDING_IDENTITY_CONFLICT","movie_source_identity",1)); return null;
        }
        var retainedConfirmed=identities.stream().filter(row -> pair.toMovieId().equals(row.get("movie_id"))
            && "TMDB".equals(row.get("source")) && "CONFIRMED".equals(row.get("match_status"))).toList();
        if(retainedConfirmed.stream().anyMatch(row -> !type.equals(row.get("source_type")) || !String.valueOf(tmdbId).equals(row.get("external_id"))
            || !sourceSeason.equals(number(row.get("season"))))) {
            blockers.add(block("TMDB_BINDING_IDENTITY_CONFLICT","movie_source_identity",1)); return null;
        }
        if((target.get("tmdb_id")!=null && !tmdbId.equals(number(target.get("tmdb_id"))))
            || (!text(target.get("tmdb_type")).isBlank() && !type.equals(target.get("tmdb_type")))) {
            blockers.add(block("TMDB_BINDING_METADATA_CONFLICT","movie_metadata",1)); return null;
        }
        boolean changes=!tmdbId.equals(number(target.get("tmdb_id"))) || !type.equals(target.get("tmdb_type"));
        return new TmdbBinding(tmdbId,type,sourceSeason.intValue(),confirmed.get(0).get("id"),gying.get("id"),changes);
    }

    private void checkTmdbBindingOwners(Pair pair, TmdbBinding binding, Map<String,List<Map<String,Object>>> all,
            List<Map<String,Object>> blockers, boolean locking) {
        String ending=" ORDER BY BINARY id LIMIT "+(MAX_REFERENCE_ROWS+1)+(locking?" FOR UPDATE":"");
        String season="tv".equals(binding.tmdbType())?" AND season=?":"";
        List<Object> arguments=new ArrayList<>(List.of(binding.tmdbType(),binding.tmdbId(),pair.fromMovieId(),pair.toMovieId()));
        if(!season.isEmpty()) arguments.add(binding.season());
        var owners=jdbc.queryForList("SELECT * FROM movie_metadata WHERE tmdb_type=? AND tmdb_id=? AND BINARY id NOT IN (?,?) AND status='ACTIVE' AND deleted_at IS NULL"+season+ending,arguments.toArray());
        all.put("tmdb_binding_metadata_owners",owners);
        var identityRows=jdbc.queryForList("SELECT * FROM movie_source_identity WHERE source='TMDB' AND source_type=? AND external_id=? AND season=? ORDER BY id LIMIT "+(MAX_REFERENCE_ROWS+1)+(locking?" FOR UPDATE":""),
            binding.tmdbType(),String.valueOf(binding.tmdbId()),binding.season());
        all.put("tmdb_binding_identity_owners",identityRows);
        var identityMovies=jdbc.queryForList("SELECT m.* FROM movie_metadata m JOIN movie_source_identity i ON m.id=i.movie_id AND BINARY m.id=BINARY i.movie_id WHERE i.source='TMDB' AND i.source_type=? AND i.external_id=? AND i.season=? AND BINARY m.id NOT IN (?,?) AND m.status='ACTIVE' AND m.deleted_at IS NULL ORDER BY BINARY m.id LIMIT "+(MAX_REFERENCE_ROWS+1)+(locking?" FOR UPDATE":""),
            binding.tmdbType(),String.valueOf(binding.tmdbId()),binding.season(),pair.fromMovieId(),pair.toMovieId());
        all.put("tmdb_binding_identity_movies",identityMovies);
        if(!owners.isEmpty() || !identityMovies.isEmpty()) blockers.add(block("TMDB_BINDING_ACTIVE_OWNER_CONFLICT","movie_metadata",owners.size()+identityMovies.size()));
        if(identityRows.size()>MAX_REFERENCE_ROWS) blockers.add(block("REFERENCE_LIMIT","movie_source_identity",identityRows.size()));
    }

    private void checkIdentity(Map<String,Object> source, Map<String,Object> target, List<Map<String,Object>> blockers) {
        MovieMetadata first=movie(source), second=movie(target);
        if(MovieDuplicateMatcher.find(List.of(first,second)).pairs().isEmpty()) blockers.add(block("NOT_DUPLICATE_CANDIDATE","movie_metadata",1));
        Set<String> series=new HashSet<>();
        for(String name:List.of(text(first.getSeriesName()),text(second.getSeriesName()))) { series.add(compactName(name)); series.add(compactSeriesBase(name)); }
        first.setAliases(withoutSeriesAliases(first.getAliases(),series)); second.setAliases(withoutSeriesAliases(second.getAliases(),series));
        first.setSeriesName(null); second.setSeriesName(null);
        if(MovieDuplicateMatcher.find(List.of(first,second)).pairs().isEmpty()) blockers.add(block("SERIES_ONLY_MATCH","movie_metadata",1));
        if(partConflict(source,target)) blockers.add(block("PART_NUMBER_CONFLICT","movie_metadata",1));
        if(source.get("tmdb_id")!=null && target.get("tmdb_id")!=null && !Objects.equals(number(source.get("tmdb_id")),number(target.get("tmdb_id"))))
            blockers.add(block("TMDB_ID_CONFLICT","movie_metadata",1));
    }

    private String withoutSeriesAliases(String value,Set<String> series) {
        List<String> aliases=new ArrayList<>();
        if(value!=null && value.trim().startsWith("[")) {
            try { JsonNode node=json.readTree(value); if(node.isArray()) for(JsonNode item:node) if(item.isTextual()) aliases.add(item.asText()); }
            catch(Exception ignored) { return null; }
        } else if(value!=null) aliases.addAll(Arrays.asList(value.split("[/|;；、\\r\\n]+")));
        return write(aliases.stream().filter(alias -> !series.contains(compactName(alias)) && !series.contains(compactSeriesBase(alias))).toList());
    }
    private static String compactSeriesBase(String value) {
        return compactName(com.gying.movie.utils.SeasonSearchUtils.baseTitle(java.text.Normalizer.normalize(text(value),java.text.Normalizer.Form.NFKC)));
    }
    private static String compactName(String value) {
        return java.text.Normalizer.normalize(text(value),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replace('’', '\'').replaceAll("[\\s\\p{Z}]+", "");
    }

    private static boolean partConflict(Map<String,Object> source, Map<String,Object> target) {
        if("tv".equals(source.get("category"))) return false;
        Pattern suffix=Pattern.compile("^(.*?)([1-9][0-9]*|[ivx]+)$",Pattern.CASE_INSENSITIVE);
        for(String key:List.of("title_cn","title_en")) {
            String a=java.text.Normalizer.normalize(text(source.get(key)),java.text.Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
            String b=java.text.Normalizer.normalize(text(target.get(key)),java.text.Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
            var left=suffix.matcher(a); var right=suffix.matcher(b);
            if(left.matches() && right.matches() && !left.group(1).isBlank()
                && left.group(1).trim().equals(right.group(1).trim()) && !left.group(2).equals(right.group(2))) return true;
        }
        return false;
    }

    private void checkReferences(Pair pair, Reference ref, List<Map<String,Object>> rows, Map<String,List<Map<String,Object>>> all, List<Map<String,Object>> blockers) {
        long pending=0, temporary=0, invalid=0;
        for(var row:rows) {
            boolean completedQuark=ref.table().equals("quark_transfer_task") && completedQuarkTransfer(row,all);
            if(TASK_TABLES.contains(ref.table()) && ((!TERMINAL.contains(text(row.get("status")).toUpperCase(Locale.ROOT)) && !completedQuark) || uncertain(row))) pending++;
            if((ref.table().equals("resource_link") && "BAIDU_TEMP".equalsIgnoreCase(text(row.get("source"))))
                || (ref.table().equals("resource_discovery_result") && text(row.get("source_ref")).startsWith("BAIDU_TEMP:"))
                || (ref.table().equals("baidu_transfer_task") && !"PERMANENT".equals(row.get("scope_key")))
                || ref.table().equals("baidu_temporary_cleanup_job") || ref.table().equals("qq_transfer_cleanup_job")) temporary++;
            if(ref.table().equals("resource_hub_task") && row.get("payload")!=null) {
                try {
                    JsonNode payload=json.readTree(text(row.get("payload")));
                    if(payload==null || !payload.isObject()) { invalid++; continue; }
                    ObjectNode safe=payload.deepCopy(); safe.remove(List.of("movieId","deferredItems")); JsonNode id=payload.get("movieId");
                    // P2P mid names the upstream GYING identity. Only retained-target history is safe under the current SQL guards.
                    if(retainedP2pIdentity(pair,row,payload)) safe.remove("mid");
                    if(containsId(safe,pair.fromMovieId()) || containsId(safe,pair.toMovieId())) invalid++;
                    if(id!=null && !id.isNull() && !id.isTextual()) invalid++;
                    if(row.get("movie_id")!=null && id!=null && id.isTextual() && !Objects.equals(text(row.get("movie_id")),id.textValue())) invalid++;
                } catch(Exception error) { invalid++; }
            }
        }
        if(pending>0) blockers.add(block("ACTIVE_OR_UNKNOWN_TASK",ref.table(),pending));
        if(temporary>0) blockers.add(block("TEMPORARY_SCOPE",ref.table(),temporary));
        if(invalid>0) blockers.add(block("TASK_PAYLOAD_INVALID",ref.table(),invalid));
        if(ref.table().equals("movie_source_identity")) {
            for(var from:rows) if(pair.fromMovieId().equals(from.get("movie_id")) && "CONFIRMED".equals(from.get("match_status")))
                for(var to:rows) if(pair.toMovieId().equals(to.get("movie_id")) && "CONFIRMED".equals(to.get("match_status"))
                    && Objects.equals(from.get("source"),to.get("source")) && Objects.equals(from.get("source_type"),to.get("source_type"))
                    && Objects.equals(from.get("season"),to.get("season")) && !Objects.equals(from.get("external_id"),to.get("external_id"))) {
                        blockers.add(block("SOURCE_IDENTITY_CONFLICT",ref.table(),1)); return;
                    }
        }
        if(ref.table().equals("baidu_transfer_task")) {
            Set<String> targetKeys=new HashSet<>(); for(var row:rows) if(pair.toMovieId().equals(row.get("movie_id"))) targetKeys.add(text(row.get("original_url_hash"))+"\u0000"+text(row.get("scope_key")));
            long conflicts=rows.stream().filter(row -> pair.fromMovieId().equals(row.get("movie_id")) && targetKeys.contains(text(row.get("original_url_hash"))+"\u0000"+text(row.get("scope_key")))).count();
            if(conflicts>0) blockers.add(block("TRANSFER_KEY_CONFLICT",ref.table(),conflicts));
        }
    }

    private boolean completedQuarkTransfer(Map<String,Object> task, Map<String,List<Map<String,Object>>> all) {
        if(!"SUBMITTED".equals(task.get("status")) || dateTime(task.get("finished_at"))==null
            || text(task.get("saved_path")).isBlank() || !text(task.get("last_error")).isBlank()
            || QqTransferMarker.isTemporary(text(task.get("request_payload")))) return false;
        String share=text(task.get("share_url")),shareHash=text(task.get("share_url_hash")),original=text(task.get("original_url"));
        if(!share.matches("https://pan\\.quark\\.cn/s/[A-Za-z0-9_-]{1,100}(?:\\?pwd=[A-Za-z0-9]{1,20})?")
            || !ResourceHubHashUtils.sha256(share).equals(shareHash) || original.isBlank()) return false;
        try {
            JsonNode request=json.readTree(text(task.get("request_payload"))),response=json.readTree(text(task.get("response_payload")));
            if(request==null || !request.isObject() || uncertainNode(request)
                || response==null || !response.isObject() || uncertainNode(response)) return false;
            java.net.URI source=java.net.URI.create(original),owned=java.net.URI.create(share);
            if(!"https".equals(source.getScheme()) || !"pan.quark.cn".equalsIgnoreCase(source.getHost())
                || source.getUserInfo()!=null || source.getPort()!=-1 || source.getPath()==null) return false;
            var sourcePath=Pattern.compile("/s/([A-Za-z0-9_-]{1,100})/?").matcher(source.getPath());
            if(!sourcePath.matches() || owned.getPath().equals("/s/"+sourcePath.group(1))) return false;
        } catch(Exception invalid) { return false; }
        String movieId=text(task.get("movie_id"));
        var discovery=find(all.getOrDefault("resource_discovery_result",List.of()),"id",text(task.get("discovery_result_id")));
        if(discovery==null || !movieId.equals(text(discovery.get("movie_id"))) || !"QUARK".equals(discovery.get("provider"))
            || !"DISK".equals(discovery.get("resource_type")) || !Set.of("DISCOVERED","SAVED","DUPLICATE").contains(text(discovery.get("status")))
            || "QQ_BOT".equalsIgnoreCase(text(discovery.get("source"))) || text(discovery.get("source_ref")).startsWith("BAIDU_TEMP:")
            || !text(discovery.get("failure_reason")).isBlank() || !share.equals(discovery.get("share_url"))
            || !shareHash.equals(discovery.get("share_url_hash")) || !original.equals(discovery.get("original_url"))) return false;
        var link=find(all.getOrDefault("resource_link",List.of()),"id",text(discovery.get("resource_link_id")));
        if(link==null || !movieId.equals(text(link.get("movie_id"))) || !"QUARK".equals(link.get("provider")) || !"DISK".equals(link.get("type"))
            || !"ACTIVE".equals(link.get("status")) || link.get("deleted_at")!=null || !"1".equals(text(link.get("audit_status")))
            || !"NORMAL".equals(link.get("link_status")) || Set.of("BAIDU_TEMP","QQ_BOT").contains(text(link.get("source")).toUpperCase(Locale.ROOT))
            || !text(link.get("last_check_error")).isBlank() || !share.equals(link.get("url")) || !shareHash.equals(link.get("url_hash"))) return false;
        LocalDateTime verified=dateTime(link.get("validated_at"));
        return verified!=null && !verified.isBefore(dateTime(task.get("finished_at")));
    }

    private static boolean retainedP2pIdentity(Pair pair, Map<String,Object> row, JsonNode payload) {
        JsonNode version=payload.get("version"),mid=payload.get("mid"),type=payload.get("typeCode");
        return "P2P_ARCHIVE".equals(row.get("task_type")) && TERMINAL.contains(text(row.get("status")).toUpperCase(Locale.ROOT))
            && Set.of("QUARK","XUNLEI","BAIDU").contains(text(row.get("source")))
            && pair.toMovieId().equals(text(row.get("movie_id"))) && version!=null && version.isIntegralNumber()
            && (version.intValue()==1 || version.intValue()==2) && version.canConvertToInt()
            && type!=null && type.isTextual() && Set.of("mv","tv","ac").contains(type.textValue())
            && mid!=null && mid.isTextual() && mid.textValue().matches("[A-Za-z0-9]{1,100}") && pair.toMovieId().equals(mid.textValue());
    }

    private static LocalDateTime dateTime(Object value) {
        if(value instanceof LocalDateTime local) return local;
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    private void apply(Pair pair, Snapshot state, String reviewId, Long actor) {
        for(Reference ref:REFERENCES) for(var row:state.rows().getOrDefault(ref.table(),List.of())) {
            boolean source=pair.fromMovieId().equals(text(row.get(ref.column())));
            Object payload=null;
            if(ref.table().equals("resource_hub_task") && row.get("payload")!=null) {
                ObjectNode parsed=(ObjectNode)readNode(text(row.get("payload")));
                if(parsed.has("movieId") && pair.fromMovieId().equals(parsed.path("movieId").asText())) { parsed.put("movieId",pair.toMovieId()); payload=write(parsed); }
            }
            if(!source && payload==null) continue;
            jdbc.update("INSERT INTO movie_merge_reference_history(review_id,reference_table,reference_row_id,original_movie_id,canonical_movie_id,original_fingerprint,recorded_at) VALUES(?,?,?,?,?,?,?)",
                reviewId,ref.table(),text(row.get("id")),pair.fromMovieId(),pair.toMovieId(),hash(row),utc(Instant.now()));
            if(ref.table().equals("user_favorite")) {
                var retained=jdbc.queryForList("SELECT id FROM user_favorite WHERE user_id=? AND movie_id=? AND BINARY movie_id=BINARY ? FOR UPDATE",row.get("user_id"),pair.toMovieId(),pair.toMovieId());
                if(!retained.isEmpty()) {
                    jdbc.update("INSERT INTO movie_merge_favorite_history(review_id,original_favorite_id,user_id,original_movie_id,canonical_movie_id,original_created_at,retained_favorite_id,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                        reviewId,row.get("id"),row.get("user_id"),pair.fromMovieId(),pair.toMovieId(),row.get("created_at"),retained.get(0).get("id"),utc(Instant.now()));
                    if(jdbc.update("DELETE FROM user_favorite WHERE id=? AND BINARY movie_id=BINARY ?",row.get("id"),pair.fromMovieId())!=1) throw conflict("Favorite changed");
                    continue;
                }
            }
            int changed;
            if(payload!=null) {
                changed=jdbc.update("UPDATE resource_hub_task SET movie_id=?,payload=?,updated_at=updated_at WHERE id=?",source?pair.toMovieId():row.get(ref.column()),payload,row.get("id"));
            } else changed=jdbc.update("UPDATE "+ref.table()+" SET "+ref.column()+"=?"+(row.containsKey("updated_at")?",updated_at=updated_at":"")+" WHERE id=? AND BINARY "+ref.column()+"=BINARY ?"+ref.filter(),pair.toMovieId(),row.get("id"),pair.fromMovieId());
            if(changed!=1) throw conflict("Reference changed");
        }
        var originalSource=find(state.rows().get("movie_metadata"),"id",pair.fromMovieId());
        jdbc.update("INSERT INTO movie_merge_reference_history(review_id,reference_table,reference_row_id,original_movie_id,canonical_movie_id,original_fingerprint,recorded_at) VALUES(?,?,?,?,?,?,?)",
            reviewId,"movie_metadata",pair.fromMovieId(),pair.fromMovieId(),pair.toMovieId(),hash(originalSource),utc(Instant.now()));
        var originalTarget=find(state.rows().get("movie_metadata"),"id",pair.toMovieId());
        jdbc.update("INSERT INTO movie_merge_reference_history(review_id,reference_table,reference_row_id,original_movie_id,canonical_movie_id,original_fingerprint,recorded_at) VALUES(?,?,?,?,?,?,?)",
            reviewId,"movie_metadata",pair.toMovieId(),pair.toMovieId(),pair.toMovieId(),hash(originalTarget),utc(Instant.now()));
        TmdbBinding binding=state.binding();
        if(binding!=null && binding.changesMetadata() && jdbc.update("UPDATE movie_metadata SET tmdb_id=?,tmdb_type=?,updated_at=updated_at WHERE id=? AND BINARY id=BINARY ? AND status='ACTIVE' AND deleted_at IS NULL",
            binding.tmdbId(),binding.tmdbType(),pair.toMovieId(),pair.toMovieId())!=1) throw conflict("Canonical TMDB binding changed");
        long normal=jdbc.queryForObject("SELECT COUNT(*) FROM resource_link WHERE movie_id=? AND BINARY movie_id=BINARY ? AND status='ACTIVE' AND deleted_at IS NULL AND audit_status=1 AND type='DISK' AND (link_status IS NULL OR link_status='NORMAL') AND (source IS NULL OR source<>'BAIDU_TEMP')",Long.class,pair.toMovieId(),pair.toMovieId());
        boolean knownTrailer="TRAILER".equals(originalTarget.get("resource_status"))||"TRAILER".equals(originalSource.get("resource_status"));
        String resourceStatus=normal>0?"AVAILABLE":knownTrailer?"TRAILER":"UNKNOWN";
        if(!resourceStatus.equals(originalTarget.get("resource_status")) && jdbc.update("UPDATE movie_metadata SET resource_status=?,updated_at=updated_at WHERE id=? AND BINARY id=BINARY ? AND status='ACTIVE' AND deleted_at IS NULL",
            resourceStatus,pair.toMovieId(),pair.toMovieId())!=1) throw conflict("Canonical movie changed");
        // Keep the source row, original identity and all unrelated metadata. No resource or cloud object is deleted.
        if(jdbc.update("UPDATE movie_metadata SET status='DELETED',deleted_at=?,updated_at=? WHERE id=? AND BINARY id=BINARY ? AND status='ACTIVE' AND deleted_at IS NULL",
            utc(Instant.now()),utc(Instant.now()),pair.fromMovieId(),pair.fromMovieId())!=1) throw conflict("Source movie changed");
        for(var row:state.rows().getOrDefault("movie_merge_alias",List.of())) if(pair.fromMovieId().equals(row.get("canonical_movie_id"))) {
            jdbc.update("INSERT INTO movie_merge_reference_history(review_id,reference_table,reference_row_id,original_movie_id,canonical_movie_id,original_fingerprint,recorded_at) VALUES(?,?,?,?,?,?,?)",
                reviewId,"movie_merge_alias",row.get("source_movie_id"),pair.fromMovieId(),pair.toMovieId(),hash(row),utc(Instant.now()));
            jdbc.update("UPDATE movie_merge_alias SET canonical_movie_id=? WHERE source_movie_id=?",pair.toMovieId(),row.get("source_movie_id"));
        }
        jdbc.update("INSERT INTO movie_merge_alias(source_movie_id,canonical_movie_id,review_id,actor_id,created_at) VALUES(?,?,?,?,?)",pair.fromMovieId(),pair.toMovieId(),reviewId,actor,utc(Instant.now()));
    }

    public static List<TriggerDefinition> triggerDefinitions() {
        try {
            String sql=new ClassPathResource("db/migration_movie_duplicate_merge_triggers.sql").getContentAsString(StandardCharsets.UTF_8);
            List<TriggerDefinition> result=new ArrayList<>();
            Pattern pattern=Pattern.compile("CREATE TRIGGER (\\w+) BEFORE (INSERT|UPDATE) ON (\\w+)\\s+FOR EACH ROW\\s+(.*)",Pattern.DOTALL);
            for(String part:sql.split("\\$\\$")) { String statement=part.trim(); var matcher=pattern.matcher(statement); if(matcher.matches()) result.add(new TriggerDefinition(matcher.group(1),matcher.group(3),matcher.group(2),statement,matcher.group(4))); }
            if(result.size()!=34) throw new IllegalStateException("Incomplete canonical write guards"); return List.copyOf(result);
        } catch(java.io.IOException error) { throw new IllegalStateException("Missing canonical write guards",error); }
    }
    public static GuardMetadataDefinition guardMetadataDefinition() {
        try {
            String sql=new ClassPathResource("db/migration_movie_duplicate_merge_guard_metadata.sql").getContentAsString(StandardCharsets.UTF_8);
            var matcher=Pattern.compile("CREATE PROCEDURE movie_merge_guard_metadata\\(\\)\\s+READS SQL DATA\\s+SQL SECURITY DEFINER\\s+(SELECT[^\\r\\n]+);\\s*$").matcher(sql);
            if(!matcher.find()) throw new IllegalStateException("Incomplete canonical guard metadata routine");
            return new GuardMetadataDefinition(matcher.group(),matcher.group(1));
        } catch(java.io.IOException error) { throw new IllegalStateException("Missing canonical guard metadata routine",error); }
    }
    public static String normalize(String value) { return value.replace("`","").replaceAll("\\s+"," ").trim(); }
    private static List<Pair> validatePairs(List<Pair> pairs) {
        if(pairs==null || pairs.isEmpty() || pairs.size()>MAX_PAIRS) throw bad("Select between 1 and 20 reviewed pairs");
        Set<String> ids=new HashSet<>(); for(Pair pair:pairs) if(pair==null || !id(pair.fromMovieId()) || !id(pair.toMovieId()) || !ids.add(pair.fromMovieId()) || !ids.add(pair.toMovieId()))
            throw bad("Pairs must use distinct exact movie IDs; overlapping groups require separate reviews");
        return List.copyOf(pairs);
    }
    private static boolean id(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    private static boolean uuid(String value) { return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private static boolean active(Map<String,Object> row) { return "ACTIVE".equals(row.get("status")) && row.get("deleted_at")==null; }
    private static Map<String,Object> find(List<Map<String,Object>> rows,String key,String value) { return rows.stream().filter(row -> value.equals(text(row.get(key)))).findFirst().orElse(null); }
    private static Map<String,Object> block(String code,String reference,long count) { return Map.of("code",code,"reference",reference,"count",count); }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant,ZoneOffset.UTC); }
    private static String text(Object value) { return value==null?"":String.valueOf(value); }
    private static Long number(Object value) { return value instanceof Number number?number.longValue():null; }
    private static String title(Map<String,Object> row,String fallback) { if(row==null) return fallback; String cn=text(row.get("title_cn")); return !cn.isBlank()?cn:!text(row.get("title_en")).isBlank()?text(row.get("title_en")):fallback; }
    private boolean uncertain(Map<String,Object> row) {
        for(String key:List.of("phase","response_payload","payload","cleanup_evidence")) {
            Object value=row.get(key); if(value==null) continue;
            if(UNCERTAIN.contains(text(value).toUpperCase(Locale.ROOT))) return true;
            if(text(value).startsWith("{")) try { if(uncertainNode(json.readTree(text(value)))) return true; } catch(Exception ignored) { return true; }
        } return false;
    }
    private static boolean uncertainNode(JsonNode node) {
        if(node==null) return false;
        if(node.isObject()) { var fields=node.fields(); while(fields.hasNext()) { var field=fields.next(); if((field.getKey().equalsIgnoreCase("status") || field.getKey().equalsIgnoreCase("phase")) && field.getValue().isTextual() && UNCERTAIN.contains(field.getValue().asText().toUpperCase(Locale.ROOT))) return true;
            if(field.getKey().endsWith("Submitting") && field.getValue().asBoolean(false)) return true; if(uncertainNode(field.getValue())) return true; } }
        if(node.isArray()) for(var child:node) if(uncertainNode(child)) return true; return false;
    }
    private static boolean containsId(JsonNode node,String id) { if(node==null) return false; if(node.isTextual()) return id.equals(node.textValue()); if(node.isObject()) { var fields=node.fields(); while(fields.hasNext()) { var field=fields.next(); if(id.equals(field.getKey()) || containsId(field.getValue(),id)) return true; } } if(node.isArray()) for(var child:node) if(containsId(child,id)) return true; return false; }
    private MovieMetadata movie(Map<String,Object> row) {
        MovieMetadata movie=new MovieMetadata(); movie.setId(text(row.get("id"))); movie.setTitleCn(text(row.get("title_cn"))); movie.setTitleEn(text(row.get("title_en")));
        movie.setCategory(text(row.get("category"))); movie.setTmdbType(text(row.get("tmdb_type"))); movie.setSeriesName(text(row.get("series_name"))); movie.setAliases(text(row.get("aliases")));
        movie.setYear(row.get("year") instanceof Number value?value.intValue():null); movie.setSeason(row.get("season") instanceof Number value?value.intValue():null); movie.setStatus(text(row.get("status")));
        movie.setActors(people(row.get("actors"))); movie.setDirectors(people(row.get("directors"))); return movie;
    }
    private List<String> people(Object value) { if(value==null) return List.of(); try { return json.readValue(text(value),json.getTypeFactory().constructCollectionType(List.class,String.class)); } catch(Exception error) { return List.of(); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception error) { throw new IllegalStateException("Unable to serialize duplicate review",error); } }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(canonical(value)).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception error) { throw new IllegalStateException("Unable to fingerprint duplicate review",error); }
    }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) { Map<String,Object> sorted=new TreeMap<>(); map.forEach((key,item)->sorted.put(text(key),canonical(item))); return sorted; }
        if(value instanceof Collection<?> items) return items.stream().map(MovieDuplicateMergeService::canonical).toList();
        if(value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if(value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor) return value.toString(); return value;
    }
    private List<Pair> readPairs(String value) { try { return json.readValue(value,json.getTypeFactory().constructCollectionType(List.class,Pair.class)); } catch(Exception error) { throw conflict("Invalid review plan"); } }
    private Map<String,Object> readMap(String value) { try { return json.readValue(value,json.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class)); } catch(Exception error) { throw conflict("Invalid review result"); } }
    private JsonNode readNode(String value) { try { return json.readTree(value); } catch(Exception error) { throw conflict("Invalid task payload"); } }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
