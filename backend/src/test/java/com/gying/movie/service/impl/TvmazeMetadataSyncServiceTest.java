package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.client.TmdbClient;
import com.gying.movie.client.TvmazeClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TvmazeMetadataSyncServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ResourceHubProperties properties = new ResourceHubProperties();
    final IResourceHubTaskService tasks = mock(IResourceHubTaskService.class);
    final IMovieMetadataService movies = mock(IMovieMetadataService.class);
    final IMovieSourceIdentityService identities = mock(IMovieSourceIdentityService.class);
    final TvmazeClient tvmaze = mock(TvmazeClient.class);
    final TmdbClient tmdb = mock(TmdbClient.class);
    final ITmdbMetadataSyncService tmdbSync = mock(ITmdbMetadataSyncService.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final MutableClock clock = new MutableClock();
    final TvmazeMetadataSyncService sync = new TvmazeMetadataSyncService(properties,tasks,movies,identities,tvmaze,tmdb,tmdbSync,mapper,manager,clock);
    final TvmazeUpdatesCheckpoint checkpoint = new TvmazeUpdatesCheckpoint(tasks,mapper);
    ResourceHubTask latest; long nextId=1;

    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-18T08:00:00Z");
        public ZoneId getZone(){ return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone){ return Clock.fixed(now,zone); }
        public Instant instant(){ return now; }
        void advance(long seconds){ now=now.plusSeconds(seconds); }
    }
    @BeforeEach void setup() {
        properties.setEnabled(true); properties.getWorker().setEnabled(true); properties.getTvmaze().setAutoSyncEnabled(true);
        properties.getTmdb().setApiKey("fixture-only");
        when(tasks.enqueue(any())).thenAnswer(call -> {
            ResourceHubTask task=call.getArgument(0); task.setId(nextId++); task.setStatus("PENDING"); task.setAttempts(0);
            task.setCreatedAt(LocalDateTime.now(clock.withZone(ZoneId.systemDefault()))); latest=task; return task;
        });
        when(tasks.getById(anyLong())).thenAnswer(c -> latest);
        when(tasks.getOne(any(),eq(false))).thenAnswer(c -> latest);
        when(tasks.update(any())).thenReturn(true);
        when(movies.updateById(any())).thenReturn(true);
        when(identities.save(any())).thenReturn(true); when(identities.updateById(any())).thenReturn(true);
        when(manager.getTransaction(any())).thenAnswer(c -> new SimpleTransactionStatus());
        when(tvmaze.fetchUpdates(anyString())).thenReturn(Map.of(1L,epoch()));
        when(tvmaze.fetchShow(anyLong())).thenAnswer(c -> show(c.getArgument(0)));
    }
    long epoch(){ return clock.instant().getEpochSecond(); }
    ObjectNode show(long id) {
        ObjectNode show=mapper.createObjectNode().put("id",id).put("updated",epoch()).put("name","Show "+id)
                .put("premiered","2023-01-15").put("language","Chinese").put("summary","<p>New &amp; safe <b>summary</b></p>");
        show.putArray("genres").add("Drama"); show.putObject("externals"); return show;
    }
    ResourceHubTask task(){ return checkpoint.enqueue(latest,10); }
    MovieMetadata movie() {
        var m=new MovieMetadata(); m.setId("canonical"); m.setTmdbId(204541L); m.setTmdbType("tv");
        m.setStatus("ACTIVE"); m.setCategory("tv"); m.setSeason(1); m.setYear(2023); m.setTitleCn("三体");
        return m;
    }
    MovieSourceIdentity bound(MovieMetadata movie,long version) {
        var identity=new MovieSourceIdentity(); identity.setId(7L); identity.setMovieId(movie.getId());
        identity.setSource("TVMAZE"); identity.setSourceType("tv"); identity.setExternalId("1");
        identity.setSeason(1); identity.setMatchStatus("CONFIRMED");
        identity.setEvidenceJson(mapper.createObjectNode().put("upstreamUpdated",version).toString());
        when(identities.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(identity)); when(identities.getOne(any(),eq(false))).thenReturn(identity);
        when(movies.getById(movie.getId())).thenReturn(movie); return identity;
    }

    @Test void unknownWithoutAnExactExternalIdIsKeptAsACandidate() throws Exception {
        task(); var result=sync.runTask(latest.getId());
        assertEquals("SUCCEEDED",result.getStatus()); assertEquals(0,result.getProcessed());
        JsonNode state=mapper.readTree(latest.getPayload()).path("crawl");
        assertEquals(1,state.path("deferred").size()); assertTrue(state.path("pending").isEmpty());
        assertEquals("NO_EXACT_TMDB_ID",state.path("deferred").path("1").path("reason").asText());
        verifyNoInteractions(tmdb,tmdbSync,movies); verify(identities,never()).save(any());
    }
    @Test void boundShowWorksWithoutTmdbKeyAndPreservesLocalChineseDataAndRatings() throws Exception {
        var m=movie(); m.setSummary("现有中文简介"); m.setGenres(List.of("剧情")); m.setImdbScore(BigDecimal.valueOf(8.1));
        var identity=bound(m,epoch()-1); properties.getTmdb().setApiKey(null);
        task(); var result=sync.runTask(latest.getId());
        assertEquals("SUCCEEDED",result.getStatus()); assertEquals(1,result.getProcessed());
        assertEquals("三体",m.getTitleCn()); assertEquals("现有中文简介",m.getSummary());
        assertEquals(List.of("剧情"),m.getGenres()); assertEquals(BigDecimal.valueOf(8.1),m.getImdbScore());
        assertEquals("Show 1",m.getTitleEn());
        JsonNode proof=mapper.readTree(identity.getEvidenceJson());
        assertEquals(epoch(),proof.path("upstreamUpdated").asLong()); assertEquals(TvmazeClient.LICENSE,proof.path("license").asText());
        assertFalse(proof.path("ownedFields").has("summary")); verifyNoInteractions(tmdb,tmdbSync);
    }
    @Test void providerOwnedFieldsRefreshAndAnAdminOverrideTakesOwnership() {
        var m=movie(); var identity=bound(m,epoch()-1);
        task(); assertEquals(1,sync.runTask(latest.getId()).getProcessed());
        assertEquals("New & safe summary",m.getSummary());
        clock.advance(86401); when(tvmaze.fetchUpdates(anyString())).thenReturn(Map.of(1L,epoch()));
        when(tvmaze.fetchShow(1)).thenReturn(show(1).put("summary","<p>Updated upstream summary</p>"));
        task(); assertEquals(1,sync.runTask(latest.getId()).getProcessed());
        assertEquals("Updated upstream summary",m.getSummary());
        m.setSummary("管理员改写");
        clock.advance(86401); when(tvmaze.fetchUpdates(anyString())).thenReturn(Map.of(1L,epoch()));
        when(tvmaze.fetchShow(1)).thenReturn(show(1).put("summary","<p>Another upstream summary</p>"));
        task(); assertEquals(1,sync.runTask(latest.getId()).getProcessed());
        assertEquals("管理员改写",m.getSummary());
    }
    @Test void sameUpstreamVersionSkipsDetailsCompletely() {
        var m=movie(); bound(m,epoch()); task();
        var result=sync.runTask(latest.getId()); assertEquals(1,result.getSkipped()); assertEquals(0,result.getProcessed());
        verify(tvmaze,never()).fetchShow(anyLong()); verifyNoInteractions(tmdbSync);
    }
    @Test void exactImdbAndTvdbAgreementImportsThroughTheStrictTvEntry() throws Exception {
        ObjectNode show=show(1); ((ObjectNode)show.path("externals")).put("imdb","tt20242042").put("thetvdb",421555);
        when(tvmaze.fetchShow(1)).thenReturn(show);
        when(tmdb.findTvByExternalId("tt20242042","imdb_id")).thenReturn(List.of(204541L));
        when(tmdb.findTvByExternalId("421555","tvdb_id")).thenReturn(List.of(204541L));
        when(tmdbSync.syncExactTv(204541,2023)).thenReturn(movie());
        task(); assertEquals(1,sync.runTask(latest.getId()).getProcessed());
        var capture=org.mockito.ArgumentCaptor.forClass(MovieSourceIdentity.class); verify(identities).save(capture.capture());
        assertEquals("canonical",capture.getValue().getMovieId()); assertEquals("1",capture.getValue().getExternalId());
        verify(tmdbSync).syncExactTv(204541,2023); verify(tmdbSync,never()).syncBestByKeyword(anyString());
    }
    @Test void disagreeingExternalIdsCannotBindOrSaveMovieMetadata() throws Exception {
        ObjectNode show=show(1); ((ObjectNode)show.path("externals")).put("imdb","tt20242042").put("thetvdb",421555);
        when(tvmaze.fetchShow(1)).thenReturn(show);
        when(tmdb.findTvByExternalId("tt20242042","imdb_id")).thenReturn(List.of(204541L));
        when(tmdb.findTvByExternalId("421555","tvdb_id")).thenReturn(List.of(9999L));
        task(); assertEquals("FAILED",sync.runTask(latest.getId()).getStatus());
        verifyNoInteractions(tmdbSync,movies); verify(identities,never()).save(any());
        assertEquals(1,mapper.readTree(latest.getPayload()).path("crawl").path("pending").path("1").path("attempts").asInt());
    }
    @Test void staleCachedDetailsAndFailedPersistenceNeverAdvanceCompletion() throws Exception {
        var m=movie(); var identity=bound(m,epoch()-1); String before=identity.getEvidenceJson();
        when(tvmaze.fetchShow(1)).thenReturn(show(1).put("updated",epoch()-2)); task();
        assertEquals("FAILED",sync.runTask(latest.getId()).getStatus()); assertEquals(before,identity.getEvidenceJson());
        verify(identities,never()).updateById(any()); verify(movies,never()).updateById(any());
        clock.advance(86401); when(tvmaze.fetchUpdates(anyString())).thenReturn(Map.of(1L,epoch()));
        when(tvmaze.fetchShow(1)).thenReturn(show(1)); when(identities.updateById(any())).thenReturn(false);
        task(); assertEquals("FAILED",sync.runTask(latest.getId()).getStatus());
        assertEquals(1,mapper.readTree(latest.getPayload()).path("crawl").path("pending").size());
        verify(manager).rollback(any());
    }
    @Test void failedItemsExhaustAfterThreeVersionsPreservingTheirAttemptsAcrossRestarts() throws Exception {
        when(tvmaze.fetchShow(1)).thenThrow(new IllegalStateException("fixture unavailable"));
        for(int i=0;i<3;i++) { task(); assertEquals("FAILED",sync.runTask(latest.getId()).getStatus()); clock.advance(86401); }
        task(); assertEquals("FAILED",sync.runTask(latest.getId()).getStatus());
        verify(tvmaze,times(3)).fetchShow(1);
        assertEquals(3,mapper.readTree(latest.getPayload()).path("crawl").path("pending").path("1").path("attempts").asInt());
    }
    @Test void sharedDailyBudgetSurvivesNewTasksAndResetsOnlyAfter24Hours() throws Exception {
        Map<Long,Long> feed=new LinkedHashMap<>(); for(long id=1;id<=50;id++) feed.put(id,epoch());
        when(tvmaze.fetchUpdates(anyString())).thenReturn(feed);
        task(); sync.runTask(latest.getId()); verify(tvmaze,times(10)).fetchShow(anyLong());
        task(); sync.runTask(latest.getId()); verify(tvmaze,times(10)).fetchShow(anyLong());
        clock.advance(86401); task(); sync.runTask(latest.getId()); verify(tvmaze,times(20)).fetchShow(anyLong());
        assertEquals(10,mapper.readTree(latest.getPayload()).path("crawl").path("dailyAttempts").asInt());
    }
    @Test void sixHourScheduleRenewsDetailsWithoutAllowingInheritedTasksToExceedTheBudget() throws Exception {
        properties.getTvmaze().setAutoSyncIntervalHours(6);
        Map<Long,Long> feed=new LinkedHashMap<>(); for(long id=1;id<=25;id++) feed.put(id,epoch());
        when(tvmaze.fetchUpdates(anyString())).thenReturn(feed);
        assertNotNull(sync.enqueueAutomatic()); sync.runTask(latest.getId());
        task(); sync.runTask(latest.getId()); verify(tvmaze,times(10)).fetchShow(anyLong());
        clock.advance(6*3600-1); assertNull(sync.enqueueAutomatic());
        clock.advance(2); assertNotNull(sync.enqueueAutomatic()); sync.runTask(latest.getId());
        verify(tvmaze,times(20)).fetchShow(anyLong());
        JsonNode state=mapper.readTree(latest.getPayload()).path("crawl");
        assertEquals(10,state.path("roundAttempts").asInt()); assertEquals(10,state.path("dailyAttempts").asInt());
        assertEquals(5,state.path("pending").size());
        task(); sync.runTask(latest.getId()); verify(tvmaze,times(20)).fetchShow(anyLong());
    }
    @Test void rateLimitStopsTheWholeRoundAndHonorsRetryAfterAcrossRestarts() throws Exception {
        when(tvmaze.fetchUpdates(anyString())).thenThrow(new TvmazeClient.RateLimited(clock.instant().plusSeconds(3600)));
        task(); assertEquals("FAILED",sync.runTask(latest.getId()).getStatus());
        assertEquals(epoch()+3600,mapper.readTree(latest.getPayload()).path("crawl").path("nextRequestAt").asLong());
        clock.advance(60); assertEquals("FAILED",sync.runTask(latest.getId()).getStatus());
        verify(tvmaze,times(1)).fetchUpdates(anyString()); verify(tvmaze,never()).fetchShow(anyLong());
    }
    @Test void pauseAndLostTaskOwnershipPreventRemoteWork() {
        task(); properties.getTvmaze().setAutoSyncEnabled(false); sync.runTask(latest.getId()); verifyNoInteractions(tvmaze);
        properties.getTvmaze().setAutoSyncEnabled(true); when(tasks.update(any())).thenReturn(false);
        sync.runTask(latest.getId()); verifyNoInteractions(tvmaze); verify(tasks,never()).updateById(any());
    }
    @Test void dailySchedulerCannotCreateAnotherRoundBeforeTheInterval() {
        assertNotNull(sync.enqueueAutomatic()); assertNull(sync.enqueueAutomatic());
        clock.advance(86401); assertNotNull(sync.enqueueAutomatic());
        properties.getTvmaze().setAutoSyncEnabled(false); clock.advance(86401); assertNull(sync.enqueueAutomatic());
    }
}
