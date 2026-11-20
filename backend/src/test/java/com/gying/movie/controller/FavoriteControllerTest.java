package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.UserFavorite;
import com.gying.movie.mapper.UserFavoriteMapper;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IUserFavoriteService;
import com.gying.movie.utils.AuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FavoriteControllerTest {
    private final IUserFavoriteService favorites = mock(IUserFavoriteService.class);
    private final IMovieMetadataService movies = mock(IMovieMetadataService.class);
    private final UserFavoriteMapper mapper = mock(UserFavoriteMapper.class);
    private final AuthHelper auth = mock(AuthHelper.class);
    private final FavoriteController controller = new FavoriteController(favorites, movies, mapper, auth);

    @BeforeEach
    void setUp() {
        when(auth.requireUser("Bearer fixture")).thenReturn(new AuthUser(7L, "fixture", "USER"));
        ReflectionTestUtils.setField(controller, "minioUrlPrefix", "https://images.example.test/movies");
    }

    @Test
    void mineUsesOneAggregateAndPreservesOrderCountsAndPagination() {
        UserFavorite first = favorite("second", 2);
        UserFavorite missing = favorite("deleted", 1);
        UserFavorite last = favorite("first", 0);
        Page<UserFavorite> page = new Page<>(2, 3, 8);
        page.setRecords(List.of(first, missing, last));
        when(favorites.page(any(Page.class), any(Wrapper.class))).thenReturn(page);
        MovieMetadata a = movie("first");
        MovieMetadata b = movie("second");
        b.setPosterUrl("posters/second.jpg");
        when(movies.listByIds(anyCollection())).thenReturn(List.of(a, b));
        when(mapper.countByMovieIds(anyCollection())).thenReturn(List.of(
                Map.of("movie_id", "first", "cnt", BigInteger.valueOf(4_000_000_000L)),
                Map.of("movie_id", "second", "cnt", 7)));

        Page<Map<String, Object>> result = controller.mine("Bearer fixture", 2, 3);

        assertEquals(2, result.getCurrent());
        assertEquals(3, result.getSize());
        assertEquals(8, result.getTotal());
        assertEquals(3, result.getPages());
        assertEquals(List.of("second", "first"), result.getRecords().stream()
                .map(row -> ((MovieMetadata) row.get("movie")).getId()).toList());
        assertEquals(7L, result.getRecords().get(0).get("favoriteCount"));
        assertEquals(4_000_000_000L, result.getRecords().get(1).get("favoriteCount"));
        assertEquals(first.getCreatedAt(), result.getRecords().get(0).get("favoritedAt"));
        assertEquals("https://images.example.test/movies/posters/second.jpg", b.getPosterUrl());
        verify(mapper).countByMovieIds(List.of("second", "deleted", "first"));
        verify(favorites, never()).count(any(Wrapper.class));
    }

    @Test
    void mineDefaultsMissingCountsToZeroAndDeduplicatesLookupIds() {
        Page<UserFavorite> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(favorite("same", 1), favorite("same", 0)));
        when(favorites.page(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(movies.listByIds(anyCollection())).thenReturn(List.of(movie("same")));
        when(mapper.countByMovieIds(anyCollection())).thenReturn(List.of());

        var result = controller.mine("Bearer fixture", 1, 20);

        assertEquals(2, result.getRecords().size());
        assertTrue(result.getRecords().stream().allMatch(row -> Long.valueOf(0).equals(row.get("favoriteCount"))));
        verify(movies).listByIds(List.of("same"));
        verify(mapper).countByMovieIds(List.of("same"));
        verify(favorites, never()).count(any(Wrapper.class));
    }

    @Test
    void emptyPageDoesNotQueryMoviesOrCounts() {
        when(favorites.page(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>(4, 20, 42));

        var result = controller.mine("Bearer fixture", 4, 20);

        assertEquals(42, result.getTotal());
        assertEquals(4, result.getCurrent());
        assertTrue(result.getRecords().isEmpty());
        verifyNoInteractions(movies, mapper);
        verify(favorites, never()).count(any(Wrapper.class));
    }

    @Test
    void sixtyFavoritesStillUseOneCountQueryAndKeepPageSizeCap() {
        List<UserFavorite> records = new ArrayList<>();
        List<MovieMetadata> metadata = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            records.add(favorite("movie-" + i, 0));
            metadata.add(movie("movie-" + i));
        }
        Page<UserFavorite> page = new Page<>(1, 60, 60);
        page.setRecords(records);
        when(favorites.page(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(movies.listByIds(anyCollection())).thenReturn(metadata);
        when(mapper.countByMovieIds(anyCollection())).thenReturn(List.of());

        assertEquals(60, controller.mine("Bearer fixture", -1, 500).getRecords().size());

        ArgumentCaptor<Page<UserFavorite>> captor = ArgumentCaptor.forClass(Page.class);
        verify(favorites).page(captor.capture(), any(Wrapper.class));
        assertEquals(1, captor.getValue().getCurrent());
        assertEquals(60, captor.getValue().getSize());
        verify(mapper, times(1)).countByMovieIds(argThat(ids -> ids.size() == 60));
        verify(favorites, never()).count(any(Wrapper.class));
    }

    @Test
    void unauthorizedRequestDoesNotReachTheDatabase() {
        when(auth.requireUser("Bearer rejected")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        assertThrows(ResponseStatusException.class, () -> controller.mine("Bearer rejected", 1, 20));

        verifyNoInteractions(favorites, movies, mapper);
    }

    private UserFavorite favorite(String movieId, int hour) {
        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(7L);
        favorite.setMovieId(movieId);
        favorite.setCreatedAt(LocalDateTime.of(2026, 10, 9, hour, 0));
        return favorite;
    }

    private MovieMetadata movie(String id) {
        MovieMetadata movie = new MovieMetadata();
        movie.setId(id);
        return movie;
    }
}
