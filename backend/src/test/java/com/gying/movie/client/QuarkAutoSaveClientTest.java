package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.utils.SeasonSearchUtils;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class QuarkAutoSaveClientTest {

    @Test
    void readsUtf8ConfigWithoutWritingTheWholeConfigBack() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        String response = """
                {"success":true,"data":{"cookie":["__uid=test"],"tasklist":[
                  {"taskname":"海军罪案调查处","savepath":"/GYing Resource Hub/tv/海军罪案调查处"}
                ]}}
                """;
        when(restTemplate.getForEntity(contains("/data"), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(response.getBytes(StandardCharsets.UTF_8)));

        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);

        assertEquals("__uid=test", client.getPrimaryCookie());

        verify(restTemplate, never()).postForEntity(
                contains("/update"), any(HttpEntity.class), eq(String.class));
    }

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
        when(restTemplate.getForEntity(contains("/data"), eq(byte[].class)))
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
        verify(restTemplate, times(3)).getForEntity(contains("/data"), eq(byte[].class));
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
    void selectsObfuscatedTitleAnchoredNumericSeasonDirectory() {
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
                          {"fid":"year","file_name":"问心2023","dir":true},
                          {"fid":"season-2","file_name":"wW问@@X心2","dir":true}
                        ]}}
                        """));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);
        String shareUrl = "https://pan.quark.cn/s/heart";

        assertEquals(shareUrl + "#/list/share/season-2", client.resolveSeasonShareUrl(
                shareUrl,
                2,
                "问心2",
                "问心"));
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
                                """),
                        ResponseEntity.ok("{\"success\":true,\"data\":{\"list\":[{\"file_name\":\"56.Up.2012.mkv\",\"dir\":false}]}}"));
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

    @Test
    void locatesZootopiaInAbbreviatedFirstMovieDirectoryByVideoNameAndYear() {
        QuarkAutoSaveClient client = movieTree(Map.of(
                "root", "[{\"fid\":\"collection\",\"file_name\":\"F 疯K D0ng W 成\",\"dir\":true}]",
                "collection", "[{\"fid\":\"second\",\"file_name\":\"枫 2（2025）\",\"dir\":true},{\"fid\":\"first\",\"file_name\":\"疯 1 (2016)\",\"dir\":true}]",
                "first", "[{\"file_name\":\"Zootopia.2016.UHD.2160p.mkv\",\"dir\":false},{\"file_name\":\"1080P 英国台粤四音轨.mkv\",\"dir\":false},{\"file_name\":\"【国语】2016.BD2160P.mp4\",\"dir\":false}]"));
        var selection = client.resolveMovieShareUrl("https://pan.quark.cn/s/fixture", "疯狂动物城", "Zootopia", null, 2016);
        assertEquals("https://pan.quark.cn/s/fixture#/list/share/first", selection.shareUrl());
        assertTrue(selection.recursive());
    }

    @Test
    void locatesOrdinalFirstMovieByItsVideoRatherThanSavingTheWholeCollection() {
        QuarkAutoSaveClient client = movieTree(Map.of(
                "root", "[{\"fid\":\"collection\",\"file_name\":\"疯狂动物城 全2部 合集\",\"dir\":true}]",
                "collection", "[{\"fid\":\"second\",\"file_name\":\"第二部（2025）4K\",\"dir\":true},{\"fid\":\"first\",\"file_name\":\"第一部 4K.UHD蓝光原盘REMUX\",\"dir\":true}]",
                "second", "[{\"file_name\":\"2.国粤英3音轨.2160p.mkv\",\"dir\":false}]",
                "first", "[{\"file_name\":\"Zootopia.2016.2160p.UHD.BluRay.REMUX.mkv\",\"dir\":false}]"));
        var selection = client.resolveMovieShareUrl("https://pan.quark.cn/s/fixture", "疯狂动物城", "Zootopia", null, 2016);
        assertEquals("https://pan.quark.cn/s/fixture#/list/share/first", selection.shareUrl());
        assertTrue(selection.recursive());
    }

    @Test
    void rejectsMixedMovieFilesAtTheShareRootInsteadOfTransferringBoth() {
        QuarkAutoSaveClient client = movieTree(Map.of("root",
                "[{\"file_name\":\"Zootopia.2016.mkv\",\"dir\":false},{\"file_name\":\"Zootopia.2.2025.mkv\",\"dir\":false}]"));
        assertThrows(IllegalStateException.class, () -> client.resolveMovieShareUrl(
                "https://pan.quark.cn/s/mixed", "疯狂动物城", "Zootopia", null, 2016));
    }

    @Test
    void doesNotTreatASequelWithTheSameYearAsTheRequestedMovie() {
        QuarkAutoSaveClient client = movieTree(Map.of("root",
                "[{\"file_name\":\"Zootopia.2.2016.mkv\",\"dir\":false}]"));
        assertThrows(IllegalStateException.class, () -> client.resolveMovieShareUrl(
                "https://pan.quark.cn/s/sequel", "疯狂动物城", "Zootopia", null, 2016));
    }

    @Test
    void neverWidensAnExplicitDirectoryToTheShareRoot() {
        QuarkAutoSaveClient client = movieTree(Map.of("first",
                "[{\"file_name\":\"Zootopia.2016.mkv\",\"dir\":false}]"));
        String scoped = "https://pan.quark.cn/s/fixture#/list/share/first";
        assertEquals(scoped, client.resolveMovieShareUrl(scoped, "疯狂动物城", "Zootopia", null, 2016).shareUrl());
    }

    @Test
    void disablesRecursionWhenSelectedDirectoryAlsoContainsUnverifiedSubdirectories() {
        QuarkAutoSaveClient client = movieTree(Map.of("first",
                "[{\"file_name\":\"Zootopia.2016.mkv\",\"dir\":false},{\"fid\":\"second\",\"file_name\":\"其他影片\",\"dir\":true}]"));
        var selected = client.resolveMovieShareUrl("https://pan.quark.cn/s/fixture#/list/share/first", "疯狂动物城", "Zootopia", null, 2016);
        assertFalse(selected.recursive());
    }

    @Test
    void failsClosedWhenOnlyUnidentifiableNestedVideosExist() {
        QuarkAutoSaveClient client = movieTree(Map.of(
                "root", "[{\"fid\":\"unknown\",\"file_name\":\"电影合集\",\"dir\":true}]",
                "unknown", "[{\"file_name\":\"movie.mkv\",\"dir\":false}]"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> client.resolveMovieShareUrl(
                "https://pan.quark.cn/s/unknown", "疯狂动物城", "Zootopia", null, 2016));
        assertTrue(error.getMessage().contains("target movie directory"));
    }

    @Test
    void keepsNamedMovieContextForNestedEncodingVersionsButNotAnotherInstallment() {
        QuarkAutoSaveClient client = movieTree(Map.of(
                "root", "[{\"fid\":\"movie\",\"file_name\":\"疯狂动物城 (2016)\",\"dir\":true}]",
                "movie", "[{\"fid\":\"second\",\"file_name\":\"第二部\",\"dir\":true},{\"fid\":\"version\",\"file_name\":\"4K蓝光版本\",\"dir\":true}]",
                "second", "[{\"file_name\":\"movie.mkv\",\"dir\":false}]",
                "version", "[{\"file_name\":\"2160p.mkv\",\"dir\":false}]"));
        assertEquals("https://pan.quark.cn/s/fixture#/list/share/version", client.resolveMovieShareUrl(
                "https://pan.quark.cn/s/fixture", "疯狂动物城", "Zootopia", null, 2016).shareUrl());
    }

    @Test
    void doesNotFallBackToRootWhenDirectoryReadFails() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate rest = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(rest);
        when(rest.postForEntity(contains("/get_share_detail"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("offline"));
        QuarkAutoSaveClient client = new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);
        assertThrows(IllegalStateException.class, () -> client.resolveMovieShareUrl(
                "https://pan.quark.cn/s/fixture", "疯狂动物城", "Zootopia", null, 2016));
        verify(rest, never()).postForEntity(contains("/run_script_now"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void boundsCyclesInMovieDirectoryTraversal() {
        QuarkAutoSaveClient client = movieTree(Map.of(
                "root", "[{\"fid\":\"cycle\",\"file_name\":\"合集\",\"dir\":true}]",
                "cycle", "[{\"fid\":\"cycle\",\"file_name\":\"合集\",\"dir\":true}]"));
        assertThrows(IllegalStateException.class, () -> client.resolveMovieShareUrl(
                "https://pan.quark.cn/s/fixture", "疯狂动物城", "Zootopia", null, 2016));
    }

    private QuarkAutoSaveClient movieTree(Map<String, String> directories) {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setToken("test-token");
        RestTemplate rest = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(rest);
        when(rest.postForEntity(contains("/get_share_detail"), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<?> request = invocation.getArgument(1);
                    String url = String.valueOf(((Map<?, ?>) request.getBody()).get("shareurl"));
                    String key = url.contains("#/list/share/") ? url.substring(url.lastIndexOf('/') + 1) : "root";
                    assertTrue(directories.containsKey(key), "Unexpected or out-of-scope directory: " + key);
                    return ResponseEntity.ok("{\"success\":true,\"data\":{\"list\":" + directories.get(key) + "}}");
                });
        return new QuarkAutoSaveClient(builder, new ObjectMapper(), properties);
    }
}
