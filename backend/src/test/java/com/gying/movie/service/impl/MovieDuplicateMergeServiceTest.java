package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieDuplicateMergeServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final MovieDuplicateMergeService service=new MovieDuplicateMergeService(jdbc,new ObjectMapper(),mock(PlatformTransactionManager.class));
    @Test void requiresExplicitNonemptyReviewedPairsWithoutOverlappingIdsOrBulkSelection() {
        assertThrows(ResponseStatusException.class,()->service.preview(null,7L));
        assertThrows(ResponseStatusException.class,()->service.preview(new MovieDuplicateMergeService.PreviewRequest(List.of()),7L));
        assertThrows(ResponseStatusException.class,()->service.preview(new MovieDuplicateMergeService.PreviewRequest(List.of(
            new MovieDuplicateMergeService.Pair("a","b"),new MovieDuplicateMergeService.Pair("b","c"))),7L));
        List<MovieDuplicateMergeService.Pair> bulk=new ArrayList<>(); for(int i=0;i<21;i++) bulk.add(new MovieDuplicateMergeService.Pair("from"+i,"to"+i));
        assertThrows(ResponseStatusException.class,()->service.preview(new MovieDuplicateMergeService.PreviewRequest(bulk),7L));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsUnconfirmedOrMalformedMergeBeforeAnyDatabaseAccess() {
        String id=UUID.randomUUID().toString();
        assertThrows(ResponseStatusException.class,()->service.merge(new MovieDuplicateMergeService.MergeRequest(id,id,false),7L));
        assertThrows(ResponseStatusException.class,()->service.merge(new MovieDuplicateMergeService.MergeRequest(id,id,null),7L));
        assertThrows(ResponseStatusException.class,()->service.merge(new MovieDuplicateMergeService.MergeRequest("all",id,true),7L));
        assertThrows(ResponseStatusException.class,()->service.merge(new MovieDuplicateMergeService.MergeRequest(id,"all",true),7L));
        verifyNoInteractions(jdbc);
    }
    @Test void metadataLookupResolvesAnOldExactIdBeforeReadingTheCanonicalRow() {
        var mapper=mock(com.gying.movie.mapper.MovieMetadataMapper.class);
        var aliases=mock(MovieMergeAliasResolver.class);
        var movies=new MovieMetadataServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(movies,"baseMapper",mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(movies,"mergeAliases",aliases);
        var canonical=new com.gying.movie.entity.MovieMetadata(); canonical.setId("KeepCase");
        when(aliases.resolve("OldCase")).thenReturn("KeepCase"); when(mapper.selectById("KeepCase")).thenReturn(canonical);
        assertSame(canonical,movies.getById("OldCase")); verify(mapper,never()).selectById("OldCase");
    }

    private static final MovieDuplicateMergeService.Pair BINDING_PAIR=new MovieDuplicateMergeService.Pair("tmdb_movie_42","GY42");
    private static Map<String,Object> bindingMovie(boolean source) {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("id",source?BINDING_PAIR.fromMovieId():BINDING_PAIR.toMovieId());
        row.put("status","ACTIVE"); row.put("category","mv"); row.put("tmdb_type","movie");
        if(source) row.put("tmdb_id",42L); return row;
    }
    private static Map<String,Object> bindingIdentity(boolean source) {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("id",source?11L:12L);
        row.put("movie_id",source?BINDING_PAIR.fromMovieId():BINDING_PAIR.toMovieId());
        row.put("source",source?"TMDB":"GYING"); row.put("source_type",source?"movie":"mv");
        row.put("external_id",source?"42":"Upstream42"); row.put("season",0); row.put("match_status","CONFIRMED"); return row;
    }
    @Test void bindingRequiresRealGyingProvenanceAndConfirmedMatchingTmdbIdentity() {
        var source=bindingMovie(true); var target=bindingMovie(false); var blockers=new ArrayList<Map<String,Object>>();
        var confirmed=bindingIdentity(true); var gying=bindingIdentity(false);
        var binding=MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(confirmed,gying),blockers);
        assertNotNull(binding); assertEquals(42L,binding.tmdbId()); assertTrue(binding.changesMetadata()); assertTrue(blockers.isEmpty());
        assertEquals(11L,binding.sourceIdentityId()); assertEquals(12L,binding.gyingIdentityId());
        for(String status:List.of("AUTO","REJECTED","PENDING")) {
            confirmed.put("match_status",status);
            assertNull(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(confirmed,gying),blockers));
        }
        confirmed.put("match_status","CONFIRMED");
        assertNull(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(confirmed),blockers));
        for(String status:List.of("REJECTED","PENDING")) {
            gying.put("match_status",status);
            assertNull(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(confirmed,gying),blockers));
        }
        gying.put("match_status","AUTO");
        assertNotNull(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(confirmed,gying),blockers));
        assertTrue(blockers.isEmpty()); assertFalse(target.containsKey("tmdb_id"));
    }
    @Test void bindingRejectsConfirmedIdentityAndExistingMetadataConflicts() {
        for(String field:List.of("external_id","source_type","season")) {
            var confirmed=bindingIdentity(true); confirmed.put(field,field.equals("season")?1:"different");
            var blockers=new ArrayList<Map<String,Object>>();
            assertNull(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,bindingMovie(true),bindingMovie(false),List.of(confirmed,bindingIdentity(false)),blockers));
            assertEquals("TMDB_BINDING_IDENTITY_CONFLICT",blockers.get(0).get("code"));
        }
        for(String field:List.of("external_id","source_type","season")) {
            var retained=bindingIdentity(true); retained.put("id",13L); retained.put("movie_id",BINDING_PAIR.toMovieId());
            retained.put(field,field.equals("season")?2:"different"); var blockers=new ArrayList<Map<String,Object>>();
            assertNull(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,bindingMovie(true),bindingMovie(false),List.of(bindingIdentity(true),bindingIdentity(false),retained),blockers));
            assertEquals("TMDB_BINDING_IDENTITY_CONFLICT",blockers.get(0).get("code"));
        }
        for(String field:List.of("tmdb_id","tmdb_type")) {
            var target=bindingMovie(false); target.put(field,field.equals("tmdb_id")?99L:"tv");
            var blockers=new ArrayList<Map<String,Object>>();
            assertNull(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,bindingMovie(true),target,List.of(bindingIdentity(true),bindingIdentity(false)),blockers));
            assertEquals("TMDB_BINDING_METADATA_CONFLICT",blockers.get(0).get("code"));
        }
    }
    @Test void bindingPreservesAlreadyBoundTargetAndRequiresExplicitSameTelevisionSeason() {
        var source=bindingMovie(true); var target=bindingMovie(false); target.put("tmdb_id",42L);
        var confirmed=bindingIdentity(true); var gying=bindingIdentity(false); var blockers=new ArrayList<Map<String,Object>>();
        assertFalse(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(confirmed,gying),blockers).changesMetadata());
        source.put("tmdb_type","tv"); source.put("category","tv"); source.put("season",2);
        target.put("tmdb_type","tv"); target.put("category","tv"); target.put("season",2); target.remove("tmdb_id");
        confirmed.put("source_type","tv"); confirmed.put("season",2); gying.put("source_type","tv"); gying.put("season",2);
        assertEquals(2,MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(confirmed,gying),blockers).season());
        target.remove("season");
        assertNull(MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(confirmed,gying),blockers));
        assertTrue(blockers.isEmpty());
    }
    @Test void animatedFilmUsesItsRealGyingAnimeIdentityWithMovieSeasonZero() {
        var source=bindingMovie(true); var target=bindingMovie(false);
        source.put("category","ac"); target.put("category","ac");
        var gying=bindingIdentity(false); gying.put("source_type","ac"); var blockers=new ArrayList<Map<String,Object>>();
        var binding=MovieDuplicateMergeService.plannedTmdbBinding(BINDING_PAIR,source,target,List.of(bindingIdentity(true),gying),blockers);
        assertNotNull(binding); assertEquals("movie",binding.tmdbType()); assertEquals(0,binding.season()); assertTrue(blockers.isEmpty());
    }
}
