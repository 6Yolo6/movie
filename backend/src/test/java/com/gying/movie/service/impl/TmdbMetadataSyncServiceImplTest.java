package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.MovieSearchCandidate;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.service.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TmdbMetadataSyncServiceImplTest {
    private final ObjectMapper json=new ObjectMapper();
    private final TmdbClient client=mock(TmdbClient.class);
    private final IMovieMetadataService movies=mock(IMovieMetadataService.class);
    private final IMovieSourceIdentityService identities=mock(IMovieSourceIdentityService.class);
    private final ResourceHubProperties properties=new ResourceHubProperties();

    private TmdbMetadataSyncServiceImpl service() {
        properties.setEnabled(true); properties.getTmdb().setApiKey("test-only-placeholder");
        return new TmdbMetadataSyncServiceImpl(client,mock(PosterStorageService.class),properties,movies,identities,
            mock(IResourceHubTaskService.class),mock(IResourceDiscoveryService.class),mock(IResourceDiscoveryResultService.class),
            mock(IResourceLinkService.class),json);
    }
    private MovieSearchCandidate candidate(String type) throws Exception {
        when(client.fetchDetails(type,247718L)).thenReturn(json.readTree("""
            {"id":247718,"title":"Retained Film","original_title":"Retained Film",
             "release_date":"2025-01-01","name":"Retained Series","original_name":"Retained Series",
             "first_air_date":"2025-01-01","seasons":[{"season_number":1}]}
            """));
        return new MovieSearchCandidate(247718L,type,"Retained",null,2025,100);
    }
    private static MovieMetadata row(String id,String status) {
        MovieMetadata movie=new MovieMetadata(); movie.setId(id); movie.setStatus(status); movie.setSeason(1);
        movie.setPosterUrl("existing.jpg"); return movie;
    }

    @ParameterizedTest @ValueSource(strings={"tmdb","series","title","tv-title"})
    void everyLookupResolvesRetiredOriginalBeforeUpdatingRetainedCanonical(String lookup) throws Exception {
        String type=lookup.equals("series")||lookup.equals("tv-title")?"tv":"movie";
        var original=row("tmdb_tv_247718_s1","DELETED"); original.setDeletedAt(LocalDateTime.of(2026,11,10,23,56));
        var retained=row("1YKW","ACTIVE"); retained.setTmdbId(247718L); retained.setTmdbType(type);
        if(lookup.equals("tmdb")) when(movies.getOne(any(),eq(false))).thenReturn(original);
        else if(lookup.equals("tv-title")) when(movies.getOne(any(),eq(false))).thenReturn(null,null,original);
        else when(movies.getOne(any(),eq(false))).thenReturn(null,original);
        when(movies.getById(original.getId())).thenReturn(retained);
        assertSame(retained,service().syncSelectedCandidate(candidate(type)));
        verify(movies).getById(original.getId()); verify(movies).updateById(retained);
        verify(movies,never()).updateById(original); verify(movies,never()).save(any());
        assertEquals("DELETED",original.getStatus()); assertNotNull(original.getDeletedAt());
        assertNull(original.getTmdbLastSyncAt());
    }

    @ParameterizedTest @ValueSource(strings={"unmapped-deleted","missing-canonical","inactive-canonical","deleted-canonical"})
    void retiredIdentityWithoutActiveCanonicalCannotBeUpdatedOrInserted(String state) throws Exception {
        var original=row("tmdb_tv_247718_s1","DELETED"); original.setDeletedAt(LocalDateTime.of(2026,11,10,23,56));
        MovieMetadata retained=switch(state) {
            case "unmapped-deleted" -> original;
            case "missing-canonical" -> null;
            case "inactive-canonical" -> row("1YKW","INACTIVE");
            default -> { var movie=row("1YKW","ACTIVE"); movie.setDeletedAt(LocalDateTime.now()); yield movie; }
        };
        when(movies.getOne(any(),eq(false))).thenReturn(original);
        when(movies.getById(original.getId())).thenReturn(retained);
        assertThrows(IllegalStateException.class,()->service().syncSelectedCandidate(candidate("tv")));
        verify(movies,never()).updateById(any()); verify(movies,never()).save(any()); verifyNoInteractions(identities);
    }

    @Test void brandNewIdentityStillInsertsWhenAllLookupsAreEmpty() throws Exception {
        var result=service().syncSelectedCandidate(candidate("movie"));
        assertEquals(247718L,result.getTmdbId()); verify(movies).save(result);
        verify(movies,never()).getById(any()); verify(movies,never()).updateById(any());
    }
}
