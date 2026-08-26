package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.utils.SeasonSearchUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class QuarkAutoSaveClientTest {

    @Test
    void includesSeasonSubdirectoryPatternInTaskPayload() {
        ResourceHubProperties properties = new ResourceHubProperties();
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(
                new RestTemplateBuilder(),
                new ObjectMapper(),
                properties);
        String pattern = SeasonSearchUtils.subdirectoryPattern(1);

        Map<String, Object> payload = client.buildTaskPayload(
                "The Rookie Season 1",
                "https://pan.quark.cn/s/source",
                "/GYing Resource Hub/tv/The Rookie Season 1",
                pattern);

        assertEquals(pattern, payload.get("update_subdir"));
        assertEquals("/GYing Resource Hub/tv/The Rookie Season 1", payload.get("savepath"));
    }

    @Test
    void submitsTaskWhenConfigCheckIsTemporarilyUnavailable() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.getForEntity(contains("/data"), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection reset"));
        when(restTemplate.postForEntity(
                contains("/api/add_task"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"code\":200}"));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);

        JsonNode result = client.addTask(Map.of(
                "taskname", "Supergirl",
                "shareurl", "https://pan.quark.cn/s/source",
                "savepath", "/GYing Resource Hub/movie/Supergirl"));

        assertTrue(result.path("success").asBoolean());
        verify(restTemplate, times(3)).getForEntity(contains("/data"), eq(String.class));
        verify(restTemplate).postForEntity(
                contains("/api/add_task"),
                any(HttpEntity.class),
                eq(String.class));
    }

    @Test
    void usesRootShareForFirstSeasonEpisodeCollection() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/get_share_detail"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"data\":{\"list\":[]}}"));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);
        String shareUrl = "https://pan.quark.cn/s/e57d386ae489";

        String resolved = client.resolveSeasonShareUrl(
                shareUrl,
                1,
                "鬼灭之刃 更至63集 4K合集 最新");

        assertEquals(shareUrl, resolved);
    }

    @Test
    void usesRootShareWhenSingleSeasonHasDirectVideoFiles() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/get_share_detail"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"success":true,"data":{"list":[
                          {"fid":"episode-1","file_name":"森林民宿.E01.1080p.mkv","dir":false},
                          {"fid":"episode-2","file_name":"森林民宿.E02.1080p.mkv","dir":false}
                        ]}}
                        """));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);
        String shareUrl = "https://pan.quark.cn/s/forest";

        assertEquals(shareUrl, client.resolveSeasonShareUrl(
                shareUrl,
                1,
                "2021 森林民宿 剧集 HDTV 中字"));
    }

    @Test
    void selectsSingleUnmarkedContentDirectoryForFirstSeason() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/get_share_detail"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(
                        ResponseEntity.ok("""
                                {"success":true,"data":{"list":[
                                  {"fid":"episodes","file_name":"森林民宿","dir":true}
                                ]}}
                                """),
                        ResponseEntity.ok("""
                                {"success":true,"data":{"list":[
                                  {"fid":"episode-1","file_name":"E01.mp4","dir":false}
                                ]}}
                                """),
                        ResponseEntity.ok("""
                                {"success":true,"data":{"list":[
                                  {"fid":"episodes","file_name":"森林民宿","dir":true}
                                ]}}
                                """),
                        ResponseEntity.ok("""
                                {"success":true,"data":{"list":[
                                  {"fid":"episode-1","file_name":"E01.mp4","dir":false}
                                ]}}
                                """));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);
        String shareUrl = "https://pan.quark.cn/s/forest-folder";

        assertEquals(shareUrl + "#/list/share/episodes", client.resolveSeasonShareUrl(
                shareUrl,
                1,
                "2021 森林民宿 剧集 HDTV 中字"));
    }

    @Test
    void rejectsRootWithConflictingSeasonDirectory() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/get_share_detail"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"success":true,"data":{"list":[
                          {"fid":"season-2","file_name":"Season 2","dir":true},
                          {"fid":"extras","file_name":"Extras","dir":true}
                        ]}}
                        """));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);

        assertThrows(IllegalStateException.class, () -> client.resolveSeasonShareUrl(
                "https://pan.quark.cn/s/multi-season",
                1,
                "森林民宿 剧集 HDTV 中字"));
    }

    @Test
    void selectsExactMovieDirectoryFromCollection() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/get_share_detail"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(
                        ResponseEntity.ok("""
                                {"success":true,"data":{"list":[
                                  {"fid":"collection","file_name":"[人生七年][1-9部合集]","dir":true}
                                ]}}
                                """),
                        ResponseEntity.ok("""
                                {"success":true,"data":{"list":[
                                  {"fid":"part-7","file_name":"人生七年7 49 Up (2005)","dir":true},
                                  {"fid":"part-8","file_name":"人生七年8 56 Up (2012)","dir":true},
                                  {"fid":"part-9","file_name":"人生七年9 63 Up (2019)","dir":true}
                                ]}}
                                """));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);

        QuarkAutoSaveClient.MovieShareSelection selection = client.resolveMovieShareUrl(
                "https://pan.quark.cn/s/source",
                "人生七年8",
                "56 Up",
                null,
                2012);

        assertEquals("https://pan.quark.cn/s/source#/list/share/part-8", selection.shareUrl());
        assertTrue(selection.recursive());
    }

    @Test
    void keepsDirectMovieShareAtRootWhenNoMovieDirectoryMatches() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/get_share_detail"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"success":true,"data":{"list":[
                          {"fid":"video","file_name":"Disclosure.Day.2026.mkv","dir":false}
                        ]}}
                        """));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);
        String shareUrl = "https://pan.quark.cn/s/direct";

        QuarkAutoSaveClient.MovieShareSelection selection = client.resolveMovieShareUrl(
                shareUrl,
                "揭秘日",
                "Disclosure Day",
                null,
                2026);

        assertEquals(shareUrl, selection.shareUrl());
        assertFalse(selection.recursive());
    }
}
