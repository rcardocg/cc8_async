package com.gigapixel.server.image;

import com.gigapixel.server.cli.PngInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SequentialPngDecoderTest {
    @TempDir Path root;
    private static final long BUDGET = 64L * 1024 * 1024;

    @Test void reconstructsAllFiveFiltersAcrossIdatBoundariesAndChecksDigest() throws Exception {
        byte[][] rows = new byte[7][13 * 3]; new Random(42).nextBytes(rows[0]);
        for (int y = 1; y < rows.length; y++) new Random(y).nextBytes(rows[y]);
        for (int filter = 0; filter < 5; filter++) {
            byte[] png = PngFixtures.png(13, 7, 8, 2, filter, rows, null, null);
            Path file = Files.write(root.resolve("input.png"), png);
            int[] count = {0};
            String sha = new SequentialPngDecoder().decode(file, PngInspector.inspect(file, 64), BUDGET, (y, rgba) -> {
                count[0]++;
                for (int x = 0; x < 13; x++) {
                    for (int channel = 0; channel < 3; channel++) assertEquals(rows[y][x * 3 + channel], rgba[x * 4 + channel]);
                    assertEquals(255, rgba[x * 4 + 3] & 255);
                }
            });
            assertEquals(7, count[0]);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)), sha);
        }
    }

    @Test void supportsPackedGrayscalePaletteAndTransparencyAndEightBitAlpha() throws Exception {
        for (int depth : new int[]{1, 2, 4, 8}) {
            int mask = (1 << depth) - 1;
            byte[] packed = new byte[(9 * depth + 7) / 8];
            for (int x = 0; x < 9; x++) packed[x * depth / 8] |= (byte) ((x & mask) << (8 - depth - x * depth % 8));
            byte[][] rows = {packed};
            var gray = decode(PngFixtures.png(9, 1, depth, 0, 0, rows, null, new byte[]{0, 1}));
            for (int x = 0; x < 9; x++) {
                assertEquals((x & mask) * 255 / mask, gray.get(0)[x * 4] & 255);
                assertEquals((x & mask) == 1 ? 0 : 255, gray.get(0)[x * 4 + 3] & 255);
            }
            byte[] palette = new byte[(mask + 1) * 3], alpha = new byte[mask + 1];
            for (int i = 0; i <= mask; i++) { palette[i * 3] = (byte) i; alpha[i] = (byte) (255 - i); }
            var indexed = decode(PngFixtures.png(9, 1, depth, 3, 0, rows, palette, alpha));
            for (int x = 0; x < 9; x++) {
                assertEquals(x & mask, indexed.get(0)[x * 4] & 255);
                assertEquals(255 - (x & mask), indexed.get(0)[x * 4 + 3] & 255);
            }
        }
        assertArrayEquals(new byte[]{10, 10, 10, 20}, decode(PngFixtures.png(1, 1, 8, 4, 0, new byte[][]{{10, 20}}, null, null)).get(0));
        assertArrayEquals(new byte[]{10, 20, 30, 40}, decode(PngFixtures.png(1, 1, 8, 6, 0, new byte[][]{{10, 20, 30, 40}}, null, null)).get(0));
        assertArrayEquals(new byte[]{10, 20, 30, 0}, decode(PngFixtures.png(1, 1, 8, 2, 0, new byte[][]{{10, 20, 30}}, null, new byte[]{0, 10, 0, 20, 0, 30})).get(0));
    }

    @Test void rejectsCorruptionTruncationInvalidFiltersExtraRowsAndExtraCompressedData() throws Exception {
        byte[] png = PngFixtures.rgb(5, 3);
        byte[] crc = png.clone(); crc[45] ^= 1;
        byte[] trailing = Arrays.copyOf(png, png.length + 1);
        byte[] extraZlib = join(Arrays.copyOf(png, png.length - 12), PngFixtures.chunk("IDAT", new byte[]{1}), Arrays.copyOfRange(png, png.length - 12, png.length));
        byte[] moreRows = PngFixtures.rgb(5, 4);
        byte[] newHeader = Arrays.copyOfRange(moreRows, 16, 29); ByteBuffer.wrap(newHeader).putInt(4, 3);
        System.arraycopy(PngFixtures.chunk("IHDR", newHeader), 0, moreRows, 8, 25);
        byte[] invalidFilter = PngFixtures.png(1, 1, 8, 2, 5, new byte[][]{{1, 2, 3}}, null, null);
        for (byte[] invalid : List.of(crc, trailing, extraZlib, moreRows, invalidFilter, Arrays.copyOf(png, png.length - 1), Arrays.copyOf(png, 50))) {
            assertThrows(IOException.class, () -> decode(invalid));
        }
    }

    @Test void rejectsUnsupportedVariantsBeforeAllocatingAndStopsOnInterruption() throws Exception {
        byte[] png = PngFixtures.rgb(5, 3);
        for (int mode = 0; mode < 2; mode++) {
            byte[] data = Arrays.copyOfRange(png, 16, 29);
            if (mode == 0) data[8] = 16; else data[12] = 1;
            byte[] unsupported = join(Arrays.copyOf(png, 8), PngFixtures.chunk("IHDR", data), Arrays.copyOfRange(png, 33, png.length));
            assertTrue(assertThrows(IOException.class, () -> decode(unsupported)).getMessage().contains("Adam7 ni 16 bits"));
        }
        Path file = Files.write(root.resolve("memory.png"), png);
        var inspected = PngInspector.inspect(file, 64);
        assertThrows(IOException.class, () -> new SequentialPngDecoder().decode(file, inspected, 1024, (y, row) -> fail()));
        try {
            assertThrows(InterruptedIOException.class, () -> new SequentialPngDecoder().decode(file, inspected, BUDGET, (y, row) -> Thread.currentThread().interrupt()));
        } finally { Thread.interrupted(); }
        assertThrows(IOException.class, () -> PngPyramidBuilder.checkDisk(root, Long.MAX_VALUE));
    }

    @Test void pyramidPreservesEveryNativePixelAndAveragesOddEdgesAtAllLevels() throws Exception {
        Path input = Files.write(root.resolve("source.png"), PngFixtures.rgb(259, 131));
        var inspection = PngInspector.inspect(input, 64);
        Path output = root.resolve("tiles");
        List<Integer> completed = new ArrayList<>();
        var result = new PngPyramidBuilder().build(input, inspection, output, BUDGET, (z, n) -> completed.add(z));
        assertEquals(inspection.pyramid().totalTiles(), result.tiles());
        assertEquals(List.of(3, 2, 1, 0), completed.stream().distinct().toList());
        var reference = ImageIO.read(input.toFile());
        for (int z = inspection.pyramid().maxZoom(); z >= 0; z--) {
            var level = inspection.pyramid().levels().get(z);
            for (int y = 0; y < level.rows(); y++) for (int x = 0; x < level.columns(); x++) {
                var tile = ImageIO.read(output.resolve(z + "/" + x + "_" + y + ".png").toFile());
                assertEquals(Math.min(64, reference.getWidth() - x * 64), tile.getWidth());
                assertEquals(Math.min(64, reference.getHeight() - y * 64), tile.getHeight());
                for (int py = 0; py < tile.getHeight(); py++) for (int px = 0; px < tile.getWidth(); px++) {
                    assertEquals(reference.getRGB(x * 64 + px, y * 64 + py), tile.getRGB(px, py), "z=" + z);
                }
            }
            var reduced = new java.awt.image.BufferedImage((reference.getWidth() + 1) / 2, (reference.getHeight() + 1) / 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < reduced.getHeight(); y++) for (int x = 0; x < reduced.getWidth(); x++) {
                int[] sum = new int[3]; int n = 0;
                for (int sy = y * 2; sy < Math.min(y * 2 + 2, reference.getHeight()); sy++) for (int sx = x * 2; sx < Math.min(x * 2 + 2, reference.getWidth()); sx++) {
                    int pixel = reference.getRGB(sx, sy); n++;
                    for (int c = 0; c < 3; c++) sum[c] += pixel >> (16 - c * 8) & 255;
                }
                reduced.setRGB(x, y, ((sum[0] + n / 2) / n) << 16 | ((sum[1] + n / 2) / n) << 8 | (sum[2] + n / 2) / n);
            }
            reference = reduced;
        }
        assertFalse(Files.exists(output.resolve("band.rgba")));
    }

    private List<byte[]> decode(byte[] png) throws IOException {
        Path file = Files.write(root.resolve("fixture.png"), png);
        List<byte[]> rows = new ArrayList<>();
        new SequentialPngDecoder().decode(file, PngInspector.inspect(file, 64), BUDGET, (y, row) -> rows.add(row.clone()));
        return rows;
    }

    @Test void reductionUsesPremultipliedAlphaAndNativeTilesKeepHiddenRgb() throws Exception {
        byte[][] rows = new byte[3][129 * 4];
        for (byte[] row : rows) for (int x = 0; x < 129; x++) {
            row[x * 4] = 10; row[x * 4 + 1] = 20; row[x * 4 + 2] = 30; row[x * 4 + 3] = 50;
        }
        System.arraycopy(new byte[]{(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, 0}, 0, rows[0], 0, 8);
        System.arraycopy(new byte[]{0, (byte) 255, 0, (byte) 128, 0, 0, (byte) 255, 0}, 0, rows[1], 0, 8);
        Path input = Files.write(root.resolve("alpha.png"), PngFixtures.png(129, 3, 8, 6, 4, rows, null, null));
        Path output = root.resolve("alpha-tiles");
        new PngPyramidBuilder().build(input, PngInspector.inspect(input, 64), output, BUDGET, (z, n) -> { });
        assertEquals(0x000000ff, ImageIO.read(output.resolve("2/0_0.png").toFile()).getRGB(1, 0));
        assertEquals(0x60aa5500, ImageIO.read(output.resolve("1/0_0.png").toFile()).getRGB(0, 0));
        assertEquals(0x320a141e, ImageIO.read(output.resolve("1/1_0.png").toFile()).getRGB(0, 1));
    }
    private static byte[] join(byte[]... parts) throws IOException {
        var out = new ByteArrayOutputStream(); for (byte[] part : parts) out.write(part); return out.toByteArray();
    }
}
