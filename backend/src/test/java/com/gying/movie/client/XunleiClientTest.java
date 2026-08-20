package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class XunleiClientTest {

    @Test
    void buildsWebSharePayloadAndIncludesPassCodeInUrl() throws Exception {
        Map<String, Object> payload = XunleiClient.sharePayload("file-id");

        assertEquals(List.of("file-id"), payload.get("file_ids"));
        assertEquals("copy", payload.get("share_to"));
        assertEquals("-1", payload.get("restore_limit"));
        assertEquals("-1", payload.get("expiration_days"));
        assertEquals(
                "https://pan.xunlei.com/s/share-id?pwd=c6jr",
                XunleiClient.parseShareUrl(new ObjectMapper().readTree(
                        "{\"share_url\":\"https://pan.xunlei.com/s/share-id\","
                                + "\"pass_code\":\"c6jr\"}")));
    }

    @Test
    void restoresOnlyTopLevelShareItemsIntoExplicitAncestorPath() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> ids = XunleiClient.extractTopLevelFileIds(mapper.readTree("""
                {
                  "share_user": {"id": "user-id", "name": "Owner"},
                  "files": [
                    {"id": "folder-id", "name": "Movie", "children": [{"id": "video-id", "name": "Movie.mkv"}]},
                    {"id": "subtitle-id", "name": "Movie.srt"}
                  ]
                }
                """));
        XunleiClient.ShareInfo share = new XunleiClient.ShareInfo(
                "share-id", "code", "token", ids, List.of("Movie", "Movie.srt"));
        XunleiClient.DirectoryInfo directory = new XunleiClient.DirectoryInfo(
                "my-transfers-id", List.of());

        Map<String, Object> payload = XunleiClient.restorePayload(share, directory);

        assertEquals(List.of("folder-id", "subtitle-id"), payload.get("file_ids"));
        assertEquals("my-transfers-id", payload.get("parent_id"));
        assertEquals(List.of(), payload.get("ancestor_ids"));
    }

    @Test
    void extractsOnlyVideoFilesFromNestedShareItems() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var response = mapper.readTree("""
                {
                  "files": [
                    {"id":"folder", "kind":"drive#folder", "children":[
                      {"id":"movie", "name":"Movie.MKV", "file_extension":"MKV", "kind":"drive#file"},
                      {"id":"poster", "name":"poster.jpg", "file_extension":"jpg", "kind":"drive#file"},
                      {"id":"readme", "name":"README.pdf", "file_extension":"pdf", "kind":"drive#file"}
                    ]},
                    {"id":"trailer", "name":"trailer.mp4", "mime_type":"video/mp4", "kind":"drive#file"}
                  ]
                }
                """);

        assertEquals(List.of("movie", "trailer"), XunleiClient.extractVideoFileIds(response));
        assertEquals(List.of("Movie.MKV", "trailer.mp4"), XunleiClient.extractVideoFileNames(response));
    }

    @Test
    void ignoresShareContainingOnlyFoldersAndDocuments() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(List.of(), XunleiClient.extractVideoFileIds(mapper.readTree("""
                {"files":[{"id":"folder","kind":"drive#folder","children":[
                  {"id":"poster","name":"poster.png","file_extension":"png"}
                ]}]}
                """)));
    }
}
