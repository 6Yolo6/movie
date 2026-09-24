package com.gying.movie.controller;

import com.gying.movie.dto.AuthUser;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.gying.movie.service.IQqBotService;
import com.gying.movie.utils.AuthHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated web adapter for the same bounded search/transfer workflow used by QQ. */
@RestController
@RequestMapping("/api/resource-search")
public class WebResourceSearchController {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+|magnet:\\?[^\\s<>]+", Pattern.CASE_INSENSITIVE);
    private static final Logger log = LoggerFactory.getLogger(WebResourceSearchController.class);
    private static final int MAX_JOBS = 200;
    private final Map<String, SearchJob> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "web-resource-search");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AuthHelper auth;
    private final IQqBotService qqBotService;

    public WebResourceSearchController(AuthHelper auth, IQqBotService qqBotService) {
        this.auth = auth;
        this.qqBotService = qqBotService;
    }

    @PostMapping("/query")
    public synchronized ResponseEntity<Map<String, Object>> query(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthUser user = auth.requireUser(authorization);
        String keyword = body == null || body.get("keyword") == null
                ? "" : String.valueOf(body.get("keyword")).trim();
        if (keyword.isBlank() || keyword.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1-80 个字符的影片名或候选序号");
        }
        Instant cutoff = Instant.now().minusSeconds(900);
        jobs.values().removeIf(job -> job.finishedAt != null && job.finishedAt.isBefore(cutoff));
        // A lost response or repeated click must not start a second transfer for this user.
        for (SearchJob job : jobs.values()) {
            if (job.userId.equals(user.getId()) && job.finishedAt == null) {
                return ResponseEntity.accepted().body(snapshot(job));
            }
        }
        if (jobs.size() >= MAX_JOBS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "搜索繁忙，请稍后重试");
        }
        SearchJob job = new SearchJob(user.getId());
        jobs.put(job.id, job);
        try {
            executor.execute(() -> run(job, keyword));
        } catch (RejectedExecutionException error) {
            jobs.remove(job.id);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "搜索繁忙，请稍后重试");
        }
        return ResponseEntity.accepted().body(snapshot(job));
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> getJob(@PathVariable String jobId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthUser user = auth.requireUser(authorization);
        SearchJob job = jobs.get(jobId);
        if (job == null || !job.userId.equals(user.getId())
                || (job.finishedAt != null && job.finishedAt.isBefore(Instant.now().minusSeconds(900)))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "搜索任务已过期或服务已重启，请重新搜索影片");
        }
        return snapshot(job);
    }

    private void run(SearchJob job, String keyword) {
        job.status = "RUNNING";
        try {
            String reply = qqBotService.buildSearchReply(keyword, "web:" + job.userId);
            job.result = Map.of("reply", reply == null ? "" : reply, "links", extractLinks(reply));
            job.status = "SUCCEEDED";
        } catch (Exception error) {
            // Upstream exception bodies can contain private URLs or credentials.
            log.warn("Web resource search failed: type={}", error.getClass().getSimpleName());
            job.result = Map.of("message", "资源查询暂时不可用，请稍后重新搜索影片；不要重复提交转存序号。");
            job.status = "FAILED";
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    private Map<String, Object> snapshot(SearchJob job) {
        // Read status first: its volatile write publishes the completed result.
        String status = job.status;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.id);
        result.put("status", status);
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) result.putAll(job.result);
        return result;
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    private static final class SearchJob {
        final String id = UUID.randomUUID().toString();
        final Long userId;
        volatile String status = "QUEUED";
        volatile Instant finishedAt;
        Map<String, Object> result = Map.of();
        SearchJob(Long userId) { this.userId = userId; }
    }

    private List<Map<String, String>> extractLinks(String reply) {
        List<Map<String, String>> links = new ArrayList<>();
        if (reply == null || reply.isBlank()) return links;
        Matcher matcher = URL.matcher(reply);
        while (matcher.find() && links.size() < 10) {
            String value = matcher.group().replaceAll("[\\s\\],；。]+$", "");
            if (value.isBlank() || links.stream().anyMatch(item -> value.equals(item.get("url")))) continue;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("url", value);
            item.put("name", "资源链接 " + (links.size() + 1));
            links.add(item);
        }
        return links;
    }
}
