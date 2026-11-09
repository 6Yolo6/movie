package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transport fixtures prove safety/order; these tests never access a real Baidu account. */
class BaiduTemporaryCleanupClientTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    final String target="/GYing QQ Temp/baidu-7";
    ResourceHubProperties properties; Fake transport; BaiduWebClient client;
    @BeforeEach void setup() {
        properties=new ResourceHubProperties();
        properties.getBaidu().setSessionStatePath(temp.resolve("private/session.enc").toString());
        properties.getBaidu().setSessionKeyPath(temp.resolve("private/session.key").toString());
        var vault=new BaiduSessionVault(properties,json);
        vault.save("100","fixture",List.of(new BaiduSessionVault.Cookie("BDUSS","private-fixture-cookie",".baidu.com","/",Instant.now().getEpochSecond()+3600,true)),false);
        transport=new Fake(); client=new BaiduWebClient(properties,vault,json,transport);
        transport.owned.put("/",List.of(file("10","GYing QQ Temp","/GYing QQ Temp",0,true,null)));
        transport.owned.put("/GYing QQ Temp",List.of(file("20","baidu-7",target,0,true,null)));
        transport.owned.put(target,List.of(file("30","Movie.mkv",target+"/Movie.mkv",100,false,"opaque-web-digest")));
    }
    @Test void capturesExactDirectoryAccountAndFullOpaqueDigestSnapshotWithoutWrites() throws Exception {
        var evidence=client.captureDestinationEvidence(plan());
        assertEquals("100",evidence.accountUid()); assertEquals("20",evidence.directoryId());
        assertEquals(List.of(new BaiduWebClient.DestinationFile("Movie.mkv","30",100,false,"opaque-web-digest")),evidence.files());
        assertTrue(BaiduWebClient.matchesDestinationEvidence(plan(),evidence));
        String saved=json.writeValueAsString(evidence); assertFalse(saved.contains("private-fixture-cookie")); assertFalse(saved.contains("fixture-token"));
        assertTrue(transport.requests.stream().allMatch(r->r.method().equals("GET")));
    }
    @Test void snapshotRejectsSameSizeReplacementRootReplacementExtraFilesAndDigestChanges() {
        var evidence=client.captureDestinationEvidence(plan());
        transport.owned.put(target,List.of(file("31","Movie.mkv",target+"/Movie.mkv",100,false,"opaque-web-digest")));
        assertEquals(BaiduWebClient.DestinationState.CHANGED,client.inspectTemporaryDirectory(evidence));
        transport.owned.put(target,List.of(file("30","Movie.mkv",target+"/Movie.mkv",100,false,"changed-digest")));
        assertEquals(BaiduWebClient.DestinationState.CHANGED,client.inspectTemporaryDirectory(evidence));
        transport.owned.put(target,List.of(file("30","Movie.mkv",target+"/Movie.mkv",100,false,"opaque-web-digest"),file("32","extra.mkv",target+"/extra.mkv",1,false,null)));
        assertEquals(BaiduWebClient.DestinationState.CHANGED,client.inspectTemporaryDirectory(evidence));
        transport.owned.put("/GYing QQ Temp",List.of(file("21","baidu-7",target,0,true,null)));
        assertEquals(BaiduWebClient.DestinationState.CHANGED,client.inspectTemporaryDirectory(evidence));
        assertEquals(0,transport.posts());
    }
    @Test void invalidTaskPathsAndPermanentArchiveRootsAreRefusedBeforeNetwork() {
        var evidence=client.captureDestinationEvidence(plan()); transport.requests.clear();
        for(String path:List.of("/","/GYing QQ Temp","/GYing QQ Temp/baidu-0","/GYing QQ Temp/baidu-07","/GYing QQ Temp//baidu-7",
                "/GYing QQ Temp/../baidu-7","/GYing QQ Temp%2f/baidu-7","/GYing QQ Temp\\baidu-7","/GYing Resource Hub/baidu-7","/mv/baidu-7")) {
            var changed=new BaiduWebClient.DestinationEvidence(evidence.accountUid(),path,evidence.directoryId(),evidence.files());
            assertThrows(BaiduActionException.class,()->client.trashTemporaryDirectory(changed));
        }
        properties.getBaidu().setSavePath("/GYing QQ Temp");
        assertThrows(BaiduActionException.class,()->client.trashTemporaryDirectory(evidence)); assertEquals(0,transport.requests.size());
    }
    @Test void changedAccountAndChangedTreePreventTrashPost() {
        var evidence=client.captureDestinationEvidence(plan());
        transport.uid="999"; assertEquals("ACCOUNT_CHANGED",assertThrows(BaiduActionException.class,()->client.trashTemporaryDirectory(evidence)).code());
        transport.uid="100"; transport.owned.put(target,List.of(file("31","Movie.mkv",target+"/Movie.mkv",100,false,"opaque-web-digest")));
        assertEquals("DESTINATION_CHANGED",assertThrows(BaiduActionException.class,()->client.trashTemporaryDirectory(evidence)).code());
        assertEquals(0,transport.posts());
    }
    @Test void accountChangeDuringTheFinalPrePostGetCannotDeleteEvenAfterInitialEvidenceMatched() {
        var evidence=client.captureDestinationEvidence(plan());transport.requests.clear();transport.accountChangeOnGetInfo=4;
        assertEquals("ACCOUNT_CHANGED",assertThrows(BaiduActionException.class,()->client.trashTemporaryDirectory(evidence)).code());assertEquals(0,transport.posts());
    }
    @Test void trashUsesOneExactPathOnFixedHostAndRequiresOwnedDisappearance() throws Exception {
        var evidence=client.captureDestinationEvidence(plan()); client.trashTemporaryDirectory(evidence);
        var request=transport.requests.stream().filter(r->r.method().equals("POST")).findFirst().orElseThrow();
        assertEquals("https",request.uri().getScheme()); assertEquals("pan.baidu.com",request.uri().getHost()); assertEquals("/api/filemanager",request.uri().getPath());
        assertEquals("delete",query(request.uri().getRawQuery()).get("opera"));
        assertEquals(List.of(target),json.readValue(query(body(request)).get("filelist"),List.class));
        assertEquals(BaiduWebClient.DestinationState.MISSING,client.inspectTemporaryDirectory(evidence));
        assertEquals(new BaiduWebClient.RecycleEvidence("100",target,"20"),client.findTemporaryRecycle(evidence));
        assertEquals(1,transport.posts()); assertTrue(transport.requests.stream().noneMatch(r->r.uri().getPath().contains("clear")));
    }
    @Test void timeoutAfterTrashRetainsUncertaintyAndGetRecoveryNeverRepeatsPost() {
        var evidence=client.captureDestinationEvidence(plan()); transport.failAfterPost="/api/filemanager";
        var error=assertThrows(BaiduActionException.class,()->client.trashTemporaryDirectory(evidence)); assertTrue(error.uncertain());
        assertFalse(error.getMessage().contains("private"));
        assertEquals(BaiduWebClient.DestinationState.MISSING,client.inspectTemporaryDirectory(evidence)); assertNotNull(client.findTemporaryRecycle(evidence));
        assertEquals(1,transport.posts());
    }
    @Test void redirectsMalformedAndRejectedTrashResponsesRemainUncertainWithoutFollowingRedirects() {
        for(var response:List.of(new BaiduWebClient.WebResponse(302,Map.of("location",List.of("https://evil.example")),new byte[0]),
                ok("not json"),okJson(Map.of("errno",-6)))) {
            setup(); var evidence=client.captureDestinationEvidence(plan()); transport.postResponse=response;
            assertTrue(assertThrows(BaiduActionException.class,()->client.trashTemporaryDirectory(evidence)).uncertain());
            assertEquals(1,transport.posts()); assertTrue(transport.requests.stream().allMatch(r->r.uri().getHost().equals("pan.baidu.com")));
        }
    }
    @Test void purgeSuppliesOnlySavedNumericDirectoryIdAndPreservesUnrelatedRecycleObjects() throws Exception {
        var evidence=client.captureDestinationEvidence(plan()); client.trashTemporaryDirectory(evidence);
        transport.recycle.add(file("99","unrelated","/Other/unrelated",0,true,null));
        var recycle=client.findTemporaryRecycle(evidence); client.purgeTemporaryDirectory(evidence,recycle);
        var request=transport.requests.stream().filter(r->r.uri().getPath().equals("/api/recycle/delete")).findFirst().orElseThrow();
        assertEquals("POST",request.method()); assertEquals(List.of(20),json.readValue(query(body(request)).get("fidlist"),List.class));
        assertTrue(client.isTemporaryDirectoryPurged(evidence,recycle)); assertEquals("99",transport.recycle.get(0).get("fs_id").toString());
        assertEquals(2,transport.posts()); assertTrue(transport.requests.stream().noneMatch(r->r.uri().toString().contains("clear")));
    }
    @Test void purgeRequiresExactPersistedIdPathAndAccountAndRejectsSameNameReplacement() {
        var evidence=client.captureDestinationEvidence(plan()); client.trashTemporaryDirectory(evidence); var recycle=client.findTemporaryRecycle(evidence);
        for(var changed:List.of(new BaiduWebClient.RecycleEvidence("999",target,"20"),new BaiduWebClient.RecycleEvidence("100",target,"21"),new BaiduWebClient.RecycleEvidence("100","/Other/baidu-7","20"))) {
            assertThrows(BaiduActionException.class,()->client.purgeTemporaryDirectory(evidence,changed));
        }
        transport.recycle.clear(); transport.recycle.add(file("21","baidu-7",target,0,true,null));
        assertEquals("RECYCLE_CHANGED",assertThrows(BaiduActionException.class,()->client.purgeTemporaryDirectory(evidence,recycle)).code());
        assertEquals(1,transport.posts());
    }
    @Test void purgeRequiresOwnedRootToStayMissingAndTreatsTimeoutAsOnlyGetRecovery() {
        var evidence=client.captureDestinationEvidence(plan()); client.trashTemporaryDirectory(evidence); var recycle=client.findTemporaryRecycle(evidence);
        transport.owned.put("/GYing QQ Temp",List.of(file("21","baidu-7",target,0,true,null)));
        assertThrows(BaiduActionException.class,()->client.purgeTemporaryDirectory(evidence,recycle)); assertEquals(1,transport.posts());
        transport.owned.put("/GYing QQ Temp",List.of()); transport.failAfterPost="/api/recycle/delete";
        assertTrue(assertThrows(BaiduActionException.class,()->client.purgeTemporaryDirectory(evidence,recycle)).uncertain());
        assertTrue(client.isTemporaryDirectoryPurged(evidence,recycle)); assertEquals(2,transport.posts());
    }
    @Test void ambiguousAndIncompleteRecycleListingsNeverAuthorizePurge() {
        var evidence=client.captureDestinationEvidence(plan()); client.trashTemporaryDirectory(evidence); var recycle=client.findTemporaryRecycle(evidence);
        transport.recycle.add(file("21","baidu-7",target,0,true,null));
        assertThrows(BaiduActionException.class,()->client.purgeTemporaryDirectory(evidence,recycle));
        transport.recycle.clear(); for(int i=0;i<100;i++) transport.recycle.add(file(Integer.toString(1000+i),"other"+i,"/Other/other"+i,0,true,null));
        assertEquals("RECYCLE_SCAN_INCOMPLETE",assertThrows(BaiduActionException.class,()->client.findTemporaryRecycle(evidence)).code());
        assertEquals(1,transport.posts());
    }
    @Test void videoOwnShareValidatorRejectsMetadataAndWrongOwnerAndInspectsNestedVideoTree() {
        transport.shared.put("root",List.of(file("40","seed.torrent","/source/seed.torrent",10,false,null)));
        assertTrue(client.validateOwnShare("https://pan.baidu.com/s/1owned")); assertFalse(client.validateOwnVideoShare("https://pan.baidu.com/s/1owned"));
        transport.shared.put("root",List.of(file("41","Folder","/source/Folder",0,true,null)));
        transport.shared.put("/source/Folder",List.of(file("42","Movie.mkv","/source/Folder/Movie.mkv",100,false,null)));
        assertTrue(client.validateOwnVideoShare("https://pan.baidu.com/s/1owned"));
        transport.shareOwner="999"; assertFalse(client.validateOwnVideoShare("https://pan.baidu.com/s/1owned")); assertEquals(0,transport.posts());
    }
    private BaiduWebClient.TransferPlan plan() { return new BaiduWebClient.TransferPlan("https://pan.baidu.com/s/1source",null,target,"1","200",List.of("11"),List.of(new BaiduWebClient.ExpectedFile("Movie.mkv",100,false))); }
    private static Map<String,Object> file(String id,String name,String path,long size,boolean dir,String digest) {
        var m=new LinkedHashMap<String,Object>();m.put("fs_id",Long.parseLong(id));m.put("server_filename",name);m.put("path",path);m.put("size",size);m.put("isdir",dir?1:0);if(digest!=null)m.put("md5",digest);return m;
    }
    private static Map<String,String> query(String raw) {var m=new LinkedHashMap<String,String>();if(raw!=null)for(String x:raw.split("&")){var p=x.split("=",2);m.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return m;}
    private static String body(HttpRequest request) {var out=new java.io.ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
        public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);out.writeBytes(a);}public void onError(Throwable error){throw new AssertionError(error);}public void onComplete(){}
    });return out.toString(StandardCharsets.UTF_8);}
    private static BaiduWebClient.WebResponse okJson(Object value){try{return ok(new ObjectMapper().writeValueAsString(value));}catch(Exception error){throw new AssertionError(error);}}
    private static BaiduWebClient.WebResponse ok(String s){return new BaiduWebClient.WebResponse(200,Map.of(),s.getBytes(StandardCharsets.UTF_8));}
    final class Fake implements BaiduWebClient.Transport {
        final List<HttpRequest> requests=new ArrayList<>(); final Map<String,List<Map<String,Object>>> owned=new HashMap<>(),shared=new HashMap<>();
        final List<Map<String,Object>> recycle=new ArrayList<>(); String uid="100",shareOwner="100",failAfterPost; int accountChangeOnGetInfo; BaiduWebClient.WebResponse postResponse;
        long posts(){return requests.stream().filter(r->r.method().equals("POST")).count();}
        public BaiduWebClient.WebResponse exchange(HttpRequest request) throws IOException {
            requests.add(request);String path=request.uri().getPath();var q=query(request.uri().getRawQuery());
            try {
                if(path.equals("/api/user/getinfo") && accountChangeOnGetInfo>0 && requests.stream().filter(r->r.uri().getPath().equals(path)).count()>=accountChangeOnGetInfo)uid="999";
                if(path.equals("/api/user/getinfo"))return ok(json.writeValueAsString(Map.of("errno",0,"records",List.of(Map.of("uk",Long.parseLong(uid))))));
                if(path.equals("/api/gettemplatevariable"))return okJson(Map.of("errno",0,"result",Map.of("bdstoken","fixture-token")));
                if(path.startsWith("/s/"))return ok("shareid:1,share_uk:"+shareOwner);
                if(path.equals("/share/list"))return ok(json.writeValueAsString(Map.of("errno",0,"list",shared.getOrDefault(q.getOrDefault("dir","root"),List.of()))));
                if(path.equals("/api/list"))return ok(json.writeValueAsString(Map.of("errno",0,"list",owned.getOrDefault(q.get("dir"),List.of()))));
                if(path.equals("/api/recycle/list"))return ok(json.writeValueAsString(Map.of("errno",0,"list",recycle)));
                if(path.equals("/api/filemanager")) {assertEquals("delete",q.get("opera"));recycle.add(file("20","baidu-7",target,0,true,null));owned.put("/GYing QQ Temp",List.of());}
                else if(path.equals("/api/recycle/delete")) {var ids=json.readTree(query(body(request)).get("fidlist"));assertEquals(1,ids.size());recycle.removeIf(f->f.get("fs_id").toString().equals(ids.path(0).asText()));}
                else throw new AssertionError("Unexpected fixture endpoint "+path);
                if(path.equals(failAfterPost))throw new IOException("private fixture failure");
                return postResponse==null?okJson(Map.of("errno",0)):postResponse;
            }catch(IOException e){throw e;}catch(Exception e){throw new AssertionError(e);}
        }
    }
}
