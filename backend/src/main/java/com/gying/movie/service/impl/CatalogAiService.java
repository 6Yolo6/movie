package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import static com.gying.movie.service.impl.CatalogRecommendationEngine.*;

/** Retrieval/recommendation owns the complete result set; the small model never truncates recall. */
@Service
public class CatalogAiService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final class Snapshot {
        private final List<Film> films;
        private final FeatureIndex features;
        private volatile CatalogTextIndex text;
        private Snapshot(List<Film> films) {this.films=films;this.features=new FeatureIndex(films);}
        private List<Film> films() {return films;}
        private FeatureIndex features() {return features;}
        private synchronized CatalogTextIndex text() {
            if(text==null)text=new CatalogTextIndex(films);
            return text;
        }
    }
    private volatile Snapshot cached;
    private volatile long refreshed;
    @org.springframework.beans.factory.annotation.Value("${minio.url-prefix:}")
    private String posterPrefix;
    public CatalogAiService(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    public record Intent(String keyword, boolean recommend, Constraints constraints) { }
    private static final Set<String> GENRES=Set.of("科幻","动作","喜剧","爱情","悬疑","惊悚","恐怖","动画","冒险","犯罪","剧情","奇幻","战争","历史","纪录片","家庭");
    public static Intent intent(String message) {
        String text=Normalizer.normalize(message==null?"":message,Normalizer.Form.NFKC).trim();
        if(text.isBlank()||text.length()>1000)return null;
        if(text.matches("(?is).*(?:批量|转存|删除|执行|发布|https?://|magnet:).*"))return null;
        boolean recommendation=text.matches("(?is).*(?:推荐|猜你喜欢|随便看|看什么|看点什么|值得看|recommend|suggest).*" );
        Set<String> genres=new TreeSet<>();for(String genre:GENRES)if(text.contains(genre))genres.add(genre);
        recommendation |= !genres.isEmpty() && text.matches("(?s).*(?:想看|想找|找点|看点|有什么|有哪些).*(?:电影|影片|片子|剧集|动漫).*" );
        if(recommendation) {
            String category=text.contains("动漫")?"ac":text.contains("电视剧")||text.contains("剧集")?"tv":text.contains("电影")?"mv":null;
            Integer year=null;var yearMatch=java.util.regex.Pattern.compile("(?:19|20)\\d{2}").matcher(text);
            if(yearMatch.find())year=Integer.valueOf(yearMatch.group());
            return new Intent("",true,new Constraints(genres,category,year,text.contains("高分")?8:0));
        }
        if(text.matches("(?is).*(?:这部|这些|刚才|上面|上一部|片库.?id|详情|简介|剧情|导演|演员|资源|评分是多少|哪一年|第[一二三四五六七八九十0-9]+[部个项]|前面|刚刚).*"))return null;
        boolean explicit=text.matches("(?is)^(?:我想看|我想找|我想搜|想看|想找|我要看|我要找|帮我|请帮我|请搜索|请找|搜索|找一下|查一下|搜|找|search |find |i want to watch ).*");
        text=text.replaceFirst("(?i)^(?:我想看|我想找|我想搜|想看|想找|我要看|我要找|请帮我搜索|请帮我找|帮我搜索|帮我找一下|帮我搜一下|帮我找|帮我搜|请搜索|请找|搜索一下|搜索|找一下|查一下|搜|找|search for |search |find |i want to watch )\\s*","");
        text=text.replaceFirst("(?:相关的?(?:电影|影片)|系列(?:电影|影片)?|所有(?:电影|影片)|全部(?:电影|影片)|有哪些(?:电影|影片)?|有吗|吗)[?？。！!]*$","").trim();
        text=text.replaceAll("^[《\\\"“]+|[》\\\"”]+$","").replaceAll("[?？。！!]+$","").trim();
        if(text.length()<2||text.length()>60||text.matches("(?s).*[?？\\n].*"))return null;
        if(!explicit && text.matches("(?s).*(?:为什么|如何|怎么|你好|谢谢|可以|帮忙|时候).*"))return null;
        return new Intent(text,false,Constraints.none());
    }
    public Map<String,Object> answer(Long userId,JsonNode request) {
        Intent intent=intent(request.path("message").asText());
        String raw=request.path("message").asText();
        if(raw.matches("(?is).*(?:批量|转存|删除|执行|发布|https?://|magnet:).*"))return null;
        if(NaturalMovieQuery.shouldSearch(raw)&&intent!=null&&!intent.recommend()) {
            ObjectNode titles=search(intent.keyword(),1,20);
            if(titles.path("catalog").path("total").asLong()>0)
                return Map.of("jobId",UUID.randomUUID().toString(),"status","SUCCEEDED","result",titles);
        }
        if(NaturalMovieQuery.shouldSearch(raw)&&!raw.matches("(?is).*(?:推荐|猜你喜欢|recommend|suggest).*")) {
            return Map.of("jobId",UUID.randomUUID().toString(),"status","SUCCEEDED","result",naturalSearch(raw,1,20));
        }
        if(intent==null)return null;
        ObjectNode result;
        String message=request.path("message").asText();
        if(NaturalMovieQuery.shouldSearch(message)) {
            var plan=parseQuery(message);
            result=intent.recommend()&&plan.text().isBlank()?recommendations(userId,plan.constraints(),12):naturalSearch(message,1,20);
        } else result=intent.recommend()?recommendations(userId,intent.constraints(),12):search(intent.keyword(),1,20);
        return Map.of("jobId",UUID.randomUUID().toString(),"status","SUCCEEDED","result",result);
    }
    public ObjectNode search(String keyword,int page,int size) {
        if(keyword==null||keyword.isBlank()||keyword.length()>60)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"请输入有效片名");
        int safePage=Math.max(1,Math.min(1000,page)),safeSize=Math.max(1,Math.min(30,size));
        String normalized=Normalizer.normalize(keyword,Normalizer.Form.NFKC).trim();
        String like="%"+normalized.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String where=" status='ACTIVE' AND deleted_at IS NULL AND (title_cn LIKE ? ESCAPE '!' OR title_en LIKE ? ESCAPE '!' OR aliases LIKE ? ESCAPE '!' OR series_name LIKE ? ESCAPE '!')";
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM movie_metadata WHERE"+where,Long.class,like,like,like,like);
        long total=count==null?0:count;int pages=(int)((total+safeSize-1)/safeSize);safePage=Math.min(safePage,Math.max(1,pages));
        String order=" ORDER BY CASE WHEN title_cn=? OR title_en=? THEN 0 WHEN title_cn LIKE ? ESCAPE '!' OR title_en LIKE ? ESCAPE '!' THEN 1 ELSE 2 END,COALESCE(year,0),COALESCE(season,0),id";
        List<Film> films=jdbc.query("SELECT "+columns()+" FROM movie_metadata m WHERE"+where+order+" LIMIT ? OFFSET ?",this::film,
                like,like,like,like,normalized,normalized,like,like,safeSize,(safePage-1)*safeSize);
        ObjectNode result=mapper.createObjectNode().put("answer",total==0?"片库暂未找到“"+normalized+"”。可换用原名、别名或更短的关键词。":"片库找到“"+normalized+"”相关影片 "+total+" 部。下方展示第 "+safePage+" 页，可翻页查看其余结果。");
        result.putObject("catalog").put("kind","search").put("keyword",normalized).put("total",total).put("page",safePage).put("pages",pages).put("size",safeSize);
        ObjectNode evidence=result.putArray("evidence").addObject().put("tool","searchMovies").put("queriedAt",Instant.now().toString()).put("total",total).put("returned",films.size());
        var movies=evidence.putArray("movies");films.forEach(f->movies.add(view(f)));
        return result;
    }
    public ObjectNode recommendations(Long userId,Constraints constraints,int limit) {
        Snapshot snapshot=snapshot();
        Map<String,Double> profile=new LinkedHashMap<>();Set<String> excluded=new HashSet<>();
        jdbc.query("SELECT movie_id FROM user_favorite WHERE user_id=? ORDER BY created_at DESC LIMIT 100",rs->{String id=rs.getString(1);profile.put(id,3D);excluded.add(id);},userId);
        jdbc.query("SELECT movie_id,MAX(created_at) seen FROM resource_operation_log WHERE user_id=? AND operation_type='VIEW' AND status='SUCCESS' AND created_at>=DATE_SUB(NOW(),INTERVAL 90 DAY) GROUP BY movie_id ORDER BY seen DESC LIMIT 100",rs->{
            String id=rs.getString(1);double age=Math.max(0,(System.currentTimeMillis()-rs.getTimestamp(2).getTime())/86400000D);profile.merge(id,Math.exp(-age/30),Double::sum);excluded.add(id);
        },userId);
        // Co-favorite support >= 2 is enforced by the engine; no other user identity is exposed.
        List<Interaction> interactions=jdbc.query("SELECT user_id,movie_id FROM user_favorite WHERE user_id<>? ORDER BY created_at DESC LIMIT 50000",(rs,n)->new Interaction(rs.getString(1),rs.getString(2)),userId);
        UserFeatures features=snapshot.features().features(profile);
        List<Ranked> ranked=snapshot.features().recommend(features,excluded,interactions,constraints,Math.max(1,Math.min(30,limit)));
        boolean personalized=features.personalized();
        ObjectNode result=mapper.createObjectNode().put("answer",ranked.isEmpty()?"暂时没有符合这些条件的影片。试试放宽年份、类型或评分。":personalized?"根据你的收藏、近期浏览和本次偏好，为你推荐以下影片；每部都附有推荐理由。":"先根据本次偏好、片库评分和热度推荐。收藏喜欢的影片或浏览详情后，猜你喜欢会逐步结合你的偏好。");
        result.putObject("catalog").put("kind","recommendation").put("personalized",personalized).put("total",ranked.size()).put("featureVersion",VERSION);
        ObjectNode evidence=result.putArray("evidence").addObject().put("tool","recommendMovies").put("queriedAt",Instant.now().toString());
        var movies=evidence.putArray("movies");for(Ranked r:ranked){ObjectNode item=view(r.movie());item.set("reasons",mapper.valueToTree(r.reasons()));movies.add(item);}
        return result;
    }
    public List<ObjectNode> related(String movieId, int limit) {
        if (movieId == null || !movieId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid movie");
        List<Film> seeds = jdbc.query("SELECT " + columns() + " FROM movie_metadata m WHERE m.id=? AND m.status='ACTIVE' AND m.deleted_at IS NULL", this::film, movieId);
        if (seeds.isEmpty())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Movie not found");
        Film seed = seeds.get(0);
        List<Film> candidates = new ArrayList<>(catalog().stream()
                .filter(f -> !f.id().equals(seed.id()) && Objects.equals(f.category(), seed.category()))
                .filter(f -> similarity(seed, f) > .10 || seed.seriesName() != null && !seed.seriesName().isBlank() && seed.seriesName().equals(f.seriesName()))
                .toList());
        candidates.add(seed);
        return recommend(candidates, Map.of(seed.id(), 3D), Set.of(seed.id()), List.of(),
                Constraints.none(), Math.max(1, Math.min(12, limit))).stream()
                .map(r -> view(r.movie())).toList();
    }
    private List<Film> catalog() { return snapshot().films(); }
    private synchronized Snapshot snapshot() {
        if(System.currentTimeMillis()-refreshed<60000&&cached!=null)return cached;
        List<Film> films=List.copyOf(jdbc.query("SELECT "+columns()+" FROM movie_metadata m WHERE m.status='ACTIVE' AND m.deleted_at IS NULL ORDER BY m.id LIMIT 50000",this::film));
        Snapshot next=new Snapshot(films);
        cached=next;refreshed=System.currentTimeMillis();return next;
    }
    private NaturalMovieQuery.Plan parseQuery(String query) {
        try { return NaturalMovieQuery.parse(query); }
        catch(IllegalArgumentException e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
    public ObjectNode naturalSearch(String query,int page,int size) {
        var plan=parseQuery(query);Snapshot snapshot=snapshot();List<CatalogTextIndex.Hit> hits=snapshot.text().search(plan);
        int safeSize=Math.max(1,Math.min(30,size)),pages=(hits.size()+safeSize-1)/safeSize;
        int safePage=Math.max(1,Math.min(Math.max(1,pages),page));int start=(safePage-1)*safeSize;
        ObjectNode result=mapper.createObjectNode().put("answer",hits.isEmpty()?"片库暂无符合描述和筛选条件的影片。请减少条件或换个描述。":"根据片名、简介与已识别条件找到 "+hits.size()+" 部影片；匹配依据见下方。");
        var metadata=result.putObject("catalog").put("kind","natural_search").put("query",plan.query()).put("total",hits.size()).put("page",safePage).put("pages",pages).put("size",safeSize).put("indexVersion",CatalogTextIndex.VERSION);
        metadata.set("filters",mapper.valueToTree(plan.constraints()));metadata.put("catalogTruncated",snapshot.films().size()==50000);
        var evidence=result.putArray("evidence").addObject().put("tool","naturalSearchMovies").put("queriedAt",Instant.now().toString()).put("total",hits.size()).put("returned",Math.min(safeSize,hits.size()-start));
        var movies=evidence.putArray("movies");
        for(var hit:hits.subList(start,Math.min(start+safeSize,hits.size()))) {var item=view(hit.movie());item.set("reasons",mapper.valueToTree(hit.reasons()));if(!hit.snippet().isBlank())item.put("summary",hit.snippet());movies.add(item);}
        return result;
    }
    private String columns() {
        return "m.id,m.title_cn,m.title_en,m.year,m.category,m.poster_url,m.series_name,m.genres,m.directors,m.actors,m.summary,m.aliases,m.regions,m.languages,m.runtime,GREATEST(COALESCE(m.douban_score,0),COALESCE(m.imdb_score,0),COALESCE(m.tmdb_vote_average,0)) rating,COALESCE(m.tmdb_popularity,0)+COALESCE(m.popularity,0) heat,(SELECT COUNT(*) FROM resource_link r WHERE r.movie_id=m.id AND r.status='ACTIVE' AND r.audit_status=1 AND r.deleted_at IS NULL AND r.link_status='NORMAL') resources";
    }
    private Film film(java.sql.ResultSet rs,int n)throws java.sql.SQLException {
        return new Film(rs.getString("id"),rs.getString("title_cn"),rs.getString("title_en"),(Integer)rs.getObject("year"),rs.getString("category"),rs.getString("poster_url"),rs.getString("series_name"),list(rs.getString("genres")),list(rs.getString("directors")),list(rs.getString("actors")),rs.getDouble("rating"),rs.getDouble("heat"),rs.getInt("resources"),rs.getString("summary"),rs.getString("aliases"),list(rs.getString("regions")),list(rs.getString("languages")),NaturalMovieQuery.runtimeMinutes(rs.getString("runtime")));
    }
    private List<String> list(String value) {try {return value==null?List.of():mapper.readValue(value,mapper.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){return List.of();}}
    private ObjectNode view(Film f) {
        ObjectNode item=mapper.createObjectNode().put("id",f.id()).put("titleCn",f.title()).put("category",Objects.toString(f.category(),"")).put("rating",f.rating()).put("resourceCount",f.resources());
        if(f.year()!=null)item.put("year",f.year());if(f.posterUrl()!=null)item.put("posterUrl",com.gying.movie.utils.PosterUrlUtils.toPublicUrl(f.posterUrl(),posterPrefix));item.set("genres",mapper.valueToTree(f.genres()));return item;
    }
}
