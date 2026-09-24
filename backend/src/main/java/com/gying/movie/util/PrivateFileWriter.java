package com.gying.movie.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** Writes credentials privately before publishing them with a same-directory atomic rename. */
public final class PrivateFileWriter {
    private PrivateFileWriter() {
    }

    public static void writeUtf8(Path destination, String content) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        boolean posix = Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null;
        String prefix = "." + target.getFileName() + ".";
        Path temporary = posix
                ? Files.createTempFile(parent, prefix, ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(parent, prefix, ".tmp");
        try {
            if (!posix) {
                // On Windows, tighten the empty file's ACL before writing any credential bytes.
                AclFileAttributeView acl = Files.getFileAttributeView(
                        temporary, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (acl == null) {
                    throw new IOException("Credential storage requires POSIX permissions or an owner ACL");
                }
                acl.setAcl(List.of(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build()));
            }
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            // Never downgrade to a non-atomic replacement; retain the previous state on failure.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
