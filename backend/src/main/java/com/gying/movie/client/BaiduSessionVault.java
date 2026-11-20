package com.gying.movie.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.util.PrivateFileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Credentials have one private, encrypted durable copy; metadata reads never create keys. */
@Component
public class BaiduSessionVault {
    private static final int MAX_BYTES = 512 * 1024;
    private static final byte[] AAD = "gying-baidu-session/v1".getBytes(StandardCharsets.UTF_8);
    private final ResourceHubProperties properties;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public BaiduSessionVault(ResourceHubProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public record Cookie(String name, String value, String domain, String path, double expires, boolean secure) {
        @Override public String toString() { return "BaiduCookie[redacted]"; }
    }
    public record Session(String uid, String displayName, List<Cookie> cookies, long importedAt, String importTicketId) {
        public Session(String uid, String displayName, List<Cookie> cookies, long importedAt) { this(uid, displayName, cookies, importedAt, null); }
        @Override public String toString() { return "BaiduSession[redacted]"; }
    }
    public record Metadata(boolean configured, String uid, String displayName, long importedAt) { }
    private record Envelope(int version, String nonce, String ciphertext) {
        @Override public String toString() { return "BaiduEncryptedEnvelope[redacted]"; }
    }

    public synchronized Metadata metadata() {
        Session session = read();
        return session == null ? new Metadata(false, null, null, 0)
                : new Metadata(true, session.uid(), session.displayName(), session.importedAt());
    }

    public synchronized Session read() {
        try {
            Path state = statePath();
            Path key = keyPath();
            checkPaths(state, key);
            if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) return null;
            if (!Files.exists(key, LinkOption.NOFOLLOW_LINKS)) throw storageError();
            byte[] keyBytes = readKey(key);
            byte[] bytes = boundedRead(state);
            Envelope envelope = mapper.readValue(bytes, Envelope.class);
            if (envelope.version() != 1) throw storageError();
            byte[] nonce = Base64.getDecoder().decode(envelope.nonce());
            byte[] ciphertext = Base64.getDecoder().decode(envelope.ciphertext());
            if (nonce.length != 12 || ciphertext.length < 16) throw storageError();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            Session session = mapper.readValue(cipher.doFinal(ciphertext), Session.class);
            validateAccount(session.uid(), session.displayName());
            if (session.importedAt() <= 0 || session.importedAt() > Instant.now().getEpochSecond() + 60) throw storageError();
            if (session.importTicketId() != null && !session.importTicketId().matches("[A-Za-z0-9_-]{16,128}")) throw storageError();
            return new Session(session.uid(), session.displayName(), validateCookies(session.cookies(), false), session.importedAt(), session.importTicketId());
        } catch (Exception ignored) {
            throw storageError();
        }
    }

    public synchronized void save(String uid, String displayName, List<Cookie> cookies, boolean allowAccountChange) {
        save(uid, displayName, cookies, allowAccountChange, null);
    }

    public synchronized boolean hasImportTicketId(String ticketId) {
        Session current = read();
        return current != null && ticketId != null && ticketId.equals(current.importTicketId());
    }

    public synchronized String lastImportTicketId() {
        Session current = read();
        return current == null ? null : current.importTicketId();
    }

