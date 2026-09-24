package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

class XunleiTokenPersistenceTest {
    @TempDir Path directory;

    @Test
    void clientPersistenceUsesPrivateAtomicWriterWithoutChangingPayload() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        Path target = directory.resolve("state.json");
        String fixture = """
                {"token_type":"Bearer","access_token":"fixture-access",
                 "refresh_token":"fixture-refresh","expires_at":4102444800,
                 "user_id":"fixture-user","captcha_token":"fixture-captcha",
                 "client_id":"fixture-client","device_id":"fixture-device",
                 "client_version":"1.0","package_name":"fixture-package"}
                """;
        Files.writeString(target, fixture);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
        ResourceHubProperties properties = new ResourceHubProperties();
        properties.getXunlei().setTokenStatePath(target.toString());
        ObjectMapper mapper = new ObjectMapper();
        XunleiClient client = new XunleiClient(new RestTemplateBuilder(), mapper, properties);
        ReflectionTestUtils.invokeMethod(client, "loadAuthState");
        Object state = ReflectionTestUtils.getField(client, "authState");
        assertNotNull(state);
        for (int i = 0; i < 2; i++) {
            ReflectionTestUtils.invokeMethod(client, "persistAuthState", state);
            assertEquals(mapper.readTree(fixture), mapper.readTree(Files.readString(target)));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target));
        }
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }
}
