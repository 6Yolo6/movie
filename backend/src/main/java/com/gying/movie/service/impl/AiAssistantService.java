package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Bounded, user-isolated queue. The loopback agent claims only catalog questions. */
@Service
public class AiAssistantService {
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private Instant bridgeSeen = Instant.EPOCH;
    public Map<String, Object> interpret(String message) {
        String id;
        synchronized (this) {
            expire();
            if (!(boolean) availability().get("available")) return Map.of();
            if (message == null || message.isBlank() || message.length()>180 || jobs.size()>=200 || jobs.values().stream().filter(j -> !j.done()).count()>=8) return Map.of();
            ObjectNode request=mapper.createObjectNode().put("message",message).put("purpose","search");
            Job job=new Job(0L,request,clock.instant()); jobs.put(job.id,job); id=job.id;
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
        while(System.nanoTime()<deadline) {
            synchronized(this) {
                Job job=jobs.get(id);
                if(job==null) return Map.of();
                if(job.done()) { jobs.remove(id); return job.result==null?Map.of():Map.of("keyword",job.result.path("keyword").asText()); }
            }
            try { Thread.sleep(200); } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        synchronized(this) { jobs.remove(id); }
        return Map.of();
    }
    @Autowired
    public AiAssistantService(ObjectMapper mapper) { this(mapper, Clock.systemUTC()); }
    AiAssistantService(ObjectMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    public synchronized void heartbeat() { bridgeSeen = clock.instant(); }
    public synchronized Map<String, Object> availability() {
        expire();
        return Map.of("available", clock.instant().isBefore(bridgeSeen.plusSeconds(15)),
                "busy", jobs.values().stream().anyMatch(j -> j.status.equals("RUNNING")));
    }
    public synchronized Map<String, Object> submit(Long userId, JsonNode input) {
        expire();
        JsonNode request = validate(input);
        for (Job job : jobs.values()) if (job.user.equals(userId) && !job.done()) return view(job);
        if (!(boolean) availability().get("available")) fail(503, "本机 AI 助手暂未连接，请稍后再试");
        if (jobs.size() >= 200 || jobs.values().stream().filter(j -> !j.done()).count() >= 8)
            fail(429, "AI 助手繁忙，请稍后再试");
        Job job = new Job(userId, request, clock.instant());
        jobs.put(job.id, job);
        return view(job);
    }
    public synchronized Map<String, Object> get(Long userId, String id) {
        expire();
        Job job = jobs.get(id);
        if (job == null || !job.user.equals(userId)) fail(404, "该问答已失效，请重新提问");
        return view(job);
    }
    public synchronized Map<String, Object> claim() {
        expire();
        bridgeSeen = clock.instant();
        if (jobs.values().stream().anyMatch(j -> j.status.equals("RUNNING"))) return Map.of();
        for (Job job : jobs.values()) if (job.status.equals("PENDING")) {
            job.status = "RUNNING"; job.started = clock.instant(); job.lease = UUID.randomUUID().toString();
            return Map.of("jobId", job.id, "lease", job.lease, "request", job.request.deepCopy());
        }
        return Map.of();
    }
    public synchronized void complete(String id, String lease, JsonNode result) {
        expire();
        Job job = jobs.get(id);
        if (job == null || !job.status.equals("RUNNING") || !Objects.equals(job.lease, lease))
            fail(409, "问答领取状态已失效");
        if (job.user.equals(0L)) {
            String raw=job.request.path("message").asText("").toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]", "");
            String keyword=result==null?"":result.path("keyword").asText("");
            String extracted=keyword.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]", "");
            if(keyword.isBlank() || keyword.length()>60 || !raw.contains(extracted) || !keyword.matches("[\\p{L}\\p{N} ·:：()（）_-]+")) {
                job.status="FAILED";
            } else { job.status="SUCCEEDED"; job.result=mapper.createObjectNode().put("keyword",keyword); }
            job.request=null; job.finished=clock.instant(); return;
        }
        if (result == null || !result.isObject() || result.toString().length() > 200000
                || !"gying".equals(result.path("scope").asText())) fail(400, "无效的片库回答");
        ObjectNode output = mapper.createObjectNode();
        output.put("answer", result.path("answer").asText("").substring(0, Math.min(12000, result.path("answer").asText("").length())));
        var evidence = output.putArray("evidence");
        boolean toolFailed = false;
        for (JsonNode step : result.path("trace")) {
            if (!"tool".equals(step.path("kind").asText())) continue;
            String name = step.path("name").asText();
            if (!Set.of("searchMovies", "getMovieDetails").contains(name)) fail(400, "回答包含未授权工具");
            JsonNode tool = step.path("result");
            if (!tool.path("ok").asBoolean()) { toolFailed = true; continue; }
            JsonNode data = tool.path("data");
            ObjectNode entry = evidence.addObject().put("tool", name).put("queriedAt", data.path("queriedAt").asText());
            for (String key : List.of("movies", "movie", "resources", "resourceSummary", "total", "returned", "truncated"))
                if (data.has(key)) entry.set(key, data.get(key).deepCopy());
        }
        if (result.has("error") || toolFailed || evidence.isEmpty() || output.path("answer").asText().isBlank()) {
            job.status = "FAILED"; job.message = "AI 查询未完成，请稍后重试或缩短问题";
        } else { job.status = "SUCCEEDED"; job.result = output; }
        job.finished = clock.instant();
        // Do not retain question/history after the request has completed.
        job.request = null;
    }
    private JsonNode validate(JsonNode input) {
        if (input == null || !input.isObject()) fail(400, "请输入有效问题");
        var fields = input.fieldNames();
        while (fields.hasNext()) if (!Set.of("message", "history").contains(fields.next())) fail(400, "请求包含未知字段");
        JsonNode message = input.path("message");
        if (!message.isTextual() || message.asText().isBlank() || message.asText().length() > 1000) fail(400, "问题需为 1–1000 个字符");
        JsonNode history = input.path("history");
        if (!history.isMissingNode() && (!history.isArray() || history.size() > 8 || history.size() % 2 != 0)) fail(400, "无效的对话历史");
        int total = message.asText().length();
        for (int i = 0; i < history.size(); i++) {
            JsonNode item = history.get(i);
            if (!item.isObject() || item.size() != 2 || !item.path("role").asText().equals(i % 2 == 0 ? "user" : "assistant")
                    || !item.path("content").isTextual() || item.path("content").asText().isBlank()
                    || item.path("content").asText().length() > 6000) fail(400, "无效的对话历史");
            total += item.path("content").asText().length();
        }
        if (total > 10000) fail(400, "对话过长，请开启新对话");
        ObjectNode request = mapper.createObjectNode().put("message", message.asText().trim()).put("scope", "gying");
        request.set("history", history.isMissingNode() ? mapper.createArrayNode() : history.deepCopy());
        return request;
    }
    public JsonNode validatedRequest(JsonNode input) { return validate(input); }
    private void expire() {
        Instant now = clock.instant();
        jobs.values().removeIf(j -> j.finished != null && now.isAfter(j.finished.plusSeconds(900)));
        for (Job j : jobs.values()) {
            if ((!j.done() && now.isAfter(j.created.plusSeconds(240)))
                    || (j.status.equals("RUNNING") && now.isAfter(j.started.plusSeconds(210)))) {
                j.status = "FAILED"; j.message = "AI 查询超时，请重新提问"; j.finished = now; j.request = null;
            }
        }
    }
    private Map<String, Object> view(Job j) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", j.id); view.put("status", j.status);
        if (j.result != null) view.put("result", j.result.deepCopy());
        if (j.message != null) view.put("message", j.message);
        return view;
    }
    private static void fail(int status, String message) { throw new ResponseStatusException(HttpStatus.valueOf(status), message); }
    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Long user; final Instant created;
        String status = "PENDING", lease, message; Instant started, finished; JsonNode request, result;
        Job(Long user, JsonNode request, Instant created) { this.user=user; this.request=request; this.created=created; }
        boolean done() { return status.equals("SUCCEEDED") || status.equals("FAILED"); }
    }
}
