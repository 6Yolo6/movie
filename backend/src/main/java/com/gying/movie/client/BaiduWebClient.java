package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded Baidu web-session adapter. No redirects are delegated to the HTTP library. */
@Component
public class BaiduWebClient {
    private static final String ORIGIN = "https://pan.baidu.com";
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final int MAX_ITEMS = 2000;
    private static final int MAX_DEPTH = 12;
    private static final Set<String> VIDEOS = Set.of("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg", "rmvb", "rm", "3gp", "vob");
    private final ResourceHubProperties properties;
    private final BaiduSessionVault vault;
    private final ObjectMapper mapper;
    private final Transport transport;

    @Autowired
    public BaiduWebClient(ResourceHubProperties properties, BaiduSessionVault vault, ObjectMapper mapper) {
        this(properties, vault, mapper, new JdkTransport());
    }
    public BaiduWebClient(ResourceHubProperties properties, BaiduSessionVault vault, ObjectMapper mapper, Transport transport) {
        this.properties = properties; this.vault = vault; this.mapper = mapper; this.transport = transport;
    }
    public record AccountInfo(String uid, String displayName) { }
    public record ExpectedFile(String relativePath, long size, boolean directory) { }
    public record TransferPlan(String sourceUrl, String code, String destination, String shareId, String fromUid,
            List<String> sourceRootFsids, List<ExpectedFile> expectedFiles) { }
    public record Submission(String remoteTaskId, String status) { }
    public record WebResponse(int status, Map<String, List<String>> headers, byte[] body) {
        @Override public String toString() { return "BaiduWebResponse[redacted]"; }
    }
    @FunctionalInterface public interface Transport { WebResponse exchange(HttpRequest request) throws IOException, InterruptedException; }
    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public WebResponse exchange(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                byte[] body = input.readNBytes(MAX_RESPONSE + 1);
                if (body.length > MAX_RESPONSE) throw new IOException("Baidu response exceeds bound");
                return new WebResponse(response.statusCode(), response.headers().map(), body);
            }
        }
    }
    private record Auth(String uid, String bdstoken, List<BaiduSessionVault.Cookie> cookies) {
        @Override public String toString() { return "BaiduAuth[redacted]"; }
    }
    private record ShareContext(String sourceUrl, String shareId, String fromUid, String sekey, List<BaiduSessionVault.Cookie> cookies) {
        @Override public String toString() { return "BaiduShareContext[redacted]"; }
    }
    private record RemoteFile(String fsid, String name, String path, long size, boolean directory, String md5) { }

    public boolean isConfigured() {
        try { return vault.metadata().configured(); }
        catch (IllegalStateException ignored) { return false; }
    }
    public AccountInfo validateSession(List<BaiduSessionVault.Cookie> cookies) {
        List<BaiduSessionVault.Cookie> accepted = BaiduSessionVault.validateCookies(cookies);
        Auth auth = authenticate(accepted);
        JsonNode info = json("GET", "/api/user/getinfo", Map.of("need_selfinfo", "1"), null, accepted, null, false);
        String uid = numeric(first(info.path("records").path(0).path("uk"), info.path("user_info").path("uk"), info.path("uk")), "ACCOUNT_INVALID");
        if (!auth.uid().equals(uid)) throw action("ACCOUNT_CHANGED", false, true, false);
        String displayName = firstText(info.path("records").path(0).path("uname"), info.path("records").path(0).path("username"), info.path("user_info").path("uname"), info.path("uname"));
        if (displayName == null) displayName = "Baidu account";
        if (displayName.length() > 200 || hasControl(displayName)) throw action("ACCOUNT_INVALID", false, true, false);
        return new AccountInfo(uid, displayName);
    }

    /** Read only. The runner must persist this plan and a submission intent before transfer(). */
    public TransferPlan planTransfer(String sourceUrl, String code, String destination) {
        return planTransfer(sourceUrl, code, destination, true, false);
    }
    public TransferPlan planTransfer(String sourceUrl, String code, String destination, boolean videoEnabled, boolean torrentEnabled) {
        String normalized = normalizedShareUrl(sourceUrl, code);
        String actualCode = extractShareCode(normalized);
        String target = safeDirectory(destination);
        Auth auth = currentAuth();
        ShareContext share = openShare(normalized, actualCode, auth);
        List<RemoteFile> roots = listShared(share, null, auth);
        List<ExpectedFile> expected = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int inspected = 0;
        for (RemoteFile root : roots) {
            List<ExpectedFile> subtree = new ArrayList<>();
            walkShared(share, auth, root, root.name(), subtree, new LinkedHashSet<>(), 0);
            inspected += subtree.size();
            if (inspected > MAX_ITEMS) throw action("TREE_LIMIT", false, true, false);
            // Transfer preserves an entire root's hierarchy. A mixed disabled subtree is skipped.
            if (subtree.stream().anyMatch(f -> !f.directory()) && subtree.stream().filter(f -> !f.directory()).allMatch(f -> allowedFile(f, videoEnabled, torrentEnabled))) {
                if (!ids.add(root.fsid())) throw action("SOURCE_INVALID", false, true, false);
                expected.addAll(subtree);
                if (expected.size() > MAX_ITEMS) throw action("TREE_LIMIT", false, true, false);
            }
        }
        if (roots.isEmpty()) throw action("SOURCE_EMPTY", false, true, false);
        if (expected.isEmpty()) throw action("SOURCE_NO_ALLOWED_FILES", false, true, false);
        return new TransferPlan(normalized, actualCode, target, share.shareId(), share.fromUid(), List.copyOf(ids), sorted(expected));
    }

    /** Never repeat after an ambiguous response: verifyDestination() is the recovery path. */
    public Submission transfer(TransferPlan plan) {
        validatePlan(plan);
        Auth auth = currentAuth();
        TransferPlan fresh = planTransfer(plan.sourceUrl(), plan.code(), plan.destination(), hasVideoFiles(plan), hasTorrentFiles(plan));
        if (!sameSource(plan, fresh)) throw action("SOURCE_CHANGED", false, true, false);
        if (verifyDestination(plan)) return new Submission(null, "REUSED");
        try { ensureDirectory(plan.destination(), auth); }
        catch (BaiduActionException error) {
            // Directory creation uses rtype=2 and is idempotent. No transfer POST has been sent yet.
            throw action(error.code(), error.retryable(), error.actionRequired(), false);
        }
        List<RemoteFile> existing = listOwned(plan.destination(), auth, true);
        if (!existing.isEmpty()) throw action("DESTINATION_CONFLICT", false, true, false);
        ShareContext context = openShare(plan.sourceUrl(), plan.code(), auth);
        Map<String, String> query = new LinkedHashMap<>(Map.of("shareid", plan.shareId(), "from", plan.fromUid(), "bdstoken", auth.bdstoken(), "ondup", "fail", "async", "1"));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("fsidlist", encodeJson(plan.sourceRootFsids().stream().map(BigInteger::new).toList()));
        form.put("path", plan.destination());
        if (context.sekey() != null) form.put("sekey", context.sekey());
        JsonNode response = json("POST", "/share/transfer", query, form, context.cookies(), plan.sourceUrl(), true);
        String taskId = firstText(response.path("taskid"), response.path("task_id"), response.path("data").path("taskid"), response.path("data").path("task_id"));
        if (taskId != null && !taskId.matches("[A-Za-z0-9_-]{1,100}")) throw action("TRANSFER_RESPONSE_INVALID", false, false, true);
        return new Submission(taskId, taskId == null ? "ACCEPTED" : "PENDING");
    }

    /** Exact tree verification: partial, extra or renamed content never counts as success. */
    public boolean verifyDestination(TransferPlan plan) {
        validatePlan(plan);
        Auth auth = currentAuth();
        List<RemoteFile> roots;
        try { roots = listOwned(plan.destination(), auth, true); }
        catch (BaiduActionException error) { if ("PATH_NOT_FOUND".equals(error.code())) return false; throw error; }
        List<ExpectedFile> actual = new ArrayList<>();
        for (RemoteFile root : roots) walkOwned(auth, root, root.name(), actual, new LinkedHashSet<>(), 0);
        return sorted(actual).equals(sorted(plan.expectedFiles()));
    }

    /** Null only after a complete bounded own-record search proves no active matching share. */
    public String findOwnShare(String path) {
        String destination = safeDirectory(path);
        Auth auth = currentAuth();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode response = json("GET", "/share/record", Map.of("page", Integer.toString(page), "desc", "1", "order", "time"), null, auth.cookies(), null, false);
            JsonNode list = response.path("list");
            if (list.isObject() && list.isEmpty()) return null;
            if (!list.isArray()) throw action("SHARE_RECORD_INVALID", true, false, false);
            for (JsonNode record : list) {
                if (!destination.equals(record.path("typicalPath").asText(null))) continue;
                JsonNode fsids = record.path("fsIds");
                if (!fsids.isArray() || fsids.size() != 1) continue;
                if (!record.path("status").isIntegralNumber()) throw action("SHARE_RECORD_INVALID", true, false, false);
                if (record.path("status").asInt() != 0) continue;
                long expiry = record.path("expiredTime").asLong(0);
                if (expiry > 0 && expiry <= Instant.now().getEpochSecond()) continue;
                String owner = firstText(record.path("uk"), record.path("uid"));
                if (owner != null && !auth.uid().equals(owner)) throw action("SHARE_OWNER_MISMATCH", false, true, false);
                RemoteFile directory = findOwnedEntry(destination, auth);
                if (directory == null || !directory.directory() || !directory.fsid().equals(fsids.path(0).asText())) continue;
                String shareId = numeric(first(record.path("shareId"), record.path("shareid")), "SHARE_RECORD_INVALID");
                JsonNode details = json("GET", "/share/surlinfoinrecord", Map.of("shareid", shareId, "sign", shareInfoSign(shareId)), null, auth.cookies(), null, false);
                String url = firstText(details.path("shorturl"), record.path("shortlink"));
                String pwd = firstText(details.path("pwd"));
                if ("0".equals(pwd)) pwd = null;
                String normalized = normalizedShareUrl(url, pwd);
                if (validateOwnShare(normalized, shareId)) return normalized;
                // Definitively expired/inaccessible own records do not prevent a replacement share.
            }
            if (list.size() < PAGE_SIZE) return null;
        }
        throw action("SHARE_RECORD_SCAN_INCOMPLETE", true, false, false);
    }

    public String createOwnShare(String path) {
        String destination = safeDirectory(path);
        String existing = findOwnShare(destination);
        if (existing != null) return existing;
        Auth auth = currentAuth();
        RemoteFile directory = findOwnedEntry(destination, auth);
        if (directory == null || !directory.directory()) throw action("PATH_NOT_FOUND", false, true, false);
        String code = randomCode();
        JsonNode response = json("POST", "/share/pset", Map.of("bdstoken", auth.bdstoken()), Map.of(
                "path_list", encodeJson(List.of(destination)), "schannel", "4", "channel_list", "[]", "period", "0", "pwd", code, "share_type", "9"), auth.cookies(), ORIGIN + "/disk/main", true);
        String url = firstText(response.path("link"), response.path("shorturl"), response.path("data").path("link"));
        if (url == null) throw action("SHARE_RESPONSE_INVALID", false, false, true);
        String normalized;
        try { normalized = normalizedShareUrl(url, code); }
        catch (IllegalArgumentException ignored) { throw action("SHARE_RESPONSE_INVALID", false, false, true); }
        try {
            if (!validateOwnShare(normalized)) throw action("OWN_SHARE_NOT_READY", true, false, true);
        } catch (BaiduActionException error) {
            throw action(error.code(), error.retryable(), error.actionRequired(), true);
        }
        return normalized;
    }

    public boolean validateOwnShare(String url) { return validateOwnShare(url, null); }
    private boolean validateOwnShare(String url, String expectedShareId) {
        String normalized = normalizedShareUrl(url, null);
        Auth auth = currentAuth();
        try {
            ShareContext share = openShare(normalized, extractShareCode(normalized), auth);
            if (!auth.uid().equals(share.fromUid())) return false;
            if (expectedShareId != null && !expectedShareId.equals(share.shareId())) throw action("SHARE_RECORD_INVALID", false, true, false);
            return !listShared(share, null, auth).isEmpty();
        } catch (BaiduActionException error) {
            if (Set.of("SHARE_EXPIRED", "EXTRACTION_CODE_INVALID", "SOURCE_EMPTY").contains(error.code())) return false;
            throw error;
        }
    }

    /** Read-only guard before metadata directory sharing or reconciliation. */
    public void verifyMetadataOnlyFolder(String folder) {
        safeDirectory(folder);
        synchronized (vault) {
            Auth auth = currentAuth();
            List<ExpectedFile> files = new ArrayList<>();
            for (RemoteFile root : listOwned(folder, auth, false)) walkOwned(auth, root, root.name(), files, new LinkedHashSet<>(), 0);
            if (files.isEmpty()) throw action("METADATA_FOLDER_INVALID", false, true, false);
            for (ExpectedFile file : files) {
                if (file.directory()) {
                    if (files.stream().noneMatch(child -> !child.directory() && child.relativePath().startsWith(file.relativePath() + "/"))) throw action("METADATA_FOLDER_INVALID", false, true, false);
                } else {
                    String lower = file.relativePath().toLowerCase(Locale.ROOT);
                    boolean allowed = file.size() > 0 && (lower.endsWith(".torrent") && file.size() <= 2L * 1024 * 1024 || lower.endsWith(".txt") && file.size() <= 16L * 1024);
                    if (!allowed) throw action("METADATA_FOLDER_INVALID", false, true, false);
                }
            }
        }
    }

    /** Archives metadata only; this adapter never invokes an offline-download endpoint. */
    public String p2pFolder(String preferred, String fallback) {
        String base = safeDirectory(preferred == null || preferred.isBlank() ? fallback : preferred);
        String folder = base.endsWith("/磁力种子") ? base : base + "/磁力种子";
        synchronized (vault) { ensureDirectory(safeDirectory(folder), currentAuth()); }
        return folder;
    }
    public String p2pUpload(String folder, String name, byte[] bytes) {
        safeDirectory(folder); safeName(name);
        if (bytes == null || bytes.length == 0 || !metadataFile(name, bytes)) throw action("METADATA_INVALID", false, true, false);
        synchronized (vault) {
            Auth auth = currentAuth();
            String path = folder + "/" + name;
            String digest = md5(bytes);
            RemoteFile before = findOwnedEntry(path, auth);
            if (before != null) return requireMetadata(before, path, bytes.length, digest);
            ensureDirectory(folder, auth);
            var crc = new java.util.zip.CRC32(); crc.update(bytes);
            String slice = md5(java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 256 * 1024)));
            JsonNode pre = json("POST", "/api/precreate", Map.of("bdstoken", auth.bdstoken()), Map.of(
                    "path", path, "size", Integer.toString(bytes.length), "isdir", "0", "block_list", encodeJson(List.of(digest)),
                    "autoinit", "1", "content-md5", digest, "slice-md5", slice, "contentCrc32", Long.toString(crc.getValue()), "rtype", "2"), auth.cookies(), ORIGIN + "/disk/main", true);
            // Precreate may complete a known block immediately. Always validate the actual entry.
            if (pre.path("return_type").asInt() == 2) {
                RemoteFile rapid = findOwnedEntry(path, auth);
                if (rapid == null) throw action("UPLOAD_UNCONFIRMED", true, false, true);
                return requireMetadata(rapid, path, bytes.length, digest);
            }
            String uploadId = firstText(pre.path("uploadid"));
            if (uploadId == null || !uploadId.matches("[A-Za-z0-9_-]{1,200}")) throw action("UPLOAD_RESPONSE_INVALID", false, false, true);
            JsonNode blocks = pre.path("block_list");
            if (!blocks.isArray() || blocks.size() > 1 || blocks.size() == 1 && (!blocks.path(0).isIntegralNumber() || blocks.path(0).asInt() != 0)) throw action("UPLOAD_RESPONSE_INVALID", false, false, true);
            if (blocks.size() == 1) uploadBlock(auth, uploadId, path, name, bytes, digest);
            // rtype=2 skips/rejects an existing path; it never overwrites or renames it.
            json("POST", "/api/create", Map.of("a", "commit", "bdstoken", auth.bdstoken()), Map.of(
                    "uploadid", uploadId, "path", path, "size", Integer.toString(bytes.length), "isdir", "0", "rtype", "2",
                    "block_list", encodeJson(List.of(digest)), "target_path", folder), auth.cookies(), ORIGIN + "/disk/main", true);
            try {
                RemoteFile after = findOwnedEntry(path, auth);
                if (after == null) throw action("UPLOAD_UNCONFIRMED", true, false, true);
                return requireMetadata(after, path, bytes.length, digest);
            } catch (BaiduActionException error) { throw action(error.code(), error.retryable(), error.actionRequired(), true); }
        }
    }
    private void uploadBlock(Auth auth, String uploadId, String path, String name, byte[] bytes, String digest) {
        // The source protocol uses this fixed PCS endpoint; redirects and returned upload hosts are never followed.
        URI endpoint = URI.create("https://pcs.baidu.com/rest/2.0/pcs/superfile2?" + form(Map.of(
                "method", "upload", "app_id", "250528", "type", "tmpfile", "path", path, "partseq", "0", "partoffset", "0", "uploadid", uploadId)));
        String boundary = "gying" + java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"metadata.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[prefix.length + bytes.length + suffix.length];
        System.arraycopy(prefix, 0, payload, 0, prefix.length); System.arraycopy(bytes, 0, payload, prefix.length, bytes.length); System.arraycopy(suffix, 0, payload, prefix.length + bytes.length, suffix.length);
        String cookies = cookieHeader(auth.cookies(), endpoint);
        if (auth.cookies().stream().noneMatch(c -> Set.of("BDUSS", "BDUSS_BFESS").contains(c.name()) && BaiduSessionVault.domainMatches(c.domain(), endpoint.getHost()) && pathMatches(c.path(), endpoint.getPath()) && (c.expires() < 0 || c.expires() > Instant.now().getEpochSecond()))) throw action("UPLOAD_SESSION_SCOPE_UNAVAILABLE", false, true, false);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0").header("Referer", ORIGIN + "/disk/main")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        try {
            WebResponse response = transport.exchange(request);
            if (response == null || response.body() == null || response.body().length > MAX_RESPONSE) throw action("UPLOAD_RESPONSE_INVALID", false, false, true);
            requireHttp(response, true);
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject()) throw action("UPLOAD_RESPONSE_INVALID", false, false, true);
            if (json.has("error_code")) throw apiError(json.path("error_code").asInt(-1), true);
            if (json.has("errno") && json.path("errno").asInt(-1) != 0) throw apiError(json.path("errno").asInt(-1), true);
            if (!digest.equalsIgnoreCase(json.path("md5").asText())) throw action("UPLOAD_DIGEST_MISMATCH", false, true, true);
        } catch (BaiduActionException error) { throw error; }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw action("INTERRUPTED", false, false, true); }
        catch (Exception ignored) { throw action("NETWORK_UNAVAILABLE", true, false, true); }
    }
    private static String requireMetadata(RemoteFile file, String path, long size, String digest) {
        if (file.directory() || !path.equals(file.path()) || file.size() != size || file.md5() == null || !digest.equalsIgnoreCase(file.md5())) throw action("DESTINATION_CONFLICT", false, true, false);
        return file.fsid();
    }
    private static boolean metadataFile(String name, byte[] bytes) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".torrent")) return bytes.length <= 2 * 1024 * 1024 && bytes[0] == 'd' && bytes[bytes.length - 1] == 'e';
        if (!lower.endsWith(".txt") || bytes.length > 16 * 1024) return false;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().trim();
            List<String> links = text.lines().map(String::trim).filter(line -> line.startsWith("magnet:?")).toList();
            if (links.size() != 1) return false;
            URI magnet = URI.create(links.get(0)); String raw = magnet.getRawSchemeSpecificPart();
            String xt = null;
            for (String pair : (raw.startsWith("?") ? raw.substring(1) : raw).split("&")) {
                String[] parts = pair.split("=", 2);
                if ("xt".equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                    if (xt != null || parts.length != 2) return false;
                    xt = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
            return "magnet".equals(magnet.getScheme()) && xt != null && xt.matches("(?i)urn:btih:([a-f0-9]{40}|[a-z2-7]{32})") && text.chars().noneMatch(c -> c < 32 && c != 10 && c != 13 && c != 9 || c == 127);
        } catch (Exception ignored) { return false; }
    }
    private static String md5(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)); } catch (Exception ignored) { throw action("DIGEST_UNAVAILABLE", false, true, false); } }

    public static String normalizeShareUrl(String url, String code) { return normalizedShareUrl(url, code); }
    public static boolean hasVideoFiles(TransferPlan plan) { return plan != null && plan.expectedFiles() != null && plan.expectedFiles().stream().anyMatch(f -> f != null && !f.directory() && isVideo(f.relativePath())); }
    public static boolean hasTorrentFiles(TransferPlan plan) { return plan != null && plan.expectedFiles() != null && plan.expectedFiles().stream().anyMatch(f -> f != null && !f.directory() && torrentFile(f)); }
    public static boolean matchesFilePolicy(TransferPlan plan, boolean videoEnabled, boolean torrentEnabled) {
        try {
            validatePlan(plan);
            return plan.expectedFiles().stream().anyMatch(f -> !f.directory()) && plan.expectedFiles().stream().filter(f -> !f.directory()).allMatch(f -> allowedFile(f, videoEnabled, torrentEnabled));
        } catch (RuntimeException ignored) { return false; }
    }
    private static boolean allowedFile(ExpectedFile f, boolean videoEnabled, boolean torrentEnabled) { return videoEnabled && isVideo(f.relativePath()) || torrentEnabled && torrentFile(f); }
    private static boolean torrentFile(ExpectedFile f) { return f.relativePath() != null && f.relativePath().toLowerCase(Locale.ROOT).endsWith(".torrent") && f.size() > 0 && f.size() <= 2L * 1024 * 1024; }

    public static String normalizedShareUrl(String url, String code) {
        if (url == null || url.length() > 1000 || hasControl(url)) throw new IllegalArgumentException("Invalid Baidu share URL");
        try {
            URI uri = URI.create(url.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                    || !Set.of("pan.baidu.com", "yun.baidu.com").contains(uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            Map<String, String> query = parseQuery(uri.getRawQuery());
            String id;
            if (uri.getPath().matches("/s/1[A-Za-z0-9_-]{1,80}/?")) id = uri.getPath().substring(3).replaceAll("/$", "");
            else if ("/share/init".equals(uri.getPath()) && query.getOrDefault("surl", "").matches("[A-Za-z0-9_-]{1,80}")) id = "1" + query.get("surl");
            else throw new IllegalArgumentException();
            String pwd = code == null || code.isBlank() ? query.get("pwd") : code.trim();
            if (pwd != null && !pwd.isBlank() && !pwd.matches("[A-Za-z0-9]{4}")) throw new IllegalArgumentException();
            return ORIGIN + "/s/" + id + (pwd == null || pwd.isBlank() ? "" : "?pwd=" + pwd);
        } catch (RuntimeException ignored) { throw new IllegalArgumentException("Invalid Baidu share URL or extraction code"); }
    }
    public static String extractShareCode(String url) {
        if (url == null) return null;
        try { return parseQuery(URI.create(url).getRawQuery()).get("pwd"); }
        catch (RuntimeException ignored) { return null; }
    }
    private Auth currentAuth() {
        BaiduSessionVault.Session session;
        try { session = vault.read(); }
        catch (IllegalStateException ignored) { throw action("SESSION_STORAGE_INVALID", false, true, false); }
        if (session == null) throw action("LOGIN_REQUIRED", false, true, false);
        Auth auth = authenticate(session.cookies());
        if (!session.uid().equals(auth.uid())) throw action("ACCOUNT_CHANGED", false, true, false);
        return auth;
    }
    private Auth authenticate(List<BaiduSessionVault.Cookie> cookies) {
        JsonNode info = json("GET", "/api/user/getinfo", Map.of("need_selfinfo", "1"), null, cookies, null, false);
        String uid = numeric(first(info.path("records").path(0).path("uk"), info.path("user_info").path("uk"), info.path("uk")), "LOGIN_REQUIRED");
        JsonNode token = json("GET", "/api/gettemplatevariable", Map.of("fields", "[\"bdstoken\"]"), null, cookies, null, false);
        String bdstoken = firstText(token.path("result").path("bdstoken"), token.path("bdstoken"));
        if (bdstoken == null || !bdstoken.matches("[A-Za-z0-9_-]{8,200}")) throw action("LOGIN_REQUIRED", false, true, false);
        return new Auth(uid, bdstoken, cookies);
    }
    private ShareContext openShare(String url, String code, Auth auth) {
        URI canonical = URI.create(normalizedShareUrl(url, code));
        List<BaiduSessionVault.Cookie> cookies = new ArrayList<>(auth.cookies());
        String html = page(canonical, cookies);
        String shareId = htmlNumber(html, "shareid");
        String owner = htmlNumber(html, "share_uk");
        if (owner == null) owner = htmlNumber(html, "uk");
        if ((shareId == null || owner == null) && code == null) throw action("SHARE_EXPIRED", false, true, false);
        String shorturl = canonical.getPath().substring(4);
        String sekey = null;
        if (code != null) {
            Map<String, String> query = new LinkedHashMap<>(Map.of("surl", shorturl, "clienttype", "1", "web", "1", "time", Long.toString(System.currentTimeMillis())));
            if (shareId != null) query.put("shareid", shareId);
            if (owner != null) query.put("uk", owner);
            WebResponse response = exchange("POST", uri("/share/verify", query), Map.of("pwd", code, "vcode", "", "vcode_str", "", "bdstoken", auth.bdstoken()), cookies, canonical.toString(), false);
            JsonNode verified = parseJson(response, false);
            sekey = firstText(verified.path("randsk"));
            if (sekey != null) {
                if (sekey.length() > 4096 || hasControl(sekey)) throw action("SOURCE_INVALID", false, true, false);
                cookies.removeIf(c -> "BDCLND".equals(c.name()));
                cookies.add(new BaiduSessionVault.Cookie("BDCLND", sekey, ".baidu.com", "/", -1, true));
                try { sekey = URLDecoder.decode(sekey, StandardCharsets.UTF_8); } catch (IllegalArgumentException ignored) { throw action("SOURCE_INVALID", false, true, false); }
            }
            addResponseCookies(cookies, response);
            html = page(canonical, cookies);
            shareId = htmlNumber(html, "shareid"); owner = htmlNumber(html, "share_uk");
            if (owner == null) owner = htmlNumber(html, "uk");
        }
        if (shareId == null || owner == null) throw action("SHARE_EXPIRED", false, true, false);
        return new ShareContext(canonical.toString(), shareId, owner, sekey, List.copyOf(cookies));
    }
    private List<RemoteFile> listShared(ShareContext share, String directory, Auth auth) {
        Map<String, String> query = new LinkedHashMap<>(Map.of("shareid", share.shareId(), "uk", share.fromUid(), "bdstoken", auth.bdstoken(), "web", "5", "num", Integer.toString(PAGE_SIZE), "app_id", "250528", "channel", "chunlei"));
        query.put("shorturl", URI.create(share.sourceUrl()).getPath().substring(4));
        query.put("root", directory == null ? "1" : "0");
        if (directory != null) query.put("dir", directory);
        return paged("/share/list", query, share.cookies(), share.sourceUrl(), "SOURCE_INVALID");
    }
    private List<RemoteFile> listOwned(String directory, Auth auth, boolean missingAllowed) {
        return paged("/api/list", new LinkedHashMap<>(Map.of("dir", directory, "num", Integer.toString(PAGE_SIZE), "order", "name", "desc", "0", "bdstoken", auth.bdstoken(), "web", "1")), auth.cookies(), null, "DESTINATION_INVALID");
    }
    private List<RemoteFile> paged(String endpoint, Map<String, String> query, List<BaiduSessionVault.Cookie> cookies, String referer, String code) {
        List<RemoteFile> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            query.put("page", Integer.toString(page));
            JsonNode response = json("GET", endpoint, query, null, cookies, referer, false);
            JsonNode list = response.path("list");
            if (!list.isArray()) throw action(code, false, true, false);
            for (JsonNode item : list) {
                String id = numeric(item.path("fs_id"), code);
                if (!ids.add(id)) throw action("PAGINATION_INCOMPLETE", true, false, false);
                String name = item.path("server_filename").asText(null);
                safeName(name);
                String path = item.path("path").asText(null);
                if (path == null || !path.startsWith("/") || hasControl(path) || path.length() > 2000) throw action(code, false, true, false);
                if ("/api/list".equals(endpoint) && !path.equals(("/".equals(query.get("dir")) ? "" : query.get("dir")) + "/" + name)) throw action(code, false, true, false);
                if (!item.path("isdir").isIntegralNumber() || !Set.of(0, 1).contains(item.path("isdir").asInt())) throw action(code, false, true, false);
                boolean directory = item.path("isdir").asInt() == 1;
                if (!directory && (!item.path("size").isIntegralNumber() || !item.path("size").canConvertToLong() || item.path("size").asLong() < 0)) throw action(code, false, true, false);
                result.add(new RemoteFile(id, name, path, directory ? 0 : item.path("size").asLong(), directory, firstText(item.path("md5"))));
                if (result.size() > MAX_ITEMS) throw action("TREE_LIMIT", false, true, false);
            }
            if (list.size() < PAGE_SIZE) return List.copyOf(result);
        }
        throw action("PAGINATION_INCOMPLETE", true, false, false);
    }
    private void walkShared(ShareContext share, Auth auth, RemoteFile file, String relative, List<ExpectedFile> files, Set<String> ancestry, int depth) {
        addExpected(file, relative, files, ancestry, depth);
        if (file.directory()) for (RemoteFile child : listShared(share, file.path(), auth)) {
            if (!child.path().equals(file.path().replaceAll("/$", "") + "/" + child.name())) throw action("SOURCE_INVALID", false, true, false);
            walkShared(share, auth, child, relative + "/" + child.name(), files, new LinkedHashSet<>(ancestry), depth + 1);
        }
    }
    private void walkOwned(Auth auth, RemoteFile file, String relative, List<ExpectedFile> files, Set<String> ancestry, int depth) {
        addExpected(file, relative, files, ancestry, depth);
        if (file.directory()) for (RemoteFile child : listOwned(file.path(), auth, false)) {
            if (!child.path().equals(file.path().replaceAll("/$", "") + "/" + child.name())) throw action("DESTINATION_INVALID", false, true, false);
            walkOwned(auth, child, relative + "/" + child.name(), files, new LinkedHashSet<>(ancestry), depth + 1);
        }
    }
    private void addExpected(RemoteFile file, String relative, List<ExpectedFile> files, Set<String> ancestry, int depth) {
        if (depth > MAX_DEPTH || files.size() >= MAX_ITEMS || !ancestry.add(file.fsid())) throw action("TREE_LIMIT", false, true, false);
        if (files.stream().anyMatch(f -> f.relativePath().equals(relative))) throw action("TREE_AMBIGUOUS", false, true, false);
        files.add(new ExpectedFile(relative, file.size(), file.directory()));
    }
    private void ensureDirectory(String target, Auth auth) {
        String current = "";
        for (String segment : target.substring(1).split("/")) {
            current += "/" + segment;
            RemoteFile entry = findOwnedEntry(current, auth);
            if (entry != null) { if (!entry.directory()) throw action("DESTINATION_CONFLICT", false, true, false); continue; }
            json("POST", "/api/create", Map.of("a", "commit", "bdstoken", auth.bdstoken()), Map.of("path", current, "isdir", "1", "block_list", "[]", "rtype", "2"), auth.cookies(), ORIGIN + "/disk/main", true);
            RemoteFile created = findOwnedEntry(current, auth);
            if (created == null || !created.directory()) throw action("DIRECTORY_NOT_READY", true, false, true);
        }
    }
    private RemoteFile findOwnedEntry(String path, Auth auth) {
        int index = path.lastIndexOf('/');
        String parent = index == 0 ? "/" : path.substring(0, index);
        String name = path.substring(index + 1);
        try {
            return listOwned(parent, auth, true).stream().filter(file -> file.name().equals(name) && file.path().equals(path)).findFirst().orElse(null);
        } catch (BaiduActionException error) { if ("PATH_NOT_FOUND".equals(error.code())) return null; throw error; }
    }

    private String page(URI start, List<BaiduSessionVault.Cookie> cookies) {
        URI current = start;
        for (int redirects = 0; redirects < 3; redirects++) {
            WebResponse response = exchange("GET", current, null, cookies, ORIGIN + "/", false);
            if (response.status() >= 300 && response.status() < 400) {
                String location = header(response, "location");
                if (location == null) throw action("SHARE_REDIRECT_INVALID", false, true, false);
                current = current.resolve(location);
                requireFixedOrigin(current);
                continue;
            }
            requireHttp(response, false);
            return new String(response.body(), StandardCharsets.UTF_8);
        }
        throw action("SHARE_REDIRECT_LIMIT", false, true, false);
    }
    private JsonNode json(String method, String path, Map<String, String> query, Map<String, String> form, List<BaiduSessionVault.Cookie> cookies, String referer, boolean write) {
        return parseJson(exchange(method, uri(path, query), form, cookies, referer, write), write);
    }
    private JsonNode parseJson(WebResponse response, boolean write) {
        requireHttp(response, write);
        try {
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject() || (!json.path("errno").isIntegralNumber() || !json.path("errno").canConvertToInt())) throw action("RESPONSE_INVALID", !write, false, write);
            int errno = json.path("errno").asInt();
            if (errno != 0) throw apiError(errno, write);
            return json;
        } catch (BaiduActionException error) { throw error; }
        catch (Exception ignored) { throw action("RESPONSE_INVALID", !write, false, write); }
    }
    private WebResponse exchange(String method, URI uri, Map<String, String> form, List<BaiduSessionVault.Cookie> cookies, String referer, boolean write) {
        requireFixedOrigin(uri);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
                .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8");
        String cookieHeader = cookieHeader(cookies, uri);
        if (!cookieHeader.isEmpty()) request.header("Cookie", cookieHeader);
        if (referer != null) { URI ref = URI.create(referer); requireFixedOrigin(ref); request.header("Referer", ref.toString()); }
        if ("POST".equals(method)) request.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(form), StandardCharsets.UTF_8));
        else request.GET();
        try {
            WebResponse response = transport.exchange(request.build());
            if (response == null || response.body() == null || response.body().length > MAX_RESPONSE) throw action("RESPONSE_INVALID", !write, false, write);
            return response;
        } catch (BaiduActionException error) { throw error; }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw action("INTERRUPTED", !write, false, write); }
        catch (Exception ignored) { throw action("NETWORK_UNAVAILABLE", true, false, write); }
    }
    private static void requireHttp(WebResponse response, boolean write) {
        int status = response.status();
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403 || status >= 300 && status < 400) throw action("LOGIN_REQUIRED", false, true, write);
        if (status == 429) throw action("RATE_LIMITED", true, false, false);
        throw action("HTTP_REJECTED", status >= 500, false, write && status >= 500);
    }
    private static BaiduActionException apiError(int errno, boolean write) {
        if (Set.of(-6, -7, 31045, 31046).contains(errno)) return action("LOGIN_REQUIRED", false, true, false);
        if (Set.of(-12, -20, -21, 112, 115).contains(errno)) return action("CHALLENGE_REQUIRED", false, true, false);
        if (errno == -9 || errno == 31066) return action("PATH_NOT_FOUND", false, false, false);
        if (errno == -62 || errno == -63) return action("EXTRACTION_CODE_INVALID", false, true, false);
        if (Set.of(2, -8, -10).contains(errno)) return action("SHARE_EXPIRED", false, true, false);
        if (errno == 12 || errno == 111) return action("DESTINATION_CONFLICT", false, true, false);
        if (errno == -33 || errno == 4) return action("CAPACITY_OR_QUOTA", false, true, false);
        return action("API_REJECTED", !write, false, false);
    }
    private static String cookieHeader(List<BaiduSessionVault.Cookie> cookies, URI uri) {
        if (cookies == null) return "";
        List<BaiduSessionVault.Cookie> selected = cookies.stream().filter(cookie -> BaiduSessionVault.domainMatches(cookie.domain(), uri.getHost()))
                .filter(cookie -> cookie.expires() < 0 || cookie.expires() > Instant.now().getEpochSecond())
                .filter(cookie -> pathMatches(cookie.path(), uri.getPath())).sorted(Comparator.comparingInt((BaiduSessionVault.Cookie c) -> c.path().length()).reversed()).toList();
        List<String> parts = new ArrayList<>();
        for (BaiduSessionVault.Cookie cookie : selected) {
            if (cookie.name() == null || cookie.value() == null || hasControl(cookie.value()) || cookie.value().contains(";")) throw action("SESSION_INVALID", false, true, false);
            parts.add(cookie.name() + "=" + cookie.value());
        }
        return String.join("; ", parts);
    }
    private static boolean pathMatches(String cookiePath, String requestPath) {
        return cookiePath.equals(requestPath) || requestPath.startsWith(cookiePath) && (cookiePath.endsWith("/") || requestPath.length() > cookiePath.length() && requestPath.charAt(cookiePath.length()) == '/');
    }
    private static void addResponseCookies(List<BaiduSessionVault.Cookie> cookies, WebResponse response) {
        if (response.headers() == null) return;
        for (var entry : response.headers().entrySet()) if (entry.getKey().equalsIgnoreCase("set-cookie")) for (String value : entry.getValue()) {
            String first = value.split(";", 2)[0]; int eq = first.indexOf('=');
            if (eq < 1 || !"BDCLND".equals(first.substring(0, eq))) continue;
            String cookie = first.substring(eq + 1);
            if (cookie.isEmpty() || cookie.length() > 4096 || hasControl(cookie) || cookie.contains(";")) continue;
            cookies.removeIf(c -> "BDCLND".equals(c.name()));
            cookies.add(new BaiduSessionVault.Cookie("BDCLND", cookie, "pan.baidu.com", "/", -1, true));
        }
    }
    private static String header(WebResponse response, String name) {
        if (response.headers() == null) return null;
        return response.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
    }
    private static URI uri(String path, Map<String, String> query) { return URI.create(ORIGIN + path + (query == null || query.isEmpty() ? "" : "?" + form(query))); }
    private static String form(Map<String, String> values) {
        if (values == null) return "";
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null) return values;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2); String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException();
        }
        return values;
    }
    private static void requireFixedOrigin(URI uri) {
        if (!"https".equals(uri.getScheme()) || !"pan.baidu.com".equals(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) throw action("UNSAFE_UPSTREAM", false, true, false);
    }
    private static String htmlNumber(String html, String key) {
        var matcher = Pattern.compile("[\"']?" + Pattern.quote(key) + "[\"']?\\s*[:=]\\s*[\"']?([0-9]{1,30})[\"']?").matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }
    private static JsonNode first(JsonNode... values) { for (JsonNode value : values) if (!value.isMissingNode() && !value.isNull()) return value; return values[0]; }
    private static String firstText(JsonNode... values) { for (JsonNode value : values) { String s = value.asText(null); if (s != null && !s.isBlank()) return s; } return null; }
    private static String numeric(JsonNode value, String code) { String text = value.asText(null); if (text == null || !text.matches("[0-9]{1,30}")) throw action(code, false, true, false); return text; }
    private String encodeJson(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ignored) { throw action("PLAN_INVALID", false, true, false); } }
    private static List<ExpectedFile> sorted(List<ExpectedFile> files) { return files.stream().sorted(Comparator.comparing(ExpectedFile::relativePath)).toList(); }
    private static boolean sameSource(TransferPlan a, TransferPlan b) { return a.shareId().equals(b.shareId()) && a.fromUid().equals(b.fromUid()) && a.sourceRootFsids().equals(b.sourceRootFsids()) && sorted(a.expectedFiles()).equals(sorted(b.expectedFiles())); }
    private static void validatePlan(TransferPlan plan) {
        if (plan == null || plan.sourceRootFsids() == null || plan.sourceRootFsids().isEmpty() || plan.sourceRootFsids().size() > MAX_ITEMS || plan.expectedFiles() == null || plan.expectedFiles().isEmpty() || plan.expectedFiles().size() > MAX_ITEMS
                || plan.shareId() == null || !plan.shareId().matches("[0-9]{1,30}") || plan.fromUid() == null || !plan.fromUid().matches("[0-9]{1,30}")) throw action("PLAN_INVALID", false, true, false);
        if (!normalizedShareUrl(plan.sourceUrl(), plan.code()).equals(plan.sourceUrl())) throw action("PLAN_INVALID", false, true, false);
        safeDirectory(plan.destination());
        if (plan.sourceRootFsids().stream().anyMatch(id -> id == null || !id.matches("[0-9]{1,30}")) || new LinkedHashSet<>(plan.sourceRootFsids()).size() != plan.sourceRootFsids().size()) throw action("PLAN_INVALID", false, true, false);
        Set<String> paths = new LinkedHashSet<>();
        Map<String, ExpectedFile> byPath = new LinkedHashMap<>();
        for (ExpectedFile file : plan.expectedFiles()) {
            if (file == null || file.relativePath() == null || file.relativePath().startsWith("/") || file.relativePath().length() > 2000 || file.size() < 0 || file.directory() && file.size() != 0 || !paths.add(file.relativePath())) throw action("PLAN_INVALID", false, true, false);
            for (String name : file.relativePath().split("/", -1)) safeName(name);
            byPath.put(file.relativePath(), file);
        }
        long rootCount = 0;
        for (ExpectedFile file : plan.expectedFiles()) {
            int slash = file.relativePath().lastIndexOf('/');
            if (slash < 0) rootCount++;
            else { ExpectedFile parent = byPath.get(file.relativePath().substring(0, slash)); if (parent == null || !parent.directory()) throw action("PLAN_INVALID", false, true, false); }
        }
        if (rootCount != plan.sourceRootFsids().size()) throw action("PLAN_INVALID", false, true, false);
    }
    private static String safeDirectory(String path) {
        if (path == null || !path.startsWith("/") || "/".equals(path) || path.length() > 300 || path.endsWith("/") || hasControl(path) || path.contains("\\")) throw action("PATH_INVALID", false, true, false);
        for (String name : path.substring(1).split("/", -1)) safeName(name);
        return path;
    }
    private static void safeName(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || hasControl(name)) throw action("PATH_INVALID", false, true, false);
    }
    private static boolean hasControl(String value) { return value.chars().anyMatch(c -> c < 32 || c == 127); }
    private static boolean isVideo(String path) { int dot = path.lastIndexOf('.'); return dot > 0 && VIDEOS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT)); }
    private static String randomCode() { String alphabet = "23456789abcdefghjkmnpqrstuvwxyz"; SecureRandom random = new SecureRandom(); StringBuilder code = new StringBuilder(); for (int i = 0; i < 4; i++) code.append(alphabet.charAt(random.nextInt(alphabet.length()))); return code.toString(); }
    private static String shareInfoSign(String id) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest((id + "_sharesurlinfo!@#").getBytes(StandardCharsets.UTF_8))); } catch (Exception ignored) { throw action("SHARE_RECORD_INVALID", false, true, false); } }
    private static BaiduActionException action(String code, boolean retryable, boolean actionRequired, boolean uncertain) { return new BaiduActionException(code, retryable, actionRequired, uncertain); }
}
