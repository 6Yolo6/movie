package com.gying.movie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.utils.SeasonSearchUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final int MOVIE_DIRECTORY_MAX_DEPTH = 6;
    private static final int MOVIE_DIRECTORY_MAX_COUNT = 64;
    private static final Pattern MOVIE_COLLECTION_PATTERN = Pattern.compile(
            "(?i)(?:合集|全集|全\\s*[0-9一二两三四五六七八九十]+\\s*部|[0-9一二三四五六七八九十]+\\s*[-~至到]\\s*[0-9一二三四五六七八九十]+\\s*部|collection)");
    private static final int MOVIE_DIRECTORY_MIN_SCORE = 200;
    private static final Pattern DIRECTORY_YEAR_PATTERN = Pattern.compile("(?<!\\d)(?:18|19|20)\\d{2}(?!\\d)");
    private static final Pattern VIDEO_FILE_PATTERN = Pattern.compile(
            "(?i).*\\.(?:mp4|mkv|avi|mov|wmv|flv|ts|m4v|webm|rmvb|iso)$");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceHubProperties properties;

    public QuarkAutoSaveClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper,
            ResourceHubProperties properties) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void deletePath(String path) {
        requireConfigured();
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            throw new IllegalArgumentException("Quark delete path is required");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = UriComponentsBuilder.fromUriString(properties.getQuark().getBaseUrl())
                .path("/delete_file")
                .queryParam("token", properties.getQuark().getToken())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(Map.of("path", path.trim()), headers), String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("success").asBoolean(false)) {
                String message = body.path("message").asText("Quark temporary file deletion failed");
                if (message.contains("未找到文件")) {
                    return;
                }
                throw new IllegalStateException(message);
            }
        } catch (RestClientException error) {
            throw new IllegalStateException("quark-auto-save delete request failed", error);
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("quark-auto-save delete response parse failed", error);
        }
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
        return resolveSeasonShareUrl(shareUrl, season, sourceTitle, sourceTitle);
    }

    public String resolveSeasonShareUrl(
            String shareUrl,
            int season,
            String sourceTitle,
            String seriesTitle) {
        if (season < 1 || season > 99) {
            return shareUrl;
        }
        requireConfigured();
        String baseShareUrl = stripDirectoryFragment(shareUrl);
        String resolved = findSeasonDirectory(
                baseShareUrl,
                shareUrl,
                season,
                seriesTitle,
                0,
                new HashSet<>());
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
        if (season == 1) {
            String rootSeason = resolveUnmarkedFirstSeasonRoot(baseShareUrl, shareUrl, sourceTitle);
            if (rootSeason != null) {
                return rootSeason;
            }
        }
        throw new IllegalStateException("Quark source has no explicit season " + season + " directory");
    }

    private String resolveUnmarkedFirstSeasonRoot(String baseShareUrl, String shareUrl, String sourceTitle) {
        JsonNode list = getShareDetailData(shareUrl).path("list");
        if (!list.isArray() || hasConflictingSeasonMarker(list, 1)) {
            return null;
        }
        boolean directVideo = false;
        int directories = 0;
        JsonNode onlyDirectory = null;
        for (JsonNode item : list) {
            String name = item.path("file_name").asText("");
            if (item.path("dir").asBoolean(false)) {
                directories++;
                onlyDirectory = item;
            } else if (isVideoFile(name)) {
                directVideo = true;
            }
        }
        if (directVideo) {
            return shareUrl;
        }
        if (directories != 1 || onlyDirectory == null) {
            return null;
        }
        String fid = onlyDirectory.path("fid").asText(null);
        if (fid == null || fid.isBlank() || SeasonSearchUtils.hasSeasonMarker(onlyDirectory.path("file_name").asText(""))) {
            return null;
        }
        ContentInspection inspection = inspectContentDirectory(
                baseShareUrl + "#/list/share/" + fid,
                0,
                new HashSet<>());
        return inspection.hasVideo() && !inspection.hasConflictingSeason()
                ? baseShareUrl + "#/list/share/" + fid
                : null;
    }

    private ContentInspection inspectContentDirectory(String shareUrl, int depth, Set<String> visited) {
        JsonNode list = getShareDetailData(shareUrl).path("list");
        if (!list.isArray()) {
            return new ContentInspection(false, true);
        }
        boolean hasVideo = false;
        boolean conflictingSeason = hasConflictingSeasonMarker(list, 1);
        for (JsonNode item : list) {
            String name = item.path("file_name").asText("");
            if (!item.path("dir").asBoolean(false) && isVideoFile(name)) {
                hasVideo = true;
                continue;
            }
            String fid = item.path("fid").asText(null);
            if (!item.path("dir").asBoolean(false) || fid == null || !visited.add(fid)) {
                continue;
            }
            if (depth >= 3) {
                continue;
            }
            ContentInspection nested = inspectContentDirectory(
                    shareUrl.substring(0, shareUrl.indexOf("#/list/share/"))
                            + "#/list/share/" + fid,
                    depth + 1,
                    visited);
            hasVideo |= nested.hasVideo();
            conflictingSeason |= nested.hasConflictingSeason();
        }
        return new ContentInspection(hasVideo, conflictingSeason);
    }

    private boolean hasConflictingSeasonMarker(JsonNode list, int season) {
        for (JsonNode item : list) {
            String name = item.path("file_name").asText("");
            if (SeasonSearchUtils.hasSeasonMarker(name) && !SeasonSearchUtils.coversSeason(name, season)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVideoFile(String name) {
        return name != null && VIDEO_FILE_PATTERN.matcher(name.trim()).matches();
    }

    public MovieShareSelection resolveMovieShareUrl(
            String shareUrl,
            String titleCn,
            String titleEn,
            String aliases,
            Integer year) {
        Set<String> expectedTitles = movieDirectoryTitles(titleCn, titleEn, aliases);
        if (shareUrl == null || shareUrl.isBlank() || expectedTitles.isEmpty()) {
            throw new IllegalStateException("Quark source target movie metadata is missing");
        }
        requireConfigured();
        // A failed lookup must never silently widen a collection back to its root.
        MovieShareSelection selected = findMovieDirectory(
                stripDirectoryFragment(shareUrl), shareUrl, "", expectedTitles, year,
                false, 0, new HashSet<>());
        if (selected == null) {
            throw new IllegalStateException("Quark source target movie directory could not be identified");
        }
        return selected;
    }

    private String findSeasonDirectory(
            String baseShareUrl,
            String currentShareUrl,
            int season,
            String seriesTitle,
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
                    && SeasonSearchUtils.matchesSeasonDirectory(name, seriesTitle, season)) {
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
                    seriesTitle,
                    depth + 1,
                    visited);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private MovieShareSelection findMovieDirectory(
            String baseShareUrl, String currentShareUrl, String directoryName,
            Set<String> expectedTitles, Integer year, boolean trustedParent,
            int depth, Set<String> visited) {
        if (!visited.add(currentShareUrl)) return null;
        if (visited.size() > MOVIE_DIRECTORY_MAX_COUNT) {
            throw new IllegalStateException("Quark source movie directory scan limit reached");
        }
        JsonNode list = getShareDetailData(currentShareUrl).path("list");
        if (!list.isArray()) {
            throw new IllegalStateException("Quark source directory listing is unavailable");
        }
        boolean collection = MOVIE_COLLECTION_PATTERN.matcher(directoryName).find();
        Integer directoryYear = extractDirectoryYear(directoryName);
        boolean wrongDirectory = year != null && directoryYear != null && !year.equals(directoryYear)
                || hasConflictingMovieSequence(directoryName, expectedTitles)
                || !collection && hasConflictingMoviePart(directoryName, expectedTitles);
        boolean trustedTitle = !collection && !wrongDirectory
                && (trustedParent || scoreMovieDirectory(directoryName, expectedTitles, year) >= MOVIE_DIRECTORY_MIN_SCORE);
        int videos = 0;
        int matchedVideos = 0;
        boolean conflictingVideo = false;
        List<JsonNode> directories = new ArrayList<>();
        for (JsonNode item : list) {
            String name = item.path("file_name").asText("");
            if (item.path("dir").asBoolean(false)) {
                if (!item.path("fid").asText("").isBlank()) directories.add(item);
            } else if (isVideoFile(name)) {
                videos++;
                Integer fileYear = extractDirectoryYear(name);
                boolean conflict = year != null && fileYear != null && !year.equals(fileYear)
                        || hasConflictingMovieSequence(name, expectedTitles);
                conflictingVideo |= conflict;
                if (!conflict && scoreMovieDirectory(name, expectedTitles, year) >= MOVIE_DIRECTORY_MIN_SCORE
                        && (year == null || year.equals(fileYear) || trustedTitle)) {
                    matchedVideos++;
                }
            }
        }
        boolean yearAnchoredDirectory = year != null && year.equals(directoryYear);
        if (videos > 0 && !collection && !wrongDirectory && !conflictingVideo
                && (trustedTitle || matchedVideos > 0 && (matchedVideos == videos || yearAnchoredDirectory))) {
            // If unverified subdirectories remain, copy only the proven direct videos.
            // In particular, never recursively save a parent containing another film.
            return new MovieShareSelection(currentShareUrl,
                    currentShareUrl.contains("#/list/share/") && directories.isEmpty());
        }
        if (depth >= MOVIE_DIRECTORY_MAX_DEPTH) return null;
        directories.sort(Comparator.comparingInt((JsonNode item) -> {
            String name = item.path("file_name").asText("");
            int score = scoreMovieDirectory(name, expectedTitles, year);
            return year != null && year.equals(extractDirectoryYear(name)) ? score + 100 : score;
        }).reversed());
        for (JsonNode directory : directories) {
            String fid = directory.path("fid").asText();
            MovieShareSelection selected = findMovieDirectory(
                    baseShareUrl, baseShareUrl + "#/list/share/" + fid,
                    directory.path("file_name").asText(""), expectedTitles, year,
                    trustedTitle && !conflictingVideo, depth + 1, visited);
            if (selected != null) return selected;
        }
        return null;
    }

    private boolean hasConflictingMovieSequence(String name, Set<String> expectedTitles) {
        String normalized = normalizeDirectoryTitle(name);
        boolean conflicting = false;
        for (String title : expectedTitles) {
            int start = normalized.indexOf(title);
            if (start < 0) continue;
            if (matchesMovieTitle(name, title)) return false;
            conflicting = true;
        }
        return conflicting;
    }

    private boolean matchesMovieTitle(String name, String title) {
        // Preserve token boundaries while allowing release-name punctuation.
        // Normalizing first would concatenate "2.2016" and hide a sequel number.
        String separators = "[\\s\\p{Punct}，。！？、：；（）《》【】「」『』]*";
        StringBuilder expression = new StringBuilder();
        for (int index = 0; index < title.length(); index++) {
            if (index > 0) expression.append(separators);
            expression.append(Pattern.quote(title.substring(index, index + 1)));
        }
        Matcher match = Pattern.compile(expression.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(name);
        while (match.find()) {
            if (Character.isDigit(title.charAt(title.length() - 1)) && match.end() < name.length()
                    && Character.isDigit(name.charAt(match.end()))) continue;
            String suffix = name.substring(match.end()).replaceFirst("^[\\s\\p{Punct}，。！？、：；（）《》【】]+", "");
            Matcher number = Pattern.compile("^([0-9]+)").matcher(suffix);
            if (number.find() && number.group(1).length() <= 2
                    && !"1".equals(number.group(1)) && !suffix.matches("(?i)^(?:4|8)k.*")) continue;
            if (suffix.matches("^第?[二两三四五六七八九十]部.*")) continue;
            return true;
        }
        return false;
    }

    private boolean hasConflictingMoviePart(String directoryName, Set<String> titles) {
        Matcher part = Pattern.compile("^\\s*第?\\s*([0-9]+|[一二两三四五六七八九十])\\s*部").matcher(directoryName);
        if (!part.find()) return false;
        String value = part.group(1);
        int number;
        if (value.matches("[0-9]+")) {
            try { number = Integer.parseInt(value); }
            catch (NumberFormatException invalid) { return true; }
        } else {
            number = "两".equals(value) ? 2 : "一二三四五六七八九十".indexOf(value) + 1;
        }
        Set<Integer> expected = new HashSet<>();
        for (String title : titles) {
            Matcher suffix = Pattern.compile("(?<![0-9])([0-9]{1,2})$").matcher(title);
            if (suffix.find()) expected.add(Integer.parseInt(suffix.group(1)));
        }
        if (expected.isEmpty()) expected.add(1);
        return !expected.contains(number);
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
            } else if (expectedTitle.length() >= 2 && matchesMovieTitle(directoryName, expectedTitle)) {
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
            // The upstream service owns its authenticated config. A protected environment
            // fallback may be used in memory, but never POST a Cookie back to /update.
            String fallbackCookie = firstUsableCookie(System.getenv("QUARK_COOKIE"), System.getenv("quark_cookie"));
            if (fallbackCookie != null) {
                return fallbackCookie;
            }
            throw new IllegalStateException("quark-auto-save cookie is not configured; configure the upstream WebUI or QUARK_COOKIE");
        }
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
                ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
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
                && error.getMessage().startsWith("quark-auto-save config check request failed");
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

    private record ContentInspection(boolean hasVideo, boolean hasConflictingSeason) {
    }
}
