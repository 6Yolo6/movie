package com.gying.movie.service.impl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiConversationContextTest {
    final ObjectMapper mapper=new ObjectMapper();
    final AiAssistantService service=new AiAssistantService(mapper);
    ObjectNode input(String message){return mapper.createObjectNode().put("message",message);}
    Map<String,Object> direct(String id){var result=mapper.createObjectNode().put("answer","样本回答");result.putObject("catalog").put("keyword","样本");result.putArray("evidence").addObject().putArray("movies").addObject().put("id","a").put("titleCn","样本");return Map.of("jobId",id,"status","SUCCEEDED","result",result);}
    @Test void directResultsAreRememberedAndOnlyOwnerMayReferenceThem(){
        String id=UUID.randomUUID().toString();service.rememberDirect(1L,input("样本"),direct(id));
        var request=service.prepareRequest(1L,input("第二部").put("previousJobId",id));assertThat(request.path("context").path("candidateIds").get(0).asText()).isEqualTo("a");
        assertThatThrownBy(()->service.prepareRequest(2L,input("第二部").put("previousJobId",id))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void directRequestKeyIsIdempotentAndCannotBeReused(){
        var body=input("样本").put("requestKey","fixture-request-key");String id=UUID.randomUUID().toString();service.rememberDirect(1L,body,direct(id));
        assertThat(service.rememberDirect(1L,body,direct(UUID.randomUUID().toString())).get("jobId")).isEqualTo(id);
        assertThatThrownBy(()->service.rememberDirect(1L,input("别的").put("requestKey","fixture-request-key"),direct(UUID.randomUUID().toString()))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void runningContextRequiresCurrentLease(){
        service.heartbeat();String previous=UUID.randomUUID().toString();service.rememberDirect(1L,input("样本"),direct(previous));
        String id=(String)service.submit(1L,input("其中一部").put("previousJobId",previous)).get("jobId");var claim=service.claim();
        assertThat(service.runningContext(id,(String)claim.get("lease")).path("candidateIds").size()).isEqualTo(1);
        assertThatThrownBy(()->service.runningContext(id,"wrong")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void supersededRecallDoesNotPolluteFollowupCandidateOrder(){
        service.heartbeat();String id=(String)service.submit(1L,input("样本")).get("jobId");var claim=service.claim();
        var result=mapper.createObjectNode().put("scope","gying").put("answer","答案");var trace=result.putArray("trace");
        var old=trace.addObject().put("kind","tool").put("name","queryCatalog").putObject("result").put("ok",true).putObject("data").put("superseded",true);old.putArray("movies").addObject().put("id","old");
        trace.addObject().put("kind","tool").put("name","queryCatalog").putObject("result").put("ok",true).putObject("data").putArray("movies").addObject().put("id","new");
        service.complete(id,(String)claim.get("lease"),result);var context=service.prepareRequest(1L,input("第一部").put("previousJobId",id));assertThat(context.path("context").path("candidateIds").size()).isEqualTo(1);assertThat(context.path("context").path("candidateIds").get(0).asText()).isEqualTo("new");
    }
    @Test void failureKeepsSafeCodeAndTrace(){
        service.heartbeat();String id=(String)service.submit(1L,input("样本")).get("jobId");var claim=service.claim();
        var result=mapper.createObjectNode().put("scope","gying").put("answer","");result.putObject("error").put("code","CONTEXT_TOO_LONG");result.putArray("trace").addObject().put("kind","planning").put("name","plan").put("status","error").put("private","secret");
        service.complete(id,(String)claim.get("lease"),result);var view=mapper.valueToTree(service.get(1L,id));assertThat(view.path("result").path("error").path("code").asText()).isEqualTo("CONTEXT_TOO_LONG");assertThat(view.toString()).doesNotContain("secret");
    }
}
