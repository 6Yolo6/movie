package com.gying.movie.service.impl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.entity.*;
import com.gying.movie.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MovieCollectionCompletionTest {
    @Test void completesMovieAndAnimeCollectionsWithoutTvSeasonIds() throws Exception {
        for (String category : List.of("mv", "ac")) {
            TmdbClient tmdb = mock(TmdbClient.class);
            PosterStorageService posters = mock(PosterStorageService.class);
            IMovieMetadataService movies = mock(IMovieMetadataService.class);
            IMovieSourceIdentityService identities = mock(IMovieSourceIdentityService.class);
            MovieMetadata anchor = new MovieMetadata(); anchor.setId("first"); anchor.setTitleCn("示例系列");
            anchor.setCategory(category); anchor.setTmdbType("movie"); anchor.setTmdbId(10L);
            anchor.setYear(2020); anchor.setStatus("ACTIVE");
            MovieMetadata gying = new MovieMetadata(); gying.setId("gying-second"); gying.setTitleCn("示例系列2");
            gying.setCategory(category); gying.setYear(2021); gying.setPosterUrl("mv/second/384.avif");
            MovieMetadata stale = new MovieMetadata();
            org.springframework.beans.BeanUtils.copyProperties(gying, stale);
            stale.setPosterUrl("tmdb/movie/11/poster.jpg");
            when(movies.list(any(Wrapper.class))).thenReturn("ac".equals(category) ? List.of(anchor,stale) : List.of(anchor));
            when(movies.save(any(MovieMetadata.class))).thenReturn(true);
            when(tmdb.fetchDetails("movie", 10L)).thenReturn(new ObjectMapper().readTree("{\"id\":10,\"belongs_to_collection\":{\"id\":99}}"));
            when(tmdb.fetchCollection(99)).thenReturn(new ObjectMapper().readTree("""
                {"id":99,"name":"示例系列","parts":[
                    {"id":10,"title":"示例系列","release_date":"2020-01-01"},
                    {"id":11,"title":"示例系列2","release_date":"2021-01-01"},
                    {"id":12,"title":"示例系列3","release_date":"2022-01-01","poster_path":"/three.jpg"},
                    {"id":13,"title":"示例系列4","release_date":"2099-01-01"}]}
                """));
            Map<String,Object> result = new MovieCollectionCompletion(tmdb, posters, movies, identities)
                    .complete(anchor, () -> List.of(gying));
            assertEquals("ac".equals(category) ? 0 : 1, result.get("gyingCreated")); assertEquals(1, result.get("tmdbCreated"));
            assertEquals(0, result.get("bound")); assertEquals(11L, gying.getTmdbId());
            assertEquals("mv/second/384.avif", gying.getPosterUrl());
            ArgumentCaptor<MovieMetadata> created = ArgumentCaptor.forClass(MovieMetadata.class);
            verify(movies).save(created.capture());
            assertEquals("tmdb_movie_12", created.getValue().getId());
            assertEquals(category, created.getValue().getCategory()); assertEquals(3, created.getValue().getSeason());
            verify(tmdb, never()).fetchDetails(eq("tv"), anyLong());
        }
    }
    @Test void doesNotInventSequelsWhenNoCollectionExists() throws Exception {
        TmdbClient tmdb = mock(TmdbClient.class);
        IMovieMetadataService movies = mock(IMovieMetadataService.class);
        MovieMetadata anchor = new MovieMetadata(); anchor.setId("single"); anchor.setCategory("mv");
        anchor.setTmdbId(10L); anchor.setTmdbType("movie");
        when(tmdb.fetchDetails("movie",10L)).thenReturn(new ObjectMapper().readTree("{\"id\":10}"));
        Map<String,Object> result = new MovieCollectionCompletion(tmdb,mock(PosterStorageService.class),movies,
                mock(IMovieSourceIdentityService.class)).complete(anchor, List::of);
        assertEquals(0,result.get("metadataCreated")); verify(movies,never()).save(any(MovieMetadata.class));
    }
}
