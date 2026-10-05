package com.gigapixel.server.model;

import com.gigapixel.server.image.PyramidMath;
import java.util.List;

public record ImageMetadata(
    String imageId,
    int width,
    int height,
    int tileSize,
    long totalTiles,
    Integer maxZoom,
    String format,
    String state,
    List<PyramidMath.Level> levels,
    List<Integer> completedLevels,
    List<Integer> availableQualities
) {
    // Compatibilidad con el catálogo plano y GTP/1 v1.
    public ImageMetadata(String imageId, int width, int height, int tileSize, long totalTiles,
                         Integer maxZoom, String format) {
        this(imageId, width, height, tileSize, totalTiles, maxZoom, format, "ready",
                List.of(new PyramidMath.Level(0, width, height,
                        tileSize > 0 ? PyramidMath.ceilDiv(width, tileSize) : 0,
                        tileSize > 0 ? PyramidMath.ceilDiv(height, tileSize) : 0, totalTiles)),
                List.of(0), List.of(3));
    }
}
