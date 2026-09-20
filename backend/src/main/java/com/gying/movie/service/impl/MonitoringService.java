package com.gying.movie.service.impl;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MonitoringService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    public MonitoringService(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc=jdbc; this.redis=redis; }

    public void access(String path,String method,int status,long duration,HttpServletRequest request,String eventType) {
        try { jdbc.update("INSERT INTO site_access_log(request_path,method,status_code,visitor_hash,user_agent_hash,referer,duration_ms,event_type) VALUES (?,?,?,?,?,?,?,?)",
                cut(path,500),cut(method,10),status,visitorHash(request),RegistrationService.sha256(header(request,"User-Agent")),cut(header(request,"Referer"),500),duration,eventType); }
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
        Map<String,Object> out=new LinkedHashMap<>();
        out.putAll(jdbc.queryForMap("SELECT COUNT(*) requestsToday, COUNT(DISTINCT visitor_hash) visitorsToday, SUM(event_type='PAGE_VIEW') pageViewsToday, SUM(status_code>=400 AND status_code<500) clientErrorsToday, SUM(status_code>=500) serverErrorsToday FROM site_access_log WHERE created_at>=CURDATE()"));
        out.putAll(jdbc.queryForMap("SELECT COUNT(*) searchesToday FROM site_search_log WHERE created_at>=CURDATE()"));
        out.putAll(jdbc.queryForMap("SELECT COUNT(*) resourceOperationsToday FROM resource_operation_log WHERE created_at>=CURDATE()"));
        out.putAll(jdbc.queryForMap("SELECT SUM(status='POSTED') socialPostedToday, SUM(status='FAILED') socialFailedToday FROM social_post_log WHERE created_at>=CURDATE()"));
        out.put("traffic",jdbc.queryForList("SELECT DATE(created_at) day,COUNT(*) requests,COUNT(DISTINCT visitor_hash) visitors,SUM(event_type='PAGE_VIEW') pageViews,SUM(status_code>=500) serverErrors FROM site_access_log WHERE created_at>=DATE_SUB(CURDATE(),INTERVAL 13 DAY) GROUP BY DATE(created_at) ORDER BY day"));
        out.put("hotSearches",hotSearches(LocalDate.now(),20));
        return out;
    }
    public List<Map<String,Object>> hotSearches(LocalDate date,int limit) {
        var set=redis.opsForZSet().reverseRangeWithScores("hot:search:"+date,0,Math.max(0,Math.min(limit,100)-1));
        if(set==null)return List.of();
        return set.stream().map(v->Map.<String,Object>of("keyword",String.valueOf(v.getValue()),"count",v.getScore()==null?0:v.getScore().longValue())).toList();
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
    public static String visitorHash(HttpServletRequest r){return RegistrationService.sha256(clientIp(r)+"|"+header(r,"User-Agent"));}
    private static String clientIp(HttpServletRequest r){String v=header(r,"CF-Connecting-IP");if(v.isBlank())v=header(r,"X-Forwarded-For");if(v.contains(","))v=v.split(",",2)[0].trim();if(v.isBlank())v=r.getRemoteAddr();return v;}
    private static String header(HttpServletRequest r,String name){String v=r.getHeader(name);return v==null?"":v;}
    private static String normalizeKeyword(String s){if(s==null)return null;String v=s.trim().replaceAll("\\s+"," ");return v.length()<2||v.length()>100?null:v;}
    private static String cut(String s,int n){if(s==null)return null;return s.length()>n?s.substring(0,n):s;}
}
