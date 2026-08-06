package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class QuarkShareClientTest {

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
