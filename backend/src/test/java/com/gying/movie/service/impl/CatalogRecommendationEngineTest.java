package com.gying.movie.service.impl;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.gying.movie.service.impl.CatalogRecommendationEngine.*;

class CatalogRecommendationEngineTest {
    private Film film(String id,String series,List<String> genres,double rating) {
        return new Film(id,id,null,2020,"mv",null,series,genres,List.of(),List.of(),rating,10,1);
    }
    @Test void personalContentOutranksPopularUnrelatedFilmAndExcludesSeen() {
        Film seed=film("seed",null,List.of("科幻"),8), related=film("related",null,List.of("科幻"),7), unrelated=film("unrelated",null,List.of("爱情"),10);
        var result=recommend(List.of(seed,unrelated,related),Map.of("seed",3D),Set.of("seed"),List.of(),Constraints.none(),10);
        assertEquals(List.of("related","unrelated"),result.stream().map(r->r.movie().id()).toList());
        assertTrue(result.get(0).reasons().get(0).contains("seed"));
    }
    @Test void sparseCollaborativeSignalsRequireTwoIndependentCoFavorites() {
        Film seed=film("seed",null,List.of("科幻"),8), other=film("other",null,List.of("爱情"),7);
        var one=List.of(new Interaction("1","seed"),new Interaction("1","other"));
        var two=new ArrayList<>(one);two.add(new Interaction("2","seed"));two.add(new Interaction("2","other"));
        var a=recommend(List.of(seed,other),Map.of("seed",3D),Set.of("seed"),one,Constraints.none(),2).get(0);
        var b=recommend(List.of(seed,other),Map.of("seed",3D),Set.of("seed"),two,Constraints.none(),2).get(0);
        assertEquals(0D,a.signals().get("collaborative"));assertTrue(b.signals().get("collaborative")>0);
        assertTrue(b.reasons().stream().anyMatch(r->r.contains("多位")));
    }
    @Test void diversityPromotesDifferentSeriesWithComparableScores() {
        var result=recommend(List.of(film("a","series",List.of("科幻"),9),film("b","series",List.of("科幻"),8.9),film("c","other",List.of("爱情"),8.8)),Map.of(),Set.of(),List.of(),Constraints.none(),3);
        assertEquals(List.of("a","c","b"),result.stream().map(r->r.movie().id()).toList());
    }
    @Test void constraintsAreConjunctiveAndMissingProfileUsesColdStart() {
        var result=recommend(List.of(film("a",null,List.of("科幻","动作"),9),film("b",null,List.of("科幻"),10),film("c",null,List.of("科幻","动作"),7)),Map.of("missing",3D),Set.of(),List.of(),new Constraints(Set.of("科幻","动作"),"mv",2020,8),10);
        assertEquals(1,result.size());assertEquals("a",result.get(0).movie().id());
        assertEquals(0D,result.get(0).signals().get("content"));
    }
    @Test void rankingIsDeterministicAndDoesNotMutateInput() {
        var input=List.of(film("b",null,List.of("剧情"),8),film("a",null,List.of("剧情"),8));
        assertEquals(recommend(input,Map.of(),Set.of(),List.of(),Constraints.none(),2),recommend(input,Map.of(),Set.of(),List.of(),Constraints.none(),2));
        assertEquals("b",input.get(0).id());
    }

    @Test void reusableFeaturesAreUserSpecificAndContainNoIdentifiers() {
        var index=new FeatureIndex(List.of(film("science",null,List.of("科幻"),8),film("romance",null,List.of("爱情"),8)));
        var a=index.features(Map.of("science",3D));var b=index.features(Map.of("romance",3D));
        assertTrue(a.vector().containsKey("genre:科幻"));assertFalse(a.vector().containsKey("science"));
        assertFalse(a.vector().containsKey("genre:爱情"));assertTrue(b.vector().containsKey("genre:爱情"));
        assertFalse(index.features(Map.of("missing",3D,"science",Double.NaN)).personalized());
        assertThrows(UnsupportedOperationException.class,()->a.vector().put("x",1D));
    }
    @Test void duplicateNeighborEventsDoNotInflateIndependentSupport() {
        var input=List.of(film("seed",null,List.of("科幻"),8),film("other",null,List.of("爱情"),8));
        var interactions=List.of(new Interaction("1","seed"),new Interaction("1","other"),new Interaction("1","seed"),new Interaction("1","other"));
        assertEquals(0,recommend(input,Map.of("seed",3D),Set.of("seed"),interactions,Constraints.none(),2).get(0).signals().get("collaborative"));
    }
    @Test void durationAndExcludedGenresApplyBeforeRanking() {
        Film valid=new Film("valid","短科幻",null,2024,"mv",null,null,List.of("科幻"),List.of(),List.of(),8,10,1,"","",List.of(),List.of(),100);
        Film missing=film("missing",null,List.of("科幻"),10), horror=film("horror",null,List.of("科幻","恐怖"),10);
        var c=NaturalMovieQuery.parse("近几年科幻电影，不要恐怖，不超过两小时").constraints();
        assertEquals(List.of("valid"),recommend(List.of(valid,missing,horror),Map.of(),Set.of(),List.of(),c,12).stream().map(r->r.movie().id()).toList());
    }
}
