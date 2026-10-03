package com.gigapixel.server.service;

import com.gigapixel.server.model.ImageMetadata;
import com.gigapixel.server.model.TileKey;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

@Service
public class TileService {
    public static final int MAX_TILE_BYTES = 512 * 1024;
    private final MetadataService metadata;

    public TileService(MetadataService metadata) {
        this.metadata = metadata;
    }

    public byte[] readTile(TileKey key) throws IOException {
        ImageMetadata image = metadata.validateTile(key);
        if (MetadataService.DEMO_ID.equals(key.imageId())) return demoTile(key, image);

        Path root = metadata.directory().toRealPath();
        Path file = root.resolve(key.imageId()).resolve(key.x() + "_" + key.y() + "." + image.format()).toRealPath();
        if (!file.startsWith(root)) throw new IOException("Tile fuera del directorio de imágenes");
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) {
            bytes = stream.readNBytes(MAX_TILE_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > MAX_TILE_BYTES) throw new IOException("Tamaño de tile inválido");
        validateHeader(bytes, key, image);
        return bytes;
    }

    private void validateHeader(byte[] bytes, TileKey key, ImageMetadata image) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Tile no reconocible");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = (int) Math.min(image.tileSize(), image.width() - (long) key.x() * image.tileSize());
                int height = (int) Math.min(image.tileSize(), image.height() - (long) key.y() * image.tileSize());
                if (reader.getWidth(0) != width || reader.getHeight(0) != height
                        || !reader.getFormatName().toLowerCase(Locale.ROOT).equals(image.format())) {
                    throw new IOException("Formato o dimensiones del tile no coinciden con el catálogo");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    // Demo explícita: se genera sólo el tile solicitado, nunca una imagen gigante en RAM.
    private byte[] demoTile(TileKey key, ImageMetadata image) throws IOException {
        BufferedImage tile = new BufferedImage(image.tileSize(), image.tileSize(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = tile.createGraphics();
        try {
            graphics.setColor((key.x() + key.y()) % 2 == 0 ? new Color(230, 239, 251) : Color.WHITE);
            graphics.fillRect(0, 0, tile.getWidth(), tile.getHeight());
            graphics.setColor(new Color(25, 45, 80));
            graphics.drawRect(0, 0, tile.getWidth() - 1, tile.getHeight() - 1);
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
            graphics.drawString("DEMO " + key.x() + "," + key.y(), 16, 32);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
            long first = ((long) key.y() * 16 + key.x()) * 16;
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 4; column++) {
                    graphics.drawString(Long.toString(first + row * 4 + column), 8 + column * 62, 80 + row * 45);
                }
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(tile, "png", bytes)) throw new IOException("Codificador PNG no disponible");
        return bytes.toByteArray();
    }
}
