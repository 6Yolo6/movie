package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.UriComponentsBuilder;

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

    @Test
    void recognizesVideoNamesAndNestedNodesUsedByXunleiShareApi() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var response = mapper.readTree("""
                {
                  "data": {
                    "file_list": [
                      {"file_id":"folder-1", "file_type":"folder", "items":[
                        {"fid":"movie-1", "filename":"Movie 2160P.MP4", "mimeType":"application/octet-stream"},
                        {"fid":"poster-1", "filename":"poster.webp", "mimeType":"image/webp"}
                      ]}
                    ]
                  }
                }
                """);

        assertEquals(List.of("movie-1"), XunleiClient.extractVideoFileIds(response));
        assertEquals(List.of("Movie 2160P.MP4"), XunleiClient.extractVideoFileNames(response));
    }

    @Test
    void walksArbitraryShareResponseListsAndCamelCaseFileFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var response = mapper.readTree("""
                {"data":{"shareFileList":[{"fileId":"folder","type":"folder","entries":[
                  {"fileId":"video-4496","fileName":"Movie.2160P.mkv","mimeType":"application/octet-stream"}
                ]}]},"meta":{"items":[{"fileId":"poster","fileName":"poster.jpg"}]}}
                """);
        assertEquals(List.of("video-4496"), XunleiClient.extractVideoFileIds(response));
        assertEquals(List.of("Movie.2160P.mkv"), XunleiClient.extractVideoFileNames(response));
    }

    @Test
    void parsesNestedShareUrlAndPassCode() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var response = mapper.readTree("""
                {"data":{"share":{"url":"https://pan.xunlei.com/s/owned","pass_code":"abcd"}}}
                """);

        assertEquals("https://pan.xunlei.com/s/owned?pwd=abcd", XunleiClient.parseShareUrl(response));
    }

    @Test
    void appendsSeparateExtractionCodeAndRemovesFragmentNoise() {
        assertEquals(
                "https://pan.xunlei.com/s/share-id?pwd=abcd",
                XunleiClient.normalizeShareUrl(
                        "https://pan.xunlei.com/s/share-id#ignored", "abcd"));
        assertEquals(
                "https://pan.xunlei.com/s/share-id?pwd=kept",
                XunleiClient.normalizeShareUrl(
                        "https://pan.xunlei.com/s/share-id?pwd=kept###", "other"));
    }

    @Test
    void normalizesBase64UrlPagingTokensForTheDriveApi() {
        assertEquals("YWJjZA==", XunleiClient.normalizeBase64Token("YWJjZA"));
        assertEquals("YWJjZA==", XunleiClient.normalizeBase64Token("YWJjZA=="));
        assertEquals("VOx/nv+token", XunleiClient.normalizeBase64Token("VOx_nv-token"));
        assertEquals(null, XunleiClient.normalizeBase64Token("illegal token"));
        assertEquals(null, XunleiClient.normalizeBase64Token("a"));
        assertEquals(null, XunleiClient.normalizeBase64Token(""));
    }

    @Test
    void usesRootShareEndpointForTheTopLevelListing() {
        var uri = UriComponentsBuilder.fromUriString(
                        XunleiClient.sharePageUri("share-id", "7zfs", "YWJjZA", null, null))
                .build();

        assertEquals("/share", uri.getPath());
        assertEquals("share-id", uri.getQueryParams().getFirst("share_id"));
        assertEquals("7zfs", uri.getQueryParams().getFirst("pass_code"));
        assertEquals("YWJjZA", decode(uri.getQueryParams().getFirst("pass_code_token")));
        assertEquals("DEFAULT_ORDER", uri.getQueryParams().getFirst("order"));
        assertEquals("SIZE_MEDIUM", uri.getQueryParams().getFirst("thumbnail_size"));
        assertEquals(null, uri.getQueryParams().getFirst("parent_id"));
    }

    @Test
    void usesShareDetailEndpointForFolderTraversal() {
        var uri = UriComponentsBuilder.fromUriString(
                        XunleiClient.sharePageUri("share-id", "7zfs", "YWJjZA", "folder-id", "ZWZnaA"))
                .build();

        assertEquals("/share/detail", uri.getPath());
        assertEquals("share-id", uri.getQueryParams().getFirst("share_id"));
        assertEquals(null, uri.getQueryParams().getFirst("pass_code"));
        assertEquals("YWJjZA", decode(uri.getQueryParams().getFirst("pass_code_token")));
        assertEquals("folder-id", uri.getQueryParams().getFirst("parent_id"));
        assertEquals("ZWZnaA", decode(uri.getQueryParams().getFirst("page_token")));
        assertEquals("MODIFY_TIME_DESC_V2", uri.getQueryParams().getFirst("order"));
        assertEquals("SIZE_MEDIUM", uri.getQueryParams().getFirst("thumbnail_size"));
    }

    @Test
    void readsPublicShareListingsWithoutAccountAuthorization() {
        assertEquals(false, XunleiClient.requiresAuthorization(HttpMethod.GET, "/share?share_id=share-id"));
        assertEquals(false, XunleiClient.requiresAuthorization(HttpMethod.GET, "/share/detail?parent_id=folder-id"));
        assertEquals(true, XunleiClient.requiresAuthorization(HttpMethod.POST, "/share/restore"));
        assertEquals(true, XunleiClient.requiresAuthorization(HttpMethod.POST, "/share"));
        assertEquals(true, XunleiClient.requiresAuthorization(HttpMethod.GET, "/files"));
    }

    @Test
    void preservesOpaqueShareTokensExactlyAsReturnedByXunlei() {
        assertEquals("VOx_nv-token", XunleiClient.opaqueShareToken("VOx_nv-token"));
        assertEquals("YWJjZA==", XunleiClient.opaqueShareToken("YWJjZA=="));
        assertEquals("YWJj+ZA==", XunleiClient.opaqueShareToken("YWJj%2BZA%3D%3D"));
        assertEquals(null, XunleiClient.opaqueShareToken("illegal token"));
        assertEquals(null, XunleiClient.opaqueShareToken(""));
    }

    @Test
    void percentEncodesReservedBase64CharactersInShareTokenQueryValues() {
        String uri = XunleiClient.sharePageUri("share-id", "7zfs", "ab+c/==", "folder-id", null);

        assertEquals(true, uri.contains("pass_code_token=ab%2Bc%2F%3D%3D"), uri);
        assertEquals("ab+c/==", decode(UriComponentsBuilder.fromUriString(uri)
                .build()
                .getQueryParams()
                .getFirst("pass_code_token")));
    }

    @Test
    void sendsEncodedShareTokensAsUriWithoutEncodingPercentSignsAgain() {
        String path = XunleiClient.sharePageUri("share-id", "7zfs", "ab+c/==", "folder-id", null);
        var uri = XunleiClient.requestUri("https://api-pan.xunlei.com/drive/v1/", path);

        assertEquals(true, uri.getRawQuery().contains("pass_code_token=ab%2Bc%2F%3D%3D"));
        assertEquals(true, uri.getQuery().contains("pass_code_token=ab+c/=="));
    }

    @Test
    void reportsOnlySafeTokenShapeForBase64Errors() {
        assertEquals(
                " (pass_code_token_length=13, first_non_base64_index=3, character_category=base64url)",
                XunleiClient.shareTokenProfile(
                        "/share/detail?pass_code_token=abc_def-token", "illegal base64 data"));
    }

    private static String decode(String value) {
        return UriUtils.decode(value, StandardCharsets.UTF_8);
    }
}
