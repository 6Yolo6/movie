package com.gying.movie.client;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class XunleiTransferImageTest {
    private final ResourceHubProperties properties = new ResourceHubProperties();
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private XunleiClient client;
    private static final String IMAGE = """
            {"id":"image-id","name":"救星小窝基地.jpg","kind":"drive#file"}
            """;
    private static final String SOURCE_FOLDER = """
            {"id":"source-folder","name":"影视剧资源分享(先转存后再查看)","kind":"drive#folder"}
            """;

    @BeforeEach
    void setUp() {
        properties.getXunlei().setAuthorization("Bearer test-access-token");
        properties.getXunlei().setCaptchaToken("test-captcha-token");
        properties.getXunlei().setTokenStatePath("");
        properties.getXunlei().setPollAttempts(2);
        properties.getXunlei().setPollIntervalMs(0);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class, org.mockito.Mockito.RETURNS_SELF);
        when(builder.build()).thenReturn(restTemplate);
        client = new XunleiClient(builder, new ObjectMapper(), properties);
    }

    @Test
    void copiesFromNamedFolderUnderMyTransfersAndWaitsForImage() {
        list("destination", "");
        root();
        list("my-transfers", SOURCE_FOLDER);
        list("source-folder", IMAGE);
        copy();
        list("destination", "");
        list("destination", IMAGE);
        client.ensureTransferImage("destination");
        server.verify();
    }

    @Test
    void skipsExistingImageWithoutCopyingAgain() {
        list("destination", IMAGE);
        client.ensureTransferImage("destination");
        server.verify();
    }

    @Test
    void searchesPaginatedSourceFolderListing() {
        list("destination", "");
        root();
        server.expect(requestTo(containsString("/files?parent_id=my-transfers")))
                .andRespond(withSuccess("{\"files\":[],\"next_page_token\":\"page-2\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/files?parent_id=my-transfers")))
                .andExpect(queryParam("page_token", "page-2"))
                .andRespond(withSuccess("{\"files\":[" + SOURCE_FOLDER + "]}", MediaType.APPLICATION_JSON));
        list("source-folder", IMAGE);
        copy();
        list("destination", IMAGE);
        client.ensureTransferImage("destination");
        server.verify();
    }

    @Test
    void missingSourceFolderFailsWithoutCreatingDirectories() {
        list("destination", "");
        root();
        list("my-transfers", "");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.ensureTransferImage("destination"));
        assertTrue(error.getMessage().contains("source folder is missing"));
        server.verify();
    }

    @Test
    void rejectsMissingImageAndDoesNotCopySameNamedFolder() {
        list("destination", "");
        root();
        list("my-transfers", SOURCE_FOLDER);
        list("source-folder", IMAGE.replace("drive#file", "drive#folder"));
        assertThrows(IllegalStateException.class, () -> client.ensureTransferImage("destination"));
        server.verify();
    }

    @Test
    void copyResponseAloneDoesNotCountAsSuccess() {
        list("destination", "");
        root();
        list("my-transfers", SOURCE_FOLDER);
        list("source-folder", IMAGE);
        copy();
        list("destination", "");
        list("destination", "");
        assertThrows(IllegalStateException.class, () -> client.ensureTransferImage("destination"));
        server.verify();
    }

    private void root() {
        list("", """
                {"id":"my-transfers","name":"我的转存","kind":"drive#folder"}
                """);
    }

    private void list(String parent, String items) {
        server.expect(requestTo(containsString("/files?parent_id=")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("parent_id", parent))
                .andRespond(withSuccess("{\"files\":[" + items + "]}", MediaType.APPLICATION_JSON));
    }

    private void copy() {
        server.expect(requestTo(containsString("/files:batchCopy")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"ids\":[\"image-id\"],\"to\":{\"parent_id\":\"destination\"}}", true))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }
}
