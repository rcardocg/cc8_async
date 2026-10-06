package com.gigapixel.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TileQualityServiceTest {

    @TempDir Path tempDir;

    private final TileQualityService service = new TileQualityService();

    @Test
    void quality0Produces64x64Png() throws Exception {
        BufferedImage source = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        source.setRGB(128, 128, 0xFF0000FF);
        byte[] nativePng = encodePng(source);

        byte[] q0 = service.generateQuality0(nativePng);

        assertNotNull(q0);
        assertTrue(q0.length > 0 && q0.length <= TileService.MAX_TILE_BYTES);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(q0));
        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
    }

    @Test
    void quality1ProducesJpegAt50Percent() throws Exception {
        BufferedImage source = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        byte[] nativePng = encodePng(source);

        byte[] q1 = service.generateQuality1(nativePng);

        assertNotNull(q1);
        assertTrue(q1.length > 0 && q1.length <= TileService.MAX_TILE_BYTES);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(q1));
        assertEquals(256, decoded.getWidth());
        assertEquals(256, decoded.getHeight());
    }

    @Test
    void quality2ProducesJpegAt85Percent() throws Exception {
        BufferedImage source = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        byte[] nativePng = encodePng(source);

        byte[] q2 = service.generateQuality2(nativePng);

        assertNotNull(q2);
        assertTrue(q2.length > 0 && q2.length <= TileService.MAX_TILE_BYTES);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(q2));
        assertEquals(256, decoded.getWidth());
        assertEquals(256, decoded.getHeight());
    }

    @Test
    void quality3ReturnsOriginalBytes() throws Exception {
        BufferedImage source = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        byte[] nativePng = encodePng(source);

        byte[] q3 = service.generateQuality3(nativePng);

        assertSame(nativePng, q3);
    }

    @Test
    void qualitiesAreGeneratedFromSameSourceAndDifferentSizes() throws Exception {
        BufferedImage source = createTestImage();
        byte[] nativePng = encodePng(source);

        byte[] q0 = service.generateQuality0(nativePng);
        byte[] q1 = service.generateQuality1(nativePng);
        byte[] q2 = service.generateQuality2(nativePng);
        byte[] q3 = service.generateQuality3(nativePng);

        assertTrue(q0.length < q1.length, "q0 (preview) debe ser menor que q1");
        assertTrue(q1.length < q2.length || q1.length == q2.length, "q1 <= q2");
        assertNotNull(q2);
        assertNotNull(q3);
        assertEquals(nativePng.length, q3.length);
    }

    @Test
    void quality0WithAlphaHandlesTransparency() throws Exception {
        BufferedImage source = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            source.setRGB(x, y, (x < 128 ? 0xFFFF0000 : 0x8800FF00));
        }
        byte[] nativePng = encodePng(source);

        byte[] q0 = service.generateQuality0(nativePng);

        assertNotNull(q0);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(q0));
        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
    }

    private BufferedImage createTestImage() {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            img.setRGB(x, y, (x * y) % 2 == 0 ? 0xFF0000 : 0x00FF00);
        }
        return img;
    }

    private byte[] encodePng(BufferedImage image) throws Exception {
        try (var out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) throw new IOException("Encoder PNG no disponible");
            return out.toByteArray();
        }
    }
}