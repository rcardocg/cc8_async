package com.gigapixel.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigapixel.server.cli.PngInspector;
import com.gigapixel.server.model.TileKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ImageRegistryTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ImagesLayout layout() throws IOException {
        return new ImagesLayout(root.resolve("originales").toString(), root.resolve("work").toString());
    }

    private byte[] header() throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(300, 280, BufferedImage.TYPE_INT_RGB), "png", out);
        return Arrays.copyOf(out.toByteArray(), 33);
    }

    @Test
    void persistsPendingLevelsAndReloadsWithoutClaimingGeneratedTiles() throws Exception {
        var layout = layout();
        var metadata = new MetadataService(mapper, layout, true);
        byte[] header = header();
        var inspection = PngInspector.inspectHeader(header, "original.png", 93_000_000_000L, 256);
        var image = metadata.register("original", inspection, header);
        assertEquals("pending", image.state());
        assertEquals(1, image.maxZoom());
        assertEquals(5, image.totalTiles());
        assertEquals(44, image.width() - image.tileSize());
        assertTrue(image.completedLevels().isEmpty());
        assertTrue(image.availableQualities().isEmpty());
        assertTrue(Files.exists(layout.imageRoot("original").resolve("meta.json")));
        assertFalse(Files.exists(layout.imageRoot("original").resolve("tiles")));
        var restarted = new MetadataService(mapper, layout, true);
        assertEquals(image, restarted.getImage("original"));
        assertEquals(java.util.List.of("demo_numeros", "original"), restarted.listImages());
        var status = restarted.status("original");
        assertEquals("awaiting_transfer", status.sourceState());
        assertEquals(0, status.processedTiles());
        assertEquals(5, status.totalTiles());
        assertNull(status.currentLevel());
        assertThrows(IllegalArgumentException.class, () -> restarted.validateTile(new TileKey("original", 1, 1, 1, 3)));
        try (var files = Files.list(layout.imageRoot("original"))) {
            assertEquals(java.util.List.of("meta.json"), files.map(file -> file.getFileName().toString()).toList());
        }
    }

    @Test
    void coordinatesAreValidatedPerLevelAndQualityWhileIdentityStaysCompatible() throws Exception {
        var metadata = new MetadataService(mapper, layout(), true);
        var header = header();
        metadata.register("real", PngInspector.inspectHeader(header, "real.png", 100, 256), header);
        assertNotNull(metadata.validateTileCoordinates(new TileKey("real", 1, 1, 1, 3)));
        assertNotNull(metadata.validateTileCoordinates(new TileKey("real", 0, 0, 0, 0)));
        for (var key : java.util.List.of(new TileKey("real", 0, 1, 0, 0), new TileKey("real", 2, 0, 0, 3),
                new TileKey("real", 1, 2, 0, 3), new TileKey("real", 1, 0, 0, 4), new TileKey("real", 0, -1, 0, 3),
                new TileKey("real", 0, 0))) {
            assertThrows(IllegalArgumentException.class, () -> metadata.validateTileCoordinates(key));
        }
        assertEquals("real:1:1:1:3", new TileKey("real", 1, 1, 1, 3).id());
        assertEquals("demo_numeros:1:2", new TileKey("demo_numeros", 1, 2).id());
        assertEquals("ready", metadata.getImage("demo_numeros").state());
    }

    @Test
    void rejectsDuplicatesReservedIdsAndExistingFoldersWithoutOverwriting() throws Exception {
        var layout = layout(); var metadata = new MetadataService(mapper, layout, true);
        var header = header(); var inspected = PngInspector.inspectHeader(header, "original.png", 100, 256);
        metadata.register("same", inspected, header);
        byte[] before = Files.readAllBytes(layout.imageRoot("same").resolve("meta.json"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> metadata.register("same", inspected, header)).getStatusCode().value());
        assertArrayEquals(before, Files.readAllBytes(layout.imageRoot("same").resolve("meta.json")));
        assertThrows(ResponseStatusException.class, () -> metadata.register("demo_numeros", inspected, header));
        assertThrows(IllegalArgumentException.class, () -> metadata.register("../escape", inspected, header));
        Files.createDirectory(layout.imageRoot("existing"));
        assertThrows(ResponseStatusException.class, () -> metadata.register("existing", inspected, header));
        assertFalse(Files.exists(layout.imageRoot("existing").resolve("meta.json")));
    }

    @Test
    void reloadsNewRegistrationsAndFailureAndRejectsCorruptManifests() throws Exception {
        var layout = layout(); var metadata = new MetadataService(mapper, layout, true);
        var registry = new ImageRegistry(mapper, layout);
        byte[] header = header();
        registry.register("external", PngInspector.inspectHeader(header, "external.png", 100, 256), header);
        assertTrue(metadata.listImages().contains("external"));
        registry.recordFailure("external", "Prueba de fallo de procesamiento");
        assertEquals("failed", metadata.status("external").state());
        assertEquals("Prueba de fallo de procesamiento", metadata.status("external").error());
        Files.writeString(layout.imageRoot("external").resolve("meta.json"), "{}");
        assertThrows(IOException.class, () -> new ImageRegistry(mapper, layout));
    }
}
