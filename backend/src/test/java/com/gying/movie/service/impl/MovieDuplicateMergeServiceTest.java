package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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
}
