package com.gying.movie.service.impl;

import jakarta.servlet.http.HttpServletRequest;
import com.gying.movie.security.ClientIpResolver;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MonitoringService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    public MonitoringService(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc=jdbc; this.redis=redis; }

    public void access(String path,String method,int status,long duration,HttpServletRequest request,String eventType) {
        try { jdbc.update("INSERT INTO site_access_log(request_path,method,status_code,visitor_hash,user_agent_hash,referer,duration_ms,event_type) VALUES (?,?,?,?,?,?,?,?)",
                safePath(path),cut(method,10),status,visitorHash(request),RegistrationService.sha256(header(request,"User-Agent")),safePath(header(request,"Referer")),duration,eventType); }
        catch(Exception ignored) { }
    }
    public void search(String keyword,long resultCount,HttpServletRequest request) {
        String normalized=normalizeKeyword(keyword); if(normalized==null)return;
        jdbc.update("INSERT INTO site_search_log(keyword,visitor_hash,source,result_count) VALUES (?,?,'WEB',?)",normalized,visitorHash(request),Math.max(0,Math.min(Integer.MAX_VALUE,resultCount)));
        redis.opsForZSet().incrementScore("hot:search:"+LocalDate.now(),normalized,1D);
        redis.expire("hot:search:"+LocalDate.now(),32,TimeUnit.DAYS);
    }
    public void resourceOperation(String movieId,Long resourceId,String type,String provider,String status,String error,HttpServletRequest request) {
        jdbc.update("INSERT INTO resource_operation_log(visitor_hash,movie_id,resource_link_id,operation_type,provider,status,error_message) VALUES (?,?,?,?,?,?,?)",
                visitorHash(request),cut(movieId,64),resourceId,cut(type,30),cut(provider,50),cut(status,30),cut(error,500));
    }
    public Map<String,Object> overview() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("date", LocalDate.now().toString());
        out.put("timeZone", "Asia/Shanghai");
        // Page-view beacons are stored alongside API calls, but are not API requests.
        out.putAll(jdbc.queryForMap("""
                SELECT COALESCE(SUM(event_type='API'),0) requestsToday,
                       COUNT(DISTINCT CASE WHEN event_type='PAGE_VIEW' THEN visitor_hash END) visitorsToday,
                       COALESCE(SUM(event_type='PAGE_VIEW'),0) pageViewsToday,
                       COALESCE(SUM(event_type='API' AND status_code>=400 AND status_code<500),0) clientErrorsToday,
                       COALESCE(SUM(event_type='API' AND status_code>=500),0) serverErrorsToday
                FROM site_access_log WHERE created_at>=CURDATE()
                """));
        out.putAll(jdbc.queryForMap("SELECT COUNT(*) searchesToday FROM site_search_log WHERE created_at>=CURDATE()"));
        out.putAll(jdbc.queryForMap("SELECT COUNT(*) resourceOperationsToday FROM resource_operation_log WHERE created_at>=CURDATE()"));
        out.putAll(jdbc.queryForMap("""
                SELECT COALESCE(SUM(status='POSTED'),0) socialPostedToday,
                       COALESCE(SUM(status IN ('FAILED','PREPARE_FAILED')),0) socialFailedToday,
                       COALESCE(SUM(status IN ('PENDING','PREPARING','PUBLISHING')),0) socialPendingToday,
                       COALESCE(SUM(status='UNKNOWN'),0) socialUnknownToday
                FROM social_post_log WHERE created_at>=CURDATE()
                """));
        out.put("traffic", jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at,'%Y-%m-%d') day,
                       COALESCE(SUM(event_type='API'),0) requests,
                       COUNT(DISTINCT CASE WHEN event_type='PAGE_VIEW' THEN visitor_hash END) visitors,
                       COALESCE(SUM(event_type='PAGE_VIEW'),0) pageViews,
                       COALESCE(SUM(event_type='API' AND status_code>=500),0) serverErrors
                FROM site_access_log WHERE created_at>=DATE_SUB(CURDATE(),INTERVAL 13 DAY)
                GROUP BY DATE_FORMAT(created_at,'%Y-%m-%d') ORDER BY day
                """));
        out.put("hotSearches", hotSearches(LocalDate.now(), 10));
        return out;
    }

    public List<Map<String,Object>> hotSearches(LocalDate date, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        try {
            var set = redis.opsForZSet().reverseRangeWithScores("hot:search:" + date, 0, safeLimit - 1L);
            if (set != null && !set.isEmpty()) {
                return set.stream().map(v -> Map.<String,Object>of("keyword", String.valueOf(v.getValue()),
                        "count", v.getScore() == null ? 0 : v.getScore().longValue())).toList();
            }
        } catch (Exception ignored) {
            // A cache outage must not hide the rest of the database-backed dashboard.
        }
        return jdbc.queryForList("""
                SELECT keyword,COUNT(*) count FROM site_search_log
                WHERE created_at>=? AND created_at<? GROUP BY keyword ORDER BY count DESC,keyword LIMIT ?
                """, java.sql.Date.valueOf(date), java.sql.Date.valueOf(date.plusDays(1)), safeLimit);
    }

    /** Aggregated hot search keywords for the public search box (recent days, Redis first, MySQL fallback). */
    public List<Map<String,Object>> hotKeywords(int days,int limit) {
        int safeDays=Math.min(Math.max(days,1),30);
        int safeLimit=Math.min(Math.max(limit,1),20);
        Map<String,Double> merged=new LinkedHashMap<>();
        try {
            LocalDate today=LocalDate.now();
            for(int offset=0;offset<safeDays;offset++){
                var tuples=redis.opsForZSet().reverseRangeWithScores("hot:search:"+today.minusDays(offset),0,49);
                if(tuples==null)continue;
                for(var tuple:tuples){
                    String keyword=tuple.getValue()==null?null:String.valueOf(tuple.getValue()).trim();
                    if(keyword==null||keyword.isBlank())continue;
                    merged.merge(keyword,tuple.getScore()==null?0D:tuple.getScore(),Double::sum);
                }
            }
        } catch(Exception ignored) { }
        if(merged.isEmpty()){
            try {
                jdbc.queryForList(
                        "SELECT keyword,COUNT(*) hits FROM site_search_log WHERE created_at>=? GROUP BY keyword ORDER BY hits DESC LIMIT ?",
                        java.sql.Date.valueOf(LocalDate.now().minusDays(safeDays-1L)),safeLimit)
                    .forEach(row->{
                        Object keyword=row.get("keyword");
                        if(keyword==null)return;
                        Object hits=row.get("hits");
                        merged.put(String.valueOf(keyword).trim(),hits instanceof Number number?number.doubleValue():0D);
                    });
            } catch(Exception ignored) { }
        }
        return merged.entrySet().stream()
                .filter(entry->!entry.getKey().isEmpty())
                .sorted((left,right)->Double.compare(right.getValue(),left.getValue()))
                .limit(safeLimit)
                .map(entry->Map.<String,Object>of("keyword",entry.getKey(),"count",entry.getValue().longValue()))
                .toList();
    }
    public Map<String,Object> logs(String q,int page,int size) {
        String term=q==null?"":q.trim(); int limit=Math.min(Math.max(size,1),100),offset=(Math.max(page,1)-1)*limit; String like="%"+term+"%";
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("access",jdbc.queryForList("SELECT id,request_path,method,status_code,duration_ms,event_type,created_at FROM site_access_log WHERE ?='' OR request_path LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?",term,like,limit,offset));
        out.put("searches",jdbc.queryForList("SELECT id,keyword,source,result_count,created_at FROM site_search_log WHERE ?='' OR keyword LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?",term,like,limit,offset));
        out.put("resourceOperations",jdbc.queryForList("SELECT id,movie_id,resource_link_id,operation_type,provider,status,error_message,created_at FROM resource_operation_log WHERE ?='' OR movie_id LIKE ? OR operation_type LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?",term,like,like,limit,offset));
        out.put("qqSearches",jdbc.queryForList("SELECT id,keyword,status,movie_id,resource_count,failure_reason,created_at FROM qq_bot_search_log WHERE ?='' OR keyword LIKE ? OR status LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?",term,like,like,limit,offset));
        out.put("socialPosts",jdbc.queryForList("SELECT id,platform,title,status,error_message,posted_at,created_at FROM social_post_log WHERE ?='' OR title LIKE ? OR status LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?",term,like,like,limit,offset));
        return out;
    }
    /** Fetch only the selected stream. The legacy multi-stream endpoint remains compatible. */
    public Map<String,Object> logPage(String type, String q, String status, int page, int size) {
        LogDefinition definition = logDefinition(type);
        String filter = status == null ? "all" : status.trim().toLowerCase(Locale.ROOT);
        String statusSql = statusCondition(type, filter);
        String term = q == null ? "" : q.trim();
        if (term.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search must be at most 100 characters");
        }
        int limit = Math.min(Math.max(size, 1), 100);
        List<Object> args = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        if (!term.isEmpty()) {
            String like = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            List<String> matches = new ArrayList<>();
            for (String column : definition.searchColumns()) {
                matches.add(column + " LIKE ? ESCAPE '!' ");
                args.add(like);
            }
            predicates.add("(" + String.join(" OR ", matches) + ")");
        }
        if (!statusSql.isEmpty()) predicates.add("(" + statusSql + ")");
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + definition.table() + where,
                Long.class, args.toArray());
        long total = count == null ? 0 : count;
        long pages = (total + limit - 1) / limit;
        int current = (int) Math.min(Math.max(page, 1), Math.max(pages, 1));
        List<Map<String,Object>> records = List.of();
        if (total > 0) {
            args.add(limit);
            args.add(((long) current - 1) * limit);
            records = jdbc.queryForList("SELECT " + definition.columns() + " FROM " + definition.table()
                    + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        }
        return Map.of("records", records, "total", total, "page", current, "size", limit, "pages", pages);
    }

    private record LogDefinition(String table, String columns, List<String> searchColumns) { }

    private static LogDefinition logDefinition(String type) {
        // SQL identifiers are entirely server-owned, never interpolated from request parameters.
        if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown log type");
        return switch (type) {
            case "access" -> new LogDefinition("site_access_log",
                    "id,request_path,method,status_code,duration_ms,event_type,created_at",
                    List.of("request_path", "method", "CAST(status_code AS CHAR)"));
            case "searches" -> new LogDefinition("site_search_log",
                    "id,keyword,source,result_count,created_at", List.of("keyword", "source"));
            case "resourceOperations" -> new LogDefinition("resource_operation_log",
                    "id,movie_id,resource_link_id,operation_type,provider,status,error_message,created_at",
                    List.of("movie_id", "CAST(resource_link_id AS CHAR)", "operation_type", "provider", "error_message"));
            case "qqSearches" -> new LogDefinition("qq_bot_search_log",
                    "id,keyword,status,movie_id,resource_count,failure_reason,created_at",
                    List.of("keyword", "status", "movie_id", "failure_reason"));
            case "socialPosts" -> new LogDefinition("social_post_log",
                    "id,platform,title,status,error_message,posted_at,created_at",
                    List.of("title", "status", "platform", "error_message"));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown log type");
        };
    }

    private static String statusCondition(String type, String status) {
        if ("all".equals(status)) return "";
        if ("access".equals(type)) {
            return switch (status) {
                case "success" -> "status_code>=200 AND status_code<400";
                case "failure" -> "status_code>=400";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported status filter");
            };
        }
        if ("searches".equals(type)) {
            return switch (status) {
                case "success" -> "result_count>0";
                case "failure" -> "result_count=0";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported status filter");
            };
        }
        if ("socialPosts".equals(type)) {
            return switch (status) {
                case "success" -> "status='POSTED'";
                case "failure" -> "status IN ('FAILED','PREPARE_FAILED')";
                case "pending" -> "status IN ('PENDING','PREPARING','PUBLISHING')";
                case "attention" -> "status='UNKNOWN'";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported status filter");
            };
        }
        return switch (status) {
            case "success" -> "status IN ('SUCCESS','SUCCEEDED','OK','SAVED','NORMAL','DONE','ACTIVE','PUBLISHED','LIBRARY_RESOURCE','RESOURCE_SELECTION')";
            case "failure" -> "status IN ('FAILED','FAIL','ERROR','REJECTED','DENIED','TIMEOUT','INVALID','NOT_FOUND','NO_RESULT','NO_METADATA','NO_RESOURCE','INVALID_RESOURCE','TRANSFER_FAILED','EXPIRED','RATE_LIMITED','UNSUPPORTED_PROVIDER')";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported status filter");
        };
    }

    public static String visitorHash(HttpServletRequest r){return RegistrationService.sha256(clientIp(r)+"|"+header(r,"User-Agent"));}
    private static String clientIp(HttpServletRequest r){return ClientIpResolver.resolved(r);}
    private static String header(HttpServletRequest r,String name){String v=r.getHeader(name);return v==null?"":v;}
    private static String normalizeKeyword(String s){if(s==null)return null;String v=s.trim().replaceAll("\\s+"," ");return v.length()<2||v.length()>100?null:v;}
    private static String safePath(String value) {
        if (value == null || value.isBlank()) return null;
        try { return cut(URI.create(value).getPath(), 500); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    private static String cut(String s,int n){if(s==null)return null;return s.length()>n?s.substring(0,n):s;}
}
