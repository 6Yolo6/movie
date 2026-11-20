package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.mapper.ResourceLinkMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceLinkServiceImplTest {
    private final ResourceLinkMapper mapper = mock(ResourceLinkMapper.class);
    private final ResourceLinkServiceImpl service = new ResourceLinkServiceImpl();
    private final MybatisConfiguration configuration = new MybatisConfiguration();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        // Build mapper SQL in memory only: no DataSource, connection or application context.
        configuration.addMapper(ResourceLinkMapper.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"movie-a", "movie-a' OR 1=1 --"})
    void approvedLiveResourcesIncludeNullStatusWithoutEscapingTheMovieBoundary(String movieId) {
        ResourceLink legacy = new ResourceLink();
        legacy.setId(1L);
        legacy.setMovieId(movieId);
        legacy.setAuditStatus(1);
        legacy.setStatus("ACTIVE");
        List<ResourceLink> rows = List.of(legacy);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(rows);

        assertEquals(rows, service.getResourcesByMovieId(movieId));

        ArgumentCaptor<Wrapper<ResourceLink>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(query.capture());
        Map<String, Object> params = Map.of("ew", query.getValue());
        BoundSql bound = configuration.getMappedStatement(ResourceLinkMapper.class.getName() + ".selectList")
                .getBoundSql(params);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertEquals("WHERE (movie_id = ? AND (source IS NULL OR source <> ?) AND audit_status = ? AND status = ? AND deleted_at IS NULL "
                + "AND (link_status IS NULL OR link_status <> ?))", sql.substring(sql.indexOf("WHERE")));
        assertFalse(sql.contains(movieId));
        assertEquals(List.of(movieId, "BAIDU_TEMP", 1, "ACTIVE", "INVALID"), bound.getParameterMappings().stream()
                .map(parameter -> configuration.newMetaObject(params).getValue(parameter.getProperty())).toList());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void noResourcesStillReturnsAnEmptyListInOneRead() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertTrue(service.getResourcesByMovieId("missing").isEmpty());
        verify(mapper).selectList(any(Wrapper.class));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void temporaryBaiduLinksStayPrivateWhileLegacyAndPermanentResourcesRemainVisible() {
        ResourceLink legacy = new ResourceLink(); legacy.setId(1L); legacy.setMovieId("movie-a");
        ResourceLink permanent = new ResourceLink(); permanent.setId(2L); permanent.setMovieId("movie-a"); permanent.setSource("RESOURCE_HUB");
        ResourceLink manual = new ResourceLink(); manual.setId(3L); manual.setMovieId("movie-a"); manual.setSource("MANUAL");
        ResourceLink temporary = new ResourceLink(); temporary.setId(4L); temporary.setMovieId("movie-a");
        temporary.setSource("BAIDU_TEMP"); temporary.setSourceRef("QQ_BOT:11111111-1111-1111-1111-111111111111");
        temporary.setUrl("https://pan.baidu.com/s/1temporary");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(temporary, legacy, permanent, manual));

        assertEquals(List.of(legacy, permanent, manual), service.getResourcesByMovieId("movie-a"));
        assertEquals("BAIDU_TEMP", temporary.getSource());
        assertEquals("QQ_BOT:11111111-1111-1111-1111-111111111111", temporary.getSourceRef());
        verify(mapper).selectList(any(Wrapper.class));
        verifyNoMoreInteractions(mapper);
    }

}
