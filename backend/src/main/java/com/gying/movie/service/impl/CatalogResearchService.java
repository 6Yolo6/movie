package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import static com.gying.movie.service.impl.CatalogRecommendationEngine.*;

/** Deterministic execution of validated plans against public, active catalog metadata. */
@Service
public class CatalogResearchService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    private volatile Dataset cached;private volatile long refreshed;
    /** A snapshot owns its lazy indexes; replacing it also invalidates every derived cache. */
    private static final class Dataset {
        final List<ObjectNode> docs;
        final List<Film> films;
        final Map<String,ObjectNode> byId;
        final Map<String,Film> filmById;
        private volatile CatalogTextIndex lexical;
        private volatile PeopleDictionary actors,directors;
        Dataset(List<ObjectNode> docs,List<Film> films,Map<String,ObjectNode> byId,Map<String,Film> filmById){
            this.docs=docs;this.films=films;this.byId=byId;this.filmById=filmById;
        }
        synchronized CatalogTextIndex lexical(){
            if(lexical==null)lexical=new CatalogTextIndex(films);
            return lexical;
        }
        synchronized PeopleDictionary people(String field){
            if(field.equals("actors")){
                if(actors==null)actors=PeopleDictionary.build(films,field);
                return actors;
            }
            if(directors==null)directors=PeopleDictionary.build(films,field);
            return directors;
        }
    }
    private record PeopleDictionary(SortedMap<String,String> exact,List<Map.Entry<String,String>> literal){
        static PeopleDictionary build(List<Film> films,String field){
            // Deduplicate repeated cast names before ICU normalization, preserving the first catalog spelling.
            Set<String> names=new LinkedHashSet<>();
            for(Film film:films)names.addAll(field.equals("actors")?film.actors():film.directors());
            SortedMap<String,String> exact=new TreeMap<>(),literal=new TreeMap<>();
            Map<String,Set<String>> aliases=new TreeMap<>();
            for(String name:names){
                String normalized=key(name);exact.putIfAbsent(normalized,name);
                if(normalized.length()<2)continue;
                literal.putIfAbsent(normalized,name);
                String[] parts=name.split("[·•]");
                if(parts.length>1){
                    String alias=key(parts[parts.length-1]);
                    if(alias.length()>=2)aliases.computeIfAbsent(alias,k->new TreeSet<>()).add(name);
                }
            }
            aliases.forEach((alias,values)->{if(values.size()==1)literal.putIfAbsent(alias,values.iterator().next());});
            List<Map.Entry<String,String>> ordered=new ArrayList<>();
            literal.forEach((word,name)->ordered.add(Map.entry(word,name)));
            ordered.sort(Comparator.comparingInt((Map.Entry<String,String> e)->e.getKey().length()).reversed());
            return new PeopleDictionary(Collections.unmodifiableSortedMap(exact),List.copyOf(ordered));
        }
    }
    public CatalogResearchService(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    private static final ThreadLocal<com.ibm.icu.text.Transliterator> SIMPLIFY=ThreadLocal.withInitial(()->com.ibm.icu.text.Transliterator.getInstance("Traditional-Simplified"));
    public static String key(String text){return SIMPLIFY.get().transliterate(Normalizer.normalize(Objects.toString(text,""),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]",""));}
    private static final Map<String,String> REGION_EQUIVALENTS=new HashMap<>(),LANGUAGE_EQUIVALENTS=new HashMap<>();
    private static void alias(Map<String,String> map,String code,String... names){map.put(key(code),code);for(String name:names)map.put(key(name),code);}
    static{
        // Sources use English names, localized names and ISO codes; normalize by field.
        for(String code:Locale.getISOCountries()){
            Locale region=new Locale("",code);String canonical=code.toLowerCase(Locale.ROOT);
            alias(REGION_EQUIVALENTS,canonical,region.getISO3Country(),region.getDisplayCountry(Locale.ENGLISH),region.getDisplayCountry(Locale.SIMPLIFIED_CHINESE));
        }
        for(String code:Locale.getISOLanguages()){
            Locale language=new Locale(code);alias(LANGUAGE_EQUIVALENTS,code,language.getISO3Language(),language.getDisplayLanguage(Locale.ENGLISH),language.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE));
        }
        alias(REGION_EQUIVALENTS,"cn","国产","大陆","中国大陆","中国","China");
        alias(REGION_EQUIVALENTS,"us","美国","United States of America","United States","USA");
        alias(REGION_EQUIVALENTS,"gb","英国","United Kingdom","UK");
        alias(REGION_EQUIVALENTS,"kr","韩国","South Korea");
        alias(REGION_EQUIVALENTS,"hk","香港","Hong Kong");alias(REGION_EQUIVALENTS,"tw","台湾","Taiwan");
        alias(LANGUAGE_EQUIVALENTS,"zh","国语","普通话","汉语普通话","中文","Mandarin","Chinese");
        alias(LANGUAGE_EQUIVALENTS,"yue","粤语","Cantonese");
    }
    private static String canonical(String text,String field){return (field.equals("regions")?REGION_EQUIVALENTS:LANGUAGE_EQUIVALENTS).getOrDefault(key(text),key(text));}
    private synchronized Dataset dataset(){
        if(cached!=null&&System.currentTimeMillis()-refreshed<60000)return cached;
        List<ObjectNode> docs=jdbc.query("SELECT m.id,m.title_cn,m.title_en,m.year,m.category,m.season,m.series_name,m.genres,m.directors,m.actors,m.summary,m.aliases,m.regions,m.languages,m.runtime,m.douban_score,m.imdb_score,m.tmdb_vote_average,COALESCE(m.tmdb_popularity,0)+COALESCE(m.popularity,0) heat,(SELECT COUNT(*) FROM resource_link r WHERE r.movie_id=m.id AND r.status='ACTIVE' AND r.audit_status=1 AND r.deleted_at IS NULL AND r.link_status='NORMAL') resources FROM movie_metadata m WHERE m.status='ACTIVE' AND m.deleted_at IS NULL ORDER BY m.id LIMIT 50000",(rs,n)->{
            ObjectNode d=mapper.createObjectNode();for(String k:List.of("id","title_cn","title_en","category","series_name","summary","aliases")){
                String value=rs.getString(k);if(value!=null)d.put(switch(k){case "title_cn"->"titleCn";case "title_en"->"titleEn";case "series_name"->"seriesName";default->k;},value);
            }
            if(rs.getObject("year")!=null)d.put("year",rs.getInt("year"));if(rs.getObject("season")!=null)d.put("season",rs.getInt("season"));
            for(String k:List.of("genres","directors","actors","regions","languages")){try{JsonNode a=mapper.readTree(Objects.toString(rs.getString(k),"[]"));if(a.isArray())d.set(k,a);}catch(Exception ignored){d.putArray(k);}}
            for(String k:List.of("douban_score","imdb_score","tmdb_vote_average"))if(rs.getObject(k)!=null)d.put(switch(k){case "douban_score"->"doubanScore";case "imdb_score"->"imdbScore";default->"tmdbVoteAverage";},rs.getDouble(k));
            Integer runtime=NaturalMovieQuery.runtimeMinutes(rs.getString("runtime"));if(runtime!=null)d.put("runtimeMinutes",runtime);
            d.put("popularity",rs.getDouble("heat")).put("resourceCount",rs.getInt("resources"));
            String embedding=String.join("\n",d.path("titleCn").asText(),d.path("titleEn").asText(),d.path("aliases").asText(),d.path("seriesName").asText(),d.path("genres").toString(),d.path("directors").toString(),d.path("actors").toString(),d.path("summary").asText());
            if(embedding.length()>2200)embedding=embedding.substring(0,2200);
            d.put("embeddingText",embedding).put("fingerprint",fingerprint(embedding));return d;
        });
        if(cached!=null&&cached.docs.equals(docs)){refreshed=System.currentTimeMillis();return cached;}
        List<Film> films=new ArrayList<>();Map<String,ObjectNode> byId=new HashMap<>();for(ObjectNode d:docs){
            films.add(new Film(d.path("id").asText(),d.path("titleCn").asText(),d.path("titleEn").asText(),d.has("year")?d.path("year").asInt():null,d.path("category").asText(),null,d.path("seriesName").asText(),strings(d,"genres"),strings(d,"directors"),strings(d,"actors"),rating(d,"any"),d.path("popularity").asDouble(),d.path("resourceCount").asInt(),d.path("summary").asText(),d.path("aliases").asText(),strings(d,"regions"),strings(d,"languages"),d.has("runtimeMinutes")?d.path("runtimeMinutes").asInt():null));byId.put(d.path("id").asText(),d);
        }
        cached=new Dataset(List.copyOf(docs),List.copyOf(films),Map.copyOf(byId),films.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Film::id,f->f)));refreshed=System.currentTimeMillis();return cached;
    }
    public static String fingerprint(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public ObjectNode documents(int page,int size){Dataset data=dataset();int n=Math.max(1,Math.min(200,size)),start=Math.max(0,page-1)*n;var result=mapper.createObjectNode().put("total",data.docs.size()).put("truncated",data.docs.size()==50000).put("queriedAt",Instant.now().toString());var items=result.putArray("documents");for(int i=start;i<Math.min(start+n,data.docs.size());i++)items.add(data.docs.get(i).deepCopy());return result;}
    private static List<String> strings(JsonNode n,String k){List<String> out=new ArrayList<>();for(JsonNode v:n.path(k))if(v.isTextual())out.add(v.asText());return out;}
    private static boolean names(JsonNode doc,String field,List<String> names){return names.stream().allMatch(w->strings(doc,field).stream().anyMatch(v->key(v).contains(key(w))));}
    private static boolean any(JsonNode doc,String field,List<String> values){return values.isEmpty()||values.stream().anyMatch(w->strings(doc,field).stream().anyMatch(v->canonical(v,field).equals(canonical(w,field))));}
    private static double rating(JsonNode doc,String source){return switch(source){case "douban"->doc.path("doubanScore").asDouble(-1);case "imdb"->doc.path("imdbScore").asDouble(-1);case "tmdb"->doc.path("tmdbVoteAverage").asDouble(-1);default->Math.max(doc.path("doubanScore").asDouble(-1),Math.max(doc.path("imdbScore").asDouble(-1),doc.path("tmdbVoteAverage").asDouble(-1)));};}
    private boolean eligible(ObjectNode d,MovieQueryPlan p,Set<String> previous,Set<String> seeds){
        if(seeds.contains(d.path("id").asText())||p.list("excludedIds").contains(d.path("id").asText()))return false;
        if(p.text("scope").equals("previousResults")&&!previous.contains(d.path("id").asText()))return false;
        if(!p.text("category").isBlank()&&!p.text("category").equals(d.path("category").asText()))return false;
        if(p.has("yearFrom")&&(!d.has("year")||d.path("year").asInt()<p.integer("yearFrom",1900))||p.has("yearTo")&&(!d.has("year")||d.path("year").asInt()>p.integer("yearTo",2100)))return false;
        if(p.has("minRuntime")&&(!d.has("runtimeMinutes")||d.path("runtimeMinutes").asInt()<p.integer("minRuntime",1))||p.has("maxRuntime")&&(!d.has("runtimeMinutes")||d.path("runtimeMinutes").asInt()>p.integer("maxRuntime",600)))return false;
        double r=rating(d,p.text("ratingSource"));if(p.has("minRating")&&r<p.number("minRating",0)||p.has("maxRating")&&(r<0||r>p.number("maxRating",10)))return false;
        Set<String> genres=new HashSet<>(strings(d,"genres"));if(!genres.containsAll(p.list("genresAll"))||!p.list("genresAny").isEmpty()&&Collections.disjoint(genres,p.list("genresAny"))||!Collections.disjoint(genres,p.list("excludedGenres")))return false;
        if(!names(d,"actors",p.list("actors"))||!names(d,"directors",p.list("directors")))return false;
        for(String field:List.of("actors","directors"))if(!p.list(field+"Any").isEmpty()&&p.list(field+"Any").stream().noneMatch(w->names(d,field,List.of(w))))return false;
        if(p.list("excludedActors").stream().anyMatch(w->names(d,"actors",List.of(w)))||p.list("excludedDirectors").stream().anyMatch(w->names(d,"directors",List.of(w))))return false;
        if(!any(d,"regions",p.list("regionsAny"))||!any(d,"languages",p.list("languagesAny")))return false;
        if(p.list("excludedLanguages").stream().anyMatch(w->any(d,"languages",List.of(w))))return false;
        return p.list("titles").isEmpty()||p.list("titles").stream().anyMatch(t->titleMatches(d,t));
    }
    private static boolean titleMatches(JsonNode doc,String title){
        String wanted=key(title);
        if(wanted.codePointCount(0,wanted.length())>2)return List.of("titleCn","titleEn","aliases","seriesName").stream().anyMatch(k->key(doc.path(k).asText()).contains(wanted));
        if(List.of("titleCn","titleEn","seriesName").stream().anyMatch(k->key(doc.path(k).asText()).equals(wanted)))return true;
        return Arrays.stream(doc.path("aliases").asText().split("[/|,，;；、\\r\\n]+")).anyMatch(alias->key(alias).equals(wanted));
    }
    private static int distance(String a,String b){int[] row=new int[b.length()+1];for(int i=0;i<row.length;i++)row[i]=i;for(int i=1;i<=a.length();i++){int prev=row[0];row[0]=i;for(int j=1;j<=b.length();j++){int old=row[j];row[j]=Math.min(Math.min(row[j]+1,row[j-1]+1),prev+(a.charAt(i-1)==b.charAt(j-1)?0:1));prev=old;}}return row[b.length()];}
    private MovieQueryPlan resolvePeople(MovieQueryPlan original,Dataset data,List<String> unresolved){
        ObjectNode json=original.json();
        for(String field:List.of("actors","actorsAny","directors","directorsAny","excludedActors","excludedDirectors")){
            List<String> requestedNames=original.list(field);ArrayNode resolved=json.putArray(field);
            if(requestedNames.isEmpty())continue;
            String source=field.toLowerCase(Locale.ROOT).contains("actor")?"actors":"directors";
            Map<String,String> dictionary=data.people(source).exact;
            for(String requested:requestedNames){
                String cleaned=requested.replaceFirst("(?:最近|近期|近[一二三四五六七八九十0-9]+年)$","");String wanted=key(cleaned);
                List<String> exact=dictionary.keySet().stream().filter(n->n.equals(wanted)||wanted.length()>=2&&n.contains(wanted)).toList();
                if(!exact.isEmpty()){resolved.add(cleaned);continue;}
                int best=100;String selected=null;boolean tie=false;
                if(wanted.length()>=4)for(var entry:dictionary.entrySet()){
                    if(Math.abs(entry.getKey().length()-wanted.length())>1)continue;int diff=distance(wanted,entry.getKey());
                    if(diff<best){best=diff;selected=entry.getValue();tie=false;}else if(diff==best)tie=true;
                }
                if(best<=1&&!tie)resolved.add(selected);else{resolved.add(cleaned);unresolved.add((source.equals("actors")?"演员":"导演")+"“"+requested+"”");}
            }
        }
        return MovieQueryPlan.parse(json,mapper);
    }
    /** Recover actual catalog people omitted by a small model; original punctuation bounds polarity. */
    private MovieQueryPlan explicitPeople(MovieQueryPlan input,Dataset data,String message){
        if(message.isBlank())return input;
        String unquoted=message.replaceAll("《[^》]*》","");String literal=key(unquoted);ObjectNode plan=input.json();
        for(String field:List.of("actors","directors")){
            List<Map.Entry<String,String>> ordered=data.people(field).literal;
            List<int[]> occupied=new ArrayList<>();
            for(var entry:ordered){
                String word=entry.getKey();int position=literal.indexOf(word);if(position<0)continue;
                int end=position+word.length();boolean overlaps=false;for(int[] span:occupied)if(position<span[1]&&end>span[0]){overlaps=true;break;}if(overlaps)continue;
                occupied.add(new int[]{position,end});
                boolean other=data.people(field.equals("actors")?"directors":"actors").exact.containsKey(key(entry.getValue()));
                if(other){boolean directing=literal.matches("(?s).*"+java.util.regex.Pattern.quote(word)+".{0,6}(?:导演|执导|作品).*" );if(directing==field.equals("actors"))continue;}
                boolean exclude=negativePerson(unquoted,word);String target=exclude?(field.equals("actors")?"excludedActors":"excludedDirectors"):(strings(plan,field+"Any").isEmpty()?field:field+"Any");
                // Prefer explicit polarity even if the model reversed it.
                for(String opposite:exclude?List.of(field,field+"Any"):List.of(field.equals("actors")?"excludedActors":"excludedDirectors")){
                    ArrayNode keep=mapper.createArrayNode();for(JsonNode n:plan.path(opposite))if(!key(n.asText()).contains(word)&&!word.contains(key(n.asText())))keep.add(n);if(plan.has(opposite))plan.set(opposite,keep);
                }
                ArrayNode canonicalNames=mapper.createArrayNode();for(JsonNode n:plan.path(target))if(!word.contains(key(n.asText()))&&!key(n.asText()).contains(word))canonicalNames.add(n);
                canonicalNames.add(entry.getValue());plan.set(target,canonicalNames);
            }
        }
        return MovieQueryPlan.parse(plan,mapper);
    }
    private static boolean negativePerson(String message,String word){
        for(String clause:message.split("[，。；,;!?！？]|(?:但是|而是|不过)")){
            String normalized=key(clause);int at=normalized.indexOf(word);if(at>=0)return normalized.substring(Math.max(0,at-10),at).matches(".*(?:不要|排除|不看|不是|别放|不含|不包括|除去|不喜欢|非).*");
        }
        return false;
    }
    public ObjectNode query(JsonNode body,JsonNode context){
        MovieQueryPlan initial=MovieQueryPlan.parse(body.path("plan"),mapper).inherit(context,mapper);Dataset data=dataset();List<String> unresolved=new ArrayList<>();String message=body.path("message").asText("");if(message.length()>1200||message.chars().anyMatch(c->c<32&&c!=10&&c!=13))throw new IllegalArgumentException("查询原文格式无效");MovieQueryPlan p=resolvePeople(explicitPeople(initial,data,message),data,unresolved);
        ObjectNode result=mapper.createObjectNode().put("source","GYing catalog").put("queriedAt",Instant.now().toString());result.set("queryPlan",p.json());
        Set<String> previous=new LinkedHashSet<>(strings(context,"candidateIds"));
        if(!unresolved.isEmpty()){
            result.put("answer","片库暂不能确定人物身份："+String.join("、",unresolved)+"。请核对姓名，或查看联网补充。");result.putArray("movies");result.set("unresolvedPeople",mapper.valueToTree(unresolved));return result.put("total",0);
        }
        if(p.text("intent").equals("clarify")){result.put("answer",p.text("clarification").isBlank()?"请补充片名、人物或你最在意的条件。":p.text("clarification"));result.putArray("movies");return result.put("total",0);}
        if(p.text("intent").equals("details")){
            String id=p.text("movieId");int ordinal=p.integer("ordinal",0);if(id.isBlank()&&ordinal>0&&ordinal<=previous.size())id=new ArrayList<>(previous).get(ordinal-1);
            if(id.isBlank()&&!p.list("titles").isEmpty()){
                List<ObjectNode> exact=data.docs.stream().filter(d->p.list("titles").stream().anyMatch(t->key(t).equals(key(d.path("titleCn").asText()))||key(t).equals(key(d.path("titleEn").asText())))).filter(d->eligible(d,p,previous,Set.of())).toList();
                if(exact.size()==1)id=exact.get(0).path("id").asText();
            }
            if(!data.byId.containsKey(id)){result.put("answer","无法确定你指的是哪一部，请选择前一轮影片或补充片名。");result.putArray("movies");return result.put("total",0);}
            return result.put("selectedId",id).put("total",1).put("answer","已定位片库影片，继续查询本次详情。");
        }
        Set<String> seeds=new HashSet<>();if(!p.text("seedTitle").isBlank())for(ObjectNode d:data.docs)if(key(d.path("titleCn").asText()).equals(key(p.text("seedTitle")))||key(d.path("titleEn").asText()).equals(key(p.text("seedTitle"))))seeds.add(d.path("id").asText());
        Predicate<Film> filter=f->eligible(data.byId.get(f.id()),p,previous,p.text("intent").equals("similar")?seeds:Set.of());
        String topic=p.text("semanticText");boolean themed=p.list("titles").isEmpty()&&!topic.isBlank()||p.text("intent").equals("similar");
        Map<String,Double> ranks=new HashMap<>();Map<String,List<String>> reasons=new HashMap<>();
        List<CatalogTextIndex.Hit> lexical=topic.isBlank()?List.of():data.lexical().recall(topic,filter,100);int rank=0;
        for(var hit:lexical){ranks.merge(hit.movie().id(),1D/(60+ ++rank),Double::sum);reasons.put(hit.movie().id(),new ArrayList<>(hit.reasons()));}
        JsonNode dense=body.path("denseCandidates");if(!dense.isMissingNode()&&(!dense.isArray()||dense.size()>100))throw new IllegalArgumentException("语义候选数量无效");
        int stale=0;rank=0;Set<String> seen=new HashSet<>();for(JsonNode hit:dense){
            String id=hit.path("id").asText();double score=hit.path("score").asDouble(-2);ObjectNode doc=data.byId.get(id);
            if(!hit.isObject()||!hit.path("score").isNumber()||!hit.path("id").isTextual()||!hit.path("fingerprint").isTextual()||!Double.isFinite(score)||score< -1||score>1||!hit.path("fingerprint").asText().matches("[a-f0-9]{64}")||!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("无效的语义候选");
            if(doc==null||!doc.path("fingerprint").asText().equals(hit.path("fingerprint").asText())){stale++;continue;}
            if(!seen.add(id)||!eligible(doc,p,previous,p.text("intent").equals("similar")?seeds:Set.of()))continue;
            if(score<.40)continue;ranks.merge(id,1D/(60+ ++rank),Double::sum);reasons.computeIfAbsent(id,k->new ArrayList<>()).add("简介语义候选（需结合资料核对）");
        }
        if(p.text("intent").equals("similar")&&!seeds.isEmpty()){
            List<Film> nearby=data.films.stream().filter(filter).sorted(Comparator.comparingDouble((Film f)->seeds.stream().map(data.filmById::get).mapToDouble(seed->similarity(seed,f)).max().orElse(0)).reversed()).limit(100).toList();rank=0;
            for(Film f:nearby){ranks.merge(f.id(),.35/(60+ ++rank),Double::sum);reasons.computeIfAbsent(f.id(),k->new ArrayList<>()).add("参照片内容特征候选");}
        }
        List<ObjectNode> matches=new ArrayList<>();for(Film f:data.films)if(filter.test(f)&&(!themed||ranks.containsKey(f.id())))matches.add(data.byId.get(f.id()));
        String sort=p.text("sort");Comparator<ObjectNode> order=switch(sort){case "rating_desc"->Comparator.comparingDouble((ObjectNode d)->rating(d,p.text("ratingSource"))).reversed();case "year_asc"->Comparator.comparingInt(d->d.path("year").asInt(9999));case "year_desc"->Comparator.comparingInt((ObjectNode d)->d.path("year").asInt()).reversed();case "popularity_desc"->Comparator.comparingDouble((ObjectNode d)->d.path("popularity").asDouble()).reversed();default->Comparator.comparingDouble((ObjectNode d)->ranks.getOrDefault(d.path("id").asText(),0D)).reversed().thenComparing(Comparator.comparingDouble((ObjectNode d)->rating(d,"any")).reversed());};
        matches.sort(order.thenComparing(d->d.path("id").asText()));
        int limit=p.integer("limit",10);if(body.has("candidateLimit")){if(!body.path("candidateLimit").isIntegralNumber()||body.path("candidateLimit").asInt()<1||body.path("candidateLimit").asInt()>20)throw new IllegalArgumentException("候选数量无效");limit=body.path("candidateLimit").asInt();}var movies=result.putArray("movies");for(ObjectNode doc:matches.subList(0,Math.min(limit,matches.size()))){
            ObjectNode item=doc.deepCopy();item.remove(List.of("embeddingText","fingerprint","popularity"));if(item.path("summary").asText().length()>700)item.put("summary",item.path("summary").asText().substring(0,700));item.put("rating",Math.max(0,rating(doc,p.text("ratingSource")))).put("ratingSource",p.text("ratingSource").isBlank()?"any":p.text("ratingSource"));var why=item.putArray("reasons");for(String reason:reasons.getOrDefault(doc.path("id").asText(),List.of("符合本次可核验的片库筛选条件")))why.add(reason);movies.add(item);
        }
        result.put("total",matches.size()).put("returned",movies.size()).put("truncated",matches.size()>movies.size()).put("semanticStaleCandidates",stale).put("retrieval",dense.isEmpty()?"lexical":"hybrid-rrf");
        result.set("unverifiedConditions",mapper.valueToTree(p.list("unsupportedConditions")));
        String answer=movies.isEmpty()?"本次片库检索没有找到可确认的候选。":themed?"找到以下可能符合描述的候选，简介和匹配依据列在下方。":"按本次已识别条件找到 "+matches.size()+" 部，先展示 "+movies.size()+" 部。";
        if(!p.list("unsupportedConditions").isEmpty())answer+=" 片库不能完整核验这些要求："+String.join("、",p.list("unsupportedConditions"))+"；不能保证下列候选满足它们。";
        return result.put("answer",answer);
    }
}
