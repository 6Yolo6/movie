package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class XunleiClient {
    private static final Pattern SHARE_PATTERN = Pattern.compile("https?://[^/]+/s/([^/?#]+)(?:[/?#].*)?", Pattern.CASE_INSENSITIVE);
    private static final String CAPTCHA_URL = "https://xluser-ssl.xunlei.com/v1/shield/captcha/init";
    private static final String PACKAGE_NAME = "pan.xunlei.com";
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v", "ts", "m2ts",
            "mpg", "mpeg", "mpe", "vob", "3gp", "3g2", "rm", "rmvb", "asf", "ogv");
    private static final String[] WEB_ALGORITHMS = ("b9Dldv6kRsRyOG4tFHzeJ4RbOi0n7nO8omFouLVgvLNB.TEHDOteMPrRB66yQIF9tF+pfPAIesa/xg."
            + "Fmx27GlNbrIxiPSQVm.crlPVriPRAiuCEKZvK4yihP55gTRvLd7qDVLsDtWzhkXt5Iqs7TpoP."
            + "E2toogseEdgXmlfnz1ppUhUvD9B2jgSA+YG.a2f3L0AioU+0PvTeCtk.6d6w1xX9j95GEPNpd+T4HmbTceZNEF310ppRe."
            + "BvsJ+CSS7i.Rv").split("\\.");
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;
    private final Map<String, CaptchaEntry> captchaCache = new ConcurrentHashMap<>();

    public XunleiClient(RestTemplateBuilder builder, ObjectMapper objectMapper, ResourceHubProperties properties) {
        this.restTemplate = builder.setConnectTimeout(Duration.ofSeconds(15)).setReadTimeout(Duration.ofSeconds(30)).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean isConfigured() {
        ResourceHubProperties.Xunlei x = properties.getXunlei();
        return x != null && x.isEnabled() && hasText(x.getAuthorization()) && hasText(x.getBaseUrl());
    }

    public RestoreResult restore(String shareUrl, String savePath) {
        requireConfigured();
        ShareInfo share = inspectShare(shareUrl);
        DirectoryInfo directory = ensureDirectory(savePath);
        long startedAt = System.currentTimeMillis();
        JsonNode response = request(HttpMethod.POST, "/share/restore", restorePayload(share, directory));
        String taskId = firstText(response.path("restore_task_id").asText(null), response.path("task_id").asText(null), response.path("data").path("restore_task_id").asText(null));
        if (!hasText(taskId)) {
            throw new IllegalStateException("Xunlei restore response did not include task id");
        }
        String restoredFileId = firstText(
                response.path("file_id").asText(null),
                response.path("data").path("file_id").asText(null));
        return new RestoreResult(
                taskId,
                response.toString(),
                directory.id(),
                restoredFileId,
                share.fileNames(),
                startedAt);
    }

    static Map<String, Object> restorePayload(ShareInfo share, DirectoryInfo directory) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("share_id", share.shareId());
        body.put("pass_code", share.passCode());
        body.put("pass_code_token", share.passCodeToken());
        body.put("file_ids", share.fileIds());
        body.put("parent_id", directory.id());
        body.put("ancestor_ids", directory.ancestorIds());
        return body;
    }

    public RestoreStatus await(String taskId) {
        ResourceHubProperties.Xunlei x = properties.getXunlei();
        JsonNode latest = null;
        for (int attempt = 0; attempt < Math.max(1, x.getPollAttempts()); attempt++) {
            latest = request(HttpMethod.GET, "/tasks/" + taskId, null);
            String status = firstText(latest.path("phase").asText(null), latest.path("status").asText(null), latest.path("data").path("phase").asText(null));
            if (isSuccess(status)) return new RestoreStatus(true, status, latest.toString());
            if (isFailure(status)) return new RestoreStatus(false, status, latest.toString());
            sleep(x.getPollIntervalMs());
        }
        return new RestoreStatus(false, "TIMEOUT", latest == null ? null : latest.toString());
    }

    public ContentSummary awaitContent(String parentId) {
        ResourceHubProperties.Xunlei x = properties.getXunlei();
        ContentSummary latest = new ContentSummary(0, 0, 0);
        for (int attempt = 0; attempt < Math.max(1, x.getPollAttempts()); attempt++) {
            latest = inspectContent(parentId);
            if (latest.videoCount() > 0) {
                return latest;
            }
            sleep(x.getPollIntervalMs());
        }
        throw new IllegalStateException("Xunlei restored folder contains no video files"
                + " (folders=" + latest.folderCount() + ", files=" + latest.fileCount() + ")");
    }

    public RestoredSelection awaitRestoredFiles(
            String restoreFolderId,
            List<String> expectedNames,
            long restoreStartedAt) {
        if (!hasText(restoreFolderId)) {
            throw new IllegalStateException("Xunlei restore response did not include restore folder id");
        }
        ResourceHubProperties.Xunlei x = properties.getXunlei();
        for (int attempt = 0; attempt < Math.max(1, x.getPollAttempts()); attempt++) {
            JsonNode response = request(HttpMethod.GET,
                    "/files?parent_id=" + restoreFolderId + "&usage=DISPLAY&limit=100", null);
            List<JsonNode> restored = selectRestoredFiles(
                    response.path("files"), expectedNames, restoreStartedAt);
            if (!restored.isEmpty()) {
                int folders = 0;
                int files = 0;
                int videos = 0;
                List<String> ids = new ArrayList<>();
                for (JsonNode item : restored) {
                    String id = item.path("id").asText(null);
                    if (!hasText(id)) continue;
                    ids.add(id);
                    if (isFolder(item)) {
                        folders++;
                        ContentSummary nested = inspectContent(id);
                        folders += nested.folderCount();
                        files += nested.fileCount();
                        videos += nested.videoCount();
                    } else {
                        files++;
                        if (isVideo(item)) videos++;
                    }
                }
                if (!ids.isEmpty() && videos > 0) {
                    return new RestoredSelection(List.copyOf(ids), new ContentSummary(folders, files, videos));
                }
            }
            sleep(x.getPollIntervalMs());
        }
        throw new IllegalStateException(
                "Xunlei restore completed but no new matching video files were found in My Transfers");
    }

    public String createShare(String parentId) {
        return createShare(List.of(parentId));
    }

    public String createShare(List<String> fileIds) {
        ResourceHubProperties.Xunlei x = properties.getXunlei();
        if (!x.isShareEnabled() || !hasText(x.getShareCreatePath())) return null;
        JsonNode response = request(HttpMethod.POST, x.getShareCreatePath(), sharePayload(fileIds));
        return parseShareUrl(response);
    }

    static Map<String, Object> sharePayload(String fileId) {
        return sharePayload(List.of(fileId));
    }

    static Map<String, Object> sharePayload(List<String> fileIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subscribe_push", "false");
        params.put("WithPassCodeInLink", "true");
        params.put("share_file_order", "MODIFY_TIME_DESC");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file_ids", fileIds);
        body.put("share_to", "copy");
        body.put("params", params);
        body.put("title", "云盘资源分享");
        body.put("restore_limit", "-1");
        body.put("expiration_days", "-1");
        return body;
    }

    static String parseShareUrl(JsonNode response) {
        String url = firstTextStatic(
                response.path("share_url").asText(null),
                response.path("url").asText(null),
                response.path("share").path("share_url").asText(null),
                response.path("share").path("url").asText(null),
                response.path("data").path("share_url").asText(null),
                response.path("data").path("url").asText(null),
                response.path("data").path("share").path("share_url").asText(null),
                response.path("data").path("share").path("url").asText(null));
        if (!hasTextStatic(url)) {
            return null;
        }
        String passCode = firstTextStatic(
                response.path("pass_code").asText(null),
                response.path("share").path("pass_code").asText(null),
                response.path("data").path("pass_code").asText(null),
                response.path("data").path("share").path("pass_code").asText(null));
        if (!hasTextStatic(passCode) || url.contains("pwd=")) {
            return url;
        }
        return UriComponentsBuilder.fromUriString(url)
                .queryParam("pwd", passCode)
                .build()
                .toUriString();
    }

    private ShareInfo inspectShare(String shareUrl) {
        Matcher matcher = SHARE_PATTERN.matcher(shareUrl == null ? "" : shareUrl.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid Xunlei share URL");
        String shareId = matcher.group(1);
        String passCode = UriComponentsBuilder.fromUriString(shareUrl).build().getQueryParams().getFirst("pwd");
        ShareSelection selection = inspectShareFiles(shareId, passCode);
        List<String> fileIds = selection.fileIds();
        if (fileIds.isEmpty()) throw new IllegalStateException("Xunlei share contains no video files");
        return new ShareInfo(shareId, passCode, selection.passCodeToken(), fileIds, selection.fileNames());
    }

    private ShareSelection inspectShareFiles(String shareId, String passCode) {
        ArrayDeque<String> folders = new ArrayDeque<>();
        Set<String> visitedFolders = new HashSet<>();
        Set<String> seenFiles = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        List<String> fileIds = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        String passCodeToken = null;

        JsonNode root = requestSharePage(shareId, passCode, null, null, null);
        passCodeToken = sharePassCodeToken(root, passCodeToken);
        collectSharePage(root, folders, fileIds, fileNames, seenFiles, seenNames);
        String rootPageToken = shareNextPageToken(root);
        while (hasText(rootPageToken)) {
            JsonNode page;
            try {
                page = requestSharePage(shareId, passCode, passCodeToken, null, rootPageToken);
            } catch (IllegalStateException error) {
                if (!isRecoverableShareTraversalError(error)) throw error;
                break;
            }
            passCodeToken = sharePassCodeToken(page, passCodeToken);
            collectSharePage(page, folders, fileIds, fileNames, seenFiles, seenNames);
            String next = shareNextPageToken(page);
            if (rootPageToken.equals(next)) break;
            rootPageToken = next;
        }

        while (!folders.isEmpty() && visitedFolders.size() < 500) {
            String parentId = folders.removeFirst();
            if (!hasText(parentId) || !visitedFolders.add(parentId)) continue;
            String pageToken = null;
            do {
                JsonNode page;
                try {
                    page = requestSharePage(shareId, passCode, passCodeToken, parentId, pageToken);
                } catch (IllegalStateException error) {
                    if (!isRecoverableShareTraversalError(error)) throw error;
                    break;
                }
                passCodeToken = sharePassCodeToken(page, passCodeToken);
                collectSharePage(page, folders, fileIds, fileNames, seenFiles, seenNames);
                String next = shareNextPageToken(page);
                if (hasText(pageToken) && pageToken.equals(next)) break;
                pageToken = next;
            } while (hasText(pageToken));
        }
        return new ShareSelection(passCodeToken, List.copyOf(fileIds), List.copyOf(fileNames));
    }

    private JsonNode requestSharePage(
            String shareId, String passCode, String passCodeToken, String parentId, String pageToken) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/share")
                .queryParam("share_id", shareId)
                .queryParam("limit", 30)
                .queryParam("keyword", "")
                .queryParam("scene", "NORMAL")
                .queryParam("order", "DEFAULT_ORDER");
        if (hasText(passCode)) uri.queryParam("pass_code", passCode);
        String normalizedPassCodeToken = normalizeBase64Token(passCodeToken);
        if (hasText(normalizedPassCodeToken)) uri.queryParam("pass_code_token", normalizedPassCodeToken);
        if (hasText(parentId)) uri.queryParam("parent_id", parentId);
        String normalizedPageToken = normalizeBase64Token(pageToken);
        if (hasText(normalizedPageToken)) uri.queryParam("page_token", normalizedPageToken);
        return request(HttpMethod.GET, uri.build().encode().toUriString(), null);
    }

    private static void collectSharePage(
            JsonNode response,
            ArrayDeque<String> folders,
            List<String> fileIds,
            List<String> fileNames,
            Set<String> seenFiles,
            Set<String> seenNames) {
        extractVideoFileIds(response).stream().filter(seenFiles::add).forEach(fileIds::add);
        extractVideoFileNames(response).stream()
                .filter(name -> seenNames.add(name.toLowerCase(Locale.ROOT)))
                .forEach(fileNames::add);
        collectFolderIds(response, folders, new HashSet<>());
    }

    private static void collectFolderIds(JsonNode node, ArrayDeque<String> folders, Set<String> seen) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode item : node) collectFolderIds(item, folders, seen);
            return;
        }
        if (!node.isObject()) return;
        if (isFolder(node)) {
            String id = firstTextStatic(
                    node.path("id").asText(null), node.path("file_id").asText(null),
                    node.path("fileId").asText(null), node.path("fid").asText(null));
            if (hasTextStatic(id) && seen.add(id)) folders.addLast(id);
        }
        node.fields().forEachRemaining(entry -> collectFolderIds(entry.getValue(), folders, seen));
    }

    private static String sharePassCodeToken(JsonNode response, String fallback) {
        String candidate = firstTextStatic(
                response.path("pass_code_token").asText(null), response.path("passCodeToken").asText(null),
                response.path("data").path("pass_code_token").asText(null),
                response.path("data").path("passCodeToken").asText(null));
        return firstTextStatic(normalizeBase64Token(candidate), fallback);
    }

    private static String shareNextPageToken(JsonNode response) {
        return normalizeBase64Token(firstTextStatic(
                response.path("next_page_token").asText(null), response.path("nextPageToken").asText(null),
                response.path("data").path("next_page_token").asText(null),
                response.path("data").path("nextPageToken").asText(null)));
    }

    private static boolean isRecoverableShareTraversalError(IllegalStateException error) {
        String message = error.getMessage();
        return message != null && message.contains("HTTP 400")
                && (message.contains("invalid_argument") || message.contains("illegal base64"));
    }

    private DirectoryInfo ensureDirectory(String path) {
        String restoreRootId = findRestoreRootId();
        String parentId = restoreRootId;
        if (!hasText(path) || "/".equals(path.trim())) return new DirectoryInfo(restoreRootId, List.of());
        for (String segment : path.trim().replaceFirst("^/+", "").split("/+")) {
            if (!hasText(segment)) continue;
            JsonNode files = request(HttpMethod.GET, "/files?parent_id=" + parentId + "&limit=100", null);
            String folderId = null;
            for (JsonNode item : files.path("files")) {
                if (segment.equals(item.path("name").asText())
                        && item.path("kind").asText("").contains("folder")) {
                    folderId = item.path("id").asText(null);
                    break;
                }
            }
            if (!hasText(folderId)) {
                JsonNode created = request(HttpMethod.POST, "/files",
                        Map.of("name", segment, "parent_id", parentId, "kind", "drive#folder"));
                folderId = firstText(
                        created.path("id").asText(null),
                        created.path("file").path("id").asText(null),
                        created.path("data").path("id").asText(null));
            }
            if (!hasText(folderId)) {
                throw new IllegalStateException("Xunlei folder creation did not return an id");
            }
            parentId = folderId;
        }
        return new DirectoryInfo(restoreRootId, List.of());
    }

    private String findRestoreRootId() {
        JsonNode response = request(HttpMethod.GET,
                "/files?parent_id=&usage=DISPLAY&limit=100", null);
        for (JsonNode item : response.path("files")) {
            if (isFolder(item) && "我的转存".equals(item.path("name").asText())) {
                String id = item.path("id").asText(null);
                if (hasText(id)) return id;
            }
        }
        throw new IllegalStateException("Xunlei system restore folder was not found");
    }

    static List<String> extractTopLevelFileIds(JsonNode response) {
        JsonNode files = firstArray(
                response.path("files"),
                response.path("file_list"),
                response.path("data").path("files"),
                response.path("data").path("file_list"));
        if (files == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : files) {
            String id = item.path("id").asText(null);
            if (hasTextStatic(id) && seen.add(id)) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    static List<String> extractTopLevelFileNames(JsonNode response) {
        JsonNode files = firstArray(
                response.path("files"),
                response.path("file_list"),
                response.path("data").path("files"),
                response.path("data").path("file_list"));
        if (files == null) return List.of();
        List<String> names = new ArrayList<>();
        for (JsonNode item : files) {
            String name = firstTextStatic(
                    item.path("name").asText(null),
                    item.path("file_name").asText(null));
            if (hasTextStatic(name)) names.add(name);
        }
        return List.copyOf(names);
    }

    /**
     * Return only playable video file ids from a share response.  Share APIs may
     * embed descendants under children/files; folders are deliberately ignored
     * so restoring a share cannot pull in advertising documents or images.
     */
    static List<String> extractVideoFileIds(JsonNode response) {
        List<String> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectVideoFiles(response, ids, seen);
        return List.copyOf(ids);
    }

    static List<String> extractVideoFileNames(JsonNode response) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectVideoNames(response, names, seen);
        return List.copyOf(names);
    }

    private static void collectVideoFiles(JsonNode node, List<String> ids, Set<String> seen) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode item : node) collectVideoFiles(item, ids, seen);
            return;
        }
        if (!node.isObject()) return;
        if (!isFolder(node)) {
            String id = firstTextStatic(
                    node.path("id").asText(null), node.path("file_id").asText(null),
                    node.path("fileId").asText(null), node.path("fid").asText(null));
            if (hasTextStatic(id) && isVideo(node) && seen.add(id)) ids.add(id);
        }
        node.fields().forEachRemaining(entry -> collectVideoFiles(entry.getValue(), ids, seen));
    }

    private static void collectVideoNames(JsonNode node, List<String> names, Set<String> seen) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode item : node) collectVideoNames(item, names, seen);
            return;
        }
        if (!node.isObject()) return;
        if (!isFolder(node)) {
            String name = firstTextStatic(
                    node.path("name").asText(null), node.path("file_name").asText(null),
                    node.path("filename").asText(null), node.path("fileName").asText(null));
            if (hasTextStatic(name) && isVideo(node) && seen.add(name.toLowerCase(Locale.ROOT))) names.add(name);
        }
        node.fields().forEachRemaining(entry -> collectVideoNames(entry.getValue(), names, seen));
    }

    static List<JsonNode> selectRestoredFiles(
            JsonNode files,
            List<String> expectedNames,
            long restoreStartedAt) {
        if (files == null || !files.isArray()) return List.of();
        long earliest = restoreStartedAt - 5_000;
        List<String> expected = expectedNames == null
                ? List.of()
                : expectedNames.stream()
                        .filter(XunleiClient::hasTextStatic)
                        .map(XunleiClient::normalizeFileName)
                        .filter(XunleiClient::hasTextStatic)
                        .toList();
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode item : files) {
            String name = firstTextStatic(
                    item.path("name").asText(null),
                    item.path("file_name").asText(null));
            if (!hasTextStatic(item.path("id").asText(null)) || !createdAfter(item, earliest)) continue;
            String normalized = normalizeFileName(name);
            boolean matches = expected.isEmpty() || expected.stream().anyMatch(value ->
                    normalized.equals(value)
                            || normalized.startsWith(value)
                            || value.startsWith(normalized));
            if (matches) result.add(item);
        }
        return List.copyOf(result);
    }

    private static boolean createdAfter(JsonNode item, long earliestEpochMs) {
        for (String field : List.of("created_time", "user_modified_time", "modified_time")) {
            String value = item.path(field).asText(null);
            if (!hasTextStatic(value)) continue;
            try {
                if (OffsetDateTime.parse(value).toInstant().toEpochMilli() >= earliestEpochMs) return true;
            } catch (Exception ignored) {
                try {
                    if (Instant.parse(value).toEpochMilli() >= earliestEpochMs) return true;
                } catch (Exception ignoredAgain) {
                    // Try the remaining timestamp fields.
                }
            }
        }
        return false;
    }

    private static String normalizeFileName(String value) {
        return hasTextStatic(value)
                ? value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "")
                : "";
    }

    private ContentSummary inspectContent(String parentId) {
        ArrayDeque<FolderEntry> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(new FolderEntry(parentId, 0));
        int folders = 0;
        int files = 0;
        int videos = 0;
        while (!queue.isEmpty() && folders + files < 500) {
            FolderEntry entry = queue.removeFirst();
            if (!seen.add(entry.id())) {
                continue;
            }
            JsonNode response = request(HttpMethod.GET,
                    "/files?parent_id=" + entry.id() + "&usage=DISPLAY&limit=100", null);
            JsonNode children = response.path("files");
            if (!children.isArray()) {
                continue;
            }
            for (JsonNode child : children) {
                if (isFolder(child)) {
                    folders++;
                    String childId = child.path("id").asText(null);
                    if (entry.depth() < 8 && hasText(childId)) {
                        queue.addLast(new FolderEntry(childId, entry.depth() + 1));
                    }
                } else {
                    files++;
                    if (isVideo(child)) {
                        videos++;
                    }
                }
            }
        }
        return new ContentSummary(folders, files, videos);
    }

    private JsonNode request(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, bearer(properties.getXunlei().getAuthorization()));
        headers.set("X-Device-Id", firstText(properties.getXunlei().getDeviceId(), deviceId()));
        headers.set("X-Client-Id", firstText(properties.getXunlei().getClientId(), jwtClaim("aud"), "xunlei-open-api"));
        headers.set("X-Client-Version", properties.getXunlei().getClientVersion());
        String action = action(method, path);
        headers.set("X-Captcha-Token", captchaToken(action, false));
        try {
            String url = properties.getXunlei().getBaseUrl().replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
            ResponseEntity<String> response = restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (response.getStatusCode().isError() || root.path("error").isObject()) throw new IllegalStateException("Xunlei API request failed: HTTP " + response.getStatusCode().value());
            return root;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String response = e.getResponseBodyAsString();
            if (response.contains("captcha_invalid") || response.contains("\"error_code\":9")) {
                headers.set("X-Captcha-Token", captchaToken(action, true));
                String url = properties.getXunlei().getBaseUrl().replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
                try {
                    ResponseEntity<String> retried = restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
                    return objectMapper.readTree(retried.getBody());
                } catch (org.springframework.web.client.HttpStatusCodeException retryError) {
                    throw new IllegalStateException(
                            "Xunlei API request failed after CAPTCHA refresh: "
                                    + method.name() + " " + safeEndpoint(path)
                                    + " HTTP " + retryError.getStatusCode().value()
                                    + apiErrorSuffix(retryError),
                            retryError);
                } catch (Exception retryError) {
                    throw new IllegalStateException("Xunlei API request failed after CAPTCHA refresh", retryError);
                }
            }
            throw new IllegalStateException(
                    (e.getStatusCode().value() == 401
                            ? "Xunlei Authorization expired or invalid; update XUNLEI_AUTHORIZATION in Resource Hub settings"
                            : "Xunlei API request failed: " + method.name() + " " + safeEndpoint(path))
                            + " HTTP " + e.getStatusCode().value() + apiErrorSuffix(e),
                    e);
        } catch (RestClientException e) { throw new IllegalStateException("Xunlei API request failed", e); }
        catch (Exception e) { throw new IllegalStateException("Xunlei API response parse failed", e); }
    }

    private static JsonNode firstArray(JsonNode... values) {
        for (JsonNode value : values) {
            if (value != null && value.isArray()) return value;
        }
        return null;
    }

    private static boolean isFolder(JsonNode item) {
        String kind = firstTextStatic(
                item.path("kind").asText(null),
                item.path("file_type").asText(null),
                item.path("type").asText(null));
        String mimeType = firstTextStatic(
                item.path("mime_type").asText(null),
                item.path("mimeType").asText(null),
                item.path("content_type").asText(null));
        return (kind != null && (kind.toLowerCase(Locale.ROOT).contains("folder")
                || kind.toLowerCase(Locale.ROOT).contains("directory")))
                || item.path("is_folder").asBoolean(false)
                || item.path("isFolder").asBoolean(false)
                || (mimeType != null && mimeType.toLowerCase(Locale.ROOT).contains("folder"));
    }

    private static boolean isVideo(JsonNode item) {
        String name = firstTextStatic(
                item.path("name").asText(null),
                item.path("file_name").asText(null),
                item.path("filename").asText(null),
                item.path("fileName").asText(null));
        String extension = firstTextStatic(
                item.path("file_extension").asText(null),
                item.path("fileExtension").asText(null),
                item.path("extension").asText(null));
        if (!hasTextStatic(extension) && hasTextStatic(name) && name.contains(".")) {
            extension = name.substring(name.lastIndexOf('.') + 1);
        }
        extension = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if (VIDEO_EXTENSIONS.contains(extension)) return true;
        String mimeType = firstTextStatic(
                item.path("mime_type").asText(null),
                item.path("mimeType").asText(null),
                item.path("content_type").asText(null),
                item.path("media_type").asText(null));
        mimeType = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mimeType.startsWith("video/")) return true;
        String category = firstTextStatic(
                item.path("file_category").asText(null),
                item.path("fileCategory").asText(null),
                item.path("category").asText(null));
        if ("VIDEO".equalsIgnoreCase(category)) return true;
        String kind = firstTextStatic(item.path("kind").asText(null), item.path("type").asText(null));
        return kind != null && kind.toLowerCase(Locale.ROOT).contains("video");
    }

    private String findUrl(JsonNode node) { if (node == null || node.isMissingNode()) return null; if (node.isTextual() && node.asText().startsWith("http")) return node.asText(); if (node.isObject()) { for (var it = node.fields(); it.hasNext();) { String v = findUrl(it.next().getValue()); if (v != null) return v; } } else if (node.isArray()) for (JsonNode item : node) { String v = findUrl(item); if (v != null) return v; } return null; }
    private String bearer(String token) { return token.trim().toLowerCase().startsWith("bearer ") ? token.trim() : "Bearer " + token.trim(); }
    private String jwtClaim(String claim) { try { String[] p = properties.getXunlei().getAuthorization().replaceFirst("(?i)^Bearer ", "").split("\\."); if (p.length < 2) return null; return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8)).path(claim).asText(null); } catch (Exception ignored) { return null; } }
    private String deviceId() {
        try {
            String userId = jwtClaim("sub");
            byte[] digest = MessageDigest.getInstance("MD5").digest(("pan-web-" + userId).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format("%02x", item));
            return value.toString();
        } catch (Exception e) { throw new IllegalStateException("Failed to derive Xunlei device id", e); }
    }
    private String captchaToken(String action, boolean refresh) {
        if (!refresh) {
            CaptchaEntry cached = captchaCache.get(action);
            if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.token();
        }
        String configured = properties.getXunlei().getCaptchaToken();
        if (!refresh && hasText(configured)) return configured.trim();
        String token = initializeCaptcha(action);
        captchaCache.put(action, new CaptchaEntry(token, System.currentTimeMillis() + 240_000));
        return token;
    }
    private String initializeCaptcha(String action) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String source = firstText(properties.getXunlei().getClientId(), jwtClaim("aud"), "Xqp0kJBXWhwaTpB6")
                + properties.getXunlei().getClientVersion() + PACKAGE_NAME
                + firstText(properties.getXunlei().getDeviceId(), deviceId()) + timestamp;
        for (String algorithm : WEB_ALGORITHMS) source = md5(source + algorithm);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("client_version", properties.getXunlei().getClientVersion()); meta.put("package_name", PACKAGE_NAME);
        meta.put("user_id", jwtClaim("sub")); meta.put("timestamp", timestamp); meta.put("captcha_sign", "1." + source);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action); body.put("captcha_token", ""); body.put("client_id", firstText(properties.getXunlei().getClientId(), jwtClaim("aud")));
        body.put("device_id", firstText(properties.getXunlei().getDeviceId(), deviceId())); body.put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor"); body.put("meta", meta);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(CAPTCHA_URL, body, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String token = root.path("captcha_token").asText(null);
            if (!hasText(token)) throw new IllegalStateException("Xunlei CAPTCHA initialization requires interactive verification");
            return token;
        } catch (RestClientException e) { throw new IllegalStateException("Xunlei CAPTCHA initialization failed", e); }
        catch (Exception e) { throw new IllegalStateException("Xunlei CAPTCHA response parse failed", e); }
    }
    private String action(HttpMethod method, String path) {
        String clean = path.split("\\?", 2)[0].replaceAll("/tasks/[^/]+$", "/tasks/{task_id}");
        return method.name() + ":/drive/v1" + clean;
    }
    private String safeEndpoint(String path) {
        return path.split("\\?", 2)[0].replaceAll("/tasks/[^/]+$", "/tasks/{task_id}");
    }
    private String apiErrorSuffix(org.springframework.web.client.HttpStatusCodeException error) {
        try {
            JsonNode root = objectMapper.readTree(error.getResponseBodyAsString());
            String name = root.path("error").isTextual() ? root.path("error").asText() : null;
            String code = root.path("error_code").isValueNode() ? root.path("error_code").asText() : null;
            String description = root.path("error_description").asText(null);
            String detail = null;
            JsonNode details = root.path("error_details");
            if (details.isArray()) {
                for (JsonNode item : details) {
                    String candidate = item.path("detail").asText(null);
                    if (hasText(candidate)) {
                        detail = candidate;
                        break;
                    }
                }
            }
            if (!hasText(name) && !hasText(code) && !hasText(description) && !hasText(detail)) return "";
            return " (error=" + firstText(name, "unknown")
                    + ", code=" + firstText(code, "unknown")
                    + (hasText(description) ? ", description=" + description : "")
                    + (hasText(detail) ? ", detail=" + detail : "") + ")";
        } catch (Exception ignored) {
            return "";
        }
    }
    static String normalizeBase64Token(String value) {
        if (!hasTextStatic(value)) return null;
        String token = value.trim().replace('-', '+').replace('_', '/');
        if (token.length() < 2 || !token.matches("[A-Za-z0-9+/]+={0,2}")) return null;
        int remainder = token.length() % 4;
        if (remainder == 1) return null;
        return remainder == 0 ? token : token + "=".repeat(4 - remainder);
    }
    private String md5(String value) {
        try { byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte item : digest) result.append(String.format("%02x", item)); return result.toString(); }
        catch (Exception e) { throw new IllegalStateException("Failed to sign Xunlei CAPTCHA request", e); }
    }
    private void requireConfigured() { if (!isConfigured()) throw new IllegalStateException("Xunlei transfer is not configured"); }
    private boolean isSuccess(String s) { return s != null && List.of("phase_type_complete", "complete", "completed", "success", "succeeded", "finished", "done").contains(s.toLowerCase()); }
    private boolean isFailure(String s) { return s != null && List.of("phase_type_error", "failed", "error", "canceled", "cancelled").contains(s.toLowerCase()); }
    private void sleep(long ms) { try { Thread.sleep(Math.max(0, ms)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted while waiting for Xunlei task", e); } }
    private String firstText(String... values) { for (String value : values) if (hasText(value)) return value.trim(); return null; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String firstTextStatic(String... values) { for (String value : values) if (hasTextStatic(value)) return value.trim(); return null; }
    private static boolean hasTextStatic(String value) { return value != null && !value.isBlank(); }
    public static String normalizeShareUrl(String url, String code) {
        if (!hasTextStatic(url)) return url;
        String normalized = url.trim();
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) normalized = normalized.substring(0, fragment);
        if (!hasTextStatic(code) || normalized.contains("pwd=")) return normalized;
        String separator = normalized.contains("?") ? "&" : "?";
        return normalized + separator + "pwd=" + code.trim();
    }
    public record ShareInfo(
            String shareId,
            String passCode,
            String passCodeToken,
            List<String> fileIds,
            List<String> fileNames) {}

    private record ShareSelection(
            String passCodeToken,
            List<String> fileIds,
            List<String> fileNames) {}
    public record DirectoryInfo(String id, List<String> ancestorIds) {}
    public record RestoreResult(
            String taskId,
            String response,
            String parentId,
            String restoredFileId,
            List<String> expectedNames,
            long startedAt) {}
    public record RestoreStatus(boolean success, String status, String response) {}
    public record ContentSummary(int folderCount, int fileCount, int videoCount) {}
    public record RestoredSelection(List<String> fileIds, ContentSummary content) {}
    private record FolderEntry(String id, int depth) {}
    private record CaptchaEntry(String token, long expiresAt) {}
}
