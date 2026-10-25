package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogResearchServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    CatalogResearchService service; List<ObjectNode> docs;
    ObjectNode film(String id,String title,int year,String genre,String director,double douban,double imdb){
        ObjectNode d=mapper.createObjectNode().put("id",id).put("titleCn",title).put("year",year).put("category","mv")
            .put("doubanScore",douban).put("imdbScore",imdb).put("runtimeMinutes",100).put("resourceCount",0)
            .put("summary",id.equals("a")?"宇航员航行太空，寻找回家的航线。":"一家人经营旅馆并调查神秘失踪事件。");
        d.putArray("genres").add(genre); d.putArray("directors").add(director);d.putArray("actors").add("凯特·布兰切特");
        d.putArray("regions").add("United States of America");d.putArray("languages").add("English");
        d.put("embeddingText",d.path("summary").asText()).put("fingerprint",CatalogResearchService.fingerprint(d.path("summary").asText()));return d;
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        docs=List.of(film("a","星际样本",2024,"科幻","克里斯托弗·诺兰",7,9),film("b","迷案样本",2019,"悬疑","王家卫",9,6),film("c","旅行样本",2020,"冒险","丹尼斯·维伦纽瓦",8,8));
        JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.query(anyString(),any(RowMapper.class))).thenReturn(docs);service=new CatalogResearchService(jdbc,mapper);
    }
    ObjectNode query(String json)throws Exception{return service.query(mapper.createObjectNode().set("plan",mapper.readTree(json)),mapper.createObjectNode());}
    List<String> ids(JsonNode result){List<String> ids=new ArrayList<>();result.path("movies").forEach(m->ids.add(m.path("id").asText()));return ids;}
    @Test void orGenresAndPersonExclusionAreHardFilters()throws Exception{
        assertThat(ids(query("{\"intent\":\"search\",\"genresAny\":[\"科幻\",\"悬疑\"],\"excludedDirectors\":[\"诺兰\"]}"))).containsExactly("b");
        assertThat(ids(query("{\"intent\":\"search\",\"excludedDirectors\":[\"王家卫\"]}"))).containsExactlyInAnyOrder("a","c");
    }
    @Test void allGenresDoesNotBecomeOr()throws Exception{assertThat(ids(query("{\"intent\":\"search\",\"genresAll\":[\"科幻\",\"悬疑\"]}"))).isEmpty();}
    @Test void sourceSpecificRatingDoesNotUseOtherSource()throws Exception{
        assertThat(ids(query("{\"intent\":\"search\",\"ratingSource\":\"douban\",\"minRating\":8.5}"))).containsExactly("b");
        assertThat(ids(query("{\"intent\":\"search\",\"ratingSource\":\"imdb\",\"minRating\":8.5}"))).containsExactly("a");
    }
    @Test void yearRuntimeAndAliasesAreIndependent()throws Exception{
        assertThat(ids(query("{\"intent\":\"search\",\"regionsAny\":[\"美国\"],\"languagesAny\":[\"英语\"],\"yearFrom\":2020,\"yearTo\":2024,\"maxRuntime\":110}"))).containsExactlyInAnyOrder("a","c");
        assertThat(ids(query("{\"intent\":\"search\",\"maxRuntime\":90}"))).isEmpty();
        assertThat(ids(query("{\"intent\":\"search\",\"excludedLanguages\":[\"英语\"]}"))).isEmpty();
    }
    @Test void missingRatingCannotSatisfyMaximum()throws Exception{
        docs.get(0).remove("doubanScore");assertThat(ids(query("{\"intent\":\"search\",\"ratingSource\":\"douban\",\"maxRating\":7}"))).isEmpty();
    }
    @Test void previousResultScopeAndOrdinalUseServerIds()throws Exception{
        var context=mapper.createObjectNode();context.putArray("candidateIds").add("c").add("a");
        ObjectNode body=mapper.createObjectNode().set("plan",mapper.readTree("{\"intent\":\"search\",\"scope\":\"previousResults\",\"yearFrom\":2022}"));
        assertThat(ids(service.query(body,context))).containsExactly("a");
        body.set("plan",mapper.readTree("{\"intent\":\"details\",\"ordinal\":2}"));assertThat(service.query(body,context).path("selectedId").asText()).isEqualTo("a");
    }
    @Test void denseHitsMustMatchCurrentFingerprintAndHardFilters()throws Exception{
        var body=mapper.createObjectNode();body.set("plan",mapper.readTree("{\"intent\":\"search\",\"semanticText\":\"未知主题\",\"yearFrom\":2022}"));
        var dense=body.putArray("denseCandidates");dense.addObject().put("id","a").put("score",.8).put("fingerprint","0".repeat(64));dense.addObject().put("id","b").put("score",.9).put("fingerprint",docs.get(1).path("fingerprint").asText());
        var result=service.query(body,mapper.createObjectNode());assertThat(ids(result)).isEmpty();assertThat(result.path("semanticStaleCandidates").asInt()).isEqualTo(1);
        ((ObjectNode)dense.get(0)).put("fingerprint",docs.get(0).path("fingerprint").asText());assertThat(ids(service.query(body,mapper.createObjectNode()))).containsExactly("a");
    }
    @Test void malformedDenseEvidenceIsRejected()throws Exception{
        var body=mapper.createObjectNode();body.set("plan",mapper.readTree("{\"intent\":\"search\"}"));body.putArray("denseCandidates").addObject().put("id","a").put("score",2).put("fingerprint","0".repeat(64));
        assertThatThrownBy(()->service.query(body,mapper.createObjectNode())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void uncertainPersonExclusionCannotReturnGuaranteedCandidates()throws Exception{
        var result=query("{\"intent\":\"search\",\"excludedDirectors\":[\"不存在导演\"]}");assertThat(ids(result)).isEmpty();assertThat(result.path("unresolvedPeople").size()).isEqualTo(1);
    }
    @Test void uniqueLongNameTypoResolvesAgainstCatalog()throws Exception{
        assertThat(ids(query("{\"intent\":\"search\",\"directors\":[\"丹尼斯维伦纽瓦瓦\"]}"))).containsExactly("c");
    }
    @Test void returnedEvidenceDoesNotExposeIndexTextAndCarriesUnverifiedConditions()throws Exception{
        var result=query("{\"intent\":\"search\",\"limit\":1,\"unsupportedConditions\":[\"获奖\"]}");assertThat(result.path("movies").size()).isEqualTo(1);assertThat(result.path("truncated").asBoolean()).isTrue();assertThat(result.toString()).doesNotContain("embeddingText","fingerprint");assertThat(result.path("answer").asText()).contains("不能保证");
    }
    @Test void originalPeopleRestoreOmissionsAndReverseWrongPolarity()throws Exception{
        ObjectNode body=mapper.createObjectNode().put("message","查凯特布兰切特主演的电影，不要王家卫导演的");body.set("plan",mapper.readTree("{\"intent\":\"search\",\"directors\":[\"王家卫\"]}"));
        var found=service.query(body,mapper.createObjectNode());assertThat(ids(found)).containsExactlyInAnyOrder("a","c");assertThat(found.path("queryPlan").path("actors").get(0).asText()).isEqualTo("凯特·布兰切特");
    }
    @Test void explicitPeoplePreserveOrAndDoNotUseQuotedTitlesAsNames()throws Exception{
        ObjectNode body=mapper.createObjectNode().put("message","王家卫或丹尼斯维伦纽瓦导演的");body.set("plan",mapper.readTree("{\"intent\":\"search\",\"directorsAny\":[\"王家卫\",\"丹尼斯维伦纽瓦\"]}"));assertThat(ids(service.query(body,mapper.createObjectNode()))).containsExactlyInAnyOrder("b","c");
        body.put("message","类似《王家卫》");body.set("plan",mapper.readTree("{\"intent\":\"search\"}"));assertThat(service.query(body,mapper.createObjectNode()).path("queryPlan").path("directors").isEmpty()).isTrue();
    }

    @Test void omittedUniqueSurnameExclusionIsRestoredFromOriginalAndExactTitleSurvivesSemanticNoise()throws Exception{
        ObjectNode body=mapper.createObjectNode().put("message","排除维伦纽瓦的作品");body.set("plan",mapper.readTree("{\"intent\":\"search\"}"));assertThat(ids(service.query(body,mapper.createObjectNode()))).containsExactlyInAnyOrder("a","b");
        docs.get(0).put("aliases","阿瑪迪斯");body.remove("message");body.set("plan",mapper.readTree("{\"intent\":\"web\",\"titles\":[\"阿玛迪斯\"],\"semanticText\":\"不存在的关键词\"}"));assertThat(ids(service.query(body,mapper.createObjectNode()))).containsExactly("a");
    }

    @Test void aShortForeignSurnameInsideAnotherActorsFullNameIsNotAnAdditionalCondition()throws Exception{
        docs.get(0).putArray("actors").add("凯特·布兰切特");docs.get(1).putArray("actors").add("瑞达·凯特布");docs.get(2).putArray("actors").add("另一个演员");
        var body=mapper.createObjectNode().put("message","查凯特布兰切特主演的电影");body.putObject("plan").put("intent","search");var found=service.query(body,mapper.createObjectNode());assertThat(ids(found)).containsExactly("a");assertThat(found.path("queryPlan").path("actors").size()).isEqualTo(1);
        body.with("plan").putArray("actors").add("凯特布");assertThat(ids(service.query(body,mapper.createObjectNode()))).containsExactly("a");
    }

}
