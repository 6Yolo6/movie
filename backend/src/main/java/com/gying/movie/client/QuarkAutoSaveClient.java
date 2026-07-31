package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.utils.SeasonSearchUtils;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class QuarkAutoSaveClient {

    private static final int CONFIG_CHECK_ATTEMPTS = 3;
    private static final long CONFIG_CHECK_RETRY_INTERVAL_MS = 200;
    private static final int MOVIE_DIRECTORY_MAX_DEPTH = 3;
    private static final int MOVIE_DIRECTORY_MIN_SCORE = 200;
    private static final Pattern DIRECTORY_YEAR_PATTERN = Pattern.compile("(?<!\\d)(?:18|19|20)\\d{2}(?!\\d)");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;

    public QuarkAutoSaveClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper,
            ResourceHubProperties properties) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> buildTaskPayload(String taskName, String shareUrl, String savePath) {
        return buildTaskPayload(taskName, shareUrl, savePath, null);
    }

    public Map<String, Object> buildTaskPayload(
            String taskName,
            String shareUrl,
            String savePath,
            String updateSubdir) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskname", taskName);
        payload.put("shareurl", shareUrl);
        payload.put("savepath", savePath);
        payload.put("pattern", properties.getQuark().getPattern());
        payload.put("replace", properties.getQuark().getReplace());
        payload.put("update_subdir", updateSubdir == null ? "" : updateSubdir.trim());
        payload.put("ignore_extension", false);
        return payload;
    }

    public String resolveSeasonShareUrl(String shareUrl, int season, String sourceTitle) {
        if (season < 1 || season > 99) {
            return shareUrl;
        }
        requireConfigured();
        String baseShareUrl = stripDirectoryFragment(shareUrl);
        String resolved = findSeasonDirectory(baseShareUrl, shareUrl, season, 0, new HashSet<>());
        if (resolved != null) {
            return resolved;
        }
        if (SeasonSearchUtils.explicitlyMatchesSeason(sourceTitle, season)
                && !SeasonSearchUtils.hasSeasonCollection(sourceTitle)) {
            return shareUrl;
        }
        if (season == 1 && SeasonSearchUtils.canUseRootForFirstSeason(sourceTitle)) {
            return shareUrl;
        }
        throw new IllegalStateException("Quark source has no explicit season " + season + " directory");
    }

    public MovieShareSelection resolveMovieShareUrl(
            String shareUrl,
            String titleCn,
            String titleEn,
            String aliases,
            Integer year) {
        if (shareUrl == null || shareUrl.isBlank()) {
            return new MovieShareSelection(shareUrl, false);
        }
        if (shareUrl.contains("#/list/share/")) {
            return new MovieShareSelection(shareUrl, true);
        }
        Set<String> expectedTitles = movieDirectoryTitles(titleCn, titleEn, aliases);
        if (expectedTitles.isEmpty()) {
            return new MovieShareSelection(shareUrl, false);
        }
        try {
            requireConfigured();
            String baseShareUrl = stripDirectoryFragment(shareUrl);
            String resolved = findMovieDirectory(
                    baseShareUrl,
                    shareUrl,
                    expectedTitles,
                    year,
                    0,
                    new HashSet<>());
            return resolved == null
                    ? new MovieShareSelection(shareUrl, false)
                    : new MovieShareSelection(resolved, true);
        } catch (IllegalStateException ignored) {
            return new MovieShareSelection(shareUrl, false);
        }
    }

    private String findSeasonDirectory(
            String baseShareUrl,
            String currentShareUrl,
            int season,
            int depth,
            Set<String> visited) {
        JsonNode list = getShareDetailData(currentShareUrl).path("list");
        if (!list.isArray()) {
            return null;
        }
        for (JsonNode item : list) {
            String fid = item.path("fid").asText(null);
            String name = item.path("file_name").asText(null);
            if (item.path("dir").asBoolean(false)
                    && fid != null
                    && SeasonSearchUtils.explicitlyMatchesSeason(name, season)) {
                return baseShareUrl + "#/list/share/" + fid;
            }
        }
        if (depth >= 3) {
            return null;
        }
        for (JsonNode item : list) {
            String fid = item.path("fid").asText(null);
            if (!item.path("dir").asBoolean(false) || fid == null || !visited.add(fid)) {
                continue;
            }
            String resolved = findSeasonDirectory(
                    baseShareUrl,
                    baseShareUrl + "#/list/share/" + fid,
                    season,
                    depth + 1,
                    visited);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private String findMovieDirectory(
            String baseShareUrl,
            String currentShareUrl,
            Set<String> expectedTitles,
            Integer year,
            int depth,
            Set<String> visited) {
        JsonNode list = getShareDetailData(currentShareUrl).path("list");
        if (!list.isArray()) {
            return null;
        }
        String bestFid = null;
        int bestScore = -1;
        for (JsonNode item : list) {
            String fid = item.path("fid").asText(null);
            if (!item.path("dir").asBoolean(false) || fid == null || fid.isBlank()) {
                continue;
            }
            int score = scoreMovieDirectory(item.path("file_name").asText(""), expectedTitles, year);
            if (score > bestScore) {
                bestScore = score;
                bestFid = fid;
            }
        }
        if (bestFid != null && bestScore >= MOVIE_DIRECTORY_MIN_SCORE) {
            return baseShareUrl + "#/list/share/" + bestFid;
        }
        if (depth >= MOVIE_DIRECTORY_MAX_DEPTH) {
            return null;
        }
        for (JsonNode item : list) {
            String fid = item.path("fid").asText(null);
            if (!item.path("dir").asBoolean(false) || fid == null || fid.isBlank() || !visited.add(fid)) {
                continue;
            }
            String resolved = findMovieDirectory(
                    baseShareUrl,
                    baseShareUrl + "#/list/share/" + fid,
                    expectedTitles,
                    year,
                    depth + 1,
                    visited);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private Set<String> movieDirectoryTitles(String titleCn, String titleEn, String aliases) {
        Set<String> titles = new LinkedHashSet<>();
        addNormalizedTitle(titles, titleCn);
        addNormalizedTitle(titles, titleEn);
        if (aliases != null && !aliases.isBlank()) {
            for (String alias : aliases.split("[/|,，、]+")) {
                addNormalizedTitle(titles, alias);
            }
        }
        return titles;
    }

    private int scoreMovieDirectory(String directoryName, Set<String> expectedTitles, Integer expectedYear) {
        String normalizedName = normalizeDirectoryTitle(directoryName);
        if (normalizedName.isBlank()) {
            return -1;
        }
        Integer directoryYear = extractDirectoryYear(directoryName);
        if (expectedYear != null && directoryYear != null && !expectedYear.equals(directoryYear)) {
            return -1;
        }
        int score = -1;
        for (String expectedTitle : expectedTitles) {
            if (normalizedName.equals(expectedTitle)) {
                score = Math.max(score, 400 + expectedTitle.length());
            } else if (expectedTitle.length() >= 2 && normalizedName.contains(expectedTitle)) {
                score = Math.max(score, 250 + expectedTitle.length());
            } else if (normalizedName.length() >= 4 && expectedTitle.contains(normalizedName)) {
                score = Math.max(score, 100 + normalizedName.length());
            }
        }
        if (score >= 0 && expectedYear != null && expectedYear.equals(directoryYear)) {
            score += 50;
        }
        return score;
    }

    private void addNormalizedTitle(Set<String> titles, String value) {
        String normalized = normalizeDirectoryTitle(value);
        if (normalized.length() >= 2) {
            titles.add(normalized);
        }
    }

    private String normalizeDirectoryTitle(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[\\s\\p{Punct}，。！？、：；（）《》【】「」『』]+", "");
    }

    private Integer extractDirectoryYear(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = DIRECTORY_YEAR_PATTERN.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }

    private JsonNode getShareDetailData(String shareUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/get_share_detail")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(Map.of("shareurl", shareUrl), headers),
                    String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("success").asBoolean(false)) {
                throw new IllegalStateException(body.path("message").asText("read Quark share directory failed"));
            }
            return body.path("data");
        } catch (RestClientException e) {
            throw new IllegalStateException("quark-auto-save share detail request failed", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("quark-auto-save share detail response parse failed", e);
        }
    }

    private String stripDirectoryFragment(String shareUrl) {
        if (shareUrl == null) {
            return null;
        }
        int fragment = shareUrl.indexOf("#/list/share/");
        return fragment < 0 ? shareUrl : shareUrl.substring(0, fragment);
    }

    public JsonNode addTask(Map<String, Object> payload) {
        requireAccountReady();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/api/add_task")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(payload, headers),
                    String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("success").asBoolean(false)) {
                throw new IllegalStateException(body.path("message").asText("quark-auto-save add task failed"));
            }
            return body;
        } catch (RestClientException e) {
            throw new IllegalStateException("quark-auto-save add task request failed", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("quark-auto-save add task response parse failed: " + e.getMessage(), e);
        }
    }

    public String runTaskNow(Map<String, Object> payload) {
        requireConfigured();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tasklist", Collections.singletonList(payload));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/run_script_now")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(request, headers),
                    String.class);
            return response.getBody();
        } catch (RestClientException e) {
            throw new IllegalStateException("quark-auto-save run task request failed", e);
        }
    }

    public void requireAccountReady() {
        try {
            getPrimaryCookie();
        } catch (IllegalStateException e) {
            if (!isTransientConfigRequestFailure(e)) {
                throw e;
            }
        }
    }

    public String getPrimaryCookie() {
        requireConfigured();
        JsonNode data = loadConfigData();
        JsonNode cookies = data.path("cookie");
        String cookie = cookies.isArray() && !cookies.isEmpty() ? cookies.get(0).asText(null) : null;
        if (!hasUsableCookie(cookie)) {
            String fallbackCookie = firstUsableCookie(System.getenv("QUARK_COOKIE"), System.getenv("quark_cookie"));
            if (fallbackCookie != null && data instanceof ObjectNode objectData) {
                ArrayNode cookieArray = objectMapper.createArrayNode();
                cookieArray.add(fallbackCookie);
                objectData.set("cookie", cookieArray);
                synchronizeRuntimeConfig(objectData);
                return fallbackCookie;
            }
            throw new IllegalStateException("quark-auto-save cookie is not configured");
        }
        synchronizeRuntimeConfig(data);
        return cookie.trim();
    }

    private JsonNode loadConfigData() {
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/data")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        RestClientException lastRequestError = null;
        for (int attempt = 1; attempt <= CONFIG_CHECK_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                JsonNode body = objectMapper.readTree(response.getBody());
                if (!body.path("success").asBoolean(false)) {
                    throw new IllegalStateException(body.path("message").asText("quark-auto-save is not logged in"));
                }
                return body.path("data");
            } catch (RestClientException e) {
                lastRequestError = e;
                if (attempt < CONFIG_CHECK_ATTEMPTS) {
                    sleep(CONFIG_CHECK_RETRY_INTERVAL_MS);
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("quark-auto-save config check response parse failed", e);
            }
        }
        throw new IllegalStateException("quark-auto-save config check request failed", lastRequestError);
    }

    private void synchronizeRuntimeConfig(JsonNode data) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/update")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(data, headers),
                    String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("success").asBoolean(false)) {
                throw new IllegalStateException(body.path("message").asText("quark-auto-save config sync failed"));
            }
        } catch (RestClientException e) {
            throw new IllegalStateException("quark-auto-save config sync request failed", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("quark-auto-save config sync response parse failed", e);
        }
    }

    private boolean hasUsableCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return false;
        }
        String trimmed = cookie.trim();
        return !trimmed.startsWith("Your pan.quark.cn Cookie");
    }

    private String firstUsableCookie(String... cookies) {
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies) {
            if (hasUsableCookie(cookie)) {
                return cookie.trim();
            }
        }
        return null;
    }

    private boolean isTransientConfigRequestFailure(IllegalStateException error) {
        return error.getMessage() != null
                && (error.getMessage().startsWith("quark-auto-save config check request failed")
                        || error.getMessage().startsWith("quark-auto-save config sync request failed"));
    }

    private void sleep(long intervalMs) {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while checking quark-auto-save configuration", e);
        }
    }

    private void requireConfigured() {
        if (properties.getQuark().getBaseUrl() == null || properties.getQuark().getBaseUrl().isBlank()) {
            throw new IllegalStateException("quark-auto-save base URL is not configured");
        }
        if (properties.getQuark().getToken() == null || properties.getQuark().getToken().isBlank()) {
            throw new IllegalStateException("quark-auto-save API token is not configured");
        }
    }

    public record MovieShareSelection(String shareUrl, boolean recursive) {
    }
}
