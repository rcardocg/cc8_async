package com.gigapixel.server.model;

public record TileKey(String imageId, Integer z, int x, int y, int q) {
    public TileKey(String imageId, int x, int y) {
        this(imageId, null, x, y, 3);
    }

    public String id() {
        return z == null ? imageId + ":" + x + ":" + y : imageId + ":" + z + ":" + x + ":" + y + ":" + q;
    }
}
