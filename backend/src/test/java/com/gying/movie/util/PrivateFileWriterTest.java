package com.gying.movie.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateFileWriterTest {
    @TempDir Path directory;

    @Test
    void createsPrivateFileAndRetainsPermissionsAcrossRepeatedReplacement() throws Exception {
        Path target = directory.resolve("nested/state.json");
        PrivateFileWriter.writeUtf8(target, "first");
        assertEquals("first", Files.readString(target));
        assertPrivate(target);
        PrivateFileWriter.writeUtf8(target, "second");
        assertEquals("second", Files.readString(target));
        assertPrivate(target);
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void replacementTightensExistingWorldReadableFile() throws Exception {
        assumePosix();
        Path target = directory.resolve("state.json");
        Files.writeString(target, "previous");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
        PrivateFileWriter.writeUtf8(target, "replacement");
        assertEquals("replacement", Files.readString(target));
        assertPrivate(target);
    }

    @Test
    void ignoresPredictableTemporarySymlinkAndDoesNotOverwriteItsVictim() throws Exception {
        assumePosix();
        Path target = directory.resolve("state.json");
        Path victim = directory.resolve("unrelated.txt");
        Files.writeString(victim, "untouched");
        Path staleTemporary = directory.resolve("state.json.tmp");
        Files.createSymbolicLink(staleTemporary, victim);
        PrivateFileWriter.writeUtf8(target, "replacement");
        assertEquals("untouched", Files.readString(victim));
        assertTrue(Files.isSymbolicLink(staleTemporary));
        assertPrivate(target);
    }

    @Test
    void replacesDestinationSymlinkRatherThanFollowingIt() throws Exception {
        assumePosix();
        Path victim = directory.resolve("unrelated.txt");
        Files.writeString(victim, "untouched");
        Path target = directory.resolve("state.json");
        Files.createSymbolicLink(target, victim);
        PrivateFileWriter.writeUtf8(target, "replacement");
        assertEquals("untouched", Files.readString(victim));
        assertFalse(Files.isSymbolicLink(target));
        assertEquals("replacement", Files.readString(target));
        assertPrivate(target);
    }

    @Test
    void failedReplacementCleansTemporaryFileAndPreservesExistingData() throws Exception {
        Path target = Files.createDirectory(directory.resolve("state.json"));
        Path existing = target.resolve("keep.txt");
        Files.writeString(existing, "untouched");
        assertThrows(IOException.class, () -> PrivateFileWriter.writeUtf8(target, "replacement"));
        assertEquals("untouched", Files.readString(existing));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    private void assumePosix() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
    }

    private void assertPrivate(Path target) throws IOException {
        if (Files.getFileAttributeView(target, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class);
            assertNotNull(acl);
            var owner = acl.getOwner();
            assertFalse(acl.getAcl().isEmpty());
            assertTrue(acl.getAcl().stream().allMatch(entry ->
                    entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner)));
        }
    }
}
