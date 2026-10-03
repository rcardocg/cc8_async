package com.gigapixel.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigapixel.server.model.TileKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MetadataAndTileServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void demoMetadataAndRealPngAgree() throws Exception {
        MetadataService metadata = new MetadataService(mapper, directory.toString(), true);
        var image = metadata.getImage(MetadataService.DEMO_ID);
        assertEquals(256L, image.totalTiles());
        byte[] bytes = new TileService(metadata).readTile(new TileKey(image.imageId(), 15, 15));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertEquals(256, decoded.getWidth());
        assertEquals(256, decoded.getHeight());
        assertTrue(bytes.length <= TileService.MAX_TILE_BYTES);
    }

    @Test
    void unknownImageAndOutOfBoundsDoNotReturnFabricatedMetadata() throws Exception {
        MetadataService metadata = new MetadataService(mapper, directory.toString(), true);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> metadata.getImage("missing")).getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> metadata.validateTile(new TileKey(MetadataService.DEMO_ID, -1, 0)));
        assertThrows(IllegalArgumentException.class, () -> metadata.validateTile(new TileKey(MetadataService.DEMO_ID, 16, 0)));
    }

    @Test
    void externalCatalogReadsOnlyRequestedTileIncludingPartialEdges() throws Exception {
        writeCatalog("real", 300, 280, 4);
        Path folder = Files.createDirectory(directory.resolve("real"));
        ImageIO.write(new BufferedImage(44, 24, BufferedImage.TYPE_INT_RGB), "png", folder.resolve("1_1.png").toFile());
        MetadataService metadata = new MetadataService(mapper, directory.toString(), false);
        assertEquals(java.util.List.of("real"), metadata.listImages());
        TileService tiles = new TileService(metadata);
        var decoded = ImageIO.read(new ByteArrayInputStream(tiles.readTile(new TileKey("real", 1, 1))));
        assertEquals(44, decoded.getWidth());
        assertEquals(24, decoded.getHeight());
        assertThrows(IOException.class, () -> tiles.readTile(new TileKey("real", 0, 0)));
    }

    @Test
    void invalidCatalogFailsAtStartup() throws Exception {
        writeCatalog("../escape", 256, 256, 1);
        assertThrows(IOException.class, () -> new MetadataService(mapper, directory.toString(), false));
        writeCatalog("real", 300, 280, 1);
        assertThrows(IOException.class, () -> new MetadataService(mapper, directory.toString(), false));
    }

    @Test
    void totalTilesUsesLongArithmeticForLargeDimensions() throws Exception {
        long side = (Integer.MAX_VALUE + 255L) / 256;
        writeCatalog("large", Integer.MAX_VALUE, Integer.MAX_VALUE, side * side);
        assertEquals(side * side, new MetadataService(mapper, directory.toString(), false).getImage("large").totalTiles());
    }

    @Test
    void rejectsOversizedOrIncorrectlySizedTile() throws Exception {
        writeCatalog("real", 256, 256, 1);
        Path folder = Files.createDirectory(directory.resolve("real"));
        Path tile = folder.resolve("0_0.png");
        TileService tiles = new TileService(new MetadataService(mapper, directory.toString(), false));
        Files.write(tile, new byte[TileService.MAX_TILE_BYTES + 1]);
        assertThrows(IOException.class, () -> tiles.readTile(new TileKey("real", 0, 0)));
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", tile.toFile());
        assertThrows(IOException.class, () -> tiles.readTile(new TileKey("real", 0, 0)));
    }

    private void writeCatalog(String id, int width, int height, long total) throws IOException {
        Files.writeString(directory.resolve("catalog.json"), """
                [{"imageId":"%s","width":%d,"height":%d,"tileSize":256,"totalTiles":%d,"maxZoom":null,"format":"png"}]
                """.formatted(id, width, height, total));
    }
}
