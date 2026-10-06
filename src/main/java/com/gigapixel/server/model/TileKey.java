package com.gigapixel.server.model;

import java.util.Comparator;

public record TileKey(String imageId, Integer z, int x, int y, int q) {
    public TileKey(String imageId, int x, int y) {
        this(imageId, null, x, y, 3);
    }

    public String id() {
        return z == null ? imageId + ":" + x + ":" + y : imageId + ":" + z + ":" + x + ":" + y + ":" + q;
    }

    public static TileKey parse(String id) {
        String[] parts = id.split(":");
        if (parts.length == 3) {
            return new TileKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } else if (parts.length == 5) {
            return new TileKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
        }
        throw new IllegalArgumentException("Invalid tile id: " + id);
    }

    public static Comparator<TileKey> byProximity(int centerX, int centerY) {
        return Comparator.comparingLong(k -> (long) (k.x() - centerX) * (k.x() - centerX)
                + (long) (k.y() - centerY) * (k.y() - centerY));
    }
}