package com.gying.movie.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted scope and exact task directory; this context contains no account credentials. */
public final class BaiduTemporaryScope {
    public static final String PERMANENT = "PERMANENT";
    public static final String DISCOVERY_PREFIX = "BAIDU_TEMP:";
    public static final String RESOURCE_SOURCE = "BAIDU_TEMP";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE_PATTERN = "QQ_BOT:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private BaiduTemporaryScope() {}

    public static boolean isPermanent(BaiduTransferTask task) {
        return task != null && PERMANENT.equals(task.getScopeKey())
                && !QqTransferMarker.isTemporary(task.getRequestPayload());
    }

    public static boolean isTemporary(BaiduTransferTask task) {
        return task != null && task.getScopeKey() != null && task.getScopeKey().matches(SCOPE_PATTERN);
    }

    public static boolean isTemporaryDiscovery(ResourceDiscoveryResult discovery) {
        // An invalid scoped clone remains excluded from permanent consumers.
        return discovery != null && discovery.getSourceRef() != null
                && discovery.getSourceRef().startsWith(DISCOVERY_PREFIX);
    }

    public static String payload(String scope, String requestHash, Long sourceDiscoveryId, String target) {
        return payload(scope, requestHash, sourceDiscoveryId, target, 10);
    }

    public static String payload(String scope, String requestHash, Long sourceDiscoveryId, String target, int retentionMinutes) {
        if (scope == null || !scope.matches(SCOPE_PATTERN) || !hash(requestHash)
                || sourceDiscoveryId == null || sourceDiscoveryId <= 0 || target == null
                || retentionMinutes < 1 || retentionMinutes > 10080) invalid();
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("origin", QqTransferMarker.ORIGIN);
            data.put("scopeKey", scope);
            data.put("requestHash", requestHash);
            data.put("sourceDiscoveryId", sourceDiscoveryId);
            data.put("targetPath", target);
            data.put("retentionMinutes", retentionMinutes);
            return MAPPER.writeValueAsString(data);
        } catch (Exception error) {
            throw new IllegalStateException("BAIDU_TEMPORARY_CONTEXT_INVALID");
        }
    }

    public static String requireTarget(BaiduTransferTask task, String expectedTarget) {
        if (!isTemporary(task) || task.getId() == null || task.getId() <= 0
                || !hash(task.getSearchRequestHash()) || expectedTarget == null) invalid();
        try {
            JsonNode data = MAPPER.readTree(task.getRequestPayload());
            if (data == null || !data.isObject() || data.size() != 6
                    || !text(data, "origin", QqTransferMarker.ORIGIN)
                    || !text(data, "scopeKey", task.getScopeKey())
                    || !text(data, "requestHash", task.getSearchRequestHash())
                    || !text(data, "targetPath", expectedTarget)
                    || !data.path("sourceDiscoveryId").isIntegralNumber()
                    || !data.path("sourceDiscoveryId").canConvertToLong()
                    || data.path("sourceDiscoveryId").longValue() <= 0
                    || !data.path("retentionMinutes").isIntegralNumber()
                    || !data.path("retentionMinutes").canConvertToInt()
                    || data.path("retentionMinutes").intValue() < 1
                    || data.path("retentionMinutes").intValue() > 10080) invalid();
            return expectedTarget;
        } catch (Exception invalid) {
            throw new IllegalStateException("BAIDU_TEMPORARY_CONTEXT_INVALID");
        }
    }

    /** Retention is allocated with the task, so delayed confirmation compensation cannot extend it. */
    public static int retentionMinutes(BaiduTransferTask task) {
        try {
            JsonNode data = MAPPER.readTree(task.getRequestPayload());
            if (data == null || !data.path("targetPath").isTextual()) invalid();
            requireTarget(task, data.path("targetPath").textValue());
            return data.path("retentionMinutes").intValue();
        } catch (Exception error) {
            throw new IllegalStateException("BAIDU_TEMPORARY_CONTEXT_INVALID");
        }
    }

    public static String requestHash(String userKey, String movieId, String sourceHash) {
        if (userKey == null || userKey.isBlank() || userKey.length() > 512
                || movieId == null || movieId.isBlank() || !hash(sourceHash)) invalid();
        try { return ResourceHubHashUtils.sha256(MAPPER.writeValueAsString(java.util.List.of(userKey, movieId, sourceHash))); }
        catch (Exception error) { throw new IllegalStateException("BAIDU_TEMPORARY_CONTEXT_INVALID"); }
    }

    private static boolean text(JsonNode data, String name, String expected) {
        return data.path(name).isTextual() && expected.equals(data.path(name).textValue());
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void invalid() { throw new IllegalStateException("BAIDU_TEMPORARY_CONTEXT_INVALID"); }
}
