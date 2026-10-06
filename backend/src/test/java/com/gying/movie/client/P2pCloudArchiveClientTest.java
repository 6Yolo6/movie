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
        var client = new P2pCloudArchiveClient(quark, xunlei, mapper);
        assertEquals("file1", client.upload("QUARK", "folder", file));
        verify(quark, never()).p2pRequest(anyString(), anyMap()); verifyNoInteractions(xunlei);
    }
    @Test void incompatibleExistingFileDoesNotGetOverwritten() {
        var quark = mock(QuarkShareClient.class); var xunlei = mock(XunleiClient.class); var file = text();
        when(quark.p2pChildren("folder")).thenReturn(List.of(mapper.valueToTree(Map.of("fid", "file1", "file_name", file.name(), "size", 1))));
        assertThrows(IllegalStateException.class, () -> new P2pCloudArchiveClient(quark, xunlei, mapper).upload("QUARK", "folder", file));
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
        assertEquals("file1", new P2pCloudArchiveClient(quark, xunlei, mapper, rest).upload("QUARK", "folder", file));
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
        var client = spy(new P2pCloudArchiveClient(quark, xunlei, mapper));
        doNothing().when(client).uploadXunleiStorage(any(), eq(file));
        assertEquals("file1", client.upload("XUNLEI", "folder", file));
        var payload = org.mockito.ArgumentCaptor.forClass(Map.class); verify(xunlei).p2pCreateFile(payload.capture());
        assertEquals("UPLOAD_TYPE_RESUMABLE", payload.getValue().get("upload_type"));
        assertEquals("drive#file", payload.getValue().get("kind")); assertFalse(payload.getValue().containsKey("url"));
        verify(xunlei, never()).restore(anyString(), anyString()); verifyNoInteractions(quark);
    }
}
