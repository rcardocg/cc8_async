package com.gigapixel.server.model;

public record ImageMetadata(
    String imageId,
    int width,
    int height,
    int tileSize,
    int totalTiles,
    Integer maxZoom,
    String format
) {}