package com.gying.movie.service.impl;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.*;
import static com.gying.movie.service.impl.CatalogRecommendationEngine.*;

/** Fixed, inspectable query grammar: user text never becomes SQL or an executable instruction. */
public final class NaturalMovieQuery {
    private NaturalMovieQuery() { }
    private static final Map<String,String> GENRES;
    static {
        Map<String,String> values=new LinkedHashMap<>();
        for(String g:List.of("科幻","动作","喜剧","爱情","悬疑","惊悚","恐怖","动画","冒险","犯罪","剧情","奇幻","战争","历史","纪录片","家庭"))values.put(g,g);
        values.putAll(Map.of("science fiction","科幻","sci-fi","科幻","action","动作","comedy","喜剧","romance","爱情","mystery","悬疑","thriller","惊悚","horror","恐怖","animation","动画","documentary","纪录片"));
        GENRES=Collections.unmodifiableMap(values);
    }
    public record Plan(String query,String text,Constraints constraints,boolean structured) { }
    public static Plan parse(String message) { return parse(message,Clock.system(ZoneId.of("Asia/Shanghai"))); }
    public static Plan parse(String message,Clock clock) {
        String query=Normalizer.normalize(Objects.toString(message,""),Normalizer.Form.NFKC).trim();
        if(query.isBlank()||query.length()>1000)throw new IllegalArgumentException("请输入 1–1000 字符的影片描述");
        String text=query.toLowerCase(Locale.ROOT);Set<String> genres=new TreeSet<>(),excluded=new TreeSet<>();boolean structured=false;
        Matcher exclusion=Pattern.compile("(?:不要|不看|排除|不含|without\\s+|exclude\\s+|no\\s+)([^,，。；;.!?]+)").matcher(text);
        StringBuffer remaining=new StringBuffer();
        while(exclusion.find()) {
            String phrase=exclusion.group(1);boolean recognized=false;
            for(var entry:GENRES.entrySet())if(phrase.contains(entry.getKey())){excluded.add(entry.getValue());recognized=true;}
            exclusion.appendReplacement(remaining,recognized?" ":Matcher.quoteReplacement(exclusion.group()));
            structured|=recognized;
        }
        exclusion.appendTail(remaining);text=remaining.toString();
        String categoryText=text;
        for(var entry:GENRES.entrySet()) {
            String word=entry.getKey();Pattern negative=Pattern.compile("(?:不要|不看|排除|不含|非|without\\s+|exclude\\s+|no\\s+)"+Pattern.quote(word));
            Matcher not=negative.matcher(text);if(not.find()) {excluded.add(entry.getValue());text=not.replaceAll(" ");structured=true;}
            Pattern positive=Pattern.compile(word.matches("[a-z -]+")?"\\b"+Pattern.quote(word)+"\\b":Pattern.quote(word));
            Matcher yes=positive.matcher(text);if(yes.find()) {genres.add(entry.getValue());text=yes.replaceAll(" ");structured=true;}
        }
        String category=null;
        if(categoryText.matches("(?s).*(?:动漫|动画片|\\banime\\b).*"))category="ac";
        else if(categoryText.matches("(?s).*(?:电视剧|剧集|电视剧|演的剧|主演的剧|\\btv series\\b|\\bshows?\\b).*"))category="tv";
        else if(categoryText.matches("(?s).*(?:电影|影片|片子|(?:科幻|动作|喜剧|爱情|悬疑|惊悚|恐怖|冒险|犯罪|剧情|奇幻|战争|历史|家庭)片|(?:演的|演过的|主演的|出演的|参演的|导演的|执导的)片|\\bfilms?\\b|\\bmovies?\\b).*"))category="mv";
        Integer year=null,from=null,to=null,maxRuntime=null;double rating=0;int current=LocalDate.now(clock).getYear();
        Matcher range=Pattern.compile("((?:19|20)\\d{2})\\s*(?:到|至|[-~–])\\s*((?:19|20)\\d{2})\\s*年?").matcher(text);
        if(range.find()) {from=Integer.valueOf(range.group(1));to=Integer.valueOf(range.group(2));text=range.replaceAll(" ");structured=true;}
        else {
            Matcher since=Pattern.compile("((?:19|20)\\d{2})\\s*年?\\s*(以后|之后|以来|以前|之前|前)").matcher(text);
            if(since.find()) {int y=Integer.parseInt(since.group(1));if(List.of("以前","之前","前").contains(since.group(2)))to=y-1;else from=y+(since.group(2).equals("以来")?0:1);text=since.replaceAll(" ");structured=true;}
            else {
                Matcher single=Pattern.compile("(?:19|20)\\d{2}").matcher(text);
                if(single.find()) {year=Integer.parseInt(single.group());text=single.replaceAll(" ");structured=true;}
            }
        }
        Matcher recent=Pattern.compile("近\\s*([1-9][0-9]?|几)\\s*年|最近几年|recent years|\\brecent\\b").matcher(text);
        if(recent.find()) {String n=recent.group(1);int years=n==null||n.equals("几")?5:Integer.parseInt(n);from=current-years+1;to=current;text=recent.replaceAll(" ");structured=true;}
        Matcher duration=Pattern.compile("(?:时长)?\\s*(?:不超过|少于|最多|以内|under\\s+|at most\\s+)?\\s*(\\d{1,3}|一|两|二|三)\\s*(小时|分钟|hours?|minutes?)\\s*(?:以内|以下)?").matcher(text);
        if(duration.find()) {
            String n=duration.group(1);int value=switch(n){case "一"->1;case "两","二"->2;case "三"->3;default->Integer.parseInt(n);};
            maxRuntime=duration.group(2).matches("小时|hours?")?value*60:value;text=duration.replaceAll(" ");structured=true;
        }
        Matcher score=Pattern.compile("(?:评分|rating)\\s*(?:至少|不低于|>=|above|over)?\\s*(10(?:\\.0)?|[0-9](?:\\.[0-9])?)\\s*(?:分)?\\s*(?:以上|及以上)?").matcher(text);
        if(score.find()) {rating=Double.parseDouble(score.group(1));text=score.replaceAll(" ");structured=true;}
        if(text.contains("高分")||text.contains("high rated")||text.contains("high-rated")) {rating=Math.max(rating,8);text=text.replace("高分"," ").replace("high rated"," ").replace("high-rated"," ");structured=true;}
        Set<String> directors=new TreeSet<>(),actors=new TreeSet<>(),regions=new TreeSet<>(),languages=new TreeSet<>();
        Matcher director=Pattern.compile("([\\p{IsHan}·]{2,20}?)(?:导演|执导)(?:的)?").matcher(text);
        if(director.find()) {String name=director.group(1).replaceFirst("^(?:请帮我找|帮我找|我想看|我想找|想看|想找|有哪些|有什么|看看|找|看|由)","");directors.add(name);text=director.replaceAll(" ");structured=true;}
        Matcher actor=Pattern.compile("([\\p{IsHan}·]{2,20}?)(?:主演|出演|参演|演过|演)(?:的)?").matcher(text);
        if(actor.find()) {String name=actor.group(1).replaceFirst("^(?:请帮我找|帮我找|我想看|我想找|想看|想找|有哪些|有什么|看看|找|看|由)","");actors.add(name);text=actor.replaceAll(" ");structured=true;}
        for(var entry:Map.of("美国","美国","日本","日本","韩国","韩国","英国","英国","法国","法国","国产","中国大陆").entrySet()) {
            if(text.contains(entry.getKey())) {regions.add(entry.getValue());text=text.replace(entry.getKey()," ");structured=true;}
        }
        for(String language:List.of("英语","日语","韩语","粤语","汉语普通话"))if(text.contains(language)){languages.add(language);text=text.replace(language," ");structured=true;}
        text=text.replaceAll("(?:[几一二两三四五六七八九十0-9]+\\s*(?:部|个)|不超过|少于|最多|以内|以下|帮我找|帮我搜|请搜索|搜索一下|我想看|我想找|找一下|想看|想找|我要看|找点|看点|有什么|有哪些|讲述|关于|剧情像|剧情是|有没有|有没有一些|偏|的|年|时长|电影|影片|片子|电视剧|剧集|动漫|推荐|一下|[，,。！？!?；;、]|(?<=\\s)片(?=\\s|$))"," ")
                .replaceAll("(?i)\\b(?:find|search|for|about|want|watch|i|a|an|the|movies?|films?|please|tv|series|recommend)\\b"," ").replaceAll("\\s+"," ").trim();
        if(from!=null&&to!=null&&from>to)throw new IllegalArgumentException("年份范围不正确");
        if(maxRuntime!=null&&(maxRuntime<1||maxRuntime>600))throw new IllegalArgumentException("时长条件超出支持范围");
        return new Plan(query,text,new Constraints(genres,category,year,rating,from,to,maxRuntime,excluded,directors,actors,regions,languages),structured);
    }
    public static boolean isFollowup(String query) {
        return query != null && query.matches("(?is).*(?:这部|这些|刚才|刚刚|上面|上一部|前面|片库.?id|第[一二三四五六七八九十0-9]+[部个项]).*");
    }
    public static boolean shouldSearch(String query) {
        if(query == null || query.isBlank() || isFollowup(query)) return false;
        // Quoted titles stay title searches, even when a name contains a genre or theme.
        if(query.contains("《") && query.contains("》") || query.contains("“") && query.contains("”") || query.contains("\"")) return false;
        if(query.matches("(?is).*(?:详情|简介|有哪些资源|评分是多少).*") && !query.matches("(?s).*(?:讲述|关于|剧情像|剧情是).*") ) return false;
        Plan plan=parse(query);
        if(plan.structured()) {
            boolean request=query.matches("(?is).*(?:想看|想找|找|搜|看点|有什么|有哪些|推荐|recommend|suggest|find|search).*");
            boolean filmWord=query.matches("(?is).*(?:电影|影片|片子|剧集|电视剧|动漫|片$|movies?|films?).*");
            if(request || filmWord || query.matches("(?s).*(?:高分|评分|不看|不要|排除|时长|近.{0,3}年).*") ) return true;
        }
        return query.matches("(?is).*(?:近.{0,3}年|时长|不超过|小时|分钟|烧脑|太空|穿越|复仇|末日|治愈|讲述|关于|剧情像|剧情是|主角|男主|女主|凶手|侦探|案件|结局|反转|失忆|梦境|循环|机器人|外星人|不要|不看|排除|评分.{0,4}[0-9]|[0-9]{4}.?[到至~-].?[0-9]{4}|(?:导演|执导|主演|出演)的|sci-fi|science fiction|under [0-9]|about ).*")
                || query.matches("(?s).*(?:一[个人群]|主角|男主|女主|凶手|侦探|案件|结局|反转|失忆|梦境|循环|机器人|外星人).*(?:电影|影片|片子|故事|的片).*");
    }
    public static Integer runtimeMinutes(String runtime) {
        if(runtime==null)return null;
        String value=Normalizer.normalize(runtime,Normalizer.Form.NFKC).trim();
        Matcher minutes=Pattern.compile("^(\\d{1,3})(?:\\s*(?:分钟|min(?:utes?)?))?$",Pattern.CASE_INSENSITIVE).matcher(value);
        if(minutes.matches()) {int n=Integer.parseInt(minutes.group(1));return n>0&&n<=600?n:null;}
        Matcher hours=Pattern.compile("^(\\d)\\s*(?:小时|h(?:ours?)?)\\s*(?:(\\d{1,2})\\s*(?:分(?:钟)?|m(?:in(?:utes?)?)?))?$",Pattern.CASE_INSENSITIVE).matcher(value);
        if(hours.matches())return Integer.parseInt(hours.group(1))*60+(hours.group(2)==null?0:Integer.parseInt(hours.group(2)));
        return null; // Unknown/episode/ambiguous runtimes must not pass a hard duration constraint.
    }
}
