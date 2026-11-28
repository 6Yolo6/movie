package com.gying.movie.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XunleiBackupAccountsTest {
    @TempDir Path directory;

    @Test void backupCanBeReadAfterRestartWithoutWritingThePrimaryOrDifferentSlot() throws Exception {
        Path primary = directory.resolve("xunlei-auth.json");
        Files.writeString(primary, "fixture-primary");
        var props = new ResourceHubProperties();
        props.getXunlei().setTokenStatePath(primary.toString());
        var mapper = new ObjectMapper();
        var accounts = new XunleiBackupAccounts(props, mapper);
        String token = token(Instant.now().plusSeconds(3600));
        assertFalse(accounts.status(3).configured());
        assertFalse(Files.exists(directory.resolve("xunlei-backup-accounts")));
        accounts.save(3, "Bearer " + token);
        assertEquals("fixture-primary", Files.readString(primary));
        assertFalse(Files.readString(directory.resolve("xunlei-backup-accounts/session-3.enc")).contains(token));
        assertFalse(new XunleiBackupAccounts(props, mapper).status(1).configured());
        assertTrue(new XunleiBackupAccounts(props, mapper).status(3).configured());
        assertThrows(IllegalArgumentException.class, () -> accounts.save(3, token(Instant.now().minusSeconds(10))));
        assertThrows(IllegalArgumentException.class, () -> accounts.save(4, token));
        assertTrue(accounts.status(3).configured());
    }

    private static String token(Instant expiry) {
        String claims = "{\"sub\":\"fixture-user\",\"exp\":" + expiry.getEpochSecond() + "}";
        return "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.US_ASCII)) + ".fixture";
    }
}
