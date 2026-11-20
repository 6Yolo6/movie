package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.entity.*;
import com.gying.movie.dto.TmdbListItem;
import com.gying.movie.service.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SeasonMetadataCompletionTest {
    TmdbClient tmdb;
    IMovieMetadataService movies;
    IMovieSourceIdentityService identities;
    IResourceLinkService resources;
    IResourceDiscoveryResultService discoveries;
    SeasonMetadataCompletion service;
    PosterStorageService posters;
    MovieMetadata first;
    ResourceLink collection;

    @BeforeEach
    void setup() {
        tmdb = mock(TmdbClient.class); movies = mock(IMovieMetadataService.class);
        identities = mock(IMovieSourceIdentityService.class); resources = mock(IResourceLinkService.class);
        discoveries = mock(IResourceDiscoveryResultService.class);
        posters = mock(PosterStorageService.class);
        service = new SeasonMetadataCompletion(tmdb, posters, movies, identities, resources, discoveries);
        first = season(1); first.setTmdbId(100L); first.setTmdbType("tv"); first.setYear(2011);
        when(movies.list(any(Wrapper.class))).thenReturn(List.of(first));
        when(movies.save(any(MovieMetadata.class))).thenReturn(true);
        when(resources.save(any(ResourceLink.class))).thenReturn(true);
        collection = new ResourceLink(); collection.setId(10L); collection.setMovieId(first.getId());
        collection.setName("破产姐妹 第1-6季合集 1080p"); collection.setType("DISK"); collection.setProvider("XUNLEI");
        collection.setStatus("ACTIVE"); collection.setLinkStatus("NORMAL"); collection.setAuditStatus(1);
        collection.setUrl("https://pan.xunlei.com/s/owned?pwd=abcd"); collection.setCode("abcd");
        when(resources.list(any(Wrapper.class))).thenReturn(List.of(collection));
    }

    @Test
    void gyingHasPriorityAndBindsAllCoveredSeasonsWithoutTouchingTmdb() {
        Map<String,Object> result = service.complete(first, existing -> {
            assertEquals(Set.of(1), existing);
            return List.of(season(2), season(3), season(4), season(5), season(6));
        });
        assertEquals(5, result.get("metadataCreated")); assertEquals(5, result.get("bound"));
        verifyNoInteractions(tmdb);
        ArgumentCaptor<ResourceLink> links = ArgumentCaptor.forClass(ResourceLink.class);
        verify(resources, times(5)).save(links.capture());
        for (ResourceLink link : links.getAllValues()) {
            assertEquals(collection.getUrl(), link.getUrl()); assertEquals("abcd",link.getCode());
            assertNull(link.getId()); assertNotEquals(first.getId(), link.getMovieId());
            assertEquals("破产姐妹 第1-6季合集 1080p", link.getName());
        }
    }

    @Test
    void tmdbFillsOnlyMissingGyingSeasonsAndDoesNotBindBeyondCollection() throws Exception {
        when(tmdb.fetchDetails("tv",100L)).thenReturn(new ObjectMapper().readTree("""
                {"id":100,"original_name":"2 Broke Girls","seasons":[
                  {"season_number":0,"air_date":"2010-01-01"},
                  {"season_number":2,"air_date":"2012-09-24"},
                  {"season_number":3,"air_date":"2013-09-23","overview":"第三季剧情"},
                  {"season_number":6,"air_date":"2016-10-10"},
                  {"season_number":7,"air_date":"2017-01-01"},
                  {"season_number":8,"air_date":"2099-01-01"}]}
                """));
        Map<String,Object> result = service.complete(first, existing -> List.of(season(2),season(4),season(5)));
        assertEquals(3,result.get("gyingCreated")); assertEquals(3,result.get("tmdbCreated"));
        assertEquals(5,result.get("bound"));
        ArgumentCaptor<MovieMetadata> rows = ArgumentCaptor.forClass(MovieMetadata.class);
        verify(movies,times(3)).save(rows.capture());
        assertEquals(List.of(3,6,7),rows.getAllValues().stream().map(MovieMetadata::getSeason).toList());
        assertEquals(2013,rows.getAllValues().get(0).getYear());
        assertEquals("第三季剧情",rows.getAllValues().get(0).getSummary());
        ArgumentCaptor<ResourceLink> links = ArgumentCaptor.forClass(ResourceLink.class);
        verify(resources,times(5)).save(links.capture());
        assertFalse(links.getAllValues().stream().anyMatch(link -> link.getMovieId().endsWith("_s7")));
    }

    @Test
    void gyingOutageFallsBackToExactTmdbWithoutInventingUnlistedSeasons() throws Exception {
        when(tmdb.fetchDetails("tv",100L)).thenReturn(new ObjectMapper().readTree(
                "{\"id\":100,\"seasons\":[{\"season_number\":2,\"air_date\":\"2012-09-24\"}]}"));
        Map<String,Object> result=service.complete(first, existing -> {throw new IllegalStateException("unavailable");});
        assertEquals(1,result.get("metadataCreated")); assertEquals(1,result.get("bound"));
        assertEquals("PARTIAL",result.get("status")); assertEquals(Set.of(3,4,5,6),result.get("missingSeasons"));
    }

    @Test
    void recoversCollectionCoverageFromDiscoveryForLegacySingleSeasonNames() {
        collection.setName("破产姐妹 第1季 (2011)");
        ResourceDiscoveryResult source=new ResourceDiscoveryResult();
        source.setResourceLinkId(collection.getId()); source.setShareUrl(collection.getUrl());
        source.setTitle("【全六季】《破产姐妹》 1-6季【1080P蓝光】");
        when(discoveries.getOne(any(Wrapper.class),eq(false))).thenReturn(source);
        Map<String,Object> result=service.complete(first,existing -> List.of(season(2),season(3),season(4),season(5),season(6)));
        assertEquals(5,result.get("bound")); assertEquals("破产姐妹 第1-6季合集 1080p",collection.getName());
        verify(resources).updateById(collection);
    }

    @Test
    void repeatedCompletionDoesNotDuplicateMetadataOrResourceBindings() {
        when(movies.list(any(Wrapper.class))).thenReturn(List.of(first,season(2),season(3),season(4),season(5),season(6)));
        when(resources.count(any(Wrapper.class))).thenReturn(1L);
        Map<String,Object> result=service.complete(first,existing -> List.of());
        assertEquals(0,result.get("metadataCreated")); assertEquals(0,result.get("bound"));
        assertEquals(5,result.get("existingBindings")); verify(movies,never()).save(any(MovieMetadata.class));
        verify(resources,never()).save(any(ResourceLink.class));
    }

    @Test
    void singleSeasonOrUnknownCollectionNeverBindsEverySeason() {
        for(String name:List.of("破产姐妹 第1季 1080P", "破产姐妹 全集 1080P")) {
            collection.setName(name);
            Map<String,Object> result=service.complete(first,existing -> List.of(season(2),season(3)));
            assertEquals(0,result.get("bound"));
        }
        verify(resources,never()).save(any(ResourceLink.class));
    }

    @Test
    void invalidOrFolderScopedLinksAreNotPropagated() {
        collection.setLinkStatus("INVALID");
        assertEquals(0,service.complete(first,existing -> List.of(season(2))).get("bound"));
        collection.setLinkStatus("NORMAL"); collection.setUrl("https://pan.quark.cn/s/owned?fid=season1");
        assertEquals(0,service.complete(first,existing -> List.of(season(2))).get("bound"));
        verify(resources,never()).save(any(ResourceLink.class));
    }

    @Test
    void ambiguousTmdbMatchDoesNotCreateMetadata() {
        first.setTmdbId(null);first.setTmdbType(null);
        TmdbListItem a=new TmdbListItem(); a.setTmdbId(1L);a.setMediaType("tv");a.setTitle("破产姐妹");a.setReleaseDate("2011-01-01");
        TmdbListItem b=new TmdbListItem(); b.setTmdbId(2L);b.setMediaType("tv");b.setTitle("破产姐妹");b.setReleaseDate("2011-02-01");
        when(tmdb.searchMulti("破产姐妹",20)).thenReturn(List.of(a,b));
        Map<String,Object> result=service.complete(first,existing -> List.of());
        assertEquals("PARTIAL",result.get("status"));verify(tmdb,never()).fetchDetails(anyString(),anyLong());
        verify(movies,never()).save(any(MovieMetadata.class));
    }

    @Test
    void existingCanonicalTmdbIdentityIsReusedRatherThanDuplicated() throws Exception {
        when(tmdb.fetchDetails("tv",100L)).thenReturn(new ObjectMapper().readTree(
                "{\"id\":100,\"seasons\":[{\"season_number\":2,\"air_date\":\"2012-09-24\"}]}"));
        MovieSourceIdentity identity = new MovieSourceIdentity(); identity.setMovieId("canonical-season-two");
        when(identities.getOne(any(Wrapper.class),eq(false))).thenReturn(identity);
        MovieMetadata canonical=season(2); canonical.setId(identity.getMovieId());
        when(movies.getById(canonical.getId())).thenReturn(canonical);
        Map<String,Object> result=service.complete(first,existing -> List.of());
        assertEquals(1,result.get("bound")); verify(movies,never()).save(any(MovieMetadata.class));
        ArgumentCaptor<ResourceLink> link=ArgumentCaptor.forClass(ResourceLink.class);
        verify(resources).save(link.capture()); assertEquals(canonical.getId(),link.getValue().getMovieId());
        assertEquals("COLLECTION_BINDING",link.getValue().getSource());
    }

    private static MovieMetadata season(int number) {
        MovieMetadata movie=new MovieMetadata(); movie.setId("gying_s"+number);
        movie.setTitleCn("破产姐妹 第"+number+"季");movie.setSeriesName("破产姐妹");
        movie.setCategory("tv");movie.setSeason(number);movie.setStatus("ACTIVE");return movie;
    }
    @Test void missingSeasonPosterNeverCopiesAnchorArtwork() throws Exception {
        first.setPosterUrl("tv/fourth-season/384.avif");
        when(tmdb.fetchDetails("tv",100L)).thenReturn(new ObjectMapper().readTree("""
            {"id":100,"seasons":[{"season_number":2,"air_date":"2020-01-01","poster_path":"/s2.jpg"}]}
            """));
        when(posters.storeTmdbSeasonPoster(100L,2,"/s2.jpg")).thenReturn("tmdb/tv/100/season-2/poster.jpg");
        service.complete(first, existing -> List.of());
        ArgumentCaptor<MovieMetadata> created=ArgumentCaptor.forClass(MovieMetadata.class);
        verify(movies).save(created.capture());
        assertEquals("tmdb/tv/100/season-2/poster.jpg",created.getValue().getPosterUrl());
        verify(posters,never()).storeTmdbPoster(any(),any(),any());
    }


    @Test void bindingUsesRefreshedGyingPosterRatherThanPreSyncSnapshot() {
        MovieMetadata stale = season(2); stale.setPosterUrl("tv/wrong-season/384.avif");
        when(movies.list(any(Wrapper.class))).thenReturn(List.of(first,stale));
        MovieMetadata refreshed = season(2); refreshed.setPosterUrl("tv/actual-second/384.avif");
        service.complete(first, existing -> List.of(refreshed));
        ArgumentCaptor<MovieMetadata> updates=ArgumentCaptor.forClass(MovieMetadata.class);
        verify(movies).updateById(updates.capture());
        assertEquals("tv/actual-second/384.avif",updates.getValue().getPosterUrl());
    }
}
