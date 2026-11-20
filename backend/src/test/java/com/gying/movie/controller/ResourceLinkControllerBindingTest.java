package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.dto.ResourceSubmissionDTO;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.service.*;
import com.gying.movie.utils.AuthHelper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceLinkControllerBindingTest {
    @Mock IResourceLinkService resourceLinkService;
    @Mock IMovieMetadataService movieService;
    @Mock ISysConfigService sysConfigService;
    @Mock AuthHelper authHelper;
    @InjectMocks ResourceLinkController controller;
    AutoCloseable mocks;
    ResourceSubmissionDTO dto;
    ResourceLink existing;
    java.util.Map<Long, ResourceLink> rows;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        when(authHelper.requireResourcePublisher("owner")).thenReturn(new AuthUser(1L, "fixture", "ADMIN"));
        existing = new ResourceLink(); existing.setId(10L); existing.setMovieId("first");
        existing.setUrl("https://pan.quark.cn/s/fixture"); existing.setType("DISK"); existing.setStatus("ACTIVE");
        existing.setUploaderId(1L); existing.setAuditStatus(1);
        when(resourceLinkService.getById(10L)).thenReturn(existing);
        when(resourceLinkService.updateById(any())).thenReturn(true);
        when(resourceLinkService.update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        rows = new java.util.LinkedHashMap<>(); rows.put(existing.getId(), existing);
        when(resourceLinkService.getById(any(java.io.Serializable.class))).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(resourceLinkService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call -> {
            QueryWrapper<?> query = call.getArgument(0);
            String sql = query.getSqlSegment();
            var values = query.getParamNameValuePairs().values();
            if (sql.contains("FOR UPDATE")) return rows.values().stream().filter(row -> values.contains(row.getId())).toList();
            if (sql.contains("source_ref IN")) return rows.values().stream()
                    .filter(row -> java.util.Set.of("COLLECTION_BINDING", "RESOURCE_BINDING").contains(row.getSource() == null ? "" : row.getSource()))
                    .filter(row -> values.contains(row.getSourceRef())).toList();
            if (sql.contains("url =")) return rows.values().stream().filter(row -> values.contains(row.getUrl())).toList();
            return List.of();
        });
        for (String id : List.of("first", "second")) {
            MovieMetadata movie = new MovieMetadata(); movie.setId(id); movie.setStatus("ACTIVE");
            when(movieService.getById(id)).thenReturn(movie);
        }
        dto = new ResourceSubmissionDTO(); dto.setMovieId("first"); dto.setType("DISK");
        dto.setUrl(existing.getUrl()); dto.setBindMovieIds(List.of("first", "second", "second"));
    }
    @AfterEach void close() throws Exception { controller.shutdownRepairInvalidExecutor(); mocks.close(); }

    @Test void adminSubmissionSkipsUserTotalQuotaButStillChecksDuplicateUrl() {
        dto.setBindMovieIds(List.of());
        when(sysConfigService.getConfigValue("resource.audit.enabled", "true")).thenReturn("true");
        when(sysConfigService.getConfigValue("resource.submit.interval.seconds", "60")).thenReturn("0");
        assertEquals(200,controller.submitResource(dto,"owner").getStatusCode().value());
        verify(sysConfigService,never()).getConfigValue(eq("resource.max.per.user"),anyString());
        verify(resourceLinkService).addResource(any());
        when(resourceLinkService.count(any())).thenReturn(1L);
        assertEquals(409,controller.submitResource(dto,"owner").getStatusCode().value());
    }
    @Test void publisherStillCannotExceedTotalQuota() {
        when(authHelper.requireResourcePublisher("owner")).thenReturn(new AuthUser(1L,"fixture","PUBLISHER"));
        when(sysConfigService.getConfigValue("resource.max.per.user","100")).thenReturn("1");
        when(resourceLinkService.count(any())).thenReturn(1L);dto.setBindMovieIds(List.of());
        assertEquals(403,controller.submitResource(dto,"owner").getStatusCode().value());
        verify(resourceLinkService,never()).addResource(any());
    }

    @Test void editAppendsDeduplicatedBindings() {
        var response = controller.updateOwnResource(10L, dto, "owner");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, ((Map<?, ?>) response.getBody()).get("boundCount"));
        var saved = ArgumentCaptor.forClass(ResourceLink.class);
        verify(resourceLinkService).addResource(saved.capture());
        assertNull(saved.getValue().getId());
        assertEquals("second", saved.getValue().getMovieId());
        assertEquals(existing.getUrl(), saved.getValue().getUrl());
    }
    @Test void alreadyBoundResourceIsSkipped() {
        when(resourceLinkService.count(any())).thenReturn(0L, 1L);
        assertEquals(200, controller.updateOwnResource(10L, dto, "owner").getStatusCode().value());
        verify(resourceLinkService, never()).addResource(any());
    }
    @Test void validatesAllBindingsBeforeUpdatingOriginal() {
        dto.setBindMovieIds(List.of("missing"));
        assertEquals(400, controller.updateOwnResource(10L, dto, "owner").getStatusCode().value());
        verify(resourceLinkService, never()).updateById(any());
        verify(resourceLinkService, never()).addResource(any());
    }
    @Test void movingPrimaryMovieChecksDuplicatesOnTarget() {
        dto.setMovieId("second"); dto.setBindMovieIds(List.of());
        when(resourceLinkService.count(any())).thenReturn(1L);
        assertEquals(409, controller.updateOwnResource(10L, dto, "owner").getStatusCode().value());
        var query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(resourceLinkService).count(query.capture());
        query.getValue().getSqlSegment();
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("second"));
        verify(resourceLinkService, never()).updateById(any());
    }
    @Test void rejectsAnotherPublishersResource() {
        when(authHelper.requireResourcePublisher("owner")).thenReturn(new AuthUser(2L, "other", "PUBLISHER"));
        assertEquals(403, controller.updateOwnResource(10L, dto, "owner").getStatusCode().value());
        verify(resourceLinkService, never()).updateById(any());
    }
    private ResourceLink bound(long id, String movieId, String sourceId, String url) {
        ResourceLink row = new ResourceLink(); row.setId(id); row.setMovieId(movieId);
        row.setUrl(url); row.setType("DISK"); row.setProvider("QUARK"); row.setStatus("ACTIVE");
        row.setSource("COLLECTION_BINDING"); row.setSourceRef(sourceId); row.setLinkStatus("INVALID");
        row.setUploaderId(1L); row.setAuditStatus(1); row.setLastCheckError("expired"); rows.put(id, row);
        MovieMetadata movie = new MovieMetadata(); movie.setId(movieId); movie.setTitleCn("示例剧 " + movieId);
        movie.setCategory("tv"); movie.setSeriesName("示例剧"); movie.setStatus("ACTIVE");
        when(movieService.getById(movieId)).thenReturn(movie);
        return row;
    }

    @Test void nineExistingSeasonsAreUpdatedInPlaceWithoutInserts() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int n=2; n<=9; n++) { String movie="season-"+n; ids.add(movie); bound(10L+n,movie,"10",existing.getUrl()); }
        dto.setBindMovieIds(ids); dto.setUrl("https://pan.quark.cn/s/replacement"); dto.setCode("new1");
        dto.setQuality("1080P"); dto.setName("示例剧 第1-9季合集 1080p");
        var response=controller.updateOwnResource(10L,dto,"owner");
        assertEquals(200,response.getStatusCode().value());
        assertEquals(0,((Map<?,?>)response.getBody()).get("boundCount"));
        assertEquals(8,((Map<?,?>)response.getBody()).get("updatedBindings"));
        assertEquals(9,rows.size());
        for (ResourceLink row:rows.values()) {
            assertEquals(dto.getUrl(),row.getUrl()); assertEquals("new1",row.getCode());
            assertEquals("NORMAL",row.getLinkStatus()); assertNull(row.getLastCheckError());
            assertEquals("1080P",row.getQuality()); assertEquals(0,row.getReportCount());
        }
        verify(resourceLinkService,never()).addResource(any());
        verify(resourceLinkService,times(9)).updateById(any());
    }

    @Test void editingChildLoadsRootSiblingsAndPreservesExistingDivergedUrls() {
        bound(20L,"second","10","https://pan.quark.cn/s/replacement");
        bound(21L,"third","10",existing.getUrl());
        ResourceLink deleted=bound(22L,"deleted","10",existing.getUrl());deleted.setStatus("DELETED");
        var response=controller.getResourceBindings(20L,"owner");
        assertEquals(200,response.getStatusCode().value());
        Map<?,?> result=(Map<?,?>)response.getBody();
        assertEquals(java.util.Set.of("first","third"),new java.util.HashSet<>((List<?>)result.get("bindMovieIds")));
        assertEquals(64,((String)result.get("bindingVersion")).length());
    }

    @Test void unrelatedVersionProviderAndUnselectedSeasonAreUntouched() {
        bound(20L,"second","10",existing.getUrl());
        ResourceLink unchecked=bound(21L,"third","10",existing.getUrl());
        ResourceLink other=bound(22L,"second","999","https://pan.xunlei.com/s/other"); other.setProvider("XUNLEI");
        ResourceLink version=bound(23L,"second","998","https://pan.quark.cn/s/different-version");
        dto.setBindMovieIds(List.of("second"));dto.setUrl("https://pan.quark.cn/s/new");
        assertEquals(200,controller.updateOwnResource(10L,dto,"owner").getStatusCode().value());
        assertEquals("https://pan.quark.cn/s/fixture",unchecked.getUrl());
        assertEquals("https://pan.xunlei.com/s/other",other.getUrl());
        assertEquals("https://pan.quark.cn/s/different-version",version.getUrl());
        verify(resourceLinkService,times(2)).updateById(any());verify(resourceLinkService,never()).addResource(any());
    }

    @Test void existingDuplicateBindingsDoNotCauseMoreInsertsOrGetDeleted() {
        ResourceLink old=bound(20L,"second","10",existing.getUrl());
        ResourceLink duplicate=bound(21L,"second","10","https://pan.quark.cn/s/previous-edit");
        dto.setUrl("https://pan.quark.cn/s/replacement");dto.setBindMovieIds(List.of("second"));
        assertEquals(200,controller.updateOwnResource(10L,dto,"owner").getStatusCode().value());
        assertEquals(dto.getUrl(),old.getUrl());assertEquals(dto.getUrl(),duplicate.getUrl());
        assertEquals("ACTIVE",old.getStatus());assertEquals("ACTIVE",duplicate.getStatus());
        assertEquals(3,rows.size());verify(resourceLinkService,never()).addResource(any());
    }

    @Test void collisionOnAnyExistingSeasonAbortsBeforeAnyWrite() {
        bound(20L,"second","10",existing.getUrl());dto.setUrl("https://pan.quark.cn/s/collision");
        when(resourceLinkService.count(any())).thenReturn(0L,1L);
        assertEquals(409,controller.updateOwnResource(10L,dto,"owner").getStatusCode().value());
        assertEquals("https://pan.quark.cn/s/fixture",existing.getUrl());
        verify(resourceLinkService,never()).updateById(any()); verify(resourceLinkService,never()).addResource(any());
    }

    @Test void staleBindingVersionAbortsBeforeAnyWrite() {
        dto.setBindingVersion("outdated");
        assertEquals(409,controller.updateOwnResource(10L,dto,"owner").getStatusCode().value());
        verify(resourceLinkService,never()).updateById(any());
    }

    @Test void publisherCannotReadOrUpdateAnotherOwnersSibling() {
        when(authHelper.requireResourcePublisher("owner")).thenReturn(new AuthUser(1L,"fixture","PUBLISHER"));
        ResourceLink other=bound(20L,"second","10",existing.getUrl());other.setUploaderId(2L);
        assertEquals(List.of(),((Map<?,?>)controller.getResourceBindings(10L,"owner").getBody()).get("bindMovieIds"));
        assertEquals(403,controller.getResourceBindings(20L,"owner").getStatusCode().value());
        dto.setBindMovieIds(List.of());dto.setUrl("https://pan.quark.cn/s/new");
        assertEquals(200,controller.updateOwnResource(10L,dto,"owner").getStatusCode().value());
        assertEquals("https://pan.quark.cn/s/fixture",other.getUrl());
    }

    @Test void newBindingGetsStableGroupReferenceAndExistingBindingIsNotRecreated() {
        bound(20L,"second","10",existing.getUrl());
        MovieMetadata third=new MovieMetadata();third.setId("third");third.setStatus("ACTIVE");when(movieService.getById("third")).thenReturn(third);
        dto.setBindMovieIds(List.of("first","third"));dto.setMovieId("second");dto.setUrl("https://pan.quark.cn/s/new");
        var response=controller.updateOwnResource(20L,dto,"owner");
        assertEquals(200,response.getStatusCode().value());
        var copy=ArgumentCaptor.forClass(ResourceLink.class); verify(resourceLinkService).addResource(copy.capture());
        assertEquals("third",copy.getValue().getMovieId()); assertEquals("RESOURCE_BINDING",copy.getValue().getSource());
        assertEquals("10",copy.getValue().getSourceRef());
    }

    @Test void nullOptionalFieldsAndFailureStateAreExplicitlyClearedAndFailuresThrow() {
        existing.setCode("old");existing.setLastCheckError("expired");existing.setRejectReason("old reason");
        dto.setBindMovieIds(List.of());
        assertEquals(200,controller.updateOwnResource(10L,dto,"owner").getStatusCode().value());
        var wrapper=ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(resourceLinkService).update(wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("last_check_error="));
        assertTrue(wrapper.getValue().getSqlSet().contains("code="));
        when(resourceLinkService.updateById(any())).thenReturn(false);
        assertThrows(IllegalStateException.class,()->controller.updateOwnResource(10L,dto,"owner"));
    }

    @Test void exactShareAcrossHistoricalRootsIsFullyPreselected() {
        existing.setProvider("QUARK");
        MovieMetadata primary = new MovieMetadata();
        primary.setId("first"); primary.setCategory("tv"); primary.setSeriesName("示例剧"); primary.setStatus("ACTIVE");
        when(movieService.getById("first")).thenReturn(primary);
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int n = 2; n <= 6; n++) {
            String movie = "season-" + n; ids.add(movie);
            ResourceLink row = bound(10L + n, movie, String.valueOf(900 + n), existing.getUrl());
            row.setSource("RESOURCE_BINDING");
        }
        var response = controller.getResourceBindings(10L, "owner");
        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> result = (Map<?, ?>) response.getBody();
        assertEquals(new java.util.HashSet<>(ids), new java.util.HashSet<>((List<?>) result.get("bindMovieIds")));
    }

    @Test void legacySameShareIsRecoveredButSameSeriesDifferentVersionIsNotSelected() {
        existing.setProvider("QUARK");
        MovieMetadata primary=new MovieMetadata();primary.setId("first");primary.setCategory("tv");primary.setSeriesName("示例剧");
        when(movieService.getById("first")).thenReturn(primary);
        ResourceLink legacy=bound(20L,"second","10",existing.getUrl());legacy.setSource(null);legacy.setSourceRef(null);
        ResourceLink other=bound(21L,"third","999","https://pan.quark.cn/s/other");other.setSource(null);other.setSourceRef(null);
        assertEquals(List.of("second"),((Map<?,?>)controller.getResourceBindings(10L,"owner").getBody()).get("bindMovieIds"));
    }

    @Test void shareRowWithoutUploaderIsStillPreselected() {
        // Production case: the XUNLEI season-5 row was created by system publishing without an
        // uploader while its five sibling seasons of the exact same share carry uploader 1.
        existing.setProvider("XUNLEI");
        MovieMetadata primary = new MovieMetadata();
        primary.setId("first"); primary.setCategory("tv"); primary.setSeriesName("示例剧"); primary.setStatus("ACTIVE");
        when(movieService.getById("first")).thenReturn(primary);
        ResourceLink season = bound(2500L, "season-5", "9005", existing.getUrl());
        season.setProvider("XUNLEI"); season.setSource("GYING_PUBLISHED"); season.setSourceRef("root");
        season.setUploaderId(null);
        var response = controller.getResourceBindings(10L, "owner");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("season-5"), ((Map<?, ?>) response.getBody()).get("bindMovieIds"));
    }

    @Test void differentRealUploadersStaySeparate() {
        existing.setProvider("XUNLEI");
        MovieMetadata primary = new MovieMetadata();
        primary.setId("first"); primary.setCategory("tv"); primary.setSeriesName("示例剧"); primary.setStatus("ACTIVE");
        when(movieService.getById("first")).thenReturn(primary);
        ResourceLink foreign = bound(2501L, "season-9", "9006", existing.getUrl());
        foreign.setProvider("XUNLEI"); foreign.setSource(null); foreign.setSourceRef(null);
        foreign.setUploaderId(2L);
        var response = controller.getResourceBindings(10L, "owner");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(), ((Map<?, ?>) response.getBody()).get("bindMovieIds"));
    }
}
