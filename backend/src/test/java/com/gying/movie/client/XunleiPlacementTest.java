package com.gying.movie.client;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class XunleiPlacementTest {
    private final ResourceHubProperties properties = new ResourceHubProperties();
    private final RestTemplate template = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private XunleiClient client;
    @BeforeEach void setup() {
        properties.getXunlei().setAuthorization("Bearer fixture-access-token");
        properties.getXunlei().setCaptchaToken("fixture-captcha-token");
        properties.getXunlei().setTokenStatePath("");
        properties.getXunlei().setPollAttempts(2); properties.getXunlei().setPollIntervalMs(0);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(template);
        client = new XunleiClient(builder, mapper, properties);
    }
    private String video(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"kind\":\"drive#file\",\"mime_type\":\"video/mp4\",\"created_time\":\"2026-10-05T00:00:00Z\"}";
    }
    private void list(String folder, String files) {
        server.expect(requestTo(containsString("/files?parent_id=" + folder)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"files\":[" + files + "]}", MediaType.APPLICATION_JSON));
    }
    @Test void movesMissingIdsEvenWhenOldVideosAlreadyExistAndWaitsForCompletion() {
        list("destination", video("old", "old.mp4"));
        server.expect(requestTo(containsString("/files:batchMove"))).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"ids\":[\"new\"],\"to\":{\"parent_id\":\"destination\"}}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        list("destination", video("old", "old.mp4"));
        list("destination", video("old", "old.mp4") + "," + video("new", "new.mp4"));
        client.moveFiles(List.of("new"), "destination"); server.verify();
    }
    @Test void alreadyPlacedIdsAreNotMovedAgain() {
        list("destination", video("new", "new.mp4")); list("destination", video("new", "new.mp4"));
        client.moveFiles(List.of("new"), "destination"); server.verify();
    }
    @Test void moveHttpSuccessWithoutExactFilePlacementFailsClosed() {
        list("destination", video("old", "old.mp4"));
        server.expect(requestTo(containsString("/files:batchMove")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        list("destination", video("old", "old.mp4")); list("destination", video("old", "old.mp4"));
        assertThrows(IllegalStateException.class, () -> client.moveFiles(List.of("new"), "destination"));
        server.verify();
    }
    @Test void checksAllPagesBeforeDecidingAFileNeedsMoving() {
        server.expect(requestTo(containsString("/files?parent_id=destination")))
                .andRespond(withSuccess("{\"files\":[],\"next_page_token\":\"page-2\"}", MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_token", "page-2"))
                .andRespond(withSuccess("{\"files\":[" + video("new", "new.mp4") + "]}", MediaType.APPLICATION_JSON));
        list("destination", video("new", "new.mp4"));
        client.moveFiles(List.of("new"), "destination"); server.verify();
    }
    @Test void requiresAllExpectedVideoNamesNotAnyVideo() throws Exception {
        var first = mapper.readTree(video("one", "one.mp4"));
        assertFalse(XunleiClient.containsExpectedVideos(List.of(first), List.of("one.mp4", "two.mp4")));
        assertTrue(XunleiClient.containsExpectedVideos(List.of(first), List.of("one.mp4")));
        list("destination", video("one", "one.mp4")); list("destination", video("one", "one.mp4"));
        assertThrows(IllegalStateException.class, () -> client.awaitExpectedContent("destination", List.of("one.mp4", "two.mp4")));
        server.verify();
    }
    @Test void rootFallbackWaitsForCompleteSetAndDoesNotAcceptPrefixFiles() {
        String unrelated = video("wrong", "one.mp4.extra.mp4");
        list("root", video("one", "one.mp4") + "," + unrelated);
        list("root", video("one", "one.mp4") + "," + video("two", "two.mp4") + "," + unrelated);
        var selected = client.awaitRestoredFiles("root", List.of("one.mp4", "two.mp4"), 0);
        assertEquals(List.of("one", "two"), selected.fileIds()); server.verify();
    }
    @Test void partialPlacementCanCombineExactDestinationAndNewRootFiles() {
        list("destination", video("one", "one.mp4")); list("root", video("two", "two.mp4"));
        var selected = client.awaitRestoredFiles("root", List.of("one.mp4", "two.mp4"), 0, "destination");
        assertEquals(java.util.Set.of("one", "two"), java.util.Set.copyOf(selected.fileIds())); server.verify();
    }
    @Test void directoryResolutionFindsExistingFoldersBeyondFirstHundredEntries() {
        server.expect(queryParam("parent_id", ""))
                .andRespond(withSuccess("{\"files\":[],\"next_page_token\":\"root-next\"}", MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_token", "root-next"))
                .andRespond(withSuccess("{\"files\":[{\"id\":\"transfers\",\"name\":\"我的转存\",\"kind\":\"drive#folder\"}]}", MediaType.APPLICATION_JSON));
        server.expect(queryParam("parent_id", "transfers"))
                .andRespond(withSuccess("{\"files\":[],\"next_page_token\":\"folder-next\"}", MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_token", "folder-next"))
                .andRespond(withSuccess("{\"files\":[{\"id\":\"hub\",\"name\":\"GYing Resource Hub\",\"kind\":\"drive#folder\"}]}", MediaType.APPLICATION_JSON));
        XunleiClient.DirectoryInfo directory = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                client, "ensureDirectory", "/GYing Resource Hub");
        assertNotNull(directory); assertEquals("hub", directory.id()); server.verify();
    }
}
