package com.gying.movie.client;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class QuarkTransferImageTest {
    private final ResourceHubProperties properties = new ResourceHubProperties();
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private QuarkShareClient client;
    private static final String IMAGE = """
            {"fid":"image-id","file_name":"救星小窝基地.jpg","dir":false}
            """;

    @BeforeEach
    void setUp() {
        properties.getQuark().setSharePollAttempts(2);
        properties.getQuark().setSharePollIntervalMs(100);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);
        QuarkAutoSaveClient autoSave = mock(QuarkAutoSaveClient.class);
        when(autoSave.getPrimaryCookie()).thenReturn("test-cookie");
        client = new QuarkShareClient(builder, new ObjectMapper(), properties, autoSave);
    }

    @Test
    void copiesRootImageAndWaitsForAsyncCompletionAndVisibility() {
        list("destination", 1, "");
        list("0", 1, IMAGE);
        copy("{\"code\":0,\"data\":{\"task_id\":\"copy-task\"}}");
        task(1);
        task(2);
        list("destination", 1, IMAGE);
        client.ensureTransferImage("destination");
        server.verify();
    }

    @Test
    void skipsExistingImageWithoutReadingOrChangingSource() {
        list("destination", 1, IMAGE);
        client.ensureTransferImage("destination");
        server.verify();
    }

    @Test
    void findsRootImageOnLaterPage() {
        list("destination", 1, "");
        String filler = "{\"fid\":\"other\",\"file_name\":\"other.jpg\",\"dir\":false}";
        list("0", 1, String.join(",", java.util.Collections.nCopies(100, filler)));
        list("0", 2, IMAGE);
        copy("{\"code\":0,\"data\":{}}");
        list("destination", 1, IMAGE);
        client.ensureTransferImage("destination");
        server.verify();
    }

    @Test
    void rejectsMissingSourceImageAndDoesNotCopySameNamedFolder() {
        list("destination", 1, "");
        list("0", 1, IMAGE.replace("false", "true"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.ensureTransferImage("destination"));
        assertTrue(error.getMessage().contains("/救星小窝基地.jpg"));
        server.verify();
    }

    @Test
    void surfacesCopyTaskFailure() {
        list("destination", 1, "");
        list("0", 1, IMAGE);
        copy("{\"code\":0,\"data\":{\"task_id\":\"copy-task\"}}");
        task(3);
        assertThrows(IllegalStateException.class, () -> client.ensureTransferImage("destination"));
        server.verify();
    }

    @Test
    void requiresImageToActuallyAppearInDestination() {
        list("destination", 1, "");
        list("0", 1, IMAGE);
        copy("{\"code\":0,\"data\":{}}");
        list("destination", 1, "");
        list("destination", 1, "");
        assertThrows(IllegalStateException.class, () -> client.ensureTransferImage("destination"));
        server.verify();
    }

    @Test
    void completedTaskWaitsForImageVisibilityAndRetryDoesNotCopyAgain() {
        list("destination", 1, "");
        list("0", 1, IMAGE);
        copy("{\"code\":0,\"data\":{\"task_id\":\"copy-task\"}}");
        task(2);
        list("destination", 1, "");
        list("destination", 1, IMAGE);
        list("destination", 1, IMAGE);
        client.ensureTransferImage("destination");
        client.ensureTransferImage("destination");
        server.verify();
    }

    @Test
    void copyHttpFailureIncludesEndpointAndCodesWithoutRawResponseOrRetry() {
        list("destination", 1, "");
        list("0", 1, IMAGE);
        server.expect(requestTo(containsString("/1/clouddrive/file/copy?")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":14001,\"message\":\"Cookie=private-cookie https://example.com/?token=secret\"}"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.ensureTransferImage("destination"));
        assertEquals("Quark POST /1/clouddrive/file/copy failed: HTTP 400, upstream code 14001",
                error.getMessage());
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void listHttpFailureKeepsStatusWithoutExposingHtmlOrAttemptingCopy() {
        server.expect(requestTo(containsString("/1/clouddrive/file/sort?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML)
                        .body("<html>secret upstream request data</html>"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.ensureTransferImage("destination"));
        assertEquals("Quark GET /1/clouddrive/file/sort failed: HTTP 502", error.getMessage());
        assertNull(error.getCause());
        server.verify();
    }

    private void list(String parent, int page, String items) {
        server.expect(requestTo(containsString("/1/clouddrive/file/sort?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("pdir_fid", parent))
                .andExpect(queryParam("_page", String.valueOf(page)))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"list\":[" + items + "]}}", MediaType.APPLICATION_JSON));
    }

    private void copy(String response) {
        server.expect(requestTo(containsString("/1/clouddrive/file/copy?")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"action_type":1,"filelist":["image-id"],"to_pdir_fid":"destination","exclude_fids":[]}
                        """, true))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void task(int status) {
        server.expect(requestTo(containsString("/1/clouddrive/task?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("task_id", "copy-task"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"status\":" + status + "}}", MediaType.APPLICATION_JSON));
    }
}
