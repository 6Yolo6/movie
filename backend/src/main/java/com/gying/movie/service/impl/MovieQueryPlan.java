package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** A validated data contract. No model-generated SQL, URL, user identity or executable action. */
public final class MovieQueryPlan {
    private static final Set<String> LISTS=Set.of("titles","actors","actorsAny","directors","directorsAny","excludedActors","excludedDirectors","genresAny","genresAll","excludedGenres","regionsAny","languagesAny","excludedLanguages","excludedIds","unsupportedConditions");
    private static final Set<String> TEXTS=Set.of("intent","semanticText","seedTitle","movieId","category","ratingSource","sort","scope","clarification");
    private static final Set<String> NUMBERS=Set.of("yearFrom","yearTo","minRuntime","maxRuntime","minRating","maxRating","limit","ordinal");
    private static final Map<String,List<String>> RANGES=Map.of("year",List.of("yearFrom","yearTo"),"runtime",List.of("minRuntime","maxRuntime"),"rating",List.of("minRating","maxRating"));
    private final ObjectNode value;
    private MovieQueryPlan(ObjectNode value){this.value=value;}
    public static MovieQueryPlan parse(JsonNode source,ObjectMapper mapper){
        if(source==null||!source.isObject()||source.toString().length()>10000)throw new IllegalArgumentException("无效的影片查询计划");
        ObjectNode out=mapper.createObjectNode();
        source.fields().forEachRemaining(e->{String k=e.getKey();JsonNode v=e.getValue();
            if(!LISTS.contains(k)&&!TEXTS.contains(k)&&!NUMBERS.contains(k)&&!k.equals("inheritContext")&&!k.equals("replaceRanges"))throw new IllegalArgumentException("查询计划包含未知字段");
            if(v.isNull())return;
            if(k.equals("replaceRanges")){
                if(!v.isArray()||v.size()>RANGES.size())throw new IllegalArgumentException("无效的范围替换条件");
                var list=out.putArray(k);Set<String> seen=new HashSet<>();
                for(JsonNode item:v){
                    if(!item.isTextual()||!RANGES.containsKey(item.asText())||!seen.add(item.asText()))throw new IllegalArgumentException("无效的范围替换条件");
                    list.add(item.asText());
                }
            }else if(LISTS.contains(k)){
                if(!v.isArray()||v.size()>(k.equals("excludedIds")?50:10))throw new IllegalArgumentException("查询条件数量超出范围");
                var list=out.putArray(k);Set<String> seen=new HashSet<>();for(JsonNode item:v){
                    if(!item.isTextual()||item.asText().length()>(k.equals("unsupportedConditions")?200:80)||item.asText().chars().anyMatch(c->c<32))throw new IllegalArgumentException("查询条件格式无效");
                    String text=item.asText().trim();if(!text.isBlank()&&seen.add(text))list.add(text);
                }
            }else if(TEXTS.contains(k)){
                int max=k.equals("semanticText")||k.equals("clarification")?300:80;
                if(!v.isTextual()||v.asText().length()>max||v.asText().chars().anyMatch(c->c<32))throw new IllegalArgumentException("查询文本格式无效");
                out.put(k,v.asText().trim());
            }else if(NUMBERS.contains(k)){
                if(!v.isNumber()||!Double.isFinite(v.asDouble()))throw new IllegalArgumentException("查询数值格式无效");
                if(!k.endsWith("Rating")&&!v.isIntegralNumber())throw new IllegalArgumentException("查询条件需要整数");
                double n=v.asDouble();double max=k.endsWith("Rating")?10:k.startsWith("year")?2100:k.equals("limit")?20:k.equals("ordinal")?30:600;
                double min=k.startsWith("year")?1900:k.endsWith("Rating")?0:1;
                if(n<min||n>max)throw new IllegalArgumentException("查询数值超出范围");out.set(k,v.deepCopy());
            }else {if(!v.isBoolean())throw new IllegalArgumentException("无效的上下文继承条件");out.put(k,v.asBoolean());}
        });
        oneOf(out,"intent",Set.of("search","similar","recommend","details","web","clarify"));
        oneOf(out,"category",Set.of("","mv","tv","ac"));oneOf(out,"ratingSource",Set.of("any","douban","imdb","tmdb"));
        oneOf(out,"sort",Set.of("relevance","rating_desc","year_asc","year_desc","popularity_desc"));oneOf(out,"scope",Set.of("catalog","previousResults"));
        for(String range:values(out,"replaceRanges")){
            if(!out.path("inheritContext").asBoolean()||RANGES.get(range).stream().noneMatch(out::has))throw new IllegalArgumentException("范围替换需要继承上下文及新的范围条件");
        }
        for(String pair:List.of("yearFrom:yearTo","minRuntime:maxRuntime","minRating:maxRating")){String[] k=pair.split(":");if(out.has(k[0])&&out.has(k[1])&&out.path(k[0]).asDouble()>out.path(k[1]).asDouble())throw new IllegalArgumentException("查询条件上下限矛盾");}
        for(String k:List.of("movieId")){if(out.has(k)&&!out.path(k).asText().isBlank()&&!out.path(k).asText().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("无效的片库标识");}
        for(String id:values(out,"excludedIds"))if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("无效的片库标识");
        if(!out.has("intent"))throw new IllegalArgumentException("查询计划缺少意图");
        return new MovieQueryPlan(out);
    }
    private static void oneOf(ObjectNode value,String key,Set<String> choices){if(value.has(key)&&!choices.contains(value.path(key).asText()))throw new IllegalArgumentException("查询计划枚举值无效");}
    public MovieQueryPlan inherit(JsonNode context,ObjectMapper mapper){
        if(!value.path("inheritContext").asBoolean())return this;
        if(!context.path("queryPlan").isObject()){
            ObjectNode independent=value.deepCopy();independent.remove("replaceRanges");independent.put("inheritContext",false);return parse(independent,mapper);
        }
        // The stored context must be valid before a conflicting intersection can be explained.
        ObjectNode previous=parse(context.path("queryPlan"),mapper).json();ObjectNode merged=previous.deepCopy();
        merged.remove(List.of("clarification","ordinal","movieId","replaceRanges"));
        // Replacement is explicit; adding a bound otherwise keeps the other bound.
        for(String range:list("replaceRanges"))merged.remove(RANGES.get(range));
        value.fields().forEachRemaining(e->{
            if(e.getKey().equals("replaceRanges"))return;
            if(e.getValue().isArray()&&e.getValue().isEmpty())return;
            if(Set.of("semanticText","seedTitle").contains(e.getKey())&&e.getValue().asText().isBlank())return;
            if((e.getKey().startsWith("excluded")||e.getKey().equals("unsupportedConditions"))&&e.getValue().isArray()){
                var a=merged.withArray(e.getKey());Set<String> seen=new HashSet<>(values(merged,e.getKey()));for(JsonNode item:e.getValue())if(seen.add(item.asText()))a.add(item);
            }else merged.set(e.getKey(),e.getValue().deepCopy());
        });
        merged.put("inheritContext",false);
        List<String> conflicts=new ArrayList<>();
        for(String range:List.of("year","runtime","rating")){
            var fields=RANGES.get(range);String lower=fields.get(0),upper=fields.get(1);
            if(merged.has(lower)&&merged.has(upper)&&merged.path(lower).asDouble()>merged.path(upper).asDouble()){
                String label=switch(range){case "year"->"上映年份";case "runtime"->"片长";default->"评分";};
                conflicts.add("上一轮"+label+"条件："+bounds(previous,range)+"；本轮明确条件："+bounds(value,range)+"。继承后"+bounds(merged,range)+"，无法同时满足。");
                merged.remove(fields);
            }
        }
        if(!conflicts.isEmpty()){
            String message=String.join(" ",conflicts),question=" 请确认要修改哪项条件。";
            merged.put("intent","clarify").put("clarification",message.substring(0,Math.min(300-question.length(),message.length()))+question);
        }
        return parse(merged,mapper);
    }
    private static String bounds(JsonNode node,String range){
        var fields=RANGES.get(range);String lower=fields.get(0),upper=fields.get(1);
        String unit=range.equals("year")?"年":range.equals("runtime")?"分钟":"分";
        List<String> conditions=new ArrayList<>();
        if(node.has(lower))conditions.add((range.equals("year")?"不早于":"至少")+node.path(lower).asText()+unit);
        if(node.has(upper))conditions.add((range.equals("year")?"不晚于":"不超过")+node.path(upper).asText()+unit);
        return String.join("、",conditions);
    }
    private static List<String> values(JsonNode n,String key){List<String> values=new ArrayList<>();for(JsonNode v:n.path(key))values.add(v.asText());return List.copyOf(values);}
    public List<String> list(String key){return values(value,key);}
    public String text(String key){return value.path(key).asText("");}
    public int integer(String key,int fallback){return value.path(key).asInt(fallback);}
    public boolean has(String key){return value.has(key);}
    public double number(String key,double fallback){return value.path(key).asDouble(fallback);}
    public ObjectNode json(){return value.deepCopy();}
}
