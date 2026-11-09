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

class BaiduWebClientTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();
    ResourceHubProperties properties;
    BaiduSessionVault vault;
    Fake transport;
    BaiduWebClient client;
    @BeforeEach void setup() {
        properties = new ResourceHubProperties();
        properties.getBaidu().setSessionStatePath(temp.resolve("private/session.json").toString());
        properties.getBaidu().setSessionKeyPath(temp.resolve("private/session.key").toString());
        vault = new BaiduSessionVault(properties, mapper);
        vault.save("100", "fixture", List.of(new BaiduSessionVault.Cookie("BDUSS", "fixture-session", ".baidu.com", "/", Instant.now().getEpochSecond()+3600, true),
                new BaiduSessionVault.Cookie("PASSPORT", "private-passport", "passport.baidu.com", "/", -1, true)), false);
        transport = new Fake(); client = new BaiduWebClient(properties, vault, mapper, transport);
        transport.shared.put("root", "["+file("11", "Movie.mkv", "/source/Movie.mkv", 100, false, null)+"]");
        transport.owned.put("/", "["+file("20", "target", "/target", 0, true, null)+"]");
        transport.owned.put("/target", "[]");
    }
    @Test void normalizesOnlyExactHostsAndRejectsAmbiguousUrls() {
        assertEquals("https://pan.baidu.com/s/1abcd?pwd=a1B2", BaiduWebClient.normalizedShareUrl("http://yun.baidu.com/share/init?surl=abcd&pwd=a1B2", null));
        for (String url : List.of("https://pan.baidu.com.evil/s/1abc", "https://user@pan.baidu.com/s/1abc", "https://pan.baidu.com:443/s/1abc", "https://pan.baidu.com/s/1abc#x", "https://pan.baidu.com/s/1abc?pwd=abcd&pwd=efgh", "https://pan.baidu.com/s/1abc?pwd=toolong")) assertThrows(IllegalArgumentException.class, () -> BaiduWebClient.normalizedShareUrl(url, null));
        assertEquals(BaiduWebClient.normalizedShareUrl("https://pan.baidu.com/s/1abc", "abcd"), BaiduWebClient.normalizeShareUrl("https://pan.baidu.com/s/1abc", "abcd"));
    }
    @Test void planIsReadOnlyAndCredentialsAreNotPersisted() throws Exception {
        var plan=client.planTransfer("https://pan.baidu.com/s/1source", null, "/target");
        assertEquals(List.of("11"), plan.sourceRootFsids());
        assertEquals(List.of(new BaiduWebClient.ExpectedFile("Movie.mkv",100,false)), plan.expectedFiles());
        assertTrue(transport.requests.stream().allMatch(r -> r.method().equals("GET")));
        String saved=mapper.writeValueAsString(plan);
        assertFalse(saved.contains("fixture-session")); assertFalse(saved.contains("fixture-token"));
        assertTrue(transport.requests.stream().allMatch(r -> !r.headers().firstValue("Cookie").orElse("").contains("PASSPORT")));
    }
    @Test void wholeRootPolicySkipsMixedDisabledAndUnknownSubtrees() {
        transport.shared.put("root", "["+file("11","Video","/Video",0,true,null)+","+file("12","OnlySeed.torrent","/OnlySeed.torrent",120,false,null)+"]");
        transport.shared.put("/Video", "["+file("13","movie.mkv","/Video/movie.mkv",100,false,null)+","+file("14","readme.exe","/Video/readme.exe",1,false,null)+"]");
        var plan=client.planTransfer("https://pan.baidu.com/s/1source",null,"/target",false,true);
        assertEquals(List.of("12"),plan.sourceRootFsids());
        assertFalse(BaiduWebClient.hasVideoFiles(plan)); assertTrue(BaiduWebClient.hasTorrentFiles(plan));
        assertTrue(BaiduWebClient.matchesFilePolicy(plan,false,true)); assertFalse(BaiduWebClient.matchesFilePolicy(plan,true,false));
        assertTrue(transport.requests.stream().allMatch(r -> r.method().equals("GET")));
    }
    @Test void recursivelyPreservesExpectedHierarchy() {
        transport.shared.put("root", "["+file("11","Season","/Season",0,true,null)+"]");
        transport.shared.put("/Season", "["+file("12","one.mkv","/Season/one.mkv",50,false,null)+"]");
        var plan=client.planTransfer("https://pan.baidu.com/s/1source",null,"/target");
        assertEquals(List.of(new BaiduWebClient.ExpectedFile("Season",0,true),new BaiduWebClient.ExpectedFile("Season/one.mkv",50,false)),plan.expectedFiles());
    }
    @Test void pathTraversalAndWrongOwnedParentAreRejected() {
        transport.shared.put("root","["+file("11","..","/..",100,false,null)+"]");
        assertEquals("PATH_INVALID",assertThrows(BaiduActionException.class,()->client.planTransfer("https://pan.baidu.com/s/1source",null,"/target")).code());
        transport.owned.put("/target","["+file("11","Movie.mkv","/other/Movie.mkv",100,false,null)+"]");
        assertEquals("DESTINATION_INVALID",assertThrows(BaiduActionException.class,()->client.verifyDestination(plan())).code());
    }
    @Test void paginationDuplicatesCannotMasqueradeAsCompleteSource() {
        String repeated="["+String.join(",",Collections.nCopies(100,file("11","Movie.mkv","/Movie.mkv",100,false,null)))+"]";
        transport.shared.put("root",repeated);
        assertEquals("PAGINATION_INCOMPLETE",assertThrows(BaiduActionException.class,()->client.planTransfer("https://pan.baidu.com/s/1source",null,"/target")).code());
    }
    @Test void exactVerificationRejectsPartialExtraAndWrongSize() {
        assertFalse(client.verifyDestination(plan()));
        transport.owned.put("/target","["+file("30","Movie.mkv","/target/Movie.mkv",99,false,null)+"]"); assertFalse(client.verifyDestination(plan()));
        transport.owned.put("/target","["+file("30","Movie.mkv","/target/Movie.mkv",100,false,null)+","+file("31","extra.mkv","/target/extra.mkv",1,false,null)+"]"); assertFalse(client.verifyDestination(plan()));
        transport.owned.put("/target","["+file("30","Movie.mkv","/target/Movie.mkv",100,false,null)+"]"); assertTrue(client.verifyDestination(plan()));
    }
    @Test void sourceChangedBeforeTransferNeverPosts() {
        transport.shared.put("root","["+file("11","Movie.mkv","/source/Movie.mkv",101,false,null)+"]");
        assertEquals("SOURCE_CHANGED",assertThrows(BaiduActionException.class,()->client.transfer(plan())).code());
        assertEquals(0,transport.count("POST","/share/transfer")); assertEquals(0,transport.count("POST","/api/create"));
    }
    @Test void ambiguousTransferIsMarkedUncertainAndDefiniteRejectionIsNot() {
        transport.failurePath="/share/transfer";
        var error=assertThrows(BaiduActionException.class,()->client.transfer(plan())); assertTrue(error.uncertain()); assertEquals("NETWORK_UNAVAILABLE",error.code());
        transport.failurePath=null; transport.transfer="{\"errno\":12}";
        error=assertThrows(BaiduActionException.class,()->client.transfer(plan())); assertFalse(error.uncertain()); assertTrue(error.actionRequired());
    }
    @Test void foreignRedirectNeverReceivesSessionCookies() {
        transport.redirect="https://evil.example/steal";
        assertEquals("UNSAFE_UPSTREAM",assertThrows(BaiduActionException.class,()->client.planTransfer("https://pan.baidu.com/s/1source",null,"/target")).code());
        assertTrue(transport.requests.stream().allMatch(r -> r.uri().getHost().equals("pan.baidu.com")));
    }
    @Test void incompleteShareRecordsPreventNewShareCreation() {
        transport.records="{\"errno\":0,\"list\":null}";
        assertThrows(BaiduActionException.class,()->client.createOwnShare("/target")); assertEquals(0,transport.count("POST","/share/pset"));
        transport.records="{\"errno\":0,\"list\":[]}"; transport.failurePath="/share/record";
        assertThrows(BaiduActionException.class,()->client.createOwnShare("/target")); assertEquals(0,transport.count("POST","/share/pset"));
    }
    @Test void ownShareOwnerIsVerifiedAndPostValidationIsUncertain() {
        transport.owner="200"; assertFalse(client.validateOwnShare("https://pan.baidu.com/s/1owned"));
        transport.owner="100"; transport.failAfterShare=true;
        var error=assertThrows(BaiduActionException.class,()->client.createOwnShare("/target")); assertTrue(error.uncertain()); assertEquals(1,transport.count("POST","/share/pset"));
    }
    @Test void existingMetadataMustMatchDigestNameAndSizeBeforeReuse() throws Exception {
        byte[] bytes="d4:infod6:lengthi1e4:name1:xee".getBytes(StandardCharsets.UTF_8);
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
        transport.owned.put("/target","["+file("30","seed.torrent","/target/seed.torrent",bytes.length,false,digest)+"]");
        assertEquals("30",client.p2pUpload("/target","seed.torrent",bytes)); assertEquals(0,transport.count("POST","/api/precreate"));
        transport.owned.put("/target","["+file("30","seed.torrent","/target/seed.torrent",bytes.length,false,"00000000000000000000000000000000")+"]");
        assertEquals("DESTINATION_CONFLICT",assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","seed.torrent",bytes)).code());
        assertEquals(0,transport.count("POST","/api/precreate"));
    }
    @Test void tinyUploadUsesFixedPcsHostAndNeverOfflineDownloadOrOverwrite() throws Exception {
        byte[] bytes=("测试资源\n仅磁力与种子，不含视频。\nmagnet:?xt=urn:btih:"+"a".repeat(40)+"\n").getBytes(StandardCharsets.UTF_8);
        transport.uploadBytes=bytes; transport.uploadName="magnet.txt";
        assertEquals("30",client.p2pUpload("/target","magnet.txt",bytes));
        assertEquals(1,transport.count("POST","/rest/2.0/pcs/superfile2"));
        assertTrue(transport.requests.stream().allMatch(r -> Set.of("pan.baidu.com","pcs.baidu.com").contains(r.uri().getHost())));
        assertTrue(transport.requests.stream().noneMatch(r -> r.uri().toString().contains("cloud_dl")));
        assertEquals("2",query(transport.createBody).get("rtype"));
        var pre=transport.requests.stream().filter(r->r.uri().getPath().equals("/api/precreate")).findFirst().orElseThrow();
        assertEquals("2",query(body(pre)).get("rtype"));
    }
    @Test void readOnlyUploadRecoveryRequiresExactContentAndNeverSubmits() throws Exception {
        byte[] bytes=magnetBytes();
        assertNull(client.p2pFindUploadedFile("/target","magnet.txt",bytes));
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
        transport.owned.put("/target","["+file("30","magnet.txt","/target/magnet.txt",bytes.length,false,digest)+"]");
        assertEquals("30",client.p2pFindUploadedFile("/target","magnet.txt",bytes));
        transport.owned.put("/target","["+file("30","magnet.txt","/target/magnet.txt",bytes.length,false,"0".repeat(32))+"]");
        assertEquals("DESTINATION_CONFLICT",assertThrows(BaiduActionException.class,()->client.p2pFindUploadedFile("/target","magnet.txt",bytes)).code());
        assertTrue(transport.requests.stream().allMatch(r->r.method().equals("GET")));
    }
    @Test void pcsZeroErrorCodeIsSuccessAndNonzeroCodePreventsCommit() throws Exception {
        byte[] bytes=magnetBytes(); transport.uploadBytes=bytes; transport.uploadName="magnet.txt";
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
        transport.uploadResponse=ok("{\"error_code\":0,\"md5\":\""+digest+"\"}");
        assertEquals("30",client.p2pUpload("/target","magnet.txt",bytes));
        transport.owned.put("/target","[]"); transport.requests.clear();
        transport.uploadResponse=ok("{\"error_code\":31024,\"error_msg\":\"fixture-session must never be persisted\"}");
        var error=assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","magnet.txt",bytes));
        assertEquals("UPLOAD_BLOCK_API_31024",error.code()); assertFalse(error.uncertain());
        assertFalse(error.getMessage().contains("fixture-session"));
        assertEquals(0,transport.count("POST","/api/create"));
    }
    @Test void uploadFailuresRetainStageAndHttpStatusWithoutRemoteText() {
        byte[] bytes=magnetBytes(); transport.uploadBytes=bytes; transport.uploadName="magnet.txt";
        transport.uploadResponse=new BaiduWebClient.WebResponse(403,Map.of("location",List.of("https://evil.example/private")),"private upstream body".getBytes(StandardCharsets.UTF_8));
        var error=assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","magnet.txt",bytes));
        assertEquals("UPLOAD_BLOCK_HTTP_403",error.code()); assertTrue(error.actionRequired()); assertTrue(error.uncertain());
        assertEquals(0,transport.count("POST","/api/create"));
        assertTrue(transport.requests.stream().allMatch(r->Set.of("pan.baidu.com","pcs.baidu.com").contains(r.uri().getHost())));
        transport.uploadResponse=ok("{broken private upstream response"); transport.requests.clear();
        error=assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","magnet.txt",bytes));
        assertEquals("UPLOAD_BLOCK_RESPONSE_INVALID",error.code()); assertTrue(error.uncertain());
        assertEquals(1,transport.count("POST","/rest/2.0/pcs/superfile2")); assertEquals(0,transport.count("POST","/api/create"));
        assertFalse(error.getMessage().contains("private"));
    }
    @Test void precreateAndCommitFailuresAreSeparatelyDiagnosable() {
        byte[] bytes=magnetBytes(); transport.uploadBytes=bytes; transport.uploadName="magnet.txt";
        transport.precreateResponse="{\"errno\":-6}";
        var error=assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","magnet.txt",bytes));
        assertEquals("UPLOAD_PRECREATE_API_NEG_6",error.code()); assertTrue(error.actionRequired()); assertFalse(error.uncertain());
        assertEquals(0,transport.count("POST","/rest/2.0/pcs/superfile2"));
        transport.precreateResponse="{\"errno\":0,\"uploadid\":\"up1\",\"block_list\":[0],\"return_type\":1}";
        transport.commitResponse="{\"errno\":12}";
        error=assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","magnet.txt",bytes));
        assertEquals("UPLOAD_COMMIT_API_12",error.code()); assertTrue(error.actionRequired()); assertFalse(error.uncertain());
        transport.commitResponse=null; transport.failurePath="/api/create";
        error=assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","magnet.txt",bytes));
        assertEquals("UPLOAD_COMMIT_NETWORK_UNAVAILABLE",error.code()); assertTrue(error.uncertain());
    }
    @Test void invalidPrecreateTicketCannotTriggerBlockOrCommit() {
        byte[] bytes=magnetBytes(); transport.uploadBytes=bytes; transport.uploadName="magnet.txt";
        for (String response:List.of("{\"errno\":0,\"uploadid\":\"up1\",\"block_list\":[0]}","{\"errno\":0,\"uploadid\":\"up1\",\"block_list\":[1],\"return_type\":1}")) {
            transport.precreateResponse=response;
            var error=assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","magnet.txt",bytes));
            assertEquals("UPLOAD_PRECREATE_RESPONSE_INVALID",error.code()); assertTrue(error.uncertain());
        }
        assertEquals(0,transport.count("POST","/rest/2.0/pcs/superfile2")); assertEquals(0,transport.count("POST","/api/create"));
    }
    private static byte[] magnetBytes() { return ("magnet:?xt=urn:btih:"+"a".repeat(40)+"\n").getBytes(StandardCharsets.UTF_8); }
    @Test void uploadBoundsAndContentRejectBeforeAnyNetwork() {
        int before=transport.requests.size();
        assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","bad.mp4",new byte[3]));
        assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","bad.txt","not a magnet".getBytes(StandardCharsets.UTF_8)));
        assertThrows(BaiduActionException.class,()->client.p2pUpload("/target","bad.torrent",new byte[2*1024*1024+1]));
        assertEquals(before,transport.requests.size());
    }
    @Test void malformedPersistedPlanFailsPolicyWithoutNetwork() {
        var malformed=new BaiduWebClient.TransferPlan("https://pan.baidu.com/s/1source",null,"/target","1","200",List.of("11"),List.of(new BaiduWebClient.ExpectedFile("missing/one.mkv",100,false)));
        assertFalse(BaiduWebClient.matchesFilePolicy(malformed,true,true));
        assertFalse(BaiduWebClient.matchesFilePolicy(new BaiduWebClient.TransferPlan("https://pan.baidu.com/s/1source",null,"/target","1","200",List.of("11"),List.of(new BaiduWebClient.ExpectedFile("giant.torrent",3*1024*1024,false))),true,true));
    }
    @Test void passwordVerificationIsTransientAndDoesNotEnterVaultOrPlan() throws Exception {
        transport.needsCode=true;
        var p=client.planTransfer("https://pan.baidu.com/s/1source", "abcd", "/target");
        assertEquals(1,transport.count("POST","/share/verify"));
        assertEquals(0,transport.count("POST","/share/transfer"));
        assertFalse(mapper.writeValueAsString(p).contains("fixture-secret"));
        assertFalse(vault.read().cookies().stream().anyMatch(cookie->cookie.name().equals("BDCLND")));
    }
    @Test void ownRecordLookupVerifiesOwnerDirectoryAndShareAccessibility() {
        transport.owner="100";
        transport.records="{\"errno\":0,\"list\":[{\"shareId\":1,\"fsIds\":[20],\"typicalPath\":\"/target\",\"status\":0,\"expiredTime\":0,\"uk\":100,\"shortlink\":\"https://pan.baidu.com/s/1owned\"}]}";
        assertEquals("https://pan.baidu.com/s/1owned?pwd=abcd",client.findOwnShare("/target"));
        assertEquals(0,transport.count("POST","/share/pset"));
        transport.owner="200"; assertNull(client.findOwnShare("/target"));
    }
    @Test void expiredOwnRecordIsSkippedWithoutDereferencingItsUrl() {
        transport.records="{\"errno\":0,\"list\":[{\"shareId\":1,\"fsIds\":[20],\"typicalPath\":\"/target\",\"status\":0,\"expiredTime\":1,\"shortlink\":\"https://evil.example/x\"}]}";
        assertNull(client.findOwnShare("/target"));assertEquals(0,transport.count("GET","/share/surlinfoinrecord"));
    }
    @Test void metadataShareGuardRejectsVideoUnknownOversizeAndEmptyFolders() {
        for (String contents : List.of("[]", "["+file("40","Movie.mkv","/target/Movie.mkv",100,false,null)+"]", "["+file("40","secret.bin","/target/secret.bin",1,false,null)+"]", "["+file("40","large.txt","/target/large.txt",16385,false,null)+"]", "["+file("40","Empty","/target/Empty",0,true,null)+"]")) {
            transport.owned.put("/target",contents);
            var error=assertThrows(BaiduActionException.class,()->client.verifyMetadataOnlyFolder("/target"));assertEquals("METADATA_FOLDER_INVALID",error.code());
        }
        assertTrue(transport.requests.stream().allMatch(r->r.method().equals("GET")));
    }
    @Test void metadataShareGuardAllowsOnlyBoundedMetadataAcrossCompleteTree() {
        transport.owned.put("/target", "["+file("40","Old","/target/Old",0,true,null)+","+file("41","current.txt","/target/current.txt",100,false,null)+"]");
        transport.owned.put("/target/Old", "["+file("42","seed.torrent","/target/Old/seed.torrent",2*1024*1024,false,null)+"]");
        assertDoesNotThrow(()->client.verifyMetadataOnlyFolder("/target"));
        transport.owned.put("/target/Old", "["+file("42","hidden.mp4","/target/Old/hidden.mp4",1,false,null)+"]");
        assertThrows(BaiduActionException.class,()->client.verifyMetadataOnlyFolder("/target"));
        assertTrue(transport.requests.stream().allMatch(r->r.method().equals("GET")));
    }
    private static BaiduWebClient.TransferPlan plan() { return new BaiduWebClient.TransferPlan("https://pan.baidu.com/s/1source",null,"/target","1","200",List.of("11"),List.of(new BaiduWebClient.ExpectedFile("Movie.mkv",100,false))); }
    private static String file(String id,String name,String path,long size,boolean dir,String md5) {
        return "{\"fs_id\":"+id+",\"server_filename\":\""+name+"\",\"path\":\""+path+"\",\"size\":"+size+",\"isdir\":"+(dir?1:0)+(md5==null?"":",\"md5\":\""+md5+"\"")+"}";
    }
    private static Map<String,String> query(String raw) { var values=new LinkedHashMap<String,String>(); if(raw!=null) for(String pair:raw.split("&")){var p=pair.split("=",2);values.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");} return values; }
    private static String body(HttpRequest request) {
        var result=new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}
            public void onNext(java.nio.ByteBuffer b){byte[] v=new byte[b.remaining()];b.get(v);result.writeBytes(v);}
            public void onError(Throwable t){throw new AssertionError(t);}
            public void onComplete(){}
        }); return result.toString(StandardCharsets.UTF_8);
    }
    private static BaiduWebClient.WebResponse ok(String value){return new BaiduWebClient.WebResponse(200,Map.of(),value.getBytes(StandardCharsets.UTF_8));}
    private static final class Fake implements BaiduWebClient.Transport {
        final List<HttpRequest> requests=new ArrayList<>();
        final Map<String,String> shared=new HashMap<>(),owned=new HashMap<>();
        String failurePath,redirect,owner="200",records="{\"errno\":0,\"list\":[]}",transfer="{\"errno\":0,\"taskid\":\"task1\"}",createBody;
        boolean failAfterShare,shareCreated,needsCode,verified;
        byte[] uploadBytes; String uploadName;
        String precreateResponse="{\"errno\":0,\"uploadid\":\"up1\",\"block_list\":[0],\"return_type\":1}",commitResponse;
        BaiduWebClient.WebResponse uploadResponse;
        long count(String method,String path){return requests.stream().filter(r->r.method().equals(method)&&r.uri().getPath().equals(path)).count();}
        public BaiduWebClient.WebResponse exchange(HttpRequest request) throws IOException {
            requests.add(request); String path=request.uri().getPath(); var q=query(request.uri().getRawQuery());
            if(path.equals(failurePath)||failAfterShare&&shareCreated&&path.equals("/api/user/getinfo"))throw new IOException("fixture failure with secret must stay private");
            return switch(path) {
                case "/api/user/getinfo" -> ok("{\"errno\":0,\"records\":[{\"uk\":100,\"uname\":\"fixture\"}]}");
                case "/api/gettemplatevariable" -> ok("{\"errno\":0,\"result\":{\"bdstoken\":\"fixture-token\"}}");
                case "/s/1source", "/s/1owned" -> redirect==null?needsCode&&!verified?ok("password page"):ok("{\"shareid\":1,\"share_uk\":"+owner+"}"):new BaiduWebClient.WebResponse(302,Map.of("location",List.of(redirect)),new byte[0]);
                case "/share/list" -> ok("{\"errno\":0,\"list\":"+shared.getOrDefault(q.getOrDefault("dir","root"),"[]")+"}");
                case "/api/list" -> ok("{\"errno\":0,\"list\":"+owned.getOrDefault(q.get("dir"),"[]")+"}");
                case "/share/transfer" -> ok(transfer);
                case "/share/record" -> ok(records);
                case "/share/pset" -> {shareCreated=true;yield ok("{\"errno\":0,\"link\":\"https://pan.baidu.com/s/1owned\"}");}
                case "/share/verify" -> {verified=true;yield ok("{\"errno\":0,\"randsk\":\"fixture-secret\"}");}
                case "/share/surlinfoinrecord" -> ok("{\"errno\":0,\"shorturl\":\"https://pan.baidu.com/s/1owned\",\"pwd\":\"abcd\"}");
                case "/api/precreate" -> ok(precreateResponse);
                case "/rest/2.0/pcs/superfile2" -> {if(uploadResponse!=null)yield uploadResponse;try{yield ok("{\"md5\":\""+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(uploadBytes))+"\"}");}catch(Exception e){throw new AssertionError(e);}}
                case "/api/create" -> {createBody=body(request);if(commitResponse!=null)yield ok(commitResponse);try{owned.put("/target","["+file("30",uploadName,"/target/"+uploadName,uploadBytes.length,false,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(uploadBytes)))+"]");}catch(Exception e){throw new AssertionError(e);}yield ok("{\"errno\":0}");}
                default -> throw new AssertionError("Unexpected fixture endpoint "+path);
            };
        }
    }
}
