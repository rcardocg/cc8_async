package com.gigapixel.server.model;

public record TileKey(String imageId, int x, int y) {
    public String id() {
        return imageId + ":" + x + ":" + y;
    }
}
