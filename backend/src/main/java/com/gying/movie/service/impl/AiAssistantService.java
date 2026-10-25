package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import com.gying.movie.utils.EncryptedAiState;
import jakarta.annotation.PreDestroy;
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
    private EncryptedAiState state;
    private JsonNode committed;
    private boolean closed;
    private static final int LEASE_SECONDS = 45;
    public Map<String, Object> interpret(String message) {
        String id;
        synchronized (this) {
            expire();
            if (!(boolean) availability().get("available")) return Map.of();
            if (message == null || message.isBlank() || message.length()>180 || jobs.size()>=200 || jobs.values().stream().filter(j -> !j.done()).count()>=8) return Map.of();
            ObjectNode request=mapper.createObjectNode().put("message",message).put("purpose","search");
            Job job=new Job(0L,request,clock.instant()); jobs.put(job.id,job); persist(); id=job.id;
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
        while(System.nanoTime()<deadline) {
            synchronized(this) {
                Job job=jobs.get(id);
                if(job==null) return Map.of();
                if(job.done()) { return job.result==null?Map.of():Map.of("keyword",job.result.path("keyword").asText()); }
            }
            try { Thread.sleep(200); } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        synchronized(this) { Job job=jobs.get(id); if(job!=null && !job.done()) { finishFailure(job,"提取超时"); persist(); } }
        return Map.of();
    }
    public AiAssistantService(ObjectMapper mapper) { this(mapper, Clock.systemUTC()); }
    @Autowired
    public AiAssistantService(ObjectMapper mapper,
            @Value("${APP_AI_STATE_FILE:}") String filename,
            @Value("${APP_AI_STATE_KEY_FILE:}") String keyFile) {
        this(mapper, Clock.systemUTC(), filename, keyFile);
    }
    AiAssistantService(ObjectMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }
    AiAssistantService(ObjectMapper mapper, Clock clock, String filename, String keyFile) {
        this(mapper, clock);
        if (filename.isBlank() != keyFile.isBlank()) throw new IllegalStateException("Both AI state settings are required");
        if (!filename.isBlank()) {
            state = new EncryptedAiState(mapper, filename, keyFile);
            try { committed = state.read(); restore(committed); expire(); }
            catch (RuntimeException e) { state.close(); throw e; }
        }
    }

    public synchronized void heartbeat() { bridgeSeen = clock.instant(); }
    public synchronized Map<String, Object> availability() {
        expire();
        return Map.of("available", clock.instant().isBefore(bridgeSeen.plusSeconds(15)),
                "busy", jobs.values().stream().anyMatch(j -> j.status.equals("RUNNING")));
    }
    public synchronized Map<String, Object> submit(Long userId, JsonNode input) {
        expire();
        JsonNode request = prepareRequest(userId,input);
        String requestKey = input.path("requestKey").asText("");
        String requestHash = fingerprint(request);
        if (!requestKey.isEmpty()) for (Job job : jobs.values()) {
            if (job.user.equals(userId) && requestKey.equals(job.requestKey)) {
                if (!requestHash.equals(job.requestHash)) fail(409, "幂等键已用于其他问题");
                return view(job);
            }
        }
        for (Job job : jobs.values()) if (job.user.equals(userId) && !job.done()) return view(job);
        if (!(boolean) availability().get("available")) fail(503, "本机 AI 助手暂未连接，请稍后再试");
        if (jobs.size() >= 200 || jobs.values().stream().filter(j -> !j.done()).count() >= 8)
            fail(429, "AI 助手繁忙，请稍后再试");
        Job job = new Job(userId, request, clock.instant());
        job.requestKey = requestKey; job.requestHash = requestHash;
        jobs.put(job.id, job);
        persist();
        return view(job);
    }
    public synchronized Map<String, Object> get(Long userId, String id) {
        expire();
        Job job = jobs.get(id);
        if (job == null || !job.user.equals(userId)) fail(404, "该问答已失效，请重新提问");
        return view(job);
    }
    public synchronized Map<String,Object> claim() { return claim(false); }
    public synchronized Map<String, Object> claim(boolean renewable) {
        expire();
        bridgeSeen = clock.instant();
        if (jobs.values().stream().anyMatch(j -> j.status.equals("RUNNING"))) return Map.of();
        for (Job job : jobs.values()) if (job.status.equals("PENDING")) {
            job.status = "RUNNING"; job.started = clock.instant(); job.lease = UUID.randomUUID().toString();
            job.attempt++; job.renewable=renewable; job.leaseUntil = clock.instant().plusSeconds(renewable?LEASE_SECONDS:210);
            persist();
            return Map.of("jobId", job.id, "lease", job.lease, "attempt", job.attempt,
                    "leaseExpiresAt", job.leaseUntil.toString(), "deadline", job.created.plusSeconds(240).toString(),
                    "request", job.request.deepCopy());
        }
        return Map.of();
    }
    public synchronized void complete(String id, String lease, JsonNode result) {
        expire();
        Job job = jobs.get(id);
        if (job == null || !Objects.equals(job.lease, lease)) fail(409, "问答领取状态已失效");
        String receipt = fingerprint(result);
        if (job.done() && Objects.equals(job.receiptHash, receipt)) return;
        if (!job.status.equals("RUNNING")) fail(409, "问答领取状态已失效");
        if (job.user.equals(0L)) {
            String raw=job.request.path("message").asText("").toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]", "");
            String keyword=result==null?"":result.path("keyword").asText("");
            String extracted=keyword.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]", "");
            if(keyword.isBlank() || keyword.length()>60 || !raw.contains(extracted) || !keyword.matches("[\\p{L}\\p{N} ·:：()（）_-]+")) {
                job.status="FAILED";
            } else { job.status="SUCCEEDED"; job.result=mapper.createObjectNode().put("keyword",keyword); }
            job.request=null; job.finished=clock.instant(); job.receiptHash=receipt; persist(); return;
        }
        if (result == null || !result.isObject() || result.toString().length() > 200000
                || !"gying".equals(result.path("scope").asText())) fail(400, "无效的片库回答");
        ObjectNode output = mapper.createObjectNode();
        output.put("answer", result.path("answer").asText("").substring(0, Math.min(12000, result.path("answer").asText("").length())));
        if(result.path("requestId").asText().matches("[A-Za-z0-9-]{1,64}"))output.put("requestId",result.path("requestId").asText());
        var safeTrace=output.putArray("trace");
        for(JsonNode step:result.path("trace")) {
            if(safeTrace.size()>=40)break;
            if(!Set.of("model","tool","error","planning","retrieval","routing").contains(step.path("kind").asText()))continue;
            var entry=safeTrace.addObject();
            for(String key:List.of("kind","name","status","timestamp"))entry.put(key,step.path(key).asText().substring(0,Math.min(100,step.path(key).asText().length())));
            if(step.path("durationMs").isNumber())entry.put("durationMs",Math.max(0,step.path("durationMs").asLong()));
        }
        var evidence = output.putArray("evidence");
        boolean toolFailed = false;
        for (JsonNode step : result.path("trace")) {
            if (!"tool".equals(step.path("kind").asText())) continue;
            String name = step.path("name").asText();
            if (!Set.of("searchMovies", "getMovieDetails", "searchMovieWeb", "queryCatalog").contains(name)) fail(400, "回答包含未授权工具");
            JsonNode tool = step.path("result");
            if (!tool.path("ok").asBoolean()) { toolFailed = true; continue; }
            JsonNode data = tool.path("data");
            if(data.path("superseded").asBoolean())continue;
            ObjectNode entry = evidence.addObject().put("tool", name).put("queriedAt", data.path("queriedAt").asText());
            for (String key : List.of("movies", "movie", "resources", "resourceSummary", "total", "returned", "truncated", "webResults", "unverifiedConditions", "retrieval", "relevanceChecked", "recalledTotal", "unresolvedPeople"))
                if (data.has(key)) entry.set(key, data.get(key).deepCopy());
        }
        if(result.path("queryPlan").isObject())output.set("queryPlan",MovieQueryPlan.parse(result.path("queryPlan"),mapper).json());
        if(result.path("partial").asBoolean())output.put("partial",true);
        if (result.has("error") || toolFailed && !result.path("partial").asBoolean() || evidence.isEmpty() || output.path("answer").asText().isBlank()) {
            job.status = "FAILED"; job.message = "AI 查询未完成，请稍后重试或缩短问题";
            var error=output.putObject("error");String code=result.path("error").path("code").asText("TOOL_EXECUTION_FAILED");
            error.put("code",code.matches("[A-Z_]{1,80}")?code:"AI_EXECUTION_FAILED");job.result=output;
        } else { job.status = "SUCCEEDED"; job.result = output; }
        job.finished = clock.instant();
        // Do not retain question/history after the request has completed.
        job.request = null;
        job.receiptHash = receipt;
        persist();
    }
    public synchronized Map<String, Object> renew(String id, String lease) {
        expire(); Job job=jobs.get(id);
        if (job==null || !Objects.equals(job.lease,lease)) fail(409,"问答领取状态已失效");
        // A lost completion acknowledgement must survive a concurrent renewal before redelivery.
        if(job.done() && job.receiptHash!=null)return Map.of("ok",true,"completed",true);
        if(!"RUNNING".equals(job.status))fail(409,"问答领取状态已失效");
        job.leaseUntil = clock.instant().plusSeconds(LEASE_SECONDS);
        persist();
        return Map.of("ok",true,"leaseExpiresAt",job.leaseUntil.toString());
    }
    public synchronized Map<String,Object> cancel(Long userId, String id) {
        expire(); Job job=jobs.get(id);
        if (job==null || !job.user.equals(userId)) fail(404,"该问答已失效，请重新提问");
        if (!job.done()) { finishFailure(job,"问答已取消"); persist(); }
        return view(job);
    }
    private JsonNode validate(JsonNode input) {
        if (input == null || !input.isObject()) fail(400, "请输入有效问题");
        var fields = input.fieldNames();
        while (fields.hasNext()) if (!Set.of("message", "history", "requestKey", "previousJobId").contains(fields.next())) fail(400, "请求包含未知字段");
        if (input.has("requestKey") && (!input.path("requestKey").isTextual()
                || !input.path("requestKey").asText().matches("[A-Za-z0-9_-]{8,64}"))) fail(400,"无效的幂等键");
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
    public synchronized JsonNode prepareRequest(Long userId,JsonNode input) {
        ObjectNode request=(ObjectNode)validate(input);
        if(input.has("previousJobId")) {
            if(!input.path("previousJobId").isTextual()||!input.path("previousJobId").asText().matches("[a-f0-9-]{36}"))fail(400,"无效的上一轮问答引用");
            expire();Job previous=jobs.get(input.path("previousJobId").asText());
            if(previous==null||!previous.user.equals(userId)||!previous.status.equals("SUCCEEDED")||previous.result==null)fail(404,"上一轮问答已失效，请重新提问");
            ObjectNode context=mapper.createObjectNode();
            if(previous.result.path("queryPlan").isObject())context.set("queryPlan",previous.result.path("queryPlan").deepCopy());
            else if(previous.result.path("catalog").path("keyword").isTextual())context.putObject("queryPlan").put("intent","search").putArray("titles").add(previous.result.path("catalog").path("keyword"));
            var ids=context.putArray("candidateIds");var candidates=context.putArray("candidates");Set<String> seen=new HashSet<>();
            for(JsonNode evidence:previous.result.path("evidence")) {
                List<JsonNode> movies=new ArrayList<>();evidence.path("movies").forEach(movies::add);if(evidence.path("movie").isObject())movies.add(evidence.path("movie"));
                for(JsonNode movie:movies)if(ids.size()<30&&seen.add(movie.path("id").asText())) {
                    ids.add(movie.path("id").asText());var entry=candidates.addObject();for(String k:List.of("id","titleCn","titleEn","year"))if(movie.has(k))entry.set(k,movie.get(k).deepCopy());
                }
            }
            request.set("context",context);
        }
        return request;
    }
    public synchronized Map<String,Object> rememberDirect(Long userId,JsonNode input,Map<String,Object> response) {
        expire();JsonNode request=prepareRequest(userId,input);String requestKey=input.path("requestKey").asText("");String hash=fingerprint(request);
        if(!requestKey.isEmpty())for(Job old:jobs.values())if(old.user.equals(userId)&&requestKey.equals(old.requestKey)){
            if(!hash.equals(old.requestHash))fail(409,"幂等键已用于其他问题");return view(old);
        }
        if(jobs.size()>=200)fail(429,"问答记录已满，请稍后再试");
        Job job=new Job(userId,null,clock.instant());job.id=Objects.toString(response.get("jobId"),UUID.randomUUID().toString());job.status="SUCCEEDED";job.finished=clock.instant();job.result=mapper.valueToTree(response.get("result"));job.requestKey=requestKey;job.requestHash=hash;
        jobs.put(job.id,job);persist();return view(job);
    }
    public synchronized JsonNode runningContext(String id,String lease) {
        if(id==null||id.isBlank())return mapper.createObjectNode();
        expire();Job job=jobs.get(id);if(job==null||!Objects.equals(job.lease,lease)||!job.status.equals("RUNNING"))fail(409,"问答执行上下文已失效");
        return job.request.path("context").isObject()?job.request.path("context").deepCopy():mapper.createObjectNode();
    }
    public JsonNode validatedRequest(JsonNode input) { return validate(input); }
    @Scheduled(fixedDelay = 10000)
    public synchronized void maintain() { if (!closed) expire(); }
    private void expire() {
        if (closed) throw new IllegalStateException("AI state is closed");
        Instant now = clock.instant();
        boolean changed = jobs.values().removeIf(j -> j.finished != null && !now.isBefore(j.finished.plusSeconds(900)));
        for (Job j : jobs.values()) {
            if (j.done()) continue;
            if (!now.isBefore(j.created.plusSeconds(240))) {
                finishFailure(j,"AI 查询超时，请重新提问"); changed=true;
            } else if (j.status.equals("RUNNING") && !now.isBefore(j.leaseUntil)) {
                if (!j.renewable || j.attempt >= 2) finishFailure(j,"AI 执行租约已失效，请重新提问");
                else { j.status="PENDING"; j.lease=null; j.leaseUntil=null; j.started=null; }
                changed=true;
            }
        }
        if (changed) persist();
    }
    private void finishFailure(Job job,String message) {
        job.status="FAILED"; job.message=message; job.finished=clock.instant(); job.request=null;
    }
    private String fingerprint(JsonNode value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(value))); }
        catch (Exception e) { throw new IllegalStateException("Cannot fingerprint AI request",e); }
    }
    private ObjectNode snapshot() {
        ObjectNode output=mapper.createObjectNode().put("version",1);
        var array=output.putArray("jobs");
        for(Job j:jobs.values()) {
            var item=array.addObject().put("id",j.id).put("user",j.user).put("created",j.created.toString())
                    .put("status",j.status).put("attempt",j.attempt).put("renewable",j.renewable);
            for(var pair:Map.of("requestKey",Objects.toString(j.requestKey,""),"requestHash",Objects.toString(j.requestHash,""),
                    "receiptHash",Objects.toString(j.receiptHash,""),"lease",Objects.toString(j.lease,""),"message",Objects.toString(j.message,"")).entrySet())
                if(!pair.getValue().isEmpty())item.put(pair.getKey(),pair.getValue());
            if(j.started!=null)item.put("started",j.started.toString());
            if(j.finished!=null)item.put("finished",j.finished.toString());
            if(j.leaseUntil!=null)item.put("leaseUntil",j.leaseUntil.toString());
            if(j.request!=null)item.set("request",j.request.deepCopy());
            if(j.result!=null)item.set("result",j.result.deepCopy());
        }
        return output;
    }
    private void persist() {
        if(state==null)return;
        JsonNode next=snapshot();
        try { state.write(next); committed=next; }
        catch(RuntimeException e) { restore(committed); throw e; }
    }
    private void restore(JsonNode saved) {
        jobs.clear();
        if(saved.isEmpty())return;
        if(saved.path("version").asInt()!=1 || !saved.path("jobs").isArray() || saved.path("jobs").size()>200)
            throw new IllegalStateException("Invalid AI queue snapshot");
        for(JsonNode item:saved.path("jobs")) {
            Job j=new Job(item.path("user").asLong(),item.get("request"),Instant.parse(item.path("created").asText()));
            j.id=item.path("id").asText(); j.status=item.path("status").asText(); j.attempt=item.path("attempt").asInt(); j.renewable=item.path("renewable").asBoolean();
            if(!j.id.matches("[a-f0-9-]{36}") || !Set.of("PENDING","RUNNING","SUCCEEDED","FAILED").contains(j.status))
                throw new IllegalStateException("Invalid AI job snapshot");
            j.lease=text(item,"lease"); j.message=text(item,"message"); j.requestKey=text(item,"requestKey");
            j.requestHash=text(item,"requestHash"); j.receiptHash=text(item,"receiptHash"); j.result=item.get("result");
            j.started=instant(item,"started"); j.finished=instant(item,"finished"); j.leaseUntil=instant(item,"leaseUntil");
            if(j.status.equals("RUNNING") && (j.leaseUntil==null || j.lease==null))throw new IllegalStateException("Missing AI lease");
            if(!j.done() && j.request==null)throw new IllegalStateException("Missing AI request");
            if(j.done() && j.finished==null)throw new IllegalStateException("Missing AI completion time");
            if(jobs.put(j.id,j)!=null)throw new IllegalStateException("Duplicate AI job");
        }
    }
    private String text(JsonNode item,String key) { return item.has(key)?item.path(key).asText():null; }
    private Instant instant(JsonNode item,String key) { return item.has(key)?Instant.parse(item.path(key).asText()):null; }
    @PreDestroy public synchronized void close() { if(!closed) { if(state!=null)state.close(); closed=true; } }
    private Map<String, Object> view(Job j) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", j.id); view.put("status", j.status); view.put("attempt",j.attempt);
        if (j.result != null) view.put("result", j.result.deepCopy());
        if (j.message != null) view.put("message", j.message);
        return view;
    }
    private static void fail(int status, String message) { throw new ResponseStatusException(HttpStatus.valueOf(status), message); }
    private static final class Job {
        String id = UUID.randomUUID().toString();
        final Long user; final Instant created;
        String status = "PENDING", lease, message, requestKey, requestHash, receiptHash; int attempt; boolean renewable; Instant started, finished, leaseUntil; JsonNode request, result;
        Job(Long user, JsonNode request, Instant created) { this.user=user; this.request=request; this.created=created; }
        boolean done() { return status.equals("SUCCEEDED") || status.equals("FAILED"); }
    }
}
