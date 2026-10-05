package com.gigapixel.server.cli;

import com.gigapixel.server.image.PyramidMath;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Solo lee los primeros 33 bytes. No abre ImageIO ni descomprime IDAT. */
public final class PngInspector {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    public record Inspection(String path, long sizeBytes, String format, long width, long height,
                             int bitDepth, int colorType, int channels, boolean interlaced,
                             long fullDecodeRgbaBytes, long packedScanlineBytes, long minimumRowBuffersBytes,
                             String validation, PyramidMath.Pyramid pyramid) { }

    private PngInspector() { }

    public static Inspection inspect(Path input, int tileSize) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IOException("No es un archivo PNG regular: " + path);
        byte[] header;
        try (DataInputStream stream = new DataInputStream(Files.newInputStream(path))) {
            header = stream.readNBytes(33);
        }
        return inspectHeader(header, path.toString(), Files.size(path), tileSize);
    }

    // Usada también por HTTP: el nombre identifica el archivo, no autoriza acceso al disco.
    public static Inspection inspectHeader(byte[] header, String path, long sizeBytes, int tileSize) throws IOException {
        if (sizeBytes < 33) throw new IOException("Archivo PNG demasiado corto: " + path);
        if (header.length > 33) throw new IOException("La inspección admite solo 33 bytes de cabecera");
        if (header.length < 33) throw new IOException("Cabecera PNG truncada: " + path);
        if (!Arrays.equals(Arrays.copyOf(header, 8), SIGNATURE)) {
            throw new IOException("Formato no soportado: se requiere PNG por firma, no por extensión: " + path);
        }
        ByteBuffer bytes = ByteBuffer.wrap(header);
        if (bytes.getInt(8) != 13 || bytes.getInt(12) != 0x49484452) {
            throw new IOException("Primer chunk PNG debe ser IHDR de 13 bytes: " + path);
        }
        CRC32 crc = new CRC32(); crc.update(header, 12, 17);
        if (crc.getValue() != Integer.toUnsignedLong(bytes.getInt(29))) {
            throw new IOException("CRC de IHDR inválido: " + path);
        }
        long width = Integer.toUnsignedLong(bytes.getInt(16)), height = Integer.toUnsignedLong(bytes.getInt(20));
        if (width == 0 || height == 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw new IOException("Dimensiones fuera del rango PNG: " + path);
        }
        int depth = Byte.toUnsignedInt(header[24]), type = Byte.toUnsignedInt(header[25]);
        int channels = switch (type) { case 0, 3 -> 1; case 2 -> 3; case 4 -> 2; case 6 -> 4; default -> 0; };
        boolean validDepth = switch (type) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            default -> false;
        };
        int interlace = Byte.toUnsignedInt(header[28]);
        if (!validDepth || header[26] != 0 || header[27] != 0 || interlace > 1) {
            throw new IOException("Parámetros IHDR PNG inválidos: " + path);
        }
        try {
            long rowBytes = Math.addExact(PyramidMath.ceilDiv(Math.multiplyExact(width, channels * (long) depth), 8), 1);
            return new Inspection(path, sizeBytes, "png", width, height, depth, type, channels,
                    interlace == 1, Math.multiplyExact(Math.multiplyExact(width, height), 4), rowBytes,
                    Math.multiplyExact(rowBytes, 2),
                    "signature_ihdr_crc_only; IDAT e IEND todavía no verificados; RGBA supone 8 bits por canal",
                    PyramidMath.calculate(width, height, tileSize));
        } catch (ArithmeticException e) {
            throw new IOException("Estimación excede el rango long: " + path, e);
        }
    }
}
