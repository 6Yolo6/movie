package com.gying.movie.service.impl;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class MovieQueryPlanTest {
    final ObjectMapper mapper=new ObjectMapper();
    MovieQueryPlan plan(String json)throws Exception{return MovieQueryPlan.parse(mapper.readTree(json),mapper);}
    @Test void rejectsUnknownFieldsAndMalformedNumbers()throws Exception{
        for(String json:new String[]{"{\"intent\":\"search\",\"sql\":\"select 1\"}","{\"intent\":\"search\",\"yearFrom\":\"2020-2024\"}","{\"intent\":\"search\",\"limit\":100}","{\"intent\":\"search\",\"yearFrom\":2024,\"yearTo\":2020}","{\"intent\":\"search\",\"actors\":[3]}"})assertThatThrownBy(()->plan(json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void inheritanceKeepsHardAndUnverifiedConditionsWithoutTransientSelection()throws Exception{
        var previous=mapper.createObjectNode();previous.set("queryPlan",mapper.readTree("{\"intent\":\"search\",\"yearFrom\":2020,\"genresAny\":[\"悬疑\"],\"semanticText\":\"失踪调查\",\"excludedDirectors\":[\"甲\"],\"unsupportedConditions\":[\"获奖\"],\"ordinal\":1}"));
        var next=plan("{\"intent\":\"search\",\"inheritContext\":true,\"genresAny\":[],\"semanticText\":\"\",\"excludedDirectors\":[\"乙\"],\"yearTo\":2023}").inherit(previous,mapper);
        assertThat(next.integer("yearFrom",0)).isEqualTo(2020);assertThat(next.list("genresAny")).containsExactly("悬疑");assertThat(next.list("excludedDirectors")).containsExactly("甲","乙");assertThat(next.list("unsupportedConditions")).containsExactly("获奖");assertThat(next.has("ordinal")).isFalse();
    }
    @Test void independentQuestionDoesNotInherit()throws Exception{
        var context=mapper.createObjectNode();context.set("queryPlan",mapper.readTree("{\"intent\":\"search\",\"yearFrom\":2020}"));assertThat(plan("{\"intent\":\"search\"}").inherit(context,mapper).has("yearFrom")).isFalse();
    }
    @Test void conflictingValidInheritedRangesAreExplainedInsteadOfRelaxed()throws Exception{
        var context=mapper.createObjectNode();context.set("queryPlan",mapper.readTree("{\"intent\":\"search\",\"yearFrom\":2020,\"maxRuntime\":110}"));
        var conflict=plan("{\"intent\":\"search\",\"inheritContext\":true,\"yearTo\":2019}").inherit(context,mapper);
        assertThat(conflict.text("intent")).isEqualTo("clarify");assertThat(conflict.text("clarification")).contains("不早于2020年","不晚于2019年","无法同时满足","修改哪项");
        assertThat(conflict.has("yearFrom")).isFalse();assertThat(conflict.has("yearTo")).isFalse();assertThat(conflict.integer("maxRuntime",0)).isEqualTo(110);
    }
    @Test void explicitYearReplacementClearsOldRangeButKeepsOtherHardConditions()throws Exception{
        var context=mapper.createObjectNode();context.set("queryPlan",mapper.readTree("{\"intent\":\"search\",\"yearFrom\":2000,\"yearTo\":2010,\"category\":\"mv\",\"maxRuntime\":110,\"limit\":2,\"excludedDirectors\":[\"甲\"]}"));
        var original=context.deepCopy();
        var replaced=plan("{\"intent\":\"search\",\"inheritContext\":true,\"replaceRanges\":[\"year\"],\"yearFrom\":2015}").inherit(context,mapper);
        assertThat(replaced.integer("yearFrom",0)).isEqualTo(2015);assertThat(replaced.has("yearTo")).isFalse();
        assertThat(replaced.text("category")).isEqualTo("mv");assertThat(replaced.integer("maxRuntime",0)).isEqualTo(110);
        assertThat(replaced.integer("limit",0)).isEqualTo(2);assertThat(replaced.list("excludedDirectors")).containsExactly("甲");
        assertThat(replaced.has("replaceRanges")).isFalse();assertThat(replaced.json().path("inheritContext").asBoolean()).isFalse();assertThat(context).isEqualTo(original);
    }
    @Test void explicitRuntimeAndRatingReplacementsAreIndependentOfOtherDimensions()throws Exception{
        var context=mapper.createObjectNode();context.set("queryPlan",mapper.readTree("{\"intent\":\"search\",\"yearFrom\":2000,\"minRuntime\":150,\"maxRuntime\":180,\"minRating\":8,\"maxRating\":9,\"ratingSource\":\"douban\"}"));
        var replaced=plan("{\"intent\":\"search\",\"inheritContext\":true,\"replaceRanges\":[\"runtime\",\"rating\"],\"maxRuntime\":100,\"maxRating\":7}").inherit(context,mapper);
        assertThat(replaced.has("minRuntime")).isFalse();assertThat(replaced.integer("maxRuntime",0)).isEqualTo(100);
        assertThat(replaced.has("minRating")).isFalse();assertThat(replaced.number("maxRating",0)).isEqualTo(7);
        assertThat(replaced.integer("yearFrom",0)).isEqualTo(2000);assertThat(replaced.text("ratingSource")).isEqualTo("douban");
    }
    @Test void replacementRequiresKnownUniqueGroupsAndNewBoundsInAnInheritedQuery()throws Exception{
        for(String json:new String[]{
            "{\"intent\":\"search\",\"inheritContext\":true,\"replaceRanges\":[\"sql\"],\"yearFrom\":2015}",
            "{\"intent\":\"search\",\"inheritContext\":true,\"replaceRanges\":[\"year\",\"year\"],\"yearFrom\":2015}",
            "{\"intent\":\"search\",\"inheritContext\":true,\"replaceRanges\":[\"year\"]}",
            "{\"intent\":\"search\",\"replaceRanges\":[\"year\"],\"yearFrom\":2015}",
            "{\"intent\":\"search\",\"inheritContext\":true,\"replaceRanges\":\"year\",\"yearFrom\":2015}",
            "{\"intent\":\"search\",\"inheritContext\":true,\"replaceRanges\":[\"year\",\"runtime\",\"rating\",\"year\"],\"yearFrom\":2015}"
        })assertThatThrownBy(()->plan(json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void replacementDirectiveIsConsumedWhenPreviousContextIsUnavailable()throws Exception{
        var current=plan("{\"intent\":\"search\",\"inheritContext\":true,\"replaceRanges\":[\"year\"],\"yearFrom\":2015}").inherit(mapper.createObjectNode(),mapper);
        assertThat(current.has("replaceRanges")).isFalse();assertThat(current.integer("yearFrom",0)).isEqualTo(2015);assertThat(current.json().path("inheritContext").asBoolean()).isFalse();
    }
    @Test void malformedStoredContextStillFailsInsteadOfBecomingClarification()throws Exception{
        var context=mapper.createObjectNode();context.set("queryPlan",mapper.readTree("{\"intent\":\"search\",\"minRuntime\":700}"));
        assertThatThrownBy(()->plan("{\"intent\":\"search\",\"inheritContext\":true,\"maxRuntime\":100}").inherit(context,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
}
