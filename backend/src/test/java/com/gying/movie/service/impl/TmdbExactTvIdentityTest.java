package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.service.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TmdbExactTvIdentityTest {
    final ObjectMapper mapper=new ObjectMapper();
    final ResourceHubProperties properties=new ResourceHubProperties();
    final TmdbClient tmdb=mock(TmdbClient.class);
    final IMovieMetadataService movies=mock(IMovieMetadataService.class);
    final IMovieSourceIdentityService identities=mock(IMovieSourceIdentityService.class);
    final TmdbMetadataSyncServiceImpl sync=new TmdbMetadataSyncServiceImpl(tmdb,mock(PosterStorageService.class),properties,
            movies,identities,mock(IResourceHubTaskService.class),mock(IResourceDiscoveryService.class),
            mock(IResourceDiscoveryResultService.class),mock(IResourceLinkService.class),mapper,mock(TmdbGyingResourceFollowUpService.class));
    @BeforeEach void fixture() {
        properties.setEnabled(true);
        when(tmdb.fetchDetails("tv",204541)).thenReturn(mapper.createObjectNode().put("id",204541)
                .put("first_air_date","2023-01-15").put("name","三体").put("original_name","Three-Body"));
        when(movies.save(any())).thenReturn(true); when(movies.updateById(any())).thenReturn(true); when(identities.save(any())).thenReturn(true);
    }
    MovieMetadata movie(String id) {
        var m=new MovieMetadata();m.setId(id);m.setStatus("ACTIVE");m.setTmdbType("tv");m.setTmdbId(204541L);
        m.setCategory("tv");m.setSeason(1);m.setYear(2023);m.setTitleCn("三体");return m;
    }
    @Test void exactExternalIdentityCreatesOnlyADeterministicTvRecord() {
        MovieMetadata m=sync.syncExactTv(204541,2023);
        assertEquals("tmdb_tv_204541",m.getId());assertEquals("三体",m.getTitleCn());
        verify(movies).save(m);verify(identities).save(any());verify(movies,never()).getOne(any(),eq(false));
    }
    @Test void anUnboundSameTitleNeverBecomesAnAutomaticIdentityMatch() {
        when(movies.count(any())).thenReturn(1L);
        assertThrows(IllegalStateException.class,()->sync.syncExactTv(204541,2023));
        verify(movies,never()).save(any());verify(movies,never()).updateById(any());verify(identities,never()).save(any());
    }
    @Test void ambiguousCanonicalRecordsCannotBeUpdatedOrDuplicated() {
        var first=movie("first");var second=movie("second");
        when(movies.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(first,second));when(movies.getById("first")).thenReturn(first);when(movies.getById("second")).thenReturn(second);
        assertThrows(IllegalStateException.class,()->sync.syncExactTv(204541,2023));
        verify(movies,never()).save(any());verify(movies,never()).updateById(any());
    }
    @Test void aliasesReuseTheirSingleActiveCanonicalRecord() {
        var canonical=movie("canonical");var alias=movie("alias");
        when(movies.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(alias,canonical));
        when(movies.getById("alias")).thenReturn(canonical);when(movies.getById("canonical")).thenReturn(canonical);
        assertEquals("canonical",sync.syncExactTv(204541,2023).getId());
        verify(movies,never()).save(any());verify(movies).updateById(canonical);
    }
    @Test void wrongYearAndWrongRemoteIdCannotWriteMetadata() {
        assertThrows(IllegalStateException.class,()->sync.syncExactTv(204541,2022));
        when(tmdb.fetchDetails("tv",204541)).thenReturn(mapper.createObjectNode().put("id",99999).put("first_air_date","2023-01-15"));
        assertThrows(IllegalStateException.class,()->sync.syncExactTv(204541,2023));
        verifyNoInteractions(movies,identities);
    }
}
