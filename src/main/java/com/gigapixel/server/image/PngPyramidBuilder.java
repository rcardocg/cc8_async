package com.gigapixel.server.image;

import com.gigapixel.server.cli.PngInspector;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.*;

/** Una banda RGBA en disco; RAM proporcional a filas + cinco tiles, no ancho × alto. */
public final class PngPyramidBuilder {
    @FunctionalInterface public interface Progress { void update(int z, long stagedTiles) throws IOException; }
    public record Result(String sha256, long tiles) { }

    public Result build(Path source, PngInspector.Inspection image, Path output, long memoryBytes, Progress progress) throws IOException {
        SequentialPngDecoder.requiredBufferBytes(image);
        Files.createDirectory(output);
        int size = image.pyramid().tileSize(), nativeZ = image.pyramid().maxZoom();
        Path nativeRoot = Files.createDirectory(output.resolve(Integer.toString(nativeZ)));
        Path bandPath = output.resolve("band.rgba");
        long[] completed = {0};
        String sha;
        try (var band = new RandomAccessFile(bandPath.toFile(), "rw")) {
            sha = new SequentialPngDecoder().decode(source, image, memoryBytes, (y, rgba) -> {
                checkDisk(output, rgba.length);
                band.write(rgba);
                int bandRows = y % size + 1;
                if (bandRows == size || y == image.height() - 1) {
                    for (long x = 0; x < image.width(); x += size) {
                        checkCancelled();
                        int width = (int) Math.min(size, image.width() - x);
                        BufferedImage tile = new BufferedImage(width, bandRows, BufferedImage.TYPE_INT_ARGB);
                        byte[] segment = new byte[width * 4];
                        int[] pixels = new int[width];
                        for (int row = 0; row < bandRows; row++) {
                            band.seek((row * image.width() + x) * 4); band.readFully(segment);
                            for (int p = 0; p < width; p++) pixels[p] = (segment[p * 4 + 3] & 255) << 24
                                    | (segment[p * 4] & 255) << 16 | (segment[p * 4 + 1] & 255) << 8 | segment[p * 4 + 2] & 255;
                            tile.setRGB(0, row, width, 1, pixels, 0, width);
                        }
                        writeTile(tile, nativeRoot.resolve(x / size + "_" + y / size + ".png"));
                        completed[0]++;
                    }
                    band.setLength(0); band.seek(0);
                    progress.update(nativeZ, completed[0]);
                }
            });
        } finally { Files.deleteIfExists(bandPath); }
        // El nivel nativo se declara completo solo después de validar IDAT/IEND y todos los CRC.
        progress.update(nativeZ, completed[0]);
        for (int z = nativeZ - 1; z >= 0; z--) {
            Path target = Files.createDirectory(output.resolve(Integer.toString(z)));
            var level = image.pyramid().levels().get(z);
            for (long y = 0; y < level.rows(); y++) for (long x = 0; x < level.columns(); x++) {
                checkCancelled();
                int w = (int) Math.min(size, level.width() - x * size), h = (int) Math.min(size, level.height() - y * size);
                BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                BufferedImage[][] children = new BufferedImage[2][2];
                var childLevel = image.pyramid().levels().get(z + 1);
                for (int cy = 0; cy < 2; cy++) for (int cx = 0; cx < 2; cx++) {
                    if (x * 2 + cx < childLevel.columns() && y * 2 + cy < childLevel.rows()) {
                        children[cy][cx] = readTile(output.resolve((z + 1) + "/" + (x * 2 + cx) + "_" + (y * 2 + cy) + ".png"), size);
                    }
                }
                for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
                    int n = 0, alpha = 0, red = 0, green = 0, blue = 0;
                    for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                        int sx = px * 2 + dx, sy = py * 2 + dy;
                        BufferedImage child = children[sy / size][sx / size];
                        if (child == null || sx % size >= child.getWidth() || sy % size >= child.getHeight()) continue;
                        int color = child.getRGB(sx % size, sy % size), a = color >>> 24;
                        n++; alpha += a; red += (color >> 16 & 255) * a; green += (color >> 8 & 255) * a; blue += (color & 255) * a;
                    }
                    result.setRGB(px, py, alpha == 0 ? 0 : ((alpha + n / 2) / n) << 24
                            | ((red + alpha / 2) / alpha) << 16 | ((green + alpha / 2) / alpha) << 8 | (blue + alpha / 2) / alpha);
                }
                writeTile(result, target.resolve(x + "_" + y + ".png")); completed[0]++;
                if (completed[0] % 64 == 0) progress.update(z, completed[0]);
            }
            progress.update(z, completed[0]);
        }
        return new Result(sha, completed[0]);
    }

    private static BufferedImage readTile(Path path, int size) throws IOException {
        // Solo tiles propios y acotados. Consultar dimensiones antes de decodificar incluso si se alteró el work.
        var reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var in = new FileImageInputStream(path.toFile())) {
            reader.setInput(in);
            if (reader.getWidth(0) > size || reader.getHeight(0) > size) throw new IOException("Tile excede dimensiones permitidas");
            return reader.read(0);
        } finally { reader.dispose(); }
    }

    private static void writeTile(BufferedImage tile, Path path) throws IOException {
        checkDisk(path.getParent(), (long) tile.getWidth() * tile.getHeight() * 4 + 4096);
        try (var out = new FileImageOutputStream(path.toFile())) {
            if (!ImageIO.write(tile, "png", out)) throw new IOException("Encoder PNG no disponible");
        }
    }

    public static void checkDisk(Path path, long required) throws IOException {
        if (required < 0 || Files.getFileStore(path).getUsableSpace() - 16L * 1024 * 1024 < required) throw new IOException("Espacio insuficiente en work (incluye reserva de 16 MiB)");
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Procesamiento cancelado");
    }
}
