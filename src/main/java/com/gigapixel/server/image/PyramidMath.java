package com.gigapixel.server.image;

import java.util.ArrayList;
import java.util.List;

// Compartida por CLI y el futuro preprocesador; ninguna operación usa punto flotante.
public final class PyramidMath {
    private PyramidMath() { }

    public record Level(int z, long width, long height, long columns, long rows, long tiles) { }
    public record Pyramid(int tileSize, int maxZoom, long totalTiles, long estimatedWorkBytes,
                          String estimateBasis, List<Level> levels) { }

    public static Pyramid calculate(long width, long height, int tileSize) {
        if (width <= 0 || height <= 0 || tileSize < 64 || tileSize > 512) {
            throw new IllegalArgumentException("Dimensiones positivas y tile-size de 64 a 512 requeridos");
        }
        int zoom = 0;
        long w = width, h = height;
        while (w > tileSize || h > tileSize) {
            w = ceilDiv(w, 2); h = ceilDiv(h, 2); zoom++;
        }
        List<Level> descending = new ArrayList<>();
        long total = 0, estimated = 0;
        w = width; h = height;
        for (int z = zoom; z >= 0; z--) {
            long columns = ceilDiv(w, tileSize), rows = ceilDiv(h, tileSize);
            long tiles = Math.multiplyExact(columns, rows);
            total = Math.addExact(total, tiles);
            // RGBA + holgura por tile (PNG/cabecera), sin suponer un ratio de compresión.
            estimated = Math.addExact(estimated, Math.addExact(
                    Math.multiplyExact(Math.multiplyExact(w, h), 4), Math.multiplyExact(tiles, 4096)));
            descending.add(new Level(z, w, h, columns, rows, tiles));
            w = ceilDiv(w, 2); h = ceilDiv(h, 2);
        }
        return new Pyramid(tileSize, zoom, total, estimated,
                "RGBA sin comprimir en todos los niveles + 4096 bytes por tile; estimación, no reserva",
                descending.reversed().stream().toList());
    }

    public static long ceilDiv(long value, long divisor) {
        if (value < 0 || divisor <= 0) throw new IllegalArgumentException("División inválida");
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }
}
