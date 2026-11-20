package com.gying.movie.service.impl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiAssistantRecoveryTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    final MutableClock clock=new MutableClock();
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-22T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(int seconds){now=now.plusSeconds(seconds);}
    }
    AiAssistantService open() throws Exception {
        Path key=directory.resolve("key.bin");
        if(!Files.exists(key))Files.write(key,new byte[32]);
        return new AiAssistantService(mapper,clock,directory.resolve("queue/state.bin").toString(),key.toString());
    }
    ObjectNode question(){return mapper.createObjectNode().put("message","private-question-fixture").put("requestKey","request_fixture_001");}
    ObjectNode answer(){
        ObjectNode result=mapper.createObjectNode().put("scope","gying").put("answer","private-answer-fixture");
        result.putArray("trace").addObject().put("kind","tool").put("name","searchMovies")
                .putObject("result").put("ok",true).putObject("data").put("queriedAt",clock.instant().toString()).putArray("movies");
        return result;
    }
    String submit(AiAssistantService queue){queue.heartbeat();return (String)queue.submit(1L,question()).get("jobId");}
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]);ObjectMapper mapper=new ObjectMapper();
        var queue=new AiAssistantService(mapper,Clock.systemUTC(),dir.resolve("process/state.bin").toString(),dir.resolve("key.bin").toString());
        queue.heartbeat();var body=mapper.createObjectNode().put("message","process-fixture").put("requestKey","process_request_01");
        String id=(String)queue.submit(1L,body).get("jobId");var claim=queue.claim(true);
        Files.writeString(dir.resolve("claimed.json"),mapper.writeValueAsString(claim));
        Runtime.getRuntime().halt(0); // Crash only this isolated fixture process after durable claim; no close hook.
    }
    @Test void realProcessCrashRetainsLeaseAndRequest() throws Exception {
        Files.write(directory.resolve("key.bin"),new byte[32]);
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var child=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                AiAssistantRecoveryTest.class.getName(),directory.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("process.log").toFile()).start();
        boolean exited=child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS);
        if(!exited)child.destroy();
        assertTrue(exited,"isolated crash fixture did not exit");assertEquals(0,child.exitValue());
        var claimed=mapper.readTree(Files.readString(directory.resolve("claimed.json")));
        try(var queue=new QueueHandle(new AiAssistantService(mapper,Clock.systemUTC(),directory.resolve("process/state.bin").toString(),directory.resolve("key.bin").toString()))) {
            assertEquals("RUNNING",queue.value.get(1L,claimed.path("jobId").asText()).get("status"));
            queue.value.complete(claimed.path("jobId").asText(),claimed.path("lease").asText(),answer());
            assertEquals("SUCCEEDED",queue.value.get(1L,claimed.path("jobId").asText()).get("status"));
        }
    }
    record QueueHandle(AiAssistantService value) implements AutoCloseable { public void close(){value.close();} }
    @Test void pendingAndRunningSurviveRestartAndCompletionIsIdempotent() throws Exception {
        var queue=open();String id=submit(queue);queue.close();
        queue=open();assertEquals("PENDING",queue.get(1L,id).get("status"));
        var claim=queue.claim(true);queue.close();
        queue=open();assertEquals("RUNNING",queue.get(1L,id).get("status"));
        queue.complete(id,(String)claim.get("lease"),answer());queue.close();
        queue=open();assertEquals(true,queue.renew(id,(String)claim.get("lease")).get("completed"));queue.complete(id,(String)claim.get("lease"),answer());
        assertEquals("SUCCEEDED",queue.get(1L,id).get("status"));
        assertEquals(id,queue.submit(1L,question()).get("jobId"));
        var changed=answer().put("answer","different");var finalQueue=queue;
        assertEquals(409,assertThrows(ResponseStatusException.class,()->finalQueue.complete(id,(String)claim.get("lease"),changed)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->finalQueue.get(2L,id)).getStatusCode().value());
        queue.close();
    }
    @Test void encryptedSnapshotDropsQuestionAfterCompletionAndExpiresAnswer() throws Exception {
        var queue=open();String id=submit(queue);var claim=queue.claim(true);
        assertFalse(new String(Files.readAllBytes(directory.resolve("queue/state.bin")),java.nio.charset.StandardCharsets.ISO_8859_1).contains("private-question-fixture"));
        queue.complete(id,(String)claim.get("lease"),answer());queue.close();
        try(var state=new com.gying.movie.utils.EncryptedAiState(mapper,directory.resolve("queue/state.bin").toString(),directory.resolve("key.bin").toString())) {
            String saved=state.read().toString();assertFalse(saved.contains("private-question-fixture"));assertTrue(saved.contains("private-answer-fixture"));
        }
        queue=open();clock.advance(900);queue.maintain();queue.close();
        queue=open();var finalQueue=queue;
        assertEquals(404,assertThrows(ResponseStatusException.class,()->finalQueue.get(1L,id)).getStatusCode().value());queue.close();
    }
    @Test void renewalSurvivesRestartAndExpiredLeaseIsFencedWithBoundedRetry() throws Exception {
        var queue=open();String id=submit(queue);var first=queue.claim(true);
        clock.advance(40);queue.renew(id,(String)first.get("lease"));queue.close();
        queue=open();clock.advance(30);assertEquals("RUNNING",queue.get(1L,id).get("status"));
        clock.advance(16);assertEquals("PENDING",queue.get(1L,id).get("status"));var second=queue.claim(true);
        assertEquals(2,second.get("attempt"));assertNotEquals(first.get("lease"),second.get("lease"));
        var finalQueue=queue;assertEquals(409,assertThrows(ResponseStatusException.class,()->finalQueue.complete(id,(String)first.get("lease"),answer())).getStatusCode().value());
        clock.advance(45);assertEquals("FAILED",queue.get(1L,id).get("status"));assertTrue(queue.claim(true).isEmpty());queue.close();
    }
    @Test void renewalCannotExtendTotalBudgetAndCancelRejectsLateResult() throws Exception {
        var queue=open();String id=submit(queue);var first=queue.claim(true);
        for(int i=0;i<5;i++){clock.advance(40);queue.renew(id,(String)first.get("lease"));}
        clock.advance(40);assertEquals("FAILED",queue.get(1L,id).get("status"));
        queue.heartbeat();String other=(String)queue.submit(1L,mapper.createObjectNode().put("message","another")).get("jobId");
        var claim=queue.claim(true);
        assertThrows(ResponseStatusException.class,()->queue.cancel(2L,other));queue.cancel(1L,other);queue.close();
        var restored=open();assertEquals("FAILED",restored.get(1L,other).get("status"));
        assertThrows(ResponseStatusException.class,()->restored.complete(other,(String)claim.get("lease"),answer()));restored.close();
    }
    @Test void requestKeyConflictsAreRejectedAndScopedToOwner() throws Exception {
        var queue=open();String id=submit(queue);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->queue.submit(1L,question().put("message","changed"))).getStatusCode().value());
        assertNotEquals(id,queue.submit(2L,question()).get("jobId"));queue.close();
    }
    @Test void failedDurableWriteRollsBackInMemoryClaim() throws Exception {
        var queue=open();String id=submit(queue);Path file=directory.resolve("queue/state.bin");
        byte[] before=Files.readAllBytes(file);Files.delete(file);Files.createDirectory(file);
        assertThrows(IllegalStateException.class,()->queue.claim(true));
        assertEquals("PENDING",queue.get(1L,id).get("status"));
        Files.delete(file);Files.write(file,before);var claim=queue.claim(true);
        assertEquals(1,claim.get("attempt"));queue.close();
    }
    @Test void competingWriterTamperingAndWrongKeyFailClosed() throws Exception {
        var queue=open();submit(queue);assertThrows(IllegalStateException.class,this::open);queue.close();
        Path state=directory.resolve("queue/state.bin");byte[] original=Files.readAllBytes(state);
        byte[] corrupt=original.clone();corrupt[corrupt.length-1]^=1;Files.write(state,corrupt);
        assertThrows(IllegalStateException.class,this::open);Files.write(state,original);
        Files.write(directory.resolve("key.bin"),"wrong-key-material-at-least-thirty-two-bytes".getBytes());
        assertThrows(IllegalStateException.class,this::open);
    }
}
