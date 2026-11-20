package com.gying.movie.service.impl;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import static com.gying.movie.service.impl.CatalogRecommendationEngine.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reproducible comparison fixture. Synthetic labels are never presented as production relevance. */
class CatalogEvaluationTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private Film read(JsonNode f) {
        return new Film(f.path("id").asText(),f.path("titleCn").asText(),f.path("titleEn").asText(),f.path("year").asInt(),f.path("category").asText(),null,null,
                strings(f.path("genres")),strings(f.path("directors")),strings(f.path("actors")),f.path("rating").asDouble(),f.path("popularity").asDouble(),1,
                f.path("summary").asText(),f.path("aliases").asText(),strings(f.path("regions")),strings(f.path("languages")),f.path("runtimeMinutes").isNumber()?f.path("runtimeMinutes").asInt():null);
    }
    private List<String> strings(JsonNode values) {List<String> result=new ArrayList<>();values.forEach(n->result.add(n.asText()));return result;}
    @Test void fixedSearchSetComparesOriginalKeywordSearchAndGroundedRetrieval() throws Exception {
        JsonNode fixture=mapper.readTree(getClass().getResourceAsStream("/catalog-evaluation/search-v1.json"));List<Film> films=new ArrayList<>();fixture.path("films").forEach(f->films.add(read(f)));
        long built=System.nanoTime();var index=new CatalogTextIndex(films);double buildMs=(System.nanoTime()-built)/1e6;
        List<Map<String,Object>> rows=new ArrayList<>();int violations=0;
        for(JsonNode c:fixture.path("cases")) {
            String query=c.path("query").asText();Set<String> relevant=new HashSet<>(strings(c.path("relevant")));
            var plan=NaturalMovieQuery.parse(query,Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneId.of("Asia/Shanghai")));
            long start=System.nanoTime();var hits=index.search(plan);double ms=(System.nanoTime()-start)/1e6;
            for(var h:hits) {if(!plan.constraints().matches(h.movie()))violations++;assertTrue(films.contains(h.movie()));assertFalse(h.reasons().isEmpty());}
            var oldIntent=CatalogAiService.intent(query);String keyword=oldIntent!=null&&!oldIntent.recommend()?oldIntent.keyword():query;
            List<String> baseline=films.stream().filter(f->(f.title()+" "+f.titleEn()+" "+f.aliases()).toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))).map(Film::id).limit(5).toList();
            List<String> retrieved=hits.stream().limit(5).map(h->h.movie().id()).toList();
            rows.add(Map.of("id",c.path("id").asText(),"split",c.path("split").asText(),"query",query,"relevant",relevant,"baselineTop5",baseline,"retrievalTop5",retrieved,"baselineHit",!Collections.disjoint(relevant,baseline),"retrievalHit",!Collections.disjoint(relevant,retrieved),"noAnswer",relevant.isEmpty(),"latencyMs",ms));
        }
        var report=mapper.createObjectNode().put("datasetVersion",fixture.path("version").asText()).put("dataKind",fixture.path("dataKind").asText()).put("cases",rows.size()).put("hardConstraintViolations",violations).put("indexBuildMs",buildMs).put("algorithm",CatalogTextIndex.VERSION);
        for(String split:List.of("development","evaluation")) {
            var subset=rows.stream().filter(r->r.get("split").equals(split)).toList();var positive=subset.stream().filter(r->!(boolean)r.get("noAnswer")).toList();
            var summary=report.putObject(split).put("positiveQueries",positive.size()).put("baselineTop5Hits",positive.stream().filter(r->(boolean)r.get("baselineHit")).count()).put("retrievalTop5Hits",positive.stream().filter(r->(boolean)r.get("retrievalHit")).count());
            summary.put("noAnswerCases",subset.stream().filter(r->(boolean)r.get("noAnswer")).count()).put("correctEmpty",subset.stream().filter(r->(boolean)r.get("noAnswer")&&((List<?>)r.get("retrievalTop5")).isEmpty()).count());
        }
        report.set("rows",mapper.valueToTree(rows));Files.createDirectories(Path.of("target/catalog-evaluation"));mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/catalog-evaluation/search-report.json").toFile(),report);
        assertEquals(40,rows.size());assertEquals(0,violations);
        assertTrue(rows.stream().filter(r->(boolean)r.get("noAnswer")).allMatch(r->((List<?>)r.get("retrievalTop5")).isEmpty()));
    }
    @Test void repeatedRankingBenchmarkUsesSameInputsAndReportsColdBuildSeparately() throws Exception {
        List<Film> films=new ArrayList<>();List<LegacyRecommendationBaseline.Film> old=new ArrayList<>();Map<String,Double> profile=new LinkedHashMap<>();Set<String> excluded=new HashSet<>();
        for(int n=0;n<2000;n++) {
            String id="film_"+n;List<String> genres=List.of("genre_"+(n%17),"genre_"+(n%5));var f=new Film(id,id,null,2000+n%25,"mv",null,"series_"+(n/3),genres,List.of("director_"+(n%31)),List.of("actor_"+(n%79)),6+n%35/10D,10+n,1);films.add(f);
            old.add(new LegacyRecommendationBaseline.Film(f.id(),f.titleCn(),f.titleEn(),f.year(),f.category(),f.posterUrl(),f.seriesName(),f.genres(),f.directors(),f.actors(),f.rating(),f.popularity(),f.resources()));
            if(n<60){profile.put(id,3D);excluded.add(id);}
        }
        long start=System.nanoTime();var index=new FeatureIndex(films);double buildMs=(System.nanoTime()-start)/1e6;var user=index.features(profile);
        List<Double> legacyTimes=new ArrayList<>(),featureTimes=new ArrayList<>();
        for(int n=0;n<7;n++) {
            start=System.nanoTime();var previous=LegacyRecommendationBaseline.recommend(old,profile,excluded,List.of(),LegacyRecommendationBaseline.Constraints.none(),12);double oldMs=(System.nanoTime()-start)/1e6;
            start=System.nanoTime();var current=index.recommend(user,excluded,List.of(),Constraints.none(),12);double newMs=(System.nanoTime()-start)/1e6;
            assertEquals(12,previous.size());assertEquals(12,current.size());assertTrue(current.stream().noneMatch(r->excluded.contains(r.movie().id())));
            if(n>=2){legacyTimes.add(oldMs);featureTimes.add(newMs);}
        }
        legacyTimes.sort(Double::compare);featureTimes.sort(Double::compare);
        var report=Map.of("dataKind","synthetic fixed 2000 films, 60 seeds; not production latency","seed",20261021,"films",2000,"seeds",60,"samples",5,"warmups",2,"legacyMedianMs",legacyTimes.get(2),"featuresMedianMs",featureTimes.get(2),"featureIndexBuildMs",buildMs,"algorithm",VERSION);
        Files.createDirectories(Path.of("target/catalog-evaluation"));mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/catalog-evaluation/recommendation-benchmark.json").toFile(),report);
    }
}
