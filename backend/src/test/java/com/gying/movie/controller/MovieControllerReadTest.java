package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.mapper.MovieMetadataMapper;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.impl.MonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MovieControllerReadTest {
    private final IMovieMetadataService movies = mock(IMovieMetadataService.class);
    private final IResourceLinkService resources = mock(IResourceLinkService.class);
    private final MovieMetadataMapper mapper = mock(MovieMetadataMapper.class);
    private final LambdaQueryChainWrapper<MovieMetadata> query = new LambdaQueryChainWrapper<>(mapper);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final MovieController controller = new MovieController();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MovieMetadata movie = new MovieMetadata();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "movieService", movies);
        ReflectionTestUtils.setField(controller, "resourceService", resources);
        ReflectionTestUtils.setField(controller, "monitoringService", new MonitoringService(jdbc, redis));
        ReflectionTestUtils.setField(controller, "minioUrlPrefix", "https://images.example.test/movies");
        movie.setId("fixture");
        movie.setPosterUrl("posters/fixture.jpg");
        new MybatisConfiguration().addMapper(MovieMetadataMapper.class);
        when(movies.lambdaQuery()).thenReturn(query);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<MovieMetadata> page = invocation.getArgument(0);
            page.setTotal(67);
            page.setRecords(List.of(movie));
            return page;
        });
    }

    @Test
    void searchReturnsItsPageAndPublicPosterWhenRedisFails() {
        when(redis.opsForZSet().incrementScore(anyString(), anyString(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException("fixture cache outage"));

        var result = list(2, 30, "fixture");

        assertEquals(List.of(movie), result.getRecords());
        assertEquals(67, result.getTotal());
        assertEquals(2, result.getCurrent());
        assertEquals("https://images.example.test/movies/posters/fixture.jpg", movie.getPosterUrl());
    }

    @Test
    void searchReturnsItsPageWhenOnlyTheStatisticsInsertFails() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("fixture statistics outage"));

        assertEquals(List.of(movie), list(1, 30, "fixture").getRecords());
    }

    @ParameterizedTest
    @CsvSource({"-2147483648,-2147483648,1,1", "0,0,1,1", "2,30,2,30", "2147483647,2147483647,2147483647,60"})
    void existingPaginationBoundsArePreserved(int page, int size, long expectedPage, long expectedSize) {
        var result = list(page, size, null);
        assertEquals(expectedPage, result.getCurrent());
        assertEquals(expectedSize, result.getSize());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankSearchDoesNotWriteStatistics(String keyword) {
        assertEquals(List.of(movie), list(1, 30, keyword).getRecords());
        verifyNoInteractions(jdbc, redis);
    }

    @Test
    void realMovieQueryFailureIsNotSwallowed() {
        var failure = new DataAccessResourceFailureException("fixture movie query outage");
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> list(1, 30, "fixture")));
        verifyNoInteractions(jdbc, redis);
    }

    @Test
    void detailReturnsMovieAndResourcesWhenViewRecordingFails() {
        ResourceLink link = new ResourceLink();
        link.setId(1L);
        when(movies.getById("fixture")).thenReturn(movie);
        when(resources.getResourcesByMovieId("fixture")).thenReturn(List.of(link));
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("fixture statistics outage"));

        var result = controller.getMovieDetail("fixture", request);

        assertSame(movie, result.getMovie());
        assertEquals(List.of(link), result.getResources());
        verifyNoInteractions(redis);
    }

    @Test
    void missingMovieStillReturnsNotFoundWithoutResourceOrTelemetryQueries() {
        var failure = assertThrows(ResponseStatusException.class, () -> controller.getMovieDetail("missing", request));
        assertEquals(404, failure.getStatusCode().value());
        verifyNoInteractions(resources, jdbc, redis);
    }

    private Page<MovieMetadata> list(int page, int size, String keyword) {
        return controller.getMovieList(page, size, null, keyword, null, null, null, null, null, request);
    }
}