    public synchronized void save(String uid, String displayName, List<Cookie> cookies, boolean allowAccountChange, String importTicketId) {
        if (importTicketId != null && !importTicketId.matches("[A-Za-z0-9_-]{16,128}")) throw new IllegalArgumentException("Invalid Baidu import ticket");
        validateAccount(uid, displayName);
        List<Cookie> accepted = validateCookies(cookies);
        Session previous = read();
        if (previous != null && importTicketId != null && importTicketId.equals(previous.importTicketId())) {
            throw new IllegalStateException("Baidu login ticket was already imported");
        }
        if (previous != null && !previous.uid().equals(uid) && !allowAccountChange) {
            throw new IllegalStateException("Baidu account replacement requires explicit confirmation");
        }
        try {
            Path state = statePath();
            Path key = keyPath();
            checkPaths(state, key);
            privateDirectory(state.getParent());
            checkPaths(state, key);
            byte[] keyBytes;
            if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) keyBytes = readKey(key);
            else {
                if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) throw storageError();
                keyBytes = new byte[32];
                random.nextBytes(keyBytes);
                PrivateFileWriter.writeUtf8(key, Base64.getEncoder().encodeToString(keyBytes));
            }
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            Session session = new Session(uid, displayName, accepted, Instant.now().getEpochSecond(), importTicketId);
            byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(session));
            Envelope envelope = new Envelope(1, Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(encrypted));
            String encoded = mapper.writeValueAsString(envelope);
            if (encoded.length() > MAX_BYTES) throw storageError();
            checkPaths(state, key);
            PrivateFileWriter.writeUtf8(state, encoded);
        } catch (Exception ignored) {
            throw storageError();
        }
    }

    public static List<Cookie> validateCookies(List<Cookie> cookies) {
        return validateCookies(cookies, true);
    }

    private static List<Cookie> validateCookies(List<Cookie> cookies, boolean activeOnly) {
        if (cookies == null || cookies.isEmpty() || cookies.size() > 100) throw new IllegalArgumentException("Invalid Baidu session cookies");
        List<Cookie> result = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        boolean authenticated = false;
        int total = 0;
        long now = Instant.now().getEpochSecond();
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.name() == null || !cookie.name().matches("[A-Za-z0-9_!#$%&'*+.^`|~-]{1,200}")
                    || cookie.value() == null || cookie.value().isEmpty() || cookie.value().length() > 32768
                    || cookie.value().chars().anyMatch(ch -> ch <= 32 || ch >= 127 || ch == ';')
                    || cookie.domain() == null || cookie.path() == null || !Double.isFinite(cookie.expires())) {
                throw new IllegalArgumentException("Invalid Baidu session cookies");
            }
            String domain = cookie.domain().toLowerCase(Locale.ROOT);
            if (!Set.of(".baidu.com", "baidu.com", "pan.baidu.com", ".pan.baidu.com", "passport.baidu.com", ".passport.baidu.com").contains(domain)
                    || !cookie.path().startsWith("/") || cookie.path().length() > 1024
                    || cookie.path().chars().anyMatch(ch -> ch < 32 || ch == 127)
                    || cookie.expires() == 0 || cookie.expires() < -1) {
                throw new IllegalArgumentException("Invalid Baidu session cookies");
            }
            if (activeOnly && cookie.expires() > 0 && cookie.expires() <= now) continue;
            String identity = domain + "|" + cookie.path() + "|" + cookie.name();
            if (!seen.add(identity)) throw new IllegalArgumentException("Duplicate Baidu session cookie");
            total += cookie.name().length() + cookie.value().length();
            if (total > 128 * 1024) throw new IllegalArgumentException("Baidu session cookies exceed size limit");
            result.add(new Cookie(cookie.name(), cookie.value(), domain, cookie.path(), cookie.expires(), cookie.secure()));
            if (("BDUSS".equals(cookie.name()) || "BDUSS_BFESS".equals(cookie.name())) && domainMatches(domain, "pan.baidu.com")) authenticated = true;
        }
        if (!authenticated) throw new IllegalArgumentException("Baidu login session is missing or expired");
        return List.copyOf(result);
    }

    static boolean domainMatches(String domain, String host) {
        String bare = domain.startsWith(".") ? domain.substring(1) : domain;
        return domain.startsWith(".") ? host.equals(bare) || host.endsWith("." + bare) : host.equals(bare);
    }

    private Path statePath() { return Path.of(properties.getBaidu().getSessionStatePath()).toAbsolutePath().normalize(); }
    private Path keyPath() { return Path.of(properties.getBaidu().getSessionKeyPath()).toAbsolutePath().normalize(); }

    private static void checkPaths(Path state, Path key) throws Exception {
        if (state.equals(key) || state.getParent() == null || !state.getParent().equals(key.getParent())
                || state.getParent().getParent() == null
                || !state.getFileName().toString().matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,100}")
                || !key.getFileName().toString().matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,100}")) throw storageError();
        for (Path candidate : List.of(state, key)) {
            for (Path cursor = candidate; cursor != null; cursor = cursor.getParent()) {
                if (Files.isSymbolicLink(cursor)) throw storageError();
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) throw storageError();
        }
    }

    private static byte[] boundedRead(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) <= 0 || Files.size(path) > MAX_BYTES) throw storageError();
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw storageError();
            return bytes;
        }
    }

    private static byte[] readKey(Path key) throws Exception {
        if (Files.getFileAttributeView(key, PosixFileAttributeView.class) != null
                && Files.getPosixFilePermissions(key).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) throw storageError();
        byte[] bytes = boundedRead(key);
        if (bytes.length > 128) throw storageError();
        byte[] decoded = Base64.getDecoder().decode(new String(bytes, StandardCharsets.US_ASCII));
        if (decoded.length != 32) throw storageError();
        return decoded;
    }

    private static void privateDirectory(Path parent) throws Exception {
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)) throw storageError();
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(parent, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw storageError();
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }

    private static void validateAccount(String uid, String displayName) {
        if (uid == null || !uid.matches("[0-9]{1,30}") || displayName == null || displayName.length() > 200
                || displayName.chars().anyMatch(ch -> ch < 32 || ch == 127)) throw new IllegalArgumentException("Invalid Baidu account metadata");
    }

    private static IllegalStateException storageError() { return new IllegalStateException("Baidu session storage is unavailable or invalid"); }
}
