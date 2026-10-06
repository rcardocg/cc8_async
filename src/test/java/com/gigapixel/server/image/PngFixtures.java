package com.gigapixel.server.image;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Fixtures pequeños independientes del decoder; codificador de los cinco filtros. */
public final class PngFixtures {
    public static byte[] chunk(String type, byte[] bytes) throws IOException {
        var output = new ByteArrayOutputStream();
        var data = new DataOutputStream(output);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(bytes);
        data.writeInt(bytes.length); data.write(name); data.write(bytes); data.writeInt((int) crc.getValue());
        return output.toByteArray();
    }

    public static byte[] png(int width, int height, int depth, int type, int filter, byte[][] rows, byte[] palette, byte[] transparency) throws IOException {
        var output = new ByteArrayOutputStream();
        new DataOutputStream(output).writeLong(0x89504e470d0a1a0aL);
        output.write(chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) depth).put((byte) type).put(new byte[3]).array()));
        if (palette != null) output.write(chunk("PLTE", palette));
        if (transparency != null) output.write(chunk("tRNS", transparency));
        var compressed = new ByteArrayOutputStream();
        int channels = switch (type) { case 2 -> 3; case 4 -> 2; case 6 -> 4; default -> 1; };
        int stride = Math.max(1, (channels * depth + 7) / 8);
        try (var deflate = new DeflaterOutputStream(compressed)) {
            for (int y = 0; y < height; y++) {
                deflate.write(filter);
                for (int x = 0; x < rows[y].length; x++) {
                    int a = x < stride ? 0 : rows[y][x - stride] & 255;
                    int b = y == 0 ? 0 : rows[y - 1][x] & 255;
                    int c = y == 0 || x < stride ? 0 : rows[y - 1][x - stride] & 255;
                    int prediction = 0;
                    if (filter == 1) prediction = a;
                    if (filter == 2) prediction = b;
                    if (filter == 3) prediction = (a + b) / 2;
                    if (filter == 4) {
                        int p = a + b - c;
                        prediction = Math.abs(p - a) <= Math.abs(p - b) && Math.abs(p - a) <= Math.abs(p - c) ? a
                                : Math.abs(p - b) <= Math.abs(p - c) ? b : c;
                    }
                    deflate.write((rows[y][x] & 255) - prediction);
                }
            }
        }
        byte[] bytes = compressed.toByteArray();
        // Fronteras IDAT deliberadamente distintas de las de filas/Deflate, incluido un chunk vacío.
        output.write(chunk("IDAT", java.util.Arrays.copyOfRange(bytes, 0, 3)));
        output.write(chunk("IDAT", new byte[0]));
        output.write(chunk("IDAT", java.util.Arrays.copyOfRange(bytes, 3, bytes.length)));
        output.write(chunk("IEND", new byte[0]));
        return output.toByteArray();
    }

    public static byte[] rgb(int width, int height) throws IOException {
        byte[][] rows = new byte[height][width * 3];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            rows[y][x * 3] = (byte) (x * 31 + y); rows[y][x * 3 + 1] = (byte) (y * 7); rows[y][x * 3 + 2] = (byte) (x + y * 3);
        }
        return png(width, height, 8, 2, 4, rows, null, null);
    }
}
