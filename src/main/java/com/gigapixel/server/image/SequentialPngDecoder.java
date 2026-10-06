package com.gigapixel.server.image;

import com.gigapixel.server.cli.PngInspector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** PNG secuencial: dos filas filtradas y una RGBA8. Nunca materializa el original. */
public final class SequentialPngDecoder {
    @FunctionalInterface public interface Rows { void accept(int y, byte[] rgba) throws IOException; }

    public static long requiredBufferBytes(PngInspector.Inspection image) throws IOException {
        if (image.interlaced() || image.bitDepth() == 16) {
            throw new IOException("P3 inicial no soporta Adam7 ni 16 bits; el original no se convierte con pérdida");
        }
        long row = image.packedScanlineBytes() - 1;
        if (row > Integer.MAX_VALUE - 8 || image.width() * 4 > Integer.MAX_VALUE - 8) {
            throw new IOException("Fila excede el tamaño de buffer admitido por Java");
        }
        return 2 * row + image.width() * 4;
    }

    public String decode(Path source, PngInspector.Inspection expected, long memoryBytes, Rows rows) throws IOException {
        if (requiredBufferBytes(expected) > memoryBytes - 32L * 1024 * 1024) {
            throw new IOException("Presupuesto insuficiente para filas y margen de 32 MiB");
        }
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try (var input = new DataInputStream(new BufferedInputStream(new DigestInputStream(Files.newInputStream(source), digest)))) {
            byte[] header = input.readNBytes(33);
            var actual = PngInspector.inspectHeader(header, expected.path(), Files.size(source), expected.pyramid().tileSize());
            if (!actual.equals(expected)) throw new IOException("El original cambió desde la inspección");
            var chunks = new IdatStream(input, expected.colorType(), expected.bitDepth());
            chunks.begin();
            Inflater inflater = new Inflater();
            try {
                var inflated = new DataInputStream(new InflaterInputStream(chunks, inflater, 8192));
                int rowBytes = (int) expected.packedScanlineBytes() - 1;
                byte[] previous = new byte[rowBytes], current = new byte[rowBytes];
                byte[] rgba = new byte[Math.toIntExact(expected.width() * 4)];
                int bpp = Math.max(1, (expected.channels() * expected.bitDepth() + 7) / 8);
                for (int y = 0; y < expected.height(); y++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Procesamiento cancelado");
                    int filter = inflated.readUnsignedByte();
                    inflated.readFully(current);
                    if (filter > 4) throw new IOException("Filtro PNG inválido: " + filter);
                    for (int i = 0; i < current.length; i++) {
                        int a = i < bpp ? 0 : current[i - bpp] & 255;
                        int b = previous[i] & 255, c = i < bpp ? 0 : previous[i - bpp] & 255;
                        int prediction = switch (filter) {
                            case 1 -> a; case 2 -> b; case 3 -> (a + b) / 2; case 4 -> paeth(a, b, c); default -> 0;
                        };
                        current[i] = (byte) ((current[i] & 255) + prediction);
                    }
                    convert(current, rgba, expected, chunks.palette, chunks.transparency);
                    rows.accept(y, rgba);
                    byte[] swap = previous; previous = current; current = swap;
                }
                if (inflated.read() != -1) throw new IOException("IDAT contiene filas adicionales");
                if (!inflater.finished() || inflater.getRemaining() != 0 || chunks.read() != -1) {
                    throw new IOException("Final Deflate inválido o bytes comprimidos adicionales");
                }
            } finally { inflater.end(); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static int ushort(byte[] bytes, int i) { return (bytes[i] & 255) * 256 + (bytes[i + 1] & 255); }

    private static void convert(byte[] row, byte[] rgba, PngInspector.Inspection image, byte[] palette, byte[] trns) throws IOException {
        int type = image.colorType(), depth = image.bitDepth(), mask = (1 << depth) - 1;
        for (int x = 0, p = 0; x < image.width(); x++, p += 4) {
            int r, g, b, a = 255;
            long bit = (long) x * depth;
            int sample = depth < 8 ? ((row[(int) (bit / 8)] & 255) >> (8 - depth - (int) (bit % 8))) & mask : row[x * image.channels()] & 255;
            if (type == 3) {
                if (sample * 3 + 2 >= palette.length) throw new IOException("Índice fuera de PLTE");
                r = palette[sample * 3] & 255; g = palette[sample * 3 + 1] & 255; b = palette[sample * 3 + 2] & 255;
                if (trns != null && sample < trns.length) a = trns[sample] & 255;
            } else if (type == 0 || type == 4) {
                r = g = b = sample * 255 / mask;
                if (type == 4) a = row[x * 2 + 1] & 255;
                else if (trns != null && sample == ushort(trns, 0)) a = 0;
            } else {
                int offset = x * image.channels();
                r = row[offset] & 255; g = row[offset + 1] & 255; b = row[offset + 2] & 255;
                if (type == 6) a = row[offset + 3] & 255;
                else if (trns != null && r == ushort(trns, 0) && g == ushort(trns, 2) && b == ushort(trns, 4)) a = 0;
            }
            rgba[p] = (byte) r; rgba[p + 1] = (byte) g; rgba[p + 2] = (byte) b; rgba[p + 3] = (byte) a;
        }
    }

    /** Concatena IDAT contiguos, comprobando cada CRC y el final completo del contenedor. */
    private static final class IdatStream extends InputStream {
        final DataInputStream input;
        final int colorType, depth;
        final CRC32 crc = new CRC32();
        final byte[] scratch = new byte[8192];
        byte[] palette, transparency;
        String type;
        int remaining;
        boolean ended;

        IdatStream(DataInputStream input, int colorType, int depth) {
            this.input = input; this.colorType = colorType; this.depth = depth;
        }

        void next() throws IOException {
            remaining = input.readInt();
            if (remaining < 0) throw new IOException("Longitud de chunk PNG fuera de rango");
            byte[] name = new byte[4]; input.readFully(name);
            for (byte ch : name) if (!(ch >= 'A' && ch <= 'Z') && !(ch >= 'a' && ch <= 'z')) throw new IOException("Tipo de chunk inválido");
            if ((name[2] & 32) != 0) throw new IOException("Bit reservado del chunk PNG inválido");
            type = new String(name, StandardCharsets.US_ASCII); crc.reset(); crc.update(name);
        }

        void finishCrc() throws IOException {
            if (crc.getValue() != Integer.toUnsignedLong(input.readInt())) throw new IOException("CRC inválido en " + type);
        }

        int payload(byte[] b, int off, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Procesamiento cancelado");
            int n = input.read(b, off, Math.min(length, remaining));
            if (n < 0) throw new EOFException("Chunk PNG truncado: " + type);
            crc.update(b, off, n); remaining -= n; return n;
        }

        void skipChunk() throws IOException {
            if (Character.isUpperCase(type.charAt(0)) || type.equals("acTL")) throw new IOException("Chunk no soportado o fuera de orden: " + type);
            while (remaining > 0) payload(scratch, 0, scratch.length);
            finishCrc();
        }

        byte[] smallChunk() throws IOException {
            byte[] bytes = new byte[remaining];
            int off = 0; while (remaining > 0) off += payload(bytes, off, remaining);
            finishCrc(); return bytes;
        }

        void begin() throws IOException {
            next();
            while (!type.equals("IDAT")) {
                if (type.equals("PLTE")) {
                    if (palette != null || transparency != null || colorType == 0 || colorType == 4 || remaining == 0 || remaining > 768
                            || remaining % 3 != 0 || (colorType == 3 && remaining / 3 > 1 << depth)) throw new IOException("PLTE inválida");
                    palette = smallChunk();
                } else if (type.equals("tRNS")) {
                    if (transparency != null || !(colorType == 0 && remaining == 2 || colorType == 2 && remaining == 6
                            || colorType == 3 && palette != null && remaining > 0 && remaining <= palette.length / 3)) throw new IOException("tRNS inválido");
                    transparency = smallChunk();
                    if (colorType != 3) for (int i = 0; i < transparency.length; i += 2) {
                        if (ushort(transparency, i) >= 1 << depth) throw new IOException("Muestra tRNS fuera de rango");
                    }
                } else skipChunk();
                next();
            }
            if (colorType == 3 && palette == null) throw new IOException("Falta PLTE");
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Procesamiento cancelado");
            if (ended) return -1;
            while (remaining == 0) {
                finishCrc(); next();
                if (!type.equals("IDAT")) {
                    while (!type.equals("IEND")) {
                        if (type.equals("tRNS")) throw new IOException("tRNS posterior a IDAT");
                        skipChunk(); next();
                    }
                    if (remaining != 0) throw new IOException("IEND debe estar vacío");
                    finishCrc();
                    if (input.read() != -1) throw new IOException("Datos posteriores a IEND");
                    ended = true; return -1;
                }
            }
            return payload(b, off, len);
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
    }
}
