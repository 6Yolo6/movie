package com.gying.movie.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.util.PrivateFileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Inactive account slots. The production client and the host refresh task never read these files. */
@Component
public class XunleiBackupAccounts {
    private final ResourceHubProperties properties;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public XunleiBackupAccounts(ResourceHubProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public record Status(boolean configured, Long expiresAt, boolean expired) { }
    private record Secret(String accessToken, long expiresAt) {
        @Override public String toString() { return "Xunlei backup authorization [redacted]"; }
    }
    private record Envelope(int version, String nonce, String ciphertext) { }

    public synchronized Status status(int slot) {
        Secret value = read(slot);
        return value == null ? new Status(false, null, false) : status(value);
    }

    public synchronized Status save(int slot, String authorization) {
        Path path = path(slot);
        Secret secret = parse(authorization);
        Path keyPath = keyPath(path);
        try {
            // Refuse to overwrite an unreadable slot, including if its key is missing.
            read(slot);
            BaiduSessionVault.checkPaths(path, keyPath);
            BaiduSessionVault.privateDirectory(path.getParent());
            BaiduSessionVault.checkPaths(path, keyPath);
            byte[] key;
            if (Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS)) key = BaiduSessionVault.readKey(keyPath);
            else {
                key = new byte[32];
                random.nextBytes(key);
                PrivateFileWriter.writeUtf8(keyPath, Base64.getEncoder().encodeToString(key));
            }
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, key, nonce, slot).doFinal(mapper.writeValueAsBytes(secret));
            PrivateFileWriter.writeUtf8(path, mapper.writeValueAsString(new Envelope(1,
                    Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(ciphertext))));
            return status(secret);
        } catch (Exception ignored) {
            throw unavailable();
        }
    }

    private Secret read(int slot) {
        Path path = path(slot);
        Path key = keyPath(path);
        try {
            BaiduSessionVault.checkPaths(path, key);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            Envelope envelope = mapper.readValue(BaiduSessionVault.boundedRead(path), Envelope.class);
            byte[] nonce = Base64.getDecoder().decode(envelope.nonce());
            if (envelope.version() != 1 || nonce.length != 12) throw unavailable();
            byte[] bytes = cipher(Cipher.DECRYPT_MODE, BaiduSessionVault.readKey(key), nonce, slot)
                    .doFinal(Base64.getDecoder().decode(envelope.ciphertext()));
            Secret storage = mapper.readValue(bytes, Secret.class);
            if (storage.accessToken() == null || storage.expiresAt() <= 0) throw unavailable();
            return storage;
        } catch (Exception ignored) {
            throw unavailable();
        }
    }

    private Secret parse(String authorization) {
        try {
            String token = authorization.strip().replaceFirst("(?i)^Bearer +", "");
            if (token.length() > 12288 || !token.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){2}")) throw new IllegalArgumentException();
            var claims = mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            var expiry = claims.path("exp");
            if (!expiry.isIntegralNumber() || !expiry.canConvertToLong()
                    || expiry.asLong() <= Instant.now().getEpochSecond() || claims.path("sub").asText("").isBlank()) {
                throw new IllegalArgumentException();
            }
            return new Secret(token, Math.multiplyExact(expiry.asLong(), 1000L));
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Invalid or expired Xunlei authorization");
        }
    }

    private Path path(int slot) {
        if (slot < 1 || slot > 3) throw new IllegalArgumentException("Invalid backup slot");
        String primary = properties.getXunlei().getTokenStatePath();
        if (primary == null || primary.isBlank()) throw unavailable();
        return Path.of(primary).toAbsolutePath().normalize().getParent().resolve("xunlei-backup-accounts/session-" + slot + ".enc");
    }

    private static Path keyPath(Path path) { return path.resolveSibling("vault.key"); }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, int slot) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("gying-xunlei-backup/v1/" + slot).getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }

    private static Status status(Secret value) {
        return new Status(true, value.expiresAt(), value.expiresAt() <= System.currentTimeMillis());
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Xunlei backup account storage unavailable");
    }
}
