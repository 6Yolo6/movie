package com.gying.movie.utils;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import org.junit.jupiter.api.Test;

class BaiduTemporaryScopeTest {
    private static final String SCOPE = "QQ_BOT:11111111-1111-1111-1111-111111111111";
    private BaiduTransferTask task() {
        BaiduTransferTask task = new BaiduTransferTask(); task.setId(7L); task.setScopeKey(SCOPE); task.setSearchRequestHash("a".repeat(64));
        task.setRequestPayload(BaiduTemporaryScope.payload(SCOPE, task.getSearchRequestHash(), 9L, "/GYing QQ Temp/baidu-7"));
        return task;
    }
    @Test void markerBindsTheExactScopeRequestAndTaskDirectory() {
        BaiduTransferTask task = task();
        assertTrue(BaiduTemporaryScope.isTemporary(task)); assertFalse(BaiduTemporaryScope.isPermanent(task));
        assertEquals("/GYing QQ Temp/baidu-7", BaiduTemporaryScope.requireTarget(task, "/GYing QQ Temp/baidu-7"));
        assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.requireTarget(task, "/GYing QQ Temp/baidu-8"));
        task.setSearchRequestHash("b".repeat(64));
        assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.requireTarget(task, "/GYing QQ Temp/baidu-7"));
    }
    @Test void coercibleOrAdditionalFieldsCannotBypassMarkerValidation() throws Exception {
        BaiduTransferTask task = task(); ObjectMapper mapper = new ObjectMapper();
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getRequestPayload());
        data.put("sourceDiscoveryId", "9"); task.setRequestPayload(data.toString());
        assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.requireTarget(task, "/GYing QQ Temp/baidu-7"));
        final BaiduTransferTask extra = task();
        data = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(extra.getRequestPayload());
        data.put("extra", true); extra.setRequestPayload(data.toString());
        assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.requireTarget(extra, "/GYing QQ Temp/baidu-7"));
    }
    @Test void permanentScopeRejectsUnknownScopeAndTemporaryMarker() {
        BaiduTransferTask task = new BaiduTransferTask(); assertTrue(BaiduTemporaryScope.isPermanent(task));
        task.setScopeKey(null); assertFalse(BaiduTemporaryScope.isPermanent(task));
        task.setScopeKey("PERMANENT"); task.setRequestPayload(QqTransferMarker.payload("/GYing QQ Temp/baidu-7"));
        assertFalse(BaiduTemporaryScope.isPermanent(task));
        ResourceDiscoveryResult clone = new ResourceDiscoveryResult(); clone.setSourceRef("BAIDU_TEMP:corrupt");
        assertTrue(BaiduTemporaryScope.isTemporaryDiscovery(clone));
    }
    @Test void requestIdentityIsStablePerUserAndSourceAndRejectsAnonymousAllocation() {
        String hash = "a".repeat(64);
        assertEquals(BaiduTemporaryScope.requestHash("web:1", "movie", hash), BaiduTemporaryScope.requestHash("web:1", "movie", hash));
        assertNotEquals(BaiduTemporaryScope.requestHash("web:1", "movie", hash), BaiduTemporaryScope.requestHash("web:2", "movie", hash));
        assertNotEquals(BaiduTemporaryScope.requestHash("web:1", "movie", hash), BaiduTemporaryScope.requestHash("web:1", "movie", "b".repeat(64)));
        assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.requestHash("", "movie", hash));
    }
    @Test void retentionIsAnExplicitTaskAllocationValueWithTheDefaultPreserved() {
        BaiduTransferTask task = task(); assertEquals(10, BaiduTemporaryScope.retentionMinutes(task));
        task.setRequestPayload(BaiduTemporaryScope.payload(SCOPE, task.getSearchRequestHash(), 9L, "/GYing QQ Temp/baidu-7", 60));
        assertEquals(60, BaiduTemporaryScope.retentionMinutes(task));
        assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.payload(SCOPE, task.getSearchRequestHash(), 9L, "/GYing QQ Temp/baidu-7", 0));
        assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.payload(SCOPE, task.getSearchRequestHash(), 9L, "/GYing QQ Temp/baidu-7", 10081));
    }
    @Test void retentionRejectsMissingCoercibleFractionalAndOutOfRangeValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String value : java.util.List.of("null", "\"10\"", "10.0", "0", "10081", "2147483648")) {
            BaiduTransferTask task = task();
            var data = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getRequestPayload());
            data.set("retentionMinutes", mapper.readTree(value)); task.setRequestPayload(data.toString());
            assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.retentionMinutes(task));
            assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.requireTarget(task, "/GYing QQ Temp/baidu-7"));
        }
        BaiduTransferTask task = task();
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getRequestPayload());
        data.remove("retentionMinutes"); task.setRequestPayload(data.toString());
        assertThrows(IllegalStateException.class, () -> BaiduTemporaryScope.retentionMinutes(task));
    }
}
