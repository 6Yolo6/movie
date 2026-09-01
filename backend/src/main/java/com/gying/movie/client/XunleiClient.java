package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import org.springframework.web.util.UriUtils;

@Component
public class XunleiClient {
    private static final Pattern SHARE_PATTERN = Pattern.compile("https?://[^/]+/s/([^/?#]+)(?:[/?#].*)?", Pattern.CASE_INSENSITIVE);
    private static final String CAPTCHA_URL = "https://xluser-ssl.xunlei.com/v1/shield/captcha/init";
    private static final String AUTH_TOKEN_URL = "https://xluser-ssl.xunlei.com/v1/auth/token";
    private static final String SIGNIN_TOKEN_URL = "https://xluser-ssl.xunlei.com/v1/auth/signin/token";
    private static final String CORE_LOGIN_URL = "https://xluser-ssl.xunlei.com/xluser.core.login/v3/login";
    private static final String PACKAGE_NAME = "pan.xunlei.com";
    private static final String ANDROID_CLIENT_ID = "Xp6vsxz_7IYVw2BB";
    private static final String ANDROID_CLIENT_SECRET = "Xp6vsy4tN9toTVdMSpomVdXpRmES";
    private static final String ANDROID_CLIENT_VERSION = "8.31.0.9726";
    private static final String ANDROID_PACKAGE_NAME = "com.xunlei.downloadprovider";
    private static final String ANDROID_USER_AGENT = "ANDROID-com.xunlei.downloadprovider/8.31.0.9726 "
            + "netWorkType/5G appid/40 deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC "
            + "OSVersion/12 protocolVersion/301 platformVersion/10 sdkVersion/512000 "
            + "Oauth2Client/0.9 (Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)";
    private static final String CORE_LOGIN_USER_AGENT = "android-ok-http-client/xl-acc-sdk/version-5.0.12.512000";
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v", "ts", "m2ts",
            "mpg", "mpeg", "mpe", "vob", "3gp", "3g2", "rm", "rmvb", "asf", "ogv");
    private static final String[] WEB_ALGORITHMS = ("b9Dldv6kRsRyOG4tFHzeJ4RbOi0n7nO8omFouLVgvLNB.TEHDOteMPrRB66yQIF9tF+pfPAIesa/xg."
            + "Fmx27GlNbrIxiPSQVm.crlPVriPRAiuCEKZvK4yihP55gTRvLd7qDVLsDtWzhkXt5Iqs7TpoP."
            + "E2toogseEdgXmlfnz1ppUhUvD9B2jgSA+YG.a2f3L0AioU+0PvTeCtk.6d6w1xX9j95GEPNpd+T4HmbTceZNEF310ppRe."
            + "BvsJ+CSS7i.Rv").split("\\.");
    private static final String[] ANDROID_ALGORITHMS = {
            "9uJNVj/wLmdwKrJaVj/omlQ", "Oz64Lp0GigmChHMf/6TNfxx7O9PyopcczMsnf",
            "Eb+L7Ce+Ej48u", "jKY0", "ASr0zCl6v8W4aidjPK5KHd1Lq3t+vBFf41dqv5+fnOd",
            "wQlozdg6r1qxh0eRmt3QgNXOvSZO6q/GXK", "gmirk+ciAvIgA/cxUUCema47jr/YToixTT+Q6O",
            "5IiCoM9B1/788ntB", "P07JH0h6qoM6TSUAK2aL9T5s2QBVeY9JWvalf", "+oK0AN"
    };
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;
    private final Map<String, CaptchaEntry> captchaCache = new ConcurrentHashMap<>();
    private final Object authLock = new Object();
    private volatile AuthState authState;
    private volatile boolean authStateLoaded;

    public XunleiClient(RestTemplateBuilder builder, ObjectMapper objectMapper, ResourceHubProperties properties) {
        this.restTemplate = builder.setConnectTimeout(Duration.ofSeconds(15)).setReadTimeout(Duration.ofSeconds(30)).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean isConfigured() {
        ResourceHubProperties.Xunlei x = properties.getXunlei();
        return x != null && x.isEnabled() && hasText(x.getBaseUrl())
                && (hasText(x.getAuthorization())
                        || hasText(x.getRefreshToken())
                        || (hasText(x.getAccount()) && hasText(x.getPassword())));
    }

    public RestoreResult restore(String shareUrl, String savePath) {
        requireConfigured();
        ShareInfo share = inspectShare(shareUrl);
        DirectoryInfo directory = ensureDirectory(savePath);
        long startedAt = System.currentTimeMillis();
        ContentSummary existing = inspectContent(directory.id());
        if (existing.videoCount() > 0) {
            return new RestoreResult(null, null, directory.id(), directory.restoreRootId(),
                    share.fileNames(), startedAt, true);
        }
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
                firstText(restoredFileId, directory.restoreRootId()),
                share.fileNames(),
                startedAt,
                false);
    }

    static Map<String, Object> restorePayload(ShareInfo share, DirectoryInfo directory) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("share_id", share.shareId());
        body.put("pass_code", share.passCode());
        body.put("pass_code_token", share.passCodeToken());
        body.put("file_ids", share.fileIds());
        body.put("parent_id", directory.id());
        // `ancestor_ids` belongs to the source share's file ancestry.  We only
        // collect playable source file ids here and do not retain that source
        // tree, so sending the destination folder's ancestry is invalid and is
        // rejected by /share/restore as "invalid file ancestors".  parent_id
        // is the complete destination selector for this restore operation.
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

    public ContentSummary contentSummary(String parentId) {
        return inspectContent(parentId);
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
            var children = objectMapper.createArrayNode();
            listFolderChildren(restoreFolderId).forEach(children::add);
            List<JsonNode> restored = selectRestoredFiles(
                    children, expectedNames, restoreStartedAt);
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

    public void moveFiles(List<String> fileIds, String parentId) {
        List<String> ids = fileIds == null ? List.of() : fileIds.stream()
                .filter(XunleiClient::hasTextStatic)
                .distinct()
                .toList();
        if (ids.isEmpty() || !hasText(parentId)) {
            throw new IllegalArgumentException("Xunlei move requires file ids and a destination folder");
        }
        for (int offset = 0; offset < ids.size(); offset += 100) {
            request(HttpMethod.POST, "/files:batchMove",
                    movePayload(ids.subList(offset, Math.min(ids.size(), offset + 100)), parentId));
        }
    }

    static Map<String, Object> movePayload(List<String> fileIds, String parentId) {
        return Map.of("to", Map.of("parent_id", parentId), "ids", List.copyOf(fileIds));
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
        IllegalStateException traversalError = null;

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
                traversalError = error;
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
                    traversalError = error;
                    break;
                }
                passCodeToken = sharePassCodeToken(page, passCodeToken);
                collectSharePage(page, folders, fileIds, fileNames, seenFiles, seenNames);
                String next = shareNextPageToken(page);
                if (hasText(pageToken) && pageToken.equals(next)) break;
                pageToken = next;
            } while (hasText(pageToken));
        }
        if (fileIds.isEmpty() && traversalError != null) throw traversalError;
        if (fileIds.isEmpty()) {
            throw new IllegalStateException("Xunlei share contains no video files");
        }
        return new ShareSelection(passCodeToken, List.copyOf(fileIds), List.copyOf(fileNames));
    }

    /**
     * The Drive restore endpoint returns the actual destination ids in
     * params.trace_file_ids.  The top-level file_id is only the My Transfers
     * root and must not be used as the restored movie folder.
     */
    public List<String> extractRestoredFileIds(String responsePayload) {
        if (!hasText(responsePayload)) return List.of();
        try {
            JsonNode response = objectMapper.readTree(responsePayload);
            List<String> ids = new ArrayList<>();
            collectTraceFileIds(response, ids);
            return List.copyOf(ids);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public String restoredFileIdsPayload(List<String> fileIds) {
        try {
            Map<String, String> trace = new LinkedHashMap<>();
            int index = 0;
            for (String id : fileIds == null ? List.<String>of() : fileIds) {
                if (hasText(id) && !trace.containsValue(id)) trace.put("restored-" + index++, id);
            }
            return objectMapper.writeValueAsString(Map.of("params", Map.of("trace_file_ids", trace)));
        } catch (Exception error) {
            throw new IllegalStateException("Xunlei restored file ids could not be serialized", error);
        }
    }

    private void collectTraceFileIds(JsonNode node, List<String> ids) throws Exception {
        if (node == null || node.isNull() || node.isMissingNode()) return;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if ("trace_file_ids".equals(field.getKey())) {
                    JsonNode trace = field.getValue();
                    if (trace.isTextual()) trace = objectMapper.readTree(trace.asText());
                    collectTraceValues(trace, ids);
                } else {
                    collectTraceFileIds(field.getValue(), ids);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) collectTraceFileIds(item, ids);
        }
    }

    private void collectTraceValues(JsonNode node, List<String> ids) {
        if (node == null || node.isNull() || node.isMissingNode()) return;
        if (node.isValueNode()) {
            String id = node.asText(null);
            if (hasText(id) && !ids.contains(id)) ids.add(id);
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) collectTraceValues(item, ids);
            return;
        }
        node.elements().forEachRemaining(item -> collectTraceValues(item, ids));
    }

    private JsonNode requestSharePage(
            String shareId, String passCode, String passCodeToken, String parentId, String pageToken) {
        return request(HttpMethod.GET, sharePageUri(shareId, passCode, passCodeToken, parentId, pageToken), null);
    }

    static String sharePageUri(
            String shareId, String passCode, String passCodeToken, String parentId, String pageToken) {
        boolean detailRequest = hasTextStatic(parentId);
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(detailRequest ? "/share/detail" : "/share")
                .queryParam("share_id", shareId)
                .queryParam("limit", 30)
                .queryParam("keyword", "")
                .queryParam("scene", "NORMAL")
                .queryParam("order", detailRequest ? "MODIFY_TIME_DESC_V2" : "DEFAULT_ORDER")
                .queryParam("thumbnail_size", "SIZE_MEDIUM");
        if (!detailRequest && hasTextStatic(passCode)) uri.queryParam("pass_code", passCode);
        String safePassCodeToken = opaqueShareToken(passCodeToken);
        if (hasTextStatic(safePassCodeToken)) {
            uri.queryParam("pass_code_token", UriUtils.encode(safePassCodeToken, StandardCharsets.UTF_8));
        }
        if (detailRequest) uri.queryParam("parent_id", parentId);
        String safePageToken = opaqueShareToken(pageToken);
        if (hasTextStatic(safePageToken)) {
            uri.queryParam("page_token", UriUtils.encode(safePageToken, StandardCharsets.UTF_8));
        }
        return uri.build(true).toUriString();
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
        return firstTextStatic(opaqueShareToken(candidate), fallback);
    }

    private static String shareNextPageToken(JsonNode response) {
        return opaqueShareToken(firstTextStatic(
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
        if (!hasText(path) || "/".equals(path.trim())) {
            return new DirectoryInfo(restoreRootId, restoreRootId);
        }
        for (String segment : path.trim().replaceFirst("^/+", "").split("/+")) {
            if (!hasText(segment)) continue;
            JsonNode files = request(HttpMethod.GET, "/files?parent_id=" + parentId + "&limit=100", null);
            String folderId = findChildFolderId(files, segment);
            if (!hasText(folderId)) {
                try {
                    JsonNode created = request(HttpMethod.POST, "/files",
                            Map.of("name", segment, "parent_id", parentId, "kind", "drive#folder"));
                    folderId = firstText(
                            created.path("id").asText(null),
                            created.path("file").path("id").asText(null),
                            created.path("data").path("id").asText(null));
                } catch (IllegalStateException error) {
                    if (!isDuplicateFolderError(error)) throw error;
                    JsonNode refreshed = request(HttpMethod.GET,
                            "/files?parent_id=" + parentId + "&limit=100", null);
                    folderId = findChildFolderId(refreshed, segment);
                }
            }
            if (!hasText(folderId)) {
                throw new IllegalStateException("Xunlei folder creation did not return an id");
            }
            parentId = folderId;
        }
        return new DirectoryInfo(parentId, restoreRootId);
    }

    static String findChildFolderId(JsonNode response, String expectedName) {
        JsonNode files = firstArray(
                response.path("files"), response.path("file_list"),
                response.path("data").path("files"), response.path("data").path("file_list"));
        if (files == null) return null;
        for (JsonNode item : files) {
            String name = firstTextStatic(
                    item.path("name").asText(null), item.path("file_name").asText(null),
                    item.path("filename").asText(null), item.path("fileName").asText(null));
            if (expectedName.equals(name) && isFolder(item)) {
                return firstTextStatic(
                        item.path("id").asText(null), item.path("file_id").asText(null),
                        item.path("fileId").asText(null), item.path("fid").asText(null));
            }
        }
        return null;
    }

    private static boolean isDuplicateFolderError(IllegalStateException error) {
        return error.getMessage() != null && error.getMessage().contains("file_duplicated_name");
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

    private List<JsonNode> listFolderChildren(String parentId) {
        List<JsonNode> result = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < 100; page++) {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/files")
                    .queryParam("parent_id", parentId)
                    .queryParam("usage", "DISPLAY")
                    .queryParam("limit", 100);
            if (hasText(pageToken)) {
                uri.queryParam("page_token", UriUtils.encode(pageToken, StandardCharsets.UTF_8));
            }
            JsonNode response = request(HttpMethod.GET, uri.build(true).toUriString(), null);
            JsonNode files = firstArray(
                    response.path("files"), response.path("file_list"),
                    response.path("data").path("files"), response.path("data").path("file_list"));
            if (files != null) files.forEach(result::add);
            String next = firstText(
                    response.path("next_page_token").asText(null),
                    response.path("data").path("next_page_token").asText(null));
            if (!hasText(next) || next.equals(pageToken)) break;
            pageToken = next;
        }
        return List.copyOf(result);
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
        return itemEpochMs(item) >= earliestEpochMs;
    }

    private static long itemEpochMs(JsonNode item) {
        for (String field : List.of("created_time", "user_modified_time", "modified_time")) {
            String value = item.path(field).asText(null);
            if (!hasTextStatic(value)) continue;
            try {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                try {
                    return Instant.parse(value).toEpochMilli();
                } catch (Exception ignoredAgain) {
                    // Try the remaining timestamp fields.
                }
            }
        }
        return 0;
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
        boolean authorizationRequired = requiresAuthorization(method, path);
        String action = action(method, path);
        boolean forceAuthorizationRefresh = false;
        boolean forceCaptchaRefresh = false;
        org.springframework.web.client.HttpStatusCodeException latestHttpError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            AuthState state = authorizationRequired
                    ? authorizedState(forceAuthorizationRefresh)
                    : optionalAuthState();
            AuthIdentity identity = state == null ? configuredWebIdentity() : state.identity();
            HttpHeaders headers = requestHeaders(
                    authorizationRequired, state, identity, action, forceCaptchaRefresh);
            try {
                URI url = requestUri(properties.getXunlei().getBaseUrl(), path);
                ResponseEntity<String> response = restTemplate.exchange(
                        url, method, new HttpEntity<>(body, headers), String.class);
                JsonNode root = objectMapper.readTree(response.getBody());
                if (response.getStatusCode().isError() || root.path("error").isObject()) {
                    throw new IllegalStateException("Xunlei API request failed: HTTP "
                            + response.getStatusCode().value());
                }
                return root;
            } catch (org.springframework.web.client.HttpStatusCodeException error) {
                latestHttpError = error;
                if (authorizationRequired && !forceAuthorizationRefresh
                        && isAuthorizationError(error) && hasAutomaticAuthorization()) {
                    forceAuthorizationRefresh = true;
                    forceCaptchaRefresh = false;
                    continue;
                }
                if (!forceCaptchaRefresh && isCaptchaError(error)) {
                    forceCaptchaRefresh = true;
                    continue;
                }
                break;
            } catch (RestClientException error) {
                throw new IllegalStateException("Xunlei API request failed", error);
            } catch (Exception error) {
                throw new IllegalStateException("Xunlei API response parse failed", error);
            }
        }
        if (latestHttpError == null) {
            throw new IllegalStateException("Xunlei API request failed");
        }
        String prefix = isAuthorizationError(latestHttpError)
                ? (hasAutomaticAuthorization()
                        ? "Xunlei automatic authorization refresh failed"
                        : "Xunlei Authorization expired or invalid; configure XUNLEI_ACCOUNT/XUNLEI_PASSWORD")
                : "Xunlei API request failed: " + method.name() + " " + safeEndpoint(path);
        throw new IllegalStateException(
                prefix + " HTTP " + latestHttpError.getStatusCode().value()
                        + apiErrorSuffix(latestHttpError)
                        + shareTokenProfile(path, latestHttpError.getResponseBodyAsString()),
                latestHttpError);
    }

    private HttpHeaders requestHeaders(
            boolean authorizationRequired,
            AuthState state,
            AuthIdentity identity,
            String action,
            boolean refreshCaptcha) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hasText(identity.userAgent())) headers.set(HttpHeaders.USER_AGENT, identity.userAgent());
        if (authorizationRequired) {
            if (state == null || !hasText(state.accessToken())) {
                throw new IllegalStateException("Xunlei authorization is unavailable");
            }
            headers.set(HttpHeaders.AUTHORIZATION,
                    firstText(state.tokenType(), "Bearer") + " " + state.accessToken());
        }
        headers.set("X-Device-Id", identity.deviceId());
        headers.set("X-Client-Id", identity.clientId());
        headers.set("X-Client-Version", identity.clientVersion());
        headers.set("X-Captcha-Token", captchaToken(action, state, identity, refreshCaptcha, null));
        return headers;
    }

    private boolean isAuthorizationError(org.springframework.web.client.HttpStatusCodeException error) {
        if (error.getStatusCode().value() == 401) return true;
        String response = error.getResponseBodyAsString();
        return response != null && (response.contains("\"error_code\":10")
                || response.contains("\"error_code\":16")
                || response.contains("\"error_code\":4121")
                || response.contains("\"error_code\":4122"));
    }

    private boolean isCaptchaError(org.springframework.web.client.HttpStatusCodeException error) {
        String response = error.getResponseBodyAsString();
        return response != null && (response.contains("captcha_invalid")
                || response.contains("\"error_code\":9"));
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

    private AuthState authorizedState(boolean forceRefresh) {
        synchronized (authLock) {
            loadAuthState();
            AuthState latestConfigured = configuredAuthState();
            if (authState != null && !hasText(authState.refreshToken()) && latestConfigured != null
                    && !latestConfigured.accessToken().equals(authState.accessToken())) {
                authState = latestConfigured;
            }
            if (!forceRefresh && isUsable(authState)) return authState;
            IllegalStateException refreshFailure = null;
            String refreshToken = firstText(
                    authState == null ? null : authState.refreshToken(),
                    properties.getXunlei().getRefreshToken());
            AuthIdentity identity = authState != null && hasText(authState.refreshToken())
                    && authState.identity() != null ? authState.identity() : automaticIdentity();
            if (hasText(refreshToken)) {
                try {
                    authState = refreshAuthorization(refreshToken, identity);
                    persistAuthState(authState);
                    return authState;
                } catch (IllegalStateException error) {
                    refreshFailure = error;
                }
            }
            AuthState configured = configuredAuthState();
            if (!forceRefresh && isUsable(configured)) {
                authState = configured;
                return authState;
            }
            if (hasText(properties.getXunlei().getAccount())
                    && hasText(properties.getXunlei().getPassword())) {
                authState = loginAuthorization(automaticIdentity());
                persistAuthState(authState);
                return authState;
            }
            if (configured != null) {
                authState = configured;
                return authState;
            }
            if (refreshFailure != null) throw refreshFailure;
            throw new IllegalStateException(
                    "Xunlei authorization is not configured; set XUNLEI_ACCOUNT/XUNLEI_PASSWORD");
        }
    }

    private AuthState optionalAuthState() {
        synchronized (authLock) {
            loadAuthState();
            AuthState latestConfigured = configuredAuthState();
            if (authState != null && !hasText(authState.refreshToken()) && latestConfigured != null
                    && !latestConfigured.accessToken().equals(authState.accessToken())) {
                authState = latestConfigured;
            }
            if (authState != null) return authState;
            return latestConfigured;
        }
    }

    private boolean hasAutomaticAuthorization() {
        synchronized (authLock) {
            loadAuthState();
            return hasText(properties.getXunlei().getRefreshToken())
                    || (authState != null && hasText(authState.refreshToken()))
                    || (hasText(properties.getXunlei().getAccount())
                            && hasText(properties.getXunlei().getPassword()));
        }
    }

    private boolean isUsable(AuthState state) {
        return state != null && hasText(state.accessToken())
                && (state.expiresAt() <= 0 || state.expiresAt() > System.currentTimeMillis() + 60_000);
    }

    private AuthState configuredAuthState() {
        String authorization = properties.getXunlei().getAuthorization();
        if (!hasText(authorization)) return null;
        String value = authorization.trim();
        String tokenType = value.regionMatches(true, 0, "Bearer ", 0, 7) ? "Bearer" : "Bearer";
        String accessToken = value.replaceFirst("(?i)^Bearer\\s+", "");
        long expiresAt = jwtLongClaim(accessToken, "exp") * 1000;
        AuthIdentity identity = configuredWebIdentity(accessToken);
        return new AuthState(tokenType, accessToken, null, expiresAt,
                jwtTextClaim(accessToken, "sub"), properties.getXunlei().getCaptchaToken(), identity);
    }

    private AuthIdentity configuredWebIdentity() {
        AuthState configured = configuredAuthState();
        return configured == null ? configuredWebIdentity(null) : configured.identity();
    }

    private AuthIdentity configuredWebIdentity(String accessToken) {
        String userId = jwtTextClaim(accessToken, "sub");
        String clientId = firstText(properties.getXunlei().getClientId(),
                jwtTextClaim(accessToken, "aud"), "xunlei-open-api");
        String deviceId = firstText(properties.getXunlei().getDeviceId(),
                md5("pan-web-" + firstText(userId, properties.getXunlei().getAccount(), "anonymous")));
        return new AuthIdentity(clientId, properties.getXunlei().getClientSecret(), deviceId,
                firstText(properties.getXunlei().getClientVersion(), "1.82.0"),
                PACKAGE_NAME, null, WEB_ALGORITHMS);
    }

    private AuthIdentity automaticIdentity() {
        boolean customClient = hasText(properties.getXunlei().getClientId())
                && hasText(properties.getXunlei().getClientSecret());
        String clientId = customClient ? properties.getXunlei().getClientId().trim() : ANDROID_CLIENT_ID;
        String clientSecret = customClient
                ? properties.getXunlei().getClientSecret().trim() : ANDROID_CLIENT_SECRET;
        String deviceId = firstText(properties.getXunlei().getDeviceId(),
                md5("gying-xunlei-" + firstText(properties.getXunlei().getAccount(), "account")));
        return new AuthIdentity(clientId, clientSecret, deviceId, ANDROID_CLIENT_VERSION,
                ANDROID_PACKAGE_NAME, ANDROID_USER_AGENT, ANDROID_ALGORITHMS);
    }

    private AuthState refreshAuthorization(String refreshToken, AuthIdentity identity) {
        JsonNode response = authRequest(AUTH_TOKEN_URL, HttpMethod.POST, identity,
                Map.of("grant_type", "refresh_token", "refresh_token", refreshToken,
                        "client_id", identity.clientId(), "client_secret", identity.clientSecret()),
                null);
        return tokenState(response, identity, refreshToken,
                authState == null ? null : authState.captchaToken());
    }

    private AuthState loginAuthorization(AuthIdentity identity) {
        JsonNode core = authRequest(CORE_LOGIN_URL, HttpMethod.POST, identity,
                coreLoginPayload(properties.getXunlei().getAccount(), properties.getXunlei().getPassword(), identity),
                CORE_LOGIN_USER_AGENT);
        String sessionId = firstText(core.path("sessionID").asText(null), core.path("session_id").asText(null));
        if (!hasText(sessionId)) {
            if (hasText(core.path("reviewurl").asText(null))) {
                throw new IllegalStateException("Xunlei account login requires interactive verification");
            }
            throw new IllegalStateException("Xunlei account login did not return a session id");
        }
        String loginCaptcha = captchaToken(
                "POST:/v1/auth/signin/token", null, identity, true, properties.getXunlei().getAccount());
        JsonNode token = authRequest(SIGNIN_TOKEN_URL, HttpMethod.POST, identity,
                Map.of("client_id", identity.clientId(), "client_secret", identity.clientSecret(),
                        "provider", "access_end_point_token", "signin_token", sessionId),
                null, loginCaptcha);
        return tokenState(token, identity, null, loginCaptcha);
    }

    static Map<String, Object> coreLoginPayload(
            String account, String password, AuthIdentity identity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", "301");
        body.put("sequenceNo", "1000012");
        body.put("platformVersion", "10");
        body.put("isCompressed", "0");
        body.put("appid", "40");
        body.put("clientVersion", ANDROID_CLIENT_VERSION);
        body.put("peerID", "00000000000000000000000000000000");
        body.put("appName", "ANDROID-com.xunlei.downloadprovider");
        body.put("sdkVersion", "512000");
        body.put("devicesign", generateDeviceSign(identity.deviceId(), identity.packageName()));
        body.put("netWorkType", "WIFI");
        body.put("providerName", "NONE");
        body.put("deviceModel", "M2004J7AC");
        body.put("deviceName", "Xiaomi_M2004j7ac");
        body.put("OSVersion", "12");
        body.put("creditkey", "");
        body.put("hl", "zh-CN");
        body.put("userName", account);
        body.put("passWord", password);
        body.put("verifyKey", "");
        body.put("verifyCode", "");
        body.put("isMd5Pwd", "0");
        return body;
    }

    private AuthState tokenState(
            JsonNode response,
            AuthIdentity identity,
            String previousRefreshToken,
            String captchaToken) {
        String accessToken = response.path("access_token").asText(null);
        if (!hasText(accessToken)) throw new IllegalStateException("Xunlei authorization returned no access token");
        String refreshToken = firstText(response.path("refresh_token").asText(null), previousRefreshToken);
        long expiresIn = response.path("expires_in").asLong(0);
        long expiresAt = expiresIn > 0
                ? System.currentTimeMillis() + expiresIn * 1000
                : jwtLongClaim(accessToken, "exp") * 1000;
        String tokenType = firstText(response.path("token_type").asText(null), "Bearer");
        properties.getXunlei().setAuthorization(tokenType + " " + accessToken);
        return new AuthState(tokenType,
                accessToken, refreshToken, expiresAt,
                firstText(response.path("user_id").asText(null), response.path("sub").asText(null),
                        jwtTextClaim(accessToken, "sub")),
                captchaToken, identity);
    }

    private JsonNode authRequest(
            String url, HttpMethod method, AuthIdentity identity, Object body, String userAgent) {
        return authRequest(url, method, identity, body, userAgent, null);
    }

    private JsonNode authRequest(
            String url,
            HttpMethod method,
            AuthIdentity identity,
            Object body,
            String userAgent,
            String captchaToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, firstText(userAgent, identity.userAgent(), "gying-movie"));
        headers.set("X-Device-Id", identity.deviceId());
        headers.set("X-Client-Id", identity.clientId());
        headers.set("X-Client-Version", identity.clientVersion());
        if (hasText(captchaToken)) headers.set("X-Captcha-Token", captchaToken);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), method, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String error = root.path("error").asText(null);
            long errorCode = root.path("error_code").asLong(0);
            if ((hasText(error) && !"success".equalsIgnoreCase(error)) || errorCode != 0) {
                if ("review_panel".equalsIgnoreCase(error)) {
                    throw new IllegalStateException("Xunlei account login requires interactive verification");
                }
                throw new IllegalStateException("Xunlei authorization request failed"
                        + (errorCode == 0 ? "" : " (code=" + errorCode + ")"));
            }
            return root;
        } catch (org.springframework.web.client.HttpStatusCodeException error) {
            throw new IllegalStateException("Xunlei authorization request failed HTTP "
                    + error.getStatusCode().value(), error);
        } catch (RestClientException error) {
            throw new IllegalStateException("Xunlei authorization request failed", error);
        } catch (Exception error) {
            if (error instanceof IllegalStateException stateError) throw stateError;
            throw new IllegalStateException("Xunlei authorization response parse failed", error);
        }
    }

    private String captchaToken(
            String action,
            AuthState state,
            AuthIdentity identity,
            boolean refresh,
            String loginAccount) {
        String cacheKey = identity.clientId() + ":" + action;
        if (!refresh) {
            CaptchaEntry cached = captchaCache.get(cacheKey);
            if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.token();
            boolean automaticState = state != null && hasText(state.refreshToken());
            String available = automaticState
                    ? firstText(state.captchaToken(), properties.getXunlei().getCaptchaToken())
                    : firstText(properties.getXunlei().getCaptchaToken(),
                            state == null ? null : state.captchaToken());
            if (hasText(available)) return available;
        }
        String token = initializeCaptcha(action, state, identity, loginAccount);
        properties.getXunlei().setCaptchaToken(token);
        captchaCache.put(cacheKey, new CaptchaEntry(token, System.currentTimeMillis() + 240_000));
        if (state != null) {
            synchronized (authLock) {
                if (authState == state) {
                    authState = new AuthState(state.tokenType(), state.accessToken(), state.refreshToken(),
                            state.expiresAt(), state.userId(), token, state.identity());
                    persistAuthState(authState);
                }
            }
        }
        return token;
    }

    private String initializeCaptcha(
            String action, AuthState state, AuthIdentity identity, String loginAccount) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (hasText(loginAccount)) {
            if (loginAccount.matches("\\w+([-+.']\\w+)*@\\w+([-.]\\w+)*\\.\\w+([-.]\\w+)*")) {
                meta.put("email", loginAccount);
            } else if (loginAccount.matches("\\d{11,18}")) {
                meta.put("phone_number", loginAccount);
            } else {
                meta.put("username", loginAccount);
            }
        } else {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String source = identity.clientId() + identity.clientVersion()
                    + identity.packageName() + identity.deviceId() + timestamp;
            for (String algorithm : identity.algorithms()) source = md5(source + algorithm);
            meta.put("client_version", identity.clientVersion());
            meta.put("package_name", identity.packageName());
            meta.put("user_id", state == null ? null : state.userId());
            meta.put("timestamp", timestamp);
            meta.put("captcha_sign", "1." + source);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        String currentCaptcha = firstText(
                state == null ? null : state.captchaToken(), properties.getXunlei().getCaptchaToken());
        body.put("captcha_token", currentCaptcha == null ? "" : currentCaptcha);
        body.put("client_id", identity.clientId());
        body.put("device_id", identity.deviceId());
        body.put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor");
        body.put("meta", meta);
        JsonNode root = authRequest(CAPTCHA_URL, HttpMethod.POST, identity, body, null);
        if (hasText(root.path("url").asText(null))) {
            throw new IllegalStateException("Xunlei CAPTCHA requires interactive verification");
        }
        String token = root.path("captcha_token").asText(null);
        if (!hasText(token)) throw new IllegalStateException("Xunlei CAPTCHA initialization returned no token");
        return token;
    }

    private void loadAuthState() {
        if (authStateLoaded) return;
        authStateLoaded = true;
        String statePath = properties.getXunlei().getTokenStatePath();
        if (!hasText(statePath)) return;
        try {
            Path path = Path.of(statePath);
            if (!Files.isRegularFile(path)) return;
            JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            String clientId = root.path("client_id").asText(null);
            String packageName = root.path("package_name").asText(null);
            String deviceId = root.path("device_id").asText(null);
            if (!hasText(clientId) || !hasText(packageName) || !hasText(deviceId)) return;
            AuthIdentity identity = new AuthIdentity(clientId,
                    firstText(properties.getXunlei().getClientSecret(), ANDROID_CLIENT_SECRET),
                    deviceId,
                    firstText(root.path("client_version").asText(null), ANDROID_CLIENT_VERSION),
                    packageName,
                    ANDROID_PACKAGE_NAME.equals(packageName) ? ANDROID_USER_AGENT : null,
                    ANDROID_PACKAGE_NAME.equals(packageName) ? ANDROID_ALGORITHMS : WEB_ALGORITHMS);
            authState = new AuthState(root.path("token_type").asText("Bearer"),
                    root.path("access_token").asText(null), root.path("refresh_token").asText(null),
                    root.path("expires_at").asLong(0), root.path("user_id").asText(null),
                    root.path("captcha_token").asText(null), identity);
            if (hasText(authState.accessToken())) {
                properties.getXunlei().setAuthorization(
                        firstText(authState.tokenType(), "Bearer") + " " + authState.accessToken());
            }
        } catch (Exception ignored) {
            authState = null;
        }
    }

    private void persistAuthState(AuthState state) {
        if (state == null || !hasText(properties.getXunlei().getTokenStatePath())) return;
        try {
            Path path = Path.of(properties.getXunlei().getTokenStatePath());
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("token_type", state.tokenType());
            payload.put("access_token", state.accessToken());
            payload.put("refresh_token", state.refreshToken());
            payload.put("expires_at", state.expiresAt());
            payload.put("user_id", state.userId());
            payload.put("captcha_token", state.captchaToken());
            payload.put("client_id", state.identity().clientId());
            payload.put("device_id", state.identity().deviceId());
            payload.put("client_version", state.identity().clientVersion());
            payload.put("package_name", state.identity().packageName());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            throw new IllegalStateException("Xunlei token state could not be persisted", error);
        }
    }

    private String jwtTextClaim(String token, String claim) {
        try {
            if (!hasText(token)) return null;
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            return objectMapper.readTree(new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                    .path(claim).asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long jwtLongClaim(String token, String claim) {
        try {
            if (!hasText(token)) return 0;
            String[] parts = token.split("\\.");
            if (parts.length < 2) return 0;
            return objectMapper.readTree(new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                    .path(claim).asLong(0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    static String generateDeviceSign(String deviceId, String packageName) {
        String sha1 = digest("SHA-1", deviceId + packageName + "40" + "34a062aaa22f906fca4fefe9fb3a3021");
        return "div101." + deviceId + digest("MD5", sha1);
    }

    private static String digest(String algorithm, String value) {
        try {
            byte[] bytes = MessageDigest.getInstance(algorithm)
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to create Xunlei device signature", error);
        }
    }
    private String action(HttpMethod method, String path) {
        String clean = path.split("\\?", 2)[0].replaceAll("/tasks/[^/]+$", "/tasks/{task_id}");
        return method.name() + ":/drive/v1" + clean;
    }
    static boolean requiresAuthorization(HttpMethod method, String path) {
        String endpoint = path == null ? "" : path.split("\\?", 2)[0];
        return method != HttpMethod.GET
                || !("/share".equals(endpoint) || "/share/detail".equals(endpoint));
    }
    static URI requestUri(String baseUrl, String path) {
        String value = baseUrl.replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
        return URI.create(value);
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
    static String opaqueShareToken(String value) {
        if (!hasTextStatic(value)) return null;
        String token = value.trim();
        try {
            if (token.contains("%")) token = UriUtils.decode(token, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return null;
        }
        return token.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character))
                ? null
                : token;
    }
    static String shareTokenProfile(String path, String responseBody) {
        if (path == null || !path.startsWith("/share/detail")
                || responseBody == null || !responseBody.contains("illegal base64")) return "";
        String token = UriComponentsBuilder.fromUriString(path).build().getQueryParams().getFirst("pass_code_token");
        token = opaqueShareToken(token);
        if (!hasTextStatic(token)) return " (pass_code_token=missing)";
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (!(Character.isLetterOrDigit(character) || character == '+' || character == '/'
                    || character == '=')) {
                String category = character == '-' || character == '_' ? "base64url" : "other";
                return " (pass_code_token_length=" + token.length()
                        + ", first_non_base64_index=" + index + ", character_category=" + category + ")";
            }
        }
        return " (pass_code_token_length=" + token.length() + ", alphabet=base64)";
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

    /**
     * Extracts the password from a Xunlei share URL. The value is read from
     * the final URL rather than inherited from the source candidate, because
     * creating a new share can assign a different password (or no password).
     */
    public static String extractShareCode(String url) {
        if (!hasTextStatic(url)) return null;
        try {
            return firstTextStatic(UriComponentsBuilder.fromUriString(url.trim())
                    .build()
                    .getQueryParams()
                    .getFirst("pwd"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
    public record DirectoryInfo(String id, String restoreRootId) {
        public DirectoryInfo(String id) {
            this(id, null);
        }
    }
    public record RestoreResult(
            String taskId,
            String response,
            String parentId,
            String restoredFileId,
            List<String> expectedNames,
            long startedAt,
            boolean reused) {
        public RestoreResult(
                String taskId,
                String response,
                String parentId,
                String restoredFileId,
                List<String> expectedNames,
                long startedAt) {
            this(taskId, response, parentId, restoredFileId, expectedNames, startedAt, false);
        }
    }
    public record RestoreStatus(boolean success, String status, String response) {}
    public record ContentSummary(int folderCount, int fileCount, int videoCount) {}
    public record RestoredSelection(List<String> fileIds, ContentSummary content) {}
    private record FolderEntry(String id, int depth) {}
    private record CaptchaEntry(String token, long expiresAt) {}
    private record AuthIdentity(
            String clientId,
            String clientSecret,
            String deviceId,
            String clientVersion,
            String packageName,
            String userAgent,
            String[] algorithms) {}
    private record AuthState(
            String tokenType,
            String accessToken,
            String refreshToken,
            long expiresAt,
            String userId,
            String captchaToken,
            AuthIdentity identity) {}
}
