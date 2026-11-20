package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class P2pCloudArchiveClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private P2pCloudArchiveClient.FileData text() {
        return new P2pCloudArchiveClient.FileData("1080P_字幕_fixture.txt", ("magnet:?xt=urn:btih:" + "a".repeat(40)).getBytes(StandardCharsets.UTF_8));
    }
    @Test void onlyBoundedMetadataFilesAreAccepted() {
        var good = text(); P2pCloudArchiveClient.validateFile(good);
        for (String name : List.of("video.mp4", "../film.txt", "folder/file.txt", "folder\\file.txt", "bad\n.txt")) {
            assertThrows(IllegalArgumentException.class, () -> P2pCloudArchiveClient.validateFile(new P2pCloudArchiveClient.FileData(name, good.bytes())));
        }
        assertThrows(IllegalArgumentException.class, () -> P2pCloudArchiveClient.validateFile(new P2pCloudArchiveClient.FileData("empty.txt", new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> P2pCloudArchiveClient.validateFile(new P2pCloudArchiveClient.FileData("large.torrent", new byte[2 * 1024 * 1024 + 1])));
        assertThrows(IllegalArgumentException.class, () -> P2pCloudArchiveClient.validateFile(new P2pCloudArchiveClient.FileData("fake.torrent", "<html>login</html>".getBytes(StandardCharsets.UTF_8))));
    }
    @Test void existingVerifiedFileIsNotUploadedAgain() throws Exception {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var file = text();
        when(quark.p2pChildren("folder")).thenReturn(List.of(mapper.valueToTree(Map.of("fid", "file1", "file_name", file.name(), "size", file.bytes().length))));
        var client = new P2pCloudArchiveClient(quark, xunlei, mock(BaiduWebClient.class), mapper);
        assertEquals("file1", client.upload("QUARK", "folder", file));
        verify(quark, never()).p2pRequest(anyString(), anyMap()); verifyNoInteractions(xunlei);
    }
    @Test void incompatibleExistingFileDoesNotGetOverwritten() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var file = text();
        when(quark.p2pChildren("folder")).thenReturn(List.of(mapper.valueToTree(Map.of("fid", "file1", "file_name", file.name(), "size", 1))));
        assertThrows(IllegalStateException.class, () -> new P2pCloudArchiveClient(quark, xunlei, mock(BaiduWebClient.class), mapper).upload("QUARK", "folder", file));
        verify(quark, never()).p2pRequest(anyString(), anyMap());
    }
    @Test void quarkMultipartUploadChecksChecksumAndConfirmsDestination() throws Exception {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var file = text();
        var rest = new RestTemplate(); var server = MockRestServiceServer.bindTo(rest).build();
        when(quark.p2pChildren("folder")).thenReturn(List.of(), List.of(mapper.valueToTree(Map.of("fid", "file1", "file_name", file.name(), "size", file.bytes().length))));
        when(quark.p2pRequest(eq("/1/clouddrive/file/upload/pre"), anyMap())).thenReturn(mapper.readTree("{\"data\":{\"task_id\":\"task1\",\"bucket\":\"fixture-bucket\",\"obj_key\":\"fixture/key\",\"upload_id\":\"upload1\",\"upload_url\":\"http://oss-cn-shenzhen.aliyuncs.com\",\"auth_info\":\"fixture\",\"callback\":{}}}"));
        when(quark.p2pRequest(eq("/1/clouddrive/file/update/hash"), anyMap())).thenReturn(mapper.readTree("{\"data\":{\"finish\":false}}"));
        when(quark.p2pRequest(eq("/1/clouddrive/file/upload/auth"), anyMap())).thenReturn(mapper.readTree("{\"data\":{\"auth_key\":\"fixture-auth\"}}"));
        server.expect(requestTo("https://fixture-bucket.oss-cn-shenzhen.aliyuncs.com/fixture/key?partNumber=1&uploadId=upload1"))
                .andExpect(method(HttpMethod.PUT)).andExpect(content().bytes(file.bytes())).andExpect(headerDoesNotExist("Cookie"))
                .andRespond(withSuccess().header("ETag", "\"" + P2pCloudArchiveClient.digest("MD5", file.bytes()) + "\""));
        server.expect(requestTo("https://fixture-bucket.oss-cn-shenzhen.aliyuncs.com/fixture/key?uploadId=upload1"))
                .andExpect(method(HttpMethod.POST)).andExpect(headerDoesNotExist("Cookie")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertEquals("file1", new P2pCloudArchiveClient(quark, xunlei, mock(BaiduWebClient.class), mapper, rest).upload("QUARK", "folder", file));
        server.verify(); verify(quark).p2pRequest(eq("/1/clouddrive/file/upload/finish"), anyMap()); verifyNoInteractions(xunlei);
    }
    @Test void storageEndpointCannotPointToAnInternalOrUnrelatedHost() throws Exception {
        for (String host : List.of("http://127.0.0.1", "https://evil.example", "http://169.254.169.254", "https://pds.quark.cn.evil.example",
                "https://evil.pds.quark.cn", "https://user@pds.quark.cn", "https://pds.quark.cn:8443",
                "https://pds.quark.cn?override=true")) {
            var pre = mapper.valueToTree(Map.of("upload_url", host, "bucket", "fixture-bucket", "obj_key", "key", "upload_id", "id"));
            assertThrows(IllegalArgumentException.class, () -> P2pCloudArchiveClient.quarkStorageUri(pre, true));
        }
    }
    @Test void quarkPdsUploadHostUsesHttpsWithoutRelaxingHostValidation() {
        var pre = mapper.valueToTree(Map.of("upload_url", "http://pds.quark.cn", "bucket", "fixture-bucket",
                "obj_key", "fixture/key", "upload_id", "upload1"));
        assertEquals("https://fixture-bucket.pds.quark.cn/fixture/key?partNumber=1&uploadId=upload1",
                P2pCloudArchiveClient.quarkStorageUri(pre, true).toString());
    }
    @Test void uploadTicketReservedCharactersRemainOneQueryValue() {
        var pre = mapper.valueToTree(Map.of("upload_url", "http://pds.quark.cn", "bucket", "fixture-bucket",
                "obj_key", "fixture/key", "upload_id", "ticket+part/==&next"));
        assertEquals("https://fixture-bucket.pds.quark.cn/fixture/key?uploadId=ticket%2Bpart%2F%3D%3D%26next",
                P2pCloudArchiveClient.quarkStorageUri(pre, false).toString());
    }
    @Test void xunleiRequestsRawUploadAndNeverAnOfflineUrl() throws Exception {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var file = text();
        when(xunlei.p2pChildren("folder")).thenReturn(List.of(), List.of(mapper.valueToTree(Map.of("id", "file1", "name", file.name(), "size", file.bytes().length, "phase", "PHASE_TYPE_COMPLETE"))));
        when(xunlei.p2pCreateFile(anyMap())).thenReturn(mapper.readTree("{\"upload_type\":\"UPLOAD_TYPE_RESUMABLE\",\"resumable\":{\"params\":{}}}"));
        var client = spy(new P2pCloudArchiveClient(quark, xunlei, mock(BaiduWebClient.class), mapper));
        doNothing().when(client).uploadXunleiStorage(any(), eq(file));
        assertEquals("file1", client.upload("XUNLEI", "folder", file));
        var payload = org.mockito.ArgumentCaptor.forClass(Map.class); verify(xunlei).p2pCreateFile(payload.capture());
        assertEquals("UPLOAD_TYPE_RESUMABLE", payload.getValue().get("upload_type"));
        assertEquals("drive#file", payload.getValue().get("kind")); assertFalse(payload.getValue().containsKey("url"));
        verify(xunlei, never()).restore(anyString(), anyString()); verifyNoInteractions(quark);
    }
    @Test void baiduUsesOnlyBoundedMetadataUploadAndVerifiedOwnShare() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
        var file = text(); String folder = "/Baidu/Movie/磁力种子";
        when(baidu.p2pFolder("/Baidu/Movie", "/Baidu/fallback")).thenReturn(folder);
        when(baidu.p2pUpload(folder, file.name(), file.bytes())).thenReturn("123");
        when(baidu.createOwnShare(folder)).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        when(baidu.findOwnShare(folder)).thenReturn("https://pan.baidu.com/s/1fixture?pwd=abcd");
        var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper);
        assertEquals(folder, client.ensureFolder("BAIDU", "/Baidu/Movie", "/Baidu/fallback"));
        assertEquals("123", client.upload("BAIDU", folder, file));
        assertEquals("https://pan.baidu.com/s/1fixture?pwd=abcd", client.createShare("BAIDU", folder, "metadata"));
        assertEquals("https://pan.baidu.com/s/1fixture?pwd=abcd", client.findOwnShare("BAIDU", folder));
        verify(baidu).findOwnShare(folder);
        verify(baidu).p2pFolder("/Baidu/Movie", "/Baidu/fallback");
        verify(baidu).p2pUpload(folder, file.name(), file.bytes()); verify(baidu).createOwnShare(folder);
        verifyNoMoreInteractions(baidu); verifyNoInteractions(quark, xunlei);
    }
    @Test void unknownProviderCannotFallThroughToAnExistingDrive() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
        var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper);
        for (String provider : java.util.Arrays.asList(null, "", "OTHER", "baidu")) {
            assertThrows(IllegalArgumentException.class, () -> client.isConfigured(provider));
            assertThrows(IllegalArgumentException.class, () -> client.verifyMetadataOnlyFolder(provider, "/Movie"));
            assertThrows(IllegalArgumentException.class, () -> client.ensureFolder(provider, null, "/Movie"));
            assertThrows(IllegalArgumentException.class, () -> client.upload(provider, "folder", text()));
            assertThrows(IllegalArgumentException.class, () -> client.findUploadedFile(provider, "folder", text()));
            assertThrows(IllegalArgumentException.class, () -> client.createShare(provider, "folder", "title"));
        }
        verifyNoInteractions(quark, xunlei, baidu);
    }
    @Test void baiduRejectsVideoBeforeCallingTheAccountClientAndRedactsUploadErrors() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
        var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper);
        assertThrows(IllegalArgumentException.class, () -> client.upload("BAIDU", "/Movie/磁力种子",
                new P2pCloudArchiveClient.FileData("video.mp4", text().bytes())));
        verifyNoInteractions(baidu);
        when(baidu.p2pUpload(anyString(), anyString(), any())).thenThrow(new IllegalStateException("fixture-sensitive-cookie"));
        var failure = assertThrows(IllegalStateException.class, () -> client.upload("BAIDU", "/Movie/磁力种子", text()));
        assertFalse(failure.getMessage().contains("fixture-sensitive-cookie")); assertNull(failure.getCause());
        verifyNoInteractions(quark, xunlei);
    }
    @Test void baiduUploadPreservesAccountActionAndUncertainOutcomeClassification() {
        for (var expected : List.of(
                new BaiduActionException("UPLOAD_SESSION_SCOPE_UNAVAILABLE", false, true, false),
                new BaiduActionException("SESSION_EXPIRED", false, true, false),
                new BaiduActionException("UPLOAD_OUTCOME_UNKNOWN", false, false, true),
                new BaiduActionException("UPLOAD_RETRYABLE", true, false, false))) {
            var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
            var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper); var file = text();
            when(baidu.p2pUpload("/fixture/metadata", file.name(), file.bytes())).thenThrow(expected);
            var actual = assertThrows(BaiduActionException.class, () -> client.upload("BAIDU", "/fixture/metadata", file));
            assertSame(expected, actual); assertEquals(expected.code(), actual.code());
            assertEquals(expected.actionRequired(), actual.actionRequired()); assertEquals(expected.uncertain(), actual.uncertain());
            assertEquals(expected.retryable(), actual.retryable());
            verify(baidu).p2pUpload("/fixture/metadata", file.name(), file.bytes()); verifyNoMoreInteractions(baidu);
            verifyNoInteractions(quark, xunlei);
        }
    }
    @Test void baiduConfiguredGateReadsSessionMetadataWithoutAnyCloudAction() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
        var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper);
        assertFalse(client.isConfigured("BAIDU")); when(baidu.isConfigured()).thenReturn(true); assertTrue(client.isConfigured("BAIDU"));
        verify(baidu, times(2)).isConfigured(); verifyNoMoreInteractions(baidu); verifyNoInteractions(quark, xunlei);
    }
    @Test void baiduMetadataShareVerificationDelegatesOnlyToTheReadOnlyTreeCheck() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
        var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper);
        client.verifyMetadataOnlyFolder("BAIDU", "/Movie/磁力种子");
        verify(baidu).verifyMetadataOnlyFolder("/Movie/磁力种子"); verifyNoMoreInteractions(baidu); verifyNoInteractions(quark, xunlei);
    }
    @Test void baiduUploadReconciliationIsReadOnlyForBothConfirmedAndMissingFiles() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
        var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper); var file = text(); String folder = "/Movie/磁力种子";
        when(baidu.p2pFindUploadedFile(folder, file.name(), file.bytes())).thenReturn("123", (String) null);
        assertEquals("123", client.findUploadedFile("BAIDU", folder, file)); assertNull(client.findUploadedFile("BAIDU", folder, file));
        verify(baidu, times(2)).p2pFindUploadedFile(folder, file.name(), file.bytes()); verifyNoMoreInteractions(baidu); verifyNoInteractions(quark, xunlei);
    }
    @Test void baiduUploadReconciliationRejectsUnsupportedProvidersAndVideoBeforeReadingTheAccount() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
        var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper);
        for (String provider : List.of("QUARK", "XUNLEI")) assertThrows(IllegalArgumentException.class,
                () -> client.findUploadedFile(provider, "folder", text()));
        assertThrows(IllegalArgumentException.class, () -> client.findUploadedFile("BAIDU", "/Movie/磁力种子",
                new P2pCloudArchiveClient.FileData("video.mp4", text().bytes())));
        verifyNoInteractions(quark, xunlei, baidu);
    }
    @Test void baiduUploadReconciliationPreservesFixedErrorsAndRedactsUnexpectedFailures() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var baidu = mock(BaiduWebClient.class);
        var client = new P2pCloudArchiveClient(quark, xunlei, baidu, mapper); var file = text();
        var expected = new BaiduActionException("UPLOAD_CHECKPOINT_MISMATCH", false, true, false);
        when(baidu.p2pFindUploadedFile(anyString(), anyString(), any())).thenThrow(expected)
                .thenThrow(new IllegalStateException("fixture-sensitive-cookie"));
        assertSame(expected, assertThrows(BaiduActionException.class, () -> client.findUploadedFile("BAIDU", "/Movie/磁力种子", file)));
        var actual = assertThrows(IllegalStateException.class, () -> client.findUploadedFile("BAIDU", "/Movie/磁力种子", file));
        assertFalse(actual.getMessage().contains("fixture-sensitive-cookie")); assertNull(actual.getCause());
        verifyNoInteractions(quark, xunlei);
    }
}
