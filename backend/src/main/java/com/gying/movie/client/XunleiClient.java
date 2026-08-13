package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;

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
        String parentId = ensureDirectory(savePath);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("share_id", share.shareId());
        body.put("pass_code", share.passCode());
        body.put("pass_code_token", share.passCodeToken());
        body.put("file_ids", share.fileIds());
        body.put("parent_id", parentId);
        body.put("ancestor_ids", List.of());
        JsonNode response = request(HttpMethod.POST, "/share/restore", body);
        String taskId = firstText(response.path("restore_task_id").asText(null), response.path("task_id").asText(null), response.path("data").path("restore_task_id").asText(null));
        if (!hasText(taskId)) {
            throw new IllegalStateException("Xunlei restore response did not include task id");
        }
        return new RestoreResult(taskId, response.toString(), parentId);
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

    public String createShare(String parentId) {
        ResourceHubProperties.Xunlei x = properties.getXunlei();
        if (!x.isShareEnabled() || !hasText(x.getShareCreatePath())) return null;
        Map<String, Object> body = Map.of("file_ids", List.of(parentId));
        JsonNode response = request(HttpMethod.POST, x.getShareCreatePath(), body);
        return findUrl(response);
    }

    private ShareInfo inspectShare(String shareUrl) {
        Matcher matcher = SHARE_PATTERN.matcher(shareUrl == null ? "" : shareUrl.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid Xunlei share URL");
        String shareId = matcher.group(1);
        String passCode = UriComponentsBuilder.fromUriString(shareUrl).build().getQueryParams().getFirst("pwd");
        JsonNode response = request(HttpMethod.GET, "/share?share_id=" + shareId + "&pass_code=" + (passCode == null ? "" : passCode) + "&limit=100", null);
        String token = firstText(response.path("pass_code_token").asText(null), response.path("data").path("pass_code_token").asText(null));
        List<String> fileIds = new ArrayList<>();
        collectFileIds(response, fileIds);
        if (fileIds.isEmpty()) throw new IllegalStateException("Xunlei share contains no files");
        return new ShareInfo(shareId, passCode, token, fileIds);
    }

    private String ensureDirectory(String path) {
        if (!hasText(path) || "/".equals(path.trim())) return "";
        JsonNode files = request(HttpMethod.GET, "/files?parent_id=&limit=100", null);
        String name = path.trim().replaceFirst("^/+", "");
        for (JsonNode item : files.path("files")) {
            if (name.equals(item.path("name").asText()) && item.path("kind").asText("").contains("folder")) return item.path("id").asText("0");
        }
        JsonNode created = request(HttpMethod.POST, "/files", Map.of("name", name, "parent_id", "", "kind", "drive#folder"));
        return firstText(created.path("id").asText(null), created.path("file").path("id").asText(null), created.path("data").path("id").asText(null), "");
    }

    private JsonNode request(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, bearer(properties.getXunlei().getAuthorization()));
        headers.set("X-Device-Id", firstText(properties.getXunlei().getDeviceId(), deviceId()));
        headers.set("X-Client-Id", firstText(properties.getXunlei().getClientId(), jwtClaim("aud"), "xunlei-open-api"));
        headers.set("X-Client-Version", properties.getXunlei().getClientVersion());
        headers.set("X-Captcha-Token", captchaToken());
        try {
            String url = properties.getXunlei().getBaseUrl().replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
            ResponseEntity<String> response = restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (response.getStatusCode().isError() || root.path("error").isObject()) throw new IllegalStateException("Xunlei API request failed: HTTP " + response.getStatusCode().value());
            return root;
        } catch (RestClientException e) { throw new IllegalStateException("Xunlei API request failed", e); }
        catch (Exception e) { throw new IllegalStateException("Xunlei API response parse failed", e); }
    }

    private void collectFileIds(JsonNode node, List<String> ids) {
        if (node == null || node.isMissingNode()) return;
        if (node.isObject()) { String id = node.path("id").asText(null); if (hasText(id) && (node.has("name") || node.has("file_name"))) ids.add(id); node.fields().forEachRemaining(e -> collectFileIds(e.getValue(), ids)); }
        else if (node.isArray()) node.forEach(item -> collectFileIds(item, ids));
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
    private String captchaToken() {
        String configured = properties.getXunlei().getCaptchaToken();
        if (hasText(configured)) return configured.trim();
        throw new IllegalStateException("Xunlei captcha token is not configured; set XUNLEI_CAPTCHA_TOKEN from an active web session");
    }
    private void requireConfigured() { if (!isConfigured()) throw new IllegalStateException("Xunlei transfer is not configured"); }
    private boolean isSuccess(String s) { return s != null && List.of("phase_type_complete", "complete", "completed", "success", "succeeded", "finished", "done").contains(s.toLowerCase()); }
    private boolean isFailure(String s) { return s != null && List.of("phase_type_error", "failed", "error", "canceled", "cancelled").contains(s.toLowerCase()); }
    private void sleep(long ms) { try { Thread.sleep(Math.max(0, ms)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted while waiting for Xunlei task", e); } }
    private String firstText(String... values) { for (String value : values) if (hasText(value)) return value.trim(); return null; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    public record ShareInfo(String shareId, String passCode, String passCodeToken, List<String> fileIds) {}
    public record RestoreResult(String taskId, String response, String parentId) {}
    public record RestoreStatus(boolean success, String status, String response) {}
}
