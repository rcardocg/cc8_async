package com.gigapixel.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigapixel.server.cli.GtpCli;
import com.gigapixel.server.cli.PngInspector;
import com.gigapixel.server.image.PngFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PngIngestionServiceTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();

    private ImagesLayout layout() throws IOException { return new ImagesLayout(root.resolve("originales").toString(), root.resolve("work").toString()); }
    private void register(MetadataService metadata, String id, byte[] png) throws IOException {
        byte[] header = Arrays.copyOf(png, 33);
        metadata.register(id, PngInspector.inspectHeader(header, "source.png", png.length, 64), header);
    }

    @Test void resumesUploadAfterRestartAndPublishesOnlyFullyVerifiedPyramid() throws Exception {
        var layout = layout(); var metadata = new MetadataService(mapper, layout, true);
        byte[] png = PngFixtures.rgb(129, 65); register(metadata, "uploaded", png);
        try (var ingestion = new PngIngestionService(layout, metadata, 64)) {
            assertEquals(50, ingestion.append("uploaded", 0, Arrays.copyOf(png, 50)).receivedBytes());
            assertThrows(ResponseStatusException.class, () -> ingestion.append("uploaded", 0, Arrays.copyOf(png, 50)));
            assertThrows(IllegalArgumentException.class, () -> ingestion.ingest("uploaded", null));
            assertEquals("pending", metadata.status("uploaded").state());
        }
        metadata = new MetadataService(mapper, layout, true);
        try (var ingestion = new PngIngestionService(layout, metadata, 64)) {
            assertEquals(50, ingestion.uploadStatus("uploaded").receivedBytes());
            ingestion.append("uploaded", 50, Arrays.copyOfRange(png, 50, png.length));
            ingestion.ingest("uploaded", null);
            assertEquals("ready", metadata.status("uploaded").state());
            assertEquals("verified", metadata.status("uploaded").sourceState());
            assertEquals(List.of(0, 1, 2), metadata.getImage("uploaded").completedLevels());
            assertEquals(List.of(3), metadata.getImage("uploaded").availableQualities());
            assertThrows(ResponseStatusException.class, () -> ingestion.ingest("uploaded", null));
            assertThrows(ResponseStatusException.class, () -> ingestion.resetUpload("uploaded"));
        }
        var restarted = new MetadataService(mapper, layout, true);
        assertEquals("ready", restarted.getImage("uploaded").state());
        assertTrue(restarted.listImages().contains("demo_numeros"));
        assertArrayEquals(png, Files.readAllBytes(layout.imageRoot("uploaded").resolve("source.part")));
    }

    @Test void corruptSourceFailsWithoutPublishingAndCanBeResetAndRetried() throws Exception {
        var layout = layout(); var metadata = new MetadataService(mapper, layout, false);
        byte[] png = PngFixtures.rgb(129, 65); register(metadata, "broken", png);
        byte[] broken = png.clone(); broken[broken.length - 1] ^= 1;
        try (var ingestion = new PngIngestionService(layout, metadata, 64)) {
            ingestion.append("broken", 0, broken);
            assertThrows(IOException.class, () -> ingestion.ingest("broken", null));
            assertEquals("failed", metadata.status("broken").state());
            assertTrue(metadata.status("broken").error().contains("CRC"));
            assertFalse(Files.exists(layout.imageRoot("broken").resolve("tiles")));
            assertFalse(Files.exists(layout.imageRoot("broken").resolve(".p3-tiles")));
            ingestion.resetUpload("broken"); ingestion.append("broken", 0, png); ingestion.ingest("broken", null);
            assertEquals("ready", metadata.status("broken").state());
        }
    }

    @Test void localOriginalStaysUnmodifiedAndOutsideWorkPathsAndConcurrentWritersAreRejected() throws Exception {
        var layout = layout(); var metadata = new MetadataService(mapper, layout, false);
        byte[] png = PngFixtures.rgb(65, 65); register(metadata, "local", png);
        Path original = Files.write(layout.originalsRoot().resolve("con espacios.png"), png);
        Files.write(root.resolve("outside.png"), png);
        try (var ingestion = new PngIngestionService(layout, metadata, 64)) {
            assertThrows(IllegalArgumentException.class, () -> ingestion.ingest("local", "../outside.png"));
            assertThrows(IllegalArgumentException.class, () -> ingestion.append("local", 0, new byte[33]));
            try (var channel = FileChannel.open(layout.workRoot().resolve(".p3.lock"), StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                assertThrows(ResponseStatusException.class, () -> ingestion.ingest("local", "con espacios.png"));
            }
            ingestion.ingest("local", "con espacios.png");
            assertArrayEquals(png, Files.readAllBytes(original));
            assertEquals("server_file", metadata.registry().registration("local").source().kind());
        }
    }

    @Test void interruptedProcessingBecomesFailedOnRestartAndRetriesFromBeginning() throws Exception {
        var layout = layout(); var metadata = new MetadataService(mapper, layout, false);
        byte[] png = PngFixtures.rgb(65, 65); register(metadata, "restart", png);
        Files.write(layout.originalsRoot().resolve("source.png"), png);
        metadata.registry().processing("restart", "server_file", 0, 1);
        Path staging = Files.createDirectory(layout.imageRoot("restart").resolve(".p3-tiles"));
        Files.writeString(staging.resolve("unfinished"), "old");
        var restarted = new MetadataService(mapper, layout, false);
        try (var ingestion = new PngIngestionService(layout, restarted, 64)) {
            assertEquals("failed", restarted.status("restart").state());
            assertTrue(restarted.status("restart").error().contains("interrumpido"));
            ingestion.ingest("restart", "source.png");
            assertEquals("ready", restarted.status("restart").state());
            assertFalse(Files.exists(staging));
        }
    }

    @Test void cancellationPersistsFailureAndLeavesNoPublishedTiles() throws Exception {
        var layout = layout(); var metadata = new MetadataService(mapper, layout, false);
        byte[] png = PngFixtures.rgb(1025, 1025); register(metadata, "cancel", png);
        Files.write(layout.originalsRoot().resolve("source.png"), png);
        try (var ingestion = new PngIngestionService(layout, metadata, 64)) {
            ingestion.start("cancel", "source.png");
            try (var second = new PngIngestionService(layout, new MetadataService(mapper, layout, false), 64)) {
                assertThrows(ResponseStatusException.class, () -> second.ingest("cancel", "source.png"));
                assertEquals("processing", metadata.status("cancel").state());
            }
            ingestion.cancel("cancel");
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (metadata.status("cancel").state().equals("processing") && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals("failed", metadata.status("cancel").state());
            assertTrue(metadata.status("cancel").error().toLowerCase().contains("cancel"));
            assertFalse(Files.exists(layout.imageRoot("cancel").resolve("tiles")));
        }
    }

    @Test void cliIngestCreatesRealManifestAndTilesWithoutStartingSpring() throws Exception {
        var layout = layout();
        Path source = Files.write(layout.originalsRoot().resolve("cli source.png"), PngFixtures.rgb(65, 65));
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = new GtpCli().run(new String[]{"ingest", source.toString(), "--image-id", "cli", "--work", layout.workRoot().toString(), "--tile-size", "64", "--max-memory-mib", "64"}, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err.toString());
        assertEquals("ready", mapper.readTree(out.toByteArray()).path("state").asText());
        assertTrue(Files.exists(layout.imageRoot("cli").resolve("tiles/0/0_0.png")));
        assertFalse(out.toString().contains("Spring"));
    }
}
