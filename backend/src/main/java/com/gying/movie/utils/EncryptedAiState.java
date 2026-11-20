package com.gying.movie.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** One writer, authenticated encryption, private files and atomic replacement. */
public final class EncryptedAiState implements AutoCloseable {
    private static final int LIMIT = 64 * 1024 * 1024;
    private final Path file;
    private final ObjectMapper mapper;
    private final SecretKeySpec key;
    private final FileChannel lockChannel;
    private final FileLock lock;
    public EncryptedAiState(ObjectMapper mapper, String filename, String keyFile) {
        this.mapper = mapper;
        try {
            file = Path.of(filename).toAbsolutePath().normalize();
            Path parent = file.getParent();
            rejectLinks(parent);
            Files.createDirectories(parent);
            privatePath(parent);
            Path secret = Path.of(keyFile).toAbsolutePath().normalize();
            rejectLinks(secret);
            if (Files.size(secret) > 4096) throw new IOException("Invalid state key size");
            byte[] material = Files.readAllBytes(secret);
            if (material.length < 32) throw new IOException("State key must contain at least 32 bytes");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("gying-ai-state-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            key = new SecretKeySpec(digest.digest(material), "AES");
            Arrays.fill(material, (byte) 0);
            Path lockFile = parent.resolve(file.getFileName() + ".lock");
            rejectLinks(lockFile); rejectLinks(file);
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            privatePath(lockFile);
            FileLock acquired;
            try { acquired = lockChannel.tryLock(); }
            catch (Exception e) { lockChannel.close(); throw e; }
            if (acquired == null) { lockChannel.close(); throw new IOException("AI state already has a writer"); }
            lock = acquired;
        } catch (Exception e) { throw new IllegalStateException("Cannot initialize private AI state", e); }
    }
    public JsonNode read() {
        try {
            rejectLinks(file);
            if (!Files.exists(file)) return mapper.createObjectNode();
            if (Files.size(file) > LIMIT + 32 || Files.size(file) < 29) throw new IOException("Invalid AI state size");
            byte[] bytes = Files.readAllBytes(file);
            if (bytes[0] != 1) throw new IOException("Unsupported AI state version");
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(bytes, 1, 13));
            JsonNode state = mapper.readTree(cipher.doFinal(bytes, 13, bytes.length - 13));
            if (state == null || !state.isObject()) throw new IOException("Invalid AI state document");
            return state;
        } catch (Exception e) { throw new IllegalStateException("Cannot authenticate AI state", e); }
    }
    public void write(JsonNode value) {
        Path temporary = null;
        try {
            byte[] plain = mapper.writeValueAsBytes(value);
            if (plain.length > LIMIT) throw new IOException("AI state exceeds limit");
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(plain);
            Arrays.fill(plain, (byte) 0);
            rejectLinks(file.getParent()); rejectLinks(file);
            temporary = Files.createTempFile(file.getParent(), ".ai-state-", ".tmp");
            privatePath(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer output = ByteBuffer.allocate(13 + ciphertext.length).put((byte) 1).put(nonce).put(ciphertext);
                output.flip(); while (output.hasRemaining()) channel.write(output);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (Exception e) { throw new IllegalStateException("Cannot commit AI state", e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    private Cipher cipher(int mode, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD("gying-ai-state-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return cipher;
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent()) {
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(p) || Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther())
                throw new IOException("Linked AI state paths are forbidden");
        }
    }
    private static void privatePath(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IOException("Private file permissions are unavailable");
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }
    @Override public void close() {
        try { lock.release(); lockChannel.close(); }
        catch (IOException e) { throw new IllegalStateException("Cannot release AI state writer", e); }
    }
}
