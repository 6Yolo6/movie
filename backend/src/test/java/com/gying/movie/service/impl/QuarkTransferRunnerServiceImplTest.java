package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.QuarkAutoSaveClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.service.IMovieMetadataService;
import com.gying.movie.service.IQuarkShareService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkTransferRunnerServiceImplTest {

    @Test
    void movieWithHistoricalSeasonOneUsesDirectMovieTransfer() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        properties.getQuark().setRunImmediately(false);
        QuarkAutoSaveClient autoSaveClient = mock(QuarkAutoSaveClient.class);
        IQuarkShareService shareService = mock(IQuarkShareService.class);
        IQuarkTransferTaskService taskService = mock(IQuarkTransferTaskService.class);
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IMovieMetadataService movieService = mock(IMovieMetadataService.class);
        QuarkTransferRunnerServiceImpl service = new QuarkTransferRunnerServiceImpl(
                properties,
                autoSaveClient,
                shareService,
                taskService,
                discoveryService,
                movieService,
                new ObjectMapper());

        QuarkTransferTask task = new QuarkTransferTask();
        task.setId(506L);
        task.setMovieId("tmdb_movie_1275779");
        task.setDiscoveryResultId(1980L);
        task.setOriginalUrl("https://pan.quark.cn/s/76db56bb540c");
        task.setStatus("FAILED");
        task.setAttempts(4);
        task.setRequestPayload("""
                {"taskname":"揭秘日 第1季","shareurl":"https://pan.quark.cn/s/76db56bb540c",
                 "savepath":"/GYing Resource Hub/movie/揭秘日/第1季","update_subdir":".*"}
                """);

        MovieMetadata movie = new MovieMetadata();
        movie.setId(task.getMovieId());
        movie.setTitleCn("揭秘日");
        movie.setYear(2026);
        movie.setCategory("mv");
        movie.setSeason(1);

        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(task.getDiscoveryResultId());
        discovery.setTitle("揭秘日 4K HDR");

        when(taskService.getById(506L)).thenReturn(task);
        when(movieService.getById(task.getMovieId())).thenReturn(movie);
        when(discoveryService.getById(task.getDiscoveryResultId())).thenReturn(discovery);
        when(autoSaveClient.resolveMovieShareUrl(
                task.getOriginalUrl(),
                movie.getTitleCn(),
                movie.getTitleEn(),
                movie.getAliases(),
                movie.getYear()))
                .thenReturn(new QuarkAutoSaveClient.MovieShareSelection(task.getOriginalUrl(), false));
        when(autoSaveClient.buildTaskPayload(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("taskname", invocation.getArgument(0));
                    payload.put("shareurl", invocation.getArgument(1));
                    payload.put("savepath", invocation.getArgument(2));
                    payload.put("update_subdir", invocation.getArgument(3));
                    return payload;
                });
        when(autoSaveClient.addTask(any()))
                .thenReturn(new ObjectMapper().createObjectNode().put("code", 200));
        when(shareService.ensureShareUrl(task))
                .thenReturn("https://pan.quark.cn/s/own-share");

        var result = service.submitOne(506L);

        assertEquals(1, result.getSubmitted());
        assertEquals(0, result.getFailed());
        assertEquals("/GYing Resource Hub/movie/揭秘日", task.getSavedPath());
        assertFalse(task.getRequestPayload().contains("第1季"));
        assertFalse(task.getRequestPayload().contains("\"update_subdir\":\".*\""));
        assertFalse(task.getRequestPayload().contains("\"update_subdir\":null"));
        verify(autoSaveClient, never()).resolveSeasonShareUrl(anyString(), anyInt(), any());
    }

    @Test
    void movieCollectionUsesMatchedDirectoryAndRecursiveTransfer() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.setEnabled(true);
        properties.getQuark().setRunImmediately(false);
        QuarkAutoSaveClient autoSaveClient = mock(QuarkAutoSaveClient.class);
        IQuarkShareService shareService = mock(IQuarkShareService.class);
        IQuarkTransferTaskService taskService = mock(IQuarkTransferTaskService.class);
        IResourceDiscoveryResultService discoveryService = mock(IResourceDiscoveryResultService.class);
        IMovieMetadataService movieService = mock(IMovieMetadataService.class);
        QuarkTransferRunnerServiceImpl service = new QuarkTransferRunnerServiceImpl(
                properties,
                autoSaveClient,
                shareService,
                taskService,
                discoveryService,
                movieService,
                new ObjectMapper());

        QuarkTransferTask task = new QuarkTransferTask();
        task.setId(766L);
        task.setMovieId("G2ez");
        task.setDiscoveryResultId(2515L);
        task.setOriginalUrl("https://pan.quark.cn/s/c6ba183fdded");
        task.setStatus("FAILED");
        task.setRequestPayload("""
                {"taskname":"人生七年8","shareurl":"https://pan.quark.cn/s/c6ba183fdded",
                 "savepath":"/GYing Resource Hub/movie/人生七年8","update_subdir":""}
                """);

        MovieMetadata movie = new MovieMetadata();
        movie.setId(task.getMovieId());
        movie.setTitleCn("人生七年8");
        movie.setTitleEn("56 Up");
        movie.setYear(2012);
        movie.setCategory("mv");

        ResourceDiscoveryResult discovery = new ResourceDiscoveryResult();
        discovery.setId(task.getDiscoveryResultId());
        discovery.setTitle("人生七年 1-9 部合集");

        String selectedUrl = task.getOriginalUrl() + "#/list/share/4369a83d53f64e8baff518a7fe3bc1d1";
        when(taskService.getById(766L)).thenReturn(task);
        when(movieService.getById(task.getMovieId())).thenReturn(movie);
        when(discoveryService.getById(task.getDiscoveryResultId())).thenReturn(discovery);
        when(autoSaveClient.resolveMovieShareUrl(
                task.getOriginalUrl(),
                movie.getTitleCn(),
                movie.getTitleEn(),
                movie.getAliases(),
                movie.getYear()))
                .thenReturn(new QuarkAutoSaveClient.MovieShareSelection(selectedUrl, true));
        when(autoSaveClient.addTask(any()))
                .thenReturn(new ObjectMapper().createObjectNode().put("code", 200));
        when(shareService.ensureShareUrl(task))
                .thenReturn("https://pan.quark.cn/s/own-share");

        var result = service.submitOne(766L);

        assertEquals(1, result.getSubmitted());
        assertEquals(0, result.getFailed());
        assertTrue(task.getRequestPayload().contains(selectedUrl));
        assertTrue(task.getRequestPayload().contains("\"update_subdir\":\".*\""));
        verify(autoSaveClient, never()).resolveSeasonShareUrl(anyString(), anyInt(), any());
    }
}
