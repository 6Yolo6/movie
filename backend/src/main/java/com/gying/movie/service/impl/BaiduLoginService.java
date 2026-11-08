package com.gying.movie.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.BaiduSessionVault;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.util.PrivateFileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** A short-lived, administrator-requested handoff to the local QR-login helper. */
@Service
public class BaiduLoginService {
    private static final long TTL_MS = 10 * 60 * 1000;
    private static final Set<String> STATES = Set.of("PENDING", "WAITING_SCAN", "CONNECTED", "FAILED", "EXPIRED");
    private static final Set<String> FAILURE_CODES = Set.of("LOGIN_REQUIRED", "SCAN_TIMEOUT", "BROWSER_UNAVAILABLE",
            "SESSION_REJECTED", "ACCOUNT_CHANGED", "VALIDATION_UNAVAILABLE", "HELPER_FAILED");
    private final ResourceHubProperties properties;
    private final BaiduSessionVault vault;
    private final BaiduWebClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public BaiduLoginService(ResourceHubProperties properties, BaiduSessionVault vault,
            BaiduWebClient client, ObjectMapper mapper) {
        this(properties, vault, client, mapper, Clock.systemUTC());
    }

    BaiduLoginService(ResourceHubProperties properties, BaiduSessionVault vault,
            BaiduWebClient client, ObjectMapper mapper, Clock clock) {
        this.properties = properties; this.vault = vault; this.client = client; this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS); this.clock = clock;
    }

    public synchronized SessionStatus start() {
        Ticket previous = current();
        if (previous != null && Set.of("PENDING", "WAITING_SCAN").contains(previous.state())) {
            return status(previous);
        }
        byte[] nonce = new byte[32]; random.nextBytes(nonce);
        long now = clock.millis();
        Ticket ticket = new Ticket(1, UUID.randomUUID().toString(), Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                "PENDING", now, now + TTL_MS, null);
        persist(ticket);
        return status(ticket);
    }

    public synchronized SessionStatus status() { return status(current()); }

    public synchronized SessionStatus helperState(String ticketId, String nonce, String state, String failureCode) {
        Ticket ticket = authenticate(ticketId, nonce);
        if ("WAITING_SCAN".equals(state)) {
            if (!"PENDING".equals(ticket.state())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Baidu login helper has already claimed this request");
            }
            ticket = new Ticket(1, ticket.ticketId(), ticket.nonce(), state, ticket.createdAt(), ticket.expiresAt(), null);
        } else if ("FAILED".equals(state) && failureCode != null && FAILURE_CODES.contains(failureCode)) {
            ticket = new Ticket(1, ticket.ticketId(), null, state, ticket.createdAt(), ticket.expiresAt(), failureCode);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Baidu login helper state");
        }
        persist(ticket);
        return status(ticket);
    }

    public synchronized SessionStatus importSession(String ticketId, String nonce, List<BaiduSessionVault.Cookie> cookies) {
        Ticket ticket = authenticate(ticketId, nonce);
        if (!"WAITING_SCAN".equals(ticket.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Baidu login helper must claim the request first");
        }
        if (cookies == null || cookies.isEmpty() || cookies.size() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Baidu browser session");
        }
        BaiduWebClient.AccountInfo account = client.validateSession(cookies);
        if (clock.millis() >= ticket.expiresAt()) {
            persist(new Ticket(1, ticket.ticketId(), null, "EXPIRED", ticket.createdAt(), ticket.expiresAt(), null));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Baidu login request expired during validation");
        }
        // A login request cannot replace the dedicated account already bound to this vault.
        BaiduSessionVault.Metadata existing = vault.metadata();
        if (existing.configured() && !existing.uid().equals(account.uid())) {
            persist(new Ticket(1, ticket.ticketId(), null, "FAILED", ticket.createdAt(), ticket.expiresAt(), "ACCOUNT_CHANGED"));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Baidu dedicated account differs; existing session retained");
        }
        vault.save(account.uid(), account.displayName(), cookies, false, ticket.ticketId());
        persist(new Ticket(1, ticket.ticketId(), null, "CONNECTED", ticket.createdAt(), ticket.expiresAt(), null));
        return status(current());
    }

    private Ticket authenticate(String ticketId, String nonce) {
        Ticket ticket = current();
        boolean valid = !vault.hasImportTicketId(ticketId) && ticketId != null && nonce != null && ticket != null && ticket.nonce() != null
                && Set.of("PENDING", "WAITING_SCAN").contains(ticket.state()) && ticketId.equals(ticket.ticketId())
                && MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8), ticket.nonce().getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Baidu login request is invalid or expired");
        return ticket;
    }

    private SessionStatus status(Ticket ticket) {
        BaiduSessionVault.Metadata metadata = vault.metadata();
        String state = ticket == null ? (metadata.configured() ? "CONNECTED" : "DISCONNECTED") : ticket.state();
        return new SessionStatus(metadata.configured(), state, ticket == null ? null : ticket.ticketId(),
                ticket == null ? null : ticket.expiresAt(), metadata.configured() ? mask(metadata.displayName()) : null,
                metadata.configured() ? Math.multiplyExact(metadata.importedAt(), 1000L) : null, ticket == null ? null : ticket.failureCode());
    }

    private Ticket current() {
        Path path = requestPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384) throw new IOException();
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(16385); }
            if (bytes.length > 16384) throw new IOException();
            Ticket ticket = mapper.readValue(bytes, Ticket.class);
            if (ticket.version() != 1 || ticket.ticketId() == null
                    || !ticket.ticketId().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                    || !STATES.contains(ticket.state()) || ticket.createdAt() < 0 || ticket.expiresAt() <= ticket.createdAt()
                    || ticket.expiresAt() - ticket.createdAt() > TTL_MS
                    || ticket.nonce() != null && !ticket.nonce().matches("[A-Za-z0-9_-]{43}")
                    || Set.of("PENDING", "WAITING_SCAN").contains(ticket.state()) && ticket.nonce() == null
                    || ticket.failureCode() != null && !FAILURE_CODES.contains(ticket.failureCode())) throw new IOException();
            if (Set.of("PENDING", "WAITING_SCAN").contains(ticket.state()) && vault.hasImportTicketId(ticket.ticketId())) {
                ticket = new Ticket(1, ticket.ticketId(), null, "CONNECTED", ticket.createdAt(), ticket.expiresAt(), null);
                persist(ticket);
            }
            if (Set.of("PENDING", "WAITING_SCAN").contains(ticket.state()) && clock.millis() >= ticket.expiresAt()) {
                ticket = new Ticket(1, ticket.ticketId(), null, "EXPIRED", ticket.createdAt(), ticket.expiresAt(), null);
                persist(ticket);
            }
            return ticket;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ResponseStatusException response) throw response;
            throw storageFailure();
        }
    }

    private Path requestPath() {
        String configured = properties.getBaidu().getLoginRequestPath();
        if (configured == null || configured.isBlank()) throw storageFailure();
        Path path = Path.of(configured).toAbsolutePath().normalize();
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw storageFailure();
        }
        return path;
    }

    private void persist(Ticket ticket) {
        try {
            Path path = requestPath();
            Files.createDirectories(path.getParent());
            if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(path.getParent(), PosixFilePermissions.fromString("rwx------"));
            }
            PrivateFileWriter.writeUtf8(path, mapper.writeValueAsString(ticket));
        } catch (IOException | RuntimeException failure) {
            throw storageFailure();
        }
    }

    private static ResponseStatusException storageFailure() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Baidu login storage unavailable");
    }
    private static String mask(String name) {
        if (name == null || name.isBlank()) return "百度专用账号";
        int first = name.codePointAt(0);
        return new String(Character.toChars(first)) + "***";
    }
    public record SessionStatus(boolean configured, String state, String ticketId, Long expiresAt,
            String accountName, Long lastValidatedAt, String failureCode) { }
    private record Ticket(int version, String ticketId, String nonce, String state, long createdAt,
            long expiresAt, String failureCode) {
        @Override public String toString() { return "BaiduLoginTicket[redacted]"; }
    }
}
