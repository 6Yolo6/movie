package com.gying.movie.service.impl;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NaturalMovieQueryTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneId.of("Asia/Shanghai"));
    @Test void originalCareerScenarioUsesHardConditionsAndKeepsAmbiguousPreference() {
        var plan=NaturalMovieQuery.parse("找近几年偏烧脑、时长不超过两小时的科幻电影",clock);
        assertEquals(2022,plan.constraints().yearFrom());assertEquals(2026,plan.constraints().yearTo());
        assertEquals(120,plan.constraints().maxRuntime());assertEquals("mv",plan.constraints().category());
        assertEquals(Set.of("科幻"),plan.constraints().genres());assertTrue(plan.text().contains("烧脑"));
    }
    @Test void negativeGenresAreNotAlsoRequired() {
        var plan=NaturalMovieQuery.parse("找高分科幻电影，不要恐怖和爱情",clock);
        assertEquals(Set.of("科幻"),plan.constraints().genres());
        assertEquals(Set.of("恐怖","爱情"),plan.constraints().excludedGenres());
        assertEquals(8,plan.constraints().minRating());
        assertEquals(Set.of("恐怖"),NaturalMovieQuery.parse("找科幻电影，不看恐怖",clock).constraints().excludedGenres());
    }
    @Test void explicitRangeAndRatingRemainConjunctive() {
        var c=NaturalMovieQuery.parse("找2010到2020年的科幻电影，评分至少8.5分",clock).constraints();
        assertEquals(2010,c.yearFrom());assertEquals(2020,c.yearTo());assertNull(c.year());assertEquals(8.5,c.minRating());
        assertThrows(IllegalArgumentException.class,()->NaturalMovieQuery.parse("2025到2020年的电影",clock));
    }
    @Test void englishQueryAndUnknownRuntimesHaveDefinedBehavior() {
        var plan=NaturalMovieQuery.parse("Find recent sci-fi movies about time travel under 2 hours",clock);
        assertEquals("mv",plan.constraints().category());assertEquals(120,plan.constraints().maxRuntime());
        assertEquals(Set.of("科幻"),plan.constraints().genres());assertTrue(plan.text().contains("time travel"));
        assertEquals(120,NaturalMovieQuery.runtimeMinutes("2小时"));assertEquals(118,NaturalMovieQuery.runtimeMinutes("118 min"));
        assertNull(NaturalMovieQuery.runtimeMinutes("45分钟/集"));assertNull(NaturalMovieQuery.runtimeMinutes(null));
    }
    @Test void namesAndRegionsBecomeFixedFilters() {
        var c=NaturalMovieQuery.parse("找诺兰导演的美国科幻电影",clock).constraints();
        assertEquals(Set.of("诺兰"),c.directors());assertEquals(Set.of("美国"),c.regions());assertEquals("mv",c.category());
    }
    @Test void followupDoesNotLoseConversationReference() {
        assertFalse(NaturalMovieQuery.shouldSearch("这部电影时长不超过两小时吗"));
        assertFalse(NaturalMovieQuery.shouldSearch("第二部有什么资源"));
        assertTrue(NaturalMovieQuery.shouldSearch("找关于时间旅行的电影"));
    }
    @Test void shortGenreWordsCountsAndActorPhrasesBecomeFilters() {
        for(String query:List.of("想看几部高分悬疑片","想找3部高分悬疑片","高分悬疑片","帮我找两部动作片")) {
            assertTrue(NaturalMovieQuery.shouldSearch(query),query);
            var plan=NaturalMovieQuery.parse(query,clock);assertEquals("mv",plan.constraints().category());assertTrue(plan.text().isBlank(),plan.text());
        }
        var actor=NaturalMovieQuery.parse("小罗伯特唐尼演的片",clock);
        assertTrue(NaturalMovieQuery.shouldSearch("小罗伯特唐尼演的片"));
        assertEquals(Set.of("小罗伯特唐尼"),actor.constraints().actors());assertEquals("mv",actor.constraints().category());assertTrue(actor.text().isBlank(),actor.text());
        assertEquals(Set.of("小罗伯特·唐尼"),NaturalMovieQuery.parse("帮我找小罗伯特·唐尼主演的电影",clock).constraints().actors());
        assertFalse(NaturalMovieQuery.shouldSearch("《爱情公寓》"));
        assertFalse(NaturalMovieQuery.shouldSearch("这部有哪些高分推荐"));
    }
    @Test void actorMiddleDotsAreEquivalentButDifferentNamesStaySeparate() {
        var plan=NaturalMovieQuery.parse("小罗伯特唐尼演的片",clock);
        var film=new CatalogRecommendationEngine.Film("m","影片",null,2024,"mv",null,null,List.of(),List.of(),List.of("小罗伯特·唐尼"),8.5,1,0);
        assertTrue(plan.constraints().matches(film));
        var other=new CatalogRecommendationEngine.Film("n","另一影片",null,2024,"mv",null,null,List.of(),List.of(),List.of("罗伯特·帕丁森"),8.5,1,0);
        assertFalse(plan.constraints().matches(other));
    }
}
