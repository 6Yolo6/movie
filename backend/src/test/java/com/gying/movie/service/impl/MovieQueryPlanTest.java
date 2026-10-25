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
    @Test void conflictingInheritedRangeFails()throws Exception{
        var context=mapper.createObjectNode();context.set("queryPlan",mapper.readTree("{\"intent\":\"search\",\"yearFrom\":2020}"));assertThatThrownBy(()->plan("{\"intent\":\"search\",\"inheritContext\":true,\"yearTo\":2019}").inherit(context,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
}
