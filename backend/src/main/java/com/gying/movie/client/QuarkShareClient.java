package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkShareResult;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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
public class QuarkShareClient {

    private static final String BASE_URL = "https://drive-pc.quark.cn";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) quark-cloud-drive/3.14.2 Chrome/112.0.5615.165 Electron/24.1.3.8 Safari/537.36";
    private static final Pattern MEDIA_FILE_PATTERN = Pattern.compile(
            "(?i).+\\.(mp4|mkv|avi|mov|flv|wmv|webm|m4v|ts|m2ts|iso)$");
    private static final int MEDIA_SCAN_MAX_DEPTH = 6;
    private static final int MEDIA_SCAN_PAGE_SIZE = 100;
    private static final int MEDIA_SCAN_MAX_PAGES = 20;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;
    private final QuarkAutoSaveClient quarkAutoSaveClient;

    public QuarkShareClient(RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            ResourceHubProperties properties,
            QuarkAutoSaveClient quarkAutoSaveClient) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.quarkAutoSaveClient = quarkAutoSaveClient;
    }

    public QuarkShareResult createShareForPath(String savePath, String title) {
        if (!properties.getQuark().isShareEnabled()) {
            throw new IllegalStateException("Quark share creation is disabled");
        }
        String cookie = quarkAutoSaveClient.getPrimaryCookie();
        PathInfo pathInfo = resolvePath(cookie, savePath);
        String shareTitle = firstText(title, pathInfo.name(), "GYing Resource");
        QuarkShareResult result;
        try {
            result = createShare(cookie, List.of(pathInfo.fid()), shareTitle);
        } catch (QuarkShareRejectedException rejected) {
            List<String> mediaFids = findMediaFids(cookie, pathInfo.fid(), 0, new HashSet<>());
            mediaFids.removeAll(rejected.invalidFids());
            if (mediaFids.isEmpty()) {
                throw rejected;
            }
            result = createShareExcludingRejected(cookie, mediaFids, shareTitle);
        }
        result.setFid(pathInfo.fid());
        return result;
    }

    private QuarkShareResult createShareExcludingRejected(
            String cookie,
            List<String> initialFids,
            String title) {
        List<String> fids = new ArrayList<>(initialFids);
        for (int attempt = 0; attempt < 3 && !fids.isEmpty(); attempt++) {
            try {
                return createShare(cookie, fids, title);
            } catch (QuarkShareRejectedException rejected) {
                if (rejected.invalidFids().isEmpty() || !fids.removeAll(rejected.invalidFids())) {
                    throw rejected;
                }
            }
        }
        throw new IllegalStateException("Saved Quark folder has no shareable media files");
    }

    private QuarkShareResult createShare(String cookie, List<String> fids, String title) {
        String taskId = createShareTask(cookie, fids, title);
        String shareId = pollShareId(cookie, taskId);
        return submitShare(cookie, shareId);
    }

    public FolderContentCheck checkFolderContent(String savePath) {
        String cookie = quarkAutoSaveClient.getPrimaryCookie();
        PathInfo pathInfo = resolvePath(cookie, savePath);
        int mediaFileCount = findMediaFiles(cookie, pathInfo.fid(), 0, new HashSet<>());
        return new FolderContentCheck(pathInfo.fid(), pathInfo.name(), mediaFileCount > 0, mediaFileCount);
    }

    public FolderContentCheck waitForFolderContent(String savePath, int attempts, long intervalMs) {
        int safeAttempts = Math.max(attempts, 1);
        FolderContentCheck lastCheck = null;
        for (int index = 0; index < safeAttempts; index++) {
            lastCheck = checkFolderContent(savePath);
            if (lastCheck.hasContent()) {
                return lastCheck;
            }
            if (index + 1 < safeAttempts) {
                sleep(Math.max(intervalMs, 100));
            }
        }
        return lastCheck;
    }

    private int findMediaFiles(String cookie, String folderFid, int depth, Set<String> visited) {
        if (depth > MEDIA_SCAN_MAX_DEPTH || !visited.add(folderFid)) {
            return 0;
        }
        for (int page = 1; page <= MEDIA_SCAN_MAX_PAGES; page++) {
            JsonNode body = get(cookie, "/1/clouddrive/file/sort", Map.of(
                    "pdir_fid", folderFid,
                    "_page", String.valueOf(page),
                    "_size", String.valueOf(MEDIA_SCAN_PAGE_SIZE),
                    "_fetch_total", "1",
                    "_fetch_sub_dirs", "0",
                    "sort", "file_type:asc,updated_at:desc"));
            ensureOk(body, "list Quark folder failed");
            JsonNode data = body.path("data");
            JsonNode list = firstArray(data.path("list"), data.path("items"), data.path("files"));
            if (list == null || list.isEmpty()) {
                break;
            }
            for (JsonNode item : list) {
                if (!item.path("dir").asBoolean(false)
                        && MEDIA_FILE_PATTERN.matcher(item.path("file_name").asText("")).matches()) {
                    return 1;
                }
            }
            if (depth < MEDIA_SCAN_MAX_DEPTH) {
                for (JsonNode item : list) {
                    String childFid = item.path("fid").asText(null);
                    if (item.path("dir").asBoolean(false)
                            && hasText(childFid)
                            && findMediaFiles(cookie, childFid, depth + 1, visited) > 0) {
                        return 1;
                    }
                }
            }
            if (list.size() < MEDIA_SCAN_PAGE_SIZE) {
                break;
            }
        }
        return 0;
    }

    private List<String> findMediaFids(String cookie, String folderFid, int depth, Set<String> visited) {
        List<String> mediaFids = new ArrayList<>();
        if (depth > MEDIA_SCAN_MAX_DEPTH || !visited.add(folderFid)) {
            return mediaFids;
        }
        for (int page = 1; page <= MEDIA_SCAN_MAX_PAGES; page++) {
            JsonNode body = get(cookie, "/1/clouddrive/file/sort", Map.of(
                    "pdir_fid", folderFid,
                    "_page", String.valueOf(page),
                    "_size", String.valueOf(MEDIA_SCAN_PAGE_SIZE),
                    "_fetch_total", "1",
                    "_fetch_sub_dirs", "0",
                    "sort", "file_type:asc,updated_at:desc"));
            ensureOk(body, "list Quark folder failed");
            JsonNode list = firstArray(body.path("data").path("list"),
                    body.path("data").path("items"), body.path("data").path("files"));
            if (list == null || list.isEmpty()) {
                break;
            }
            for (JsonNode item : list) {
                String fid = item.path("fid").asText(null);
                String fileName = item.path("file_name").asText("");
                if (!item.path("dir").asBoolean(false) && hasText(fid)
                        && MEDIA_FILE_PATTERN.matcher(fileName).matches()) {
                    mediaFids.add(fid);
                }
            }
            if (depth < MEDIA_SCAN_MAX_DEPTH) {
                for (JsonNode item : list) {
                    String childFid = item.path("fid").asText(null);
                    if (item.path("dir").asBoolean(false) && hasText(childFid)) {
                        mediaFids.addAll(findMediaFids(cookie, childFid, depth + 1, visited));
                    }
                }
            }
            if (list.size() < MEDIA_SCAN_PAGE_SIZE) {
                break;
            }
        }
        return mediaFids;
    }

    private PathInfo resolvePath(String cookie, String savePath) {
        String normalizedPath = normalizePath(savePath);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_path", List.of(normalizedPath));
        payload.put("namespace", "0");
        JsonNode body = post(cookie, "/1/clouddrive/file/info/path_list", payload);
        ensureOk(body, "resolve Quark save path failed");
        JsonNode data = body.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("Quark save path does not exist: " + normalizedPath);
        }
        JsonNode item = data.get(0);
        String fid = item.path("fid").asText(null);
        if (fid == null || fid.isBlank()) {
            throw new IllegalStateException("Quark save path fid is empty: " + normalizedPath);
        }
        return new PathInfo(fid, item.path("file_name").asText(lastPathSegment(normalizedPath)));
    }

    private String createShareTask(String cookie, List<String> fids, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fid_list", fids);
        payload.put("title", title);
        payload.put("url_type", properties.getQuark().getShareUrlType());
        payload.put("expired_type", properties.getQuark().getShareExpiredType());
        if (properties.getQuark().getShareUrlType() == 2 && hasText(properties.getQuark().getSharePasscode())) {
            payload.put("passcode", properties.getQuark().getSharePasscode().trim());
        }
        JsonNode body = post(cookie, "/1/clouddrive/share", payload);
        ensureOk(body, "create Quark share task failed");
        String taskId = body.path("data").path("task_id").asText(null);
        if (!hasText(taskId)) {
            throw new IllegalStateException("create Quark share task response missing task_id");
        }
        return taskId;
    }

    private String pollShareId(String cookie, String taskId) {
        int attempts = Math.max(properties.getQuark().getSharePollAttempts(), 1);
        long intervalMs = Math.max(properties.getQuark().getSharePollIntervalMs(), 100);
        for (int i = 0; i < attempts; i++) {
            JsonNode body = get(cookie, "/1/clouddrive/task",
                    Map.of("task_id", taskId, "retry_index", String.valueOf(i)));
            if (body.path("code").asInt(-1) == 0 || body.path("status").asInt(-1) == 200) {
                String shareId = findTextByField(body, "share_id", "shareId");
                if (hasText(shareId)) {
                    return shareId;
                }
            }
            if (body.path("data").path("status").asInt(0) == 3
                    || (body.has("code") && body.path("code").asInt(0) != 0)) {
                throw new QuarkShareRejectedException(
                        firstText(body.path("message").asText(null), "Quark share task failed"),
                        findTextArrayByField(body, "invalid_fids"));
            }
            if (i + 1 < attempts) {
                sleep(intervalMs);
            }
        }
        throw new IllegalStateException("Quark share task did not return share_id");
    }

    private QuarkShareResult submitShare(String cookie, String shareId) {
        JsonNode body = post(cookie, "/1/clouddrive/share/password", Map.of("share_id", shareId));
        ensureOk(body, "submit Quark share failed");
        String shareUrl = findTextByField(body, "share_url", "shareUrl");
        if (!hasText(shareUrl)) {
            throw new IllegalStateException("submit Quark share response missing share_url");
        }
        String passcode = findTextByField(body, "passcode", "pwd", "password");
        QuarkShareResult result = new QuarkShareResult();
        result.setShareUrl(hasText(passcode) ? shareUrl + "?pwd=" + passcode : shareUrl);
        result.setTitle(findTextByField(body, "title"));
        return result;
    }

    private String findTextByField(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull() || fieldNames == null) {
            return null;
        }
        if (node.isObject()) {
            for (String fieldName : fieldNames) {
                JsonNode value = node.get(fieldName);
                if (value != null && value.isValueNode() && hasText(value.asText(null))) {
                    return value.asText().trim();
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String found = findTextByField(fields.next().getValue(), fieldNames);
                if (hasText(found)) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findTextByField(child, fieldNames);
                if (hasText(found)) {
                    return found;
                }
            }
        }
        return null;
    }

    private Set<String> findTextArrayByField(JsonNode node, String fieldName) {
        Set<String> values = new HashSet<>();
        collectTextArrayByField(node, fieldName, values);
        return values;
    }

    private void collectTextArrayByField(JsonNode node, String fieldName, Set<String> values) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode field = node.get(fieldName);
            if (field != null && field.isArray()) {
                for (JsonNode value : field) {
                    if (value.isValueNode() && hasText(value.asText(null))) {
                        values.add(value.asText().trim());
                    }
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                collectTextArrayByField(fields.next().getValue(), fieldName, values);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectTextArrayByField(child, fieldName, values);
            }
        }
    }

    private JsonNode get(String cookie, String path, Map<String, String> extraParams) {
        String url = baseUrl(path, extraParams);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers(cookie)), String.class);
            return objectMapper.readTree(response.getBody());
        } catch (RestClientException e) {
            throw new IllegalStateException("Quark request failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("Quark response parse failed: " + e.getMessage(), e);
        }
    }

    private JsonNode post(String cookie, String path, Object payload) {
        String url = baseUrl(path, Map.of());
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url,
                    new HttpEntity<>(payload, headers(cookie)), String.class);
            return objectMapper.readTree(response.getBody());
        } catch (RestClientException e) {
            throw new IllegalStateException("Quark request failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("Quark response parse failed: " + e.getMessage(), e);
        }
    }

    private String baseUrl(String path, Map<String, String> extraParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE_URL)
                .path(path)
                .queryParam("pr", "ucpro")
                .queryParam("fr", "pc")
                .queryParam("uc_param_str", "")
                .queryParam("__dt", ThreadLocalRandom.current().nextInt(600, 10000))
                .queryParam("__t", System.currentTimeMillis());
        extraParams.forEach(builder::queryParam);
        return builder.toUriString();
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", USER_AGENT);
        headers.set("Origin", "https://pan.quark.cn");
        headers.set("Referer", "https://pan.quark.cn/");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9");
        headers.set(HttpHeaders.COOKIE, cookie);
        return headers;
    }

    private void ensureOk(JsonNode body, String message) {
        if (body.has("code") && body.path("code").asInt(-1) == 0) {
            return;
        }
        if (!body.has("code") && body.path("status").asInt(-1) == 200) {
            return;
        }
        throw new IllegalStateException(body.path("message").asText(message));
    }

    private String normalizePath(String savePath) {
        String path = firstText(savePath, "/");
        path = path.replace('\\', '/').replaceAll("/+", "/");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String lastPathSegment(String path) {
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private void sleep(long intervalMs) {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling Quark share task", e);
        }
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private JsonNode firstArray(JsonNode... values) {
        for (JsonNode value : values) {
            if (value != null && value.isArray()) {
                return value;
            }
        }
        return null;
    }

    private record PathInfo(String fid, String name) {
    }

    private static final class QuarkShareRejectedException extends IllegalStateException {
        private final Set<String> invalidFids;

        private QuarkShareRejectedException(String message, Set<String> invalidFids) {
            super(message);
            this.invalidFids = invalidFids == null ? Set.of() : Set.copyOf(invalidFids);
        }

        private Set<String> invalidFids() {
            return invalidFids;
        }
    }

    public record FolderContentCheck(String fid, String name, boolean hasContent, int itemCount) {
    }
}
