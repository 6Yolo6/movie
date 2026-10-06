package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Raw, bounded metadata-file uploads only. Never submit magnet URLs to offline-download APIs. */
@Component
public class P2pCloudArchiveClient {
    public record FileData(String name, byte[] bytes) {}
    private static final String OSS_AGENT = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit";
    private final QuarkShareClient quark;
    private final XunleiClient xunlei;
    private final ObjectMapper mapper;
    private final RestTemplate storage;

    @Autowired
    public P2pCloudArchiveClient(QuarkShareClient quark, XunleiClient xunlei, ObjectMapper mapper) {
        this(quark, xunlei, mapper, storageClient());
    }
    P2pCloudArchiveClient(QuarkShareClient quark, XunleiClient xunlei, ObjectMapper mapper, RestTemplate storage) {
        this.quark = quark; this.xunlei = xunlei; this.mapper = mapper; this.storage = storage;
    }
    private static RestTemplate storageClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(5000); factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }
    public String ensureFolder(String provider, String preferred, String fallback) {
        requireProvider(provider);
        return "QUARK".equals(provider) ? quark.p2pFolder(preferred, fallback) : xunlei.p2pFolder(preferred, fallback);
    }
    public String createShare(String provider, String folder, String title) {
        requireProvider(provider);
        return "QUARK".equals(provider) ? quark.p2pShare(folder, title) : xunlei.createShare(folder);
    }
    public String upload(String provider, String folder, FileData file) {
        requireProvider(provider); validateFile(file);
        if (folder == null || !folder.matches("[A-Za-z0-9_-]{1,128}") || "0".equals(folder)) {
            throw new IllegalArgumentException("Movie P2P folder required");
        }
        String existing = findFile(provider, folder, file);
        if (existing != null) return existing;
        try {
            if ("QUARK".equals(provider)) uploadQuark(folder, file); else uploadXunlei(folder, file);
            for (int attempt = 0; attempt < 12; attempt++) {
                String fid = findFile(provider, folder, file);
                if (fid != null) return fid;
                Thread.sleep(1000);
            }
            throw new IllegalStateException("Uploaded P2P file was not confirmed in its destination");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("P2P upload interrupted");
        } catch (Exception error) {
            // Provider responses can contain temporary storage credentials; never relay them.
            throw new IllegalStateException(provider + " P2P file upload or verification failed (" + error.getClass().getSimpleName() + ")");
        }
    }
    private String findFile(String provider, String folder, FileData file) {
        List<JsonNode> items = "QUARK".equals(provider) ? quark.p2pChildren(folder) : xunlei.p2pChildren(folder);
        for (JsonNode item : items) {
            String name = item.path("file_name").asText(item.path("name").asText());
            if (!file.name().equals(name)) continue;
            if (item.path("dir").asBoolean() || "drive#folder".equals(item.path("kind").asText())
                    || item.path("size").asLong(-1) != file.bytes().length) {
                throw new IllegalStateException("Existing P2P filename has incompatible content");
            }
            String remoteHash = item.path("sha1").asText();
            if (!remoteHash.isBlank() && !remoteHash.equalsIgnoreCase(digest("SHA-1", file.bytes()))) {
                throw new IllegalStateException("Existing P2P filename hash mismatch");
            }
            if ("XUNLEI".equals(provider) && item.hasNonNull("phase")
                    && !"PHASE_TYPE_COMPLETE".equals(item.path("phase").asText())) return null;
            String id = item.path("fid").asText(item.path("id").asText());
            if (!id.isBlank()) return id;
        }
        return null;
    }
    private void uploadQuark(String folder, FileData file) throws Exception {
        String type = file.name().endsWith(".torrent") ? "application/x-bittorrent" : "text/plain";
        long now = System.currentTimeMillis();
        JsonNode pre = quark.p2pRequest("/1/clouddrive/file/upload/pre", Map.of(
                "ccp_hash_update", true, "dir_name", "", "file_name", file.name(), "format_type", type,
                "l_created_at", now, "l_updated_at", now, "pdir_fid", folder, "size", file.bytes().length)).path("data");
        String task = required(pre, "task_id");
        JsonNode hash = quark.p2pRequest("/1/clouddrive/file/update/hash", Map.of("task_id", task,
                "md5", digest("MD5", file.bytes()), "sha1", digest("SHA-1", file.bytes())));
        if (hash.path("data").path("finish").asBoolean()) return;
        String bucket = required(pre, "bucket"), key = required(pre, "obj_key"), uploadId = required(pre, "upload_id");
        URI uri = quarkStorageUri(pre, true);
        String date = httpDate();
        String canonical = "PUT\n\n" + type + "\n" + date + "\nx-oss-date:" + date
                + "\nx-oss-user-agent:" + OSS_AGENT + "\n/" + bucket + "/" + key + "?partNumber=1&uploadId=" + uploadId;
        HttpHeaders headers = quarkHeaders(pre, canonical, date, type);
        ResponseEntity<byte[]> put = storage.exchange(uri, HttpMethod.PUT, new HttpEntity<>(file.bytes(), headers), byte[].class);
        String etag = put.getHeaders().getETag();
        if (put.getStatusCode().value() != 200 || etag == null || !etag.replace("\"", "").equalsIgnoreCase(digest("MD5", file.bytes()))) {
            throw new IllegalStateException("Quark uploaded part checksum mismatch");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                + etag + "</ETag></Part></CompleteMultipartUpload>";
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        String md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body));
        String callback = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(pre.path("callback")));
        date = httpDate();
        canonical = "POST\n" + md5 + "\napplication/xml\n" + date + "\nx-oss-callback:" + callback
                + "\nx-oss-date:" + date + "\nx-oss-user-agent:" + OSS_AGENT + "\n/" + bucket + "/" + key + "?uploadId=" + uploadId;
        headers = quarkHeaders(pre, canonical, date, "application/xml");
        headers.set("Content-MD5", md5); headers.set("x-oss-callback", callback);
        ResponseEntity<byte[]> complete = storage.exchange(quarkStorageUri(pre, false), HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        if (complete.getStatusCode().value() != 200) throw new IllegalStateException("Quark upload commit failed");
        quark.p2pRequest("/1/clouddrive/file/upload/finish", Map.of("obj_key", key, "task_id", task));
    }
    private HttpHeaders quarkHeaders(JsonNode pre, String canonical, String date, String type) {
        JsonNode auth = quark.p2pRequest("/1/clouddrive/file/upload/auth", Map.of("auth_info", required(pre, "auth_info"),
                "auth_meta", canonical, "task_id", required(pre, "task_id")));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", required(auth.path("data"), "auth_key"));
        headers.set("Content-Type", type); headers.set("Referer", "https://pan.quark.cn/");
        headers.set("x-oss-date", date); headers.set("x-oss-user-agent", OSS_AGENT);
        return headers;
    }
    static URI quarkStorageUri(JsonNode pre, boolean part) {
        URI endpoint = URI.create(required(pre, "upload_url"));
        String host = endpoint.getHost(), bucket = required(pre, "bucket"), key = required(pre, "obj_key");
        if (host == null || !("pds.quark.cn".equals(host) || host.endsWith(".aliyuncs.com"))
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || endpoint.getPort() != -1 || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || !bucket.matches("[a-z0-9-]{3,64}")
                || !key.matches("[A-Za-z0-9/_.=-]{1,512}")) throw new IllegalArgumentException("Untrusted Quark storage endpoint");
        var builder = UriComponentsBuilder.fromUriString("https://" + bucket + "." + host).path("/" + key);
        if (part) builder.queryParam("partNumber", 1);
        return builder.queryParam("uploadId", "{uploadId}").encode()
                .buildAndExpand(Map.of("uploadId", required(pre, "upload_id"))).toUri();
    }
    private void uploadXunlei(String folder, FileData file) throws Exception {
        JsonNode response = xunlei.p2pCreateFile(Map.of("kind", "drive#file", "parent_id", folder, "name", file.name(),
                "size", file.bytes().length, "hash", gcid(file.bytes()), "upload_type", "UPLOAD_TYPE_RESUMABLE", "space", ""));
        if (response.has("data")) response = response.path("data");
        if ("UPLOAD_TYPE_RESUMABLE".equals(response.path("upload_type").asText())) {
            uploadXunleiStorage(response.path("resumable").path("params"), file);
        } else if (!"PHASE_TYPE_COMPLETE".equals(response.path("file").path("phase").asText(response.path("phase").asText()))) {
            throw new IllegalStateException("Xunlei upload initialization failed");
        }
    }
    void uploadXunleiStorage(JsonNode params, FileData file) throws Exception {
        String bucket = required(params, "bucket"), endpoint = required(params, "endpoint");
        URI uri = URI.create(endpoint.startsWith("http") ? endpoint : "https://" + endpoint);
        String host = uri.getHost();
        if (host != null && host.startsWith(bucket + ".")) host = host.substring(bucket.length() + 1);
        if (!"https".equals(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                || !(host.endsWith(".xunlei.com") || host.endsWith(".sandai.net") || host.endsWith(".myqcloud.com")
                || host.endsWith(".aliyuncs.com"))) throw new IllegalArgumentException("Untrusted Xunlei storage endpoint");
        var client = MinioClient.builder().endpoint("https://" + host).region("xunlei")
                .credentialsProvider(new io.minio.credentials.StaticProvider(required(params, "access_key_id"), required(params, "access_key_secret"), required(params, "security_token"))).build();
        client.enableVirtualStyleEndpoint();
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(required(params, "key"))
                .stream(new ByteArrayInputStream(file.bytes()), file.bytes().length, -1)
                .contentType(file.name().endsWith(".torrent") ? "application/x-bittorrent" : "text/plain").build());
    }
    static String gcid(byte[] bytes) {
        try {
            var combined = MessageDigest.getInstance("SHA-1");
            for (int offset = 0; offset < bytes.length; offset += 256 * 1024) {
                var block = MessageDigest.getInstance("SHA-1");
                block.update(bytes, offset, Math.min(256 * 1024, bytes.length - offset)); combined.update(block.digest());
            }
            return HexFormat.of().withUpperCase().formatHex(combined.digest());
        } catch (Exception error) { throw new IllegalStateException("GCID unavailable"); }
    }
    static String digest(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException("Digest unavailable"); }
    }
    static void validateFile(FileData file) {
        if (file == null || file.bytes() == null || file.bytes().length == 0 || file.bytes().length > 2 * 1024 * 1024
                || file.name() == null || file.name().length() > 180 || file.name().contains("..")
                || file.name().matches(".*[\\\\/\\p{Cntrl}].*")) throw new IllegalArgumentException("Invalid P2P metadata file");
        if (file.name().endsWith(".txt")) {
            if (file.bytes().length > 16384 || !new String(file.bytes(), StandardCharsets.UTF_8).contains("magnet:?xt=urn:btih:")) {
                throw new IllegalArgumentException("Magnet text required");
            }
        } else if (!file.name().endsWith(".torrent") || file.bytes()[0] != 'd' || file.bytes()[file.bytes().length - 1] != 'e') {
            throw new IllegalArgumentException("Only magnet text and torrent metadata may be uploaded");
        }
    }
    private static void requireProvider(String provider) {
        if (!"QUARK".equals(provider) && !"XUNLEI".equals(provider)) throw new IllegalArgumentException("Unsupported P2P archive provider");
    }
    private static String required(JsonNode node, String key) {
        String text = node.path(key).asText();
        if (text.isBlank()) throw new IllegalStateException("P2P upload response missing " + key);
        return text;
    }
    private static String httpDate() { return DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).format(ZonedDateTime.now(ZoneOffset.UTC)); }
}
