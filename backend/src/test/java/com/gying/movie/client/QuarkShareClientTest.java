package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkShareResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class QuarkShareClientTest {

    @Test
    void fallsBackToValidMediaFilesWhenFolderShareContainsRejectedFile() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setSharePollAttempts(1);
        QuarkAutoSaveClient autoSaveClient = mock(QuarkAutoSaveClient.class);
        when(autoSaveClient.getPrimaryCookie()).thenReturn("cookie=value");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/1/clouddrive/file/info/path_list"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"code":0,"data":[{"fid":"movie-folder","file_name":"Toy Story 5"}]}
                        """));
        when(restTemplate.postForEntity(
                contains("/1/clouddrive/share?"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(
                        ResponseEntity.ok("""
                                {"code":0,"data":{"task_id":"folder-share-task"}}
                                """),
                        ResponseEntity.ok("""
                                {"code":0,"data":{"task_id":"file-share-task"}}
                                """));
        when(restTemplate.exchange(
                contains("/1/clouddrive/task"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(
                        ResponseEntity.ok("""
                                {"status":403,"code":41026,"message":"no shareable files","data":{
                                  "status":3,"creation_snapshot":{"invalid_fids":["bad-video"]}
                                }}
                                """),
                        ResponseEntity.ok("""
                                {"code":0,"data":{"share_id":"owned-share"}}
                                """));
        when(restTemplate.exchange(
                contains("/1/clouddrive/file/sort"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"code":0,"data":{"list":[
                          {"fid":"bad-video","file_name":"bad.mp4","dir":false},
                          {"fid":"good-video","file_name":"good.mkv","dir":false}
                        ]}}
                        """));
        when(restTemplate.postForEntity(
                contains("/1/clouddrive/share/password"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"code":0,"data":{"share_url":"https://pan.quark.cn/s/owned"}}
                        """));
        QuarkShareClient client = new QuarkShareClient(
                builder,
                new ObjectMapper(),
                properties,
                autoSaveClient);

        QuarkShareResult result = client.createShareForPath(
                "/GYing Resource Hub/movie/Toy Story 5",
                "Toy Story 5");

        assertEquals("https://pan.quark.cn/s/owned", result.getShareUrl());
        ArgumentCaptor<HttpEntity> requests = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(2)).postForEntity(
                contains("/1/clouddrive/share?"), requests.capture(), eq(String.class));
        String retryPayload = requests.getAllValues().get(1).getBody().toString();
        assertTrue(retryPayload.contains("good-video"));
        assertFalse(retryPayload.contains("bad-video"));
    }

    @Test
    void keepsPollingUntilNestedShareIdIsAvailable() {
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getQuark().setSharePollAttempts(2);
        properties.getQuark().setSharePollIntervalMs(100);
        QuarkAutoSaveClient autoSaveClient = mock(QuarkAutoSaveClient.class);
        when(autoSaveClient.getPrimaryCookie()).thenReturn("cookie=value");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/1/clouddrive/file/info/path_list"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"code":0,"data":[{"fid":"movie-folder","file_name":"Toy Story 5"}]}
                        """));
        when(restTemplate.postForEntity(
                contains("/1/clouddrive/share?"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"code":0,"data":{"task_id":"share-task"}}
                        """));
        when(restTemplate.exchange(
                contains("/1/clouddrive/task"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(
                        ResponseEntity.ok("""
                                {"code":0,"data":{"status":2}}
                                """),
                        ResponseEntity.ok("""
                                {"code":0,"data":{"result":{"share_info":{"share_id":"owned-share"}}}}
                                """));
        when(restTemplate.postForEntity(
                contains("/1/clouddrive/share/password"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"code":0,"data":{"share":{"share_url":"https://pan.quark.cn/s/owned","passcode":"abcd"}}}
                        """));
        QuarkShareClient client = new QuarkShareClient(
                builder,
                new ObjectMapper(),
                properties,
                autoSaveClient);

        QuarkShareResult result = client.createShareForPath(
                "/GYing Resource Hub/movie/Toy Story 5",
                "Toy Story 5");

        assertEquals("https://pan.quark.cn/s/owned?pwd=abcd", result.getShareUrl());
        verify(restTemplate, times(2)).exchange(
                contains("/1/clouddrive/task"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class));
    }

    @Test
    void findsTransferredMediaInsideNestedFolder() {
        ResourceHubProperties properties = new ResourceHubProperties();
        QuarkAutoSaveClient autoSaveClient = mock(QuarkAutoSaveClient.class);
        when(autoSaveClient.getPrimaryCookie()).thenReturn("cookie=value");
        RestTemplate restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.postForEntity(
                contains("/1/clouddrive/file/info/path_list"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"code":0,"data":[{"fid":"root-folder","file_name":"Supergirl"}]}
                        """));
        when(restTemplate.exchange(
                contains("/1/clouddrive/file/sort"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(
                        ResponseEntity.ok("""
                                {"code":0,"data":{"list":[
                                  {"fid":"season-folder","file_name":"Season 1","dir":true}
                                ]}}
                                """),
                        ResponseEntity.ok("""
                                {"code":0,"data":{"list":[
                                  {"fid":"subtitle","file_name":"Supergirl.srt","dir":false},
                                  {"fid":"video","file_name":"Supergirl.S01E01.mkv","dir":false}
                                ]}}
                                """));
        QuarkShareClient client = new QuarkShareClient(
                builder,
                new ObjectMapper(),
                properties,
                autoSaveClient);

        QuarkShareClient.FolderContentCheck result = client.checkFolderContent(
                "/GYing Resource Hub/tv/Supergirl/第1季");

        assertTrue(result.hasContent());
        assertEquals(1, result.itemCount());
        verify(restTemplate, times(2)).exchange(
                contains("/1/clouddrive/file/sort"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class));
    }
}
