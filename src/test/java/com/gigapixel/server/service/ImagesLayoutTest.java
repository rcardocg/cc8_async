package com.gigapixel.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImagesLayoutTest {
    @TempDir Path directory;

    @Test
    void createsRootsWithSpacesAndLeavesNoProbeFiles() throws Exception {
        Path originals = directory.resolve("originales con espacios");
        Path work = directory.resolve("work con espacios");
        ImagesLayout layout = new ImagesLayout(originals.toString(), work.toString());
        assertEquals(originals.toRealPath(), layout.originalsRoot());
        assertEquals(work.toRealPath(), layout.workRoot());
        assertEquals(work.resolve("ejemplo"), layout.imageRoot("ejemplo"));
        try (var files = Files.list(work)) {
            assertEquals(0, files.count());
        }
        assertTrue(layout.freeBytes() >= 0);
        assertThrows(IllegalArgumentException.class, () -> layout.imageRoot("../escape"));
    }

    @Test
    void rejectsOverlappingRoots() {
        Path originals = directory.resolve("originales");
        assertThrows(IOException.class, () -> new ImagesLayout(originals.toString(), originals.toString()));
        assertThrows(IOException.class, () -> new ImagesLayout(originals.toString(), originals.resolve("work").toString()));
        assertThrows(IOException.class, () -> new ImagesLayout(originals.resolve("nested").toString(), originals.toString()));
    }

    @Test
    void identifiesInvalidWorkPathAtStartup() throws Exception {
        Path file = Files.writeString(directory.resolve("no-es-directorio"), "x");
        IOException error = assertThrows(IOException.class, () -> new ImagesLayout(
                directory.resolve("originales").toString(), file.toString()));
        assertTrue(error.getMessage().contains("trabajo"));
        assertTrue(error.getMessage().contains(file.toString()));
    }

    @Test
    void acceptsReadOnlyOriginalsButRejectsReadOnlyWork() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path originals = Files.createDirectory(directory.resolve("originales"));
        Path work = Files.createDirectory(directory.resolve("work"));
        Set<PosixFilePermission> originalMode = Files.getPosixFilePermissions(originals);
        Set<PosixFilePermission> workMode = Files.getPosixFilePermissions(work);
        Set<PosixFilePermission> readOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
        try {
            Files.setPosixFilePermissions(originals, readOnly);
            assumeTrue(!Files.isWritable(originals), "La cuenta puede escribir incluso sin permisos (root)");
            new ImagesLayout(originals.toString(), work.toString());
            Files.setPosixFilePermissions(work, readOnly);
            IOException error = assertThrows(IOException.class,
                    () -> new ImagesLayout(originals.toString(), work.toString()));
            assertTrue(error.getMessage().contains("no escribible"));
            assertTrue(error.getMessage().contains(work.toString()));
        } finally {
            Files.setPosixFilePermissions(originals, originalMode);
            Files.setPosixFilePermissions(work, workMode);
        }
    }
}
