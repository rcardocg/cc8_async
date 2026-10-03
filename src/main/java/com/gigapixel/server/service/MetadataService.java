package com.gigapixel.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigapixel.server.model.ImageMetadata;
import com.gigapixel.server.model.TileKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MetadataService {
    public static final String DEMO_ID = "demo_numeros";
    private final Map<String, ImageMetadata> images = new LinkedHashMap<>();
    private final Path directory;

    // El catálogo se carga al arrancar. Ningún archivo de imagen completo se abre aquí.
    public MetadataService(ObjectMapper mapper,
                           @Value("${images.directory:./data/images}") String directory,
                           @Value("${images.demo-enabled:true}") boolean demoEnabled) throws IOException {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        if (demoEnabled) {
            images.put(DEMO_ID, new ImageMetadata(DEMO_ID, 4096, 4096, 256, 256L, null, "png"));
        }
        Path catalog = this.directory.resolve("catalog.json");
        if (Files.exists(catalog)) {
            if (Files.size(catalog) > 1_048_576) {
                throw new IOException("El catálogo excede 1 MiB");
            }
            ImageMetadata[] entries = mapper.readValue(catalog.toFile(), ImageMetadata[].class);
            if (entries == null) throw new IOException("El catálogo debe ser un arreglo JSON");
            for (ImageMetadata entry : entries) {
                validate(entry);
                if (images.putIfAbsent(entry.imageId(), entry) != null || DEMO_ID.equals(entry.imageId())) {
                    throw new IOException("Identificador duplicado o reservado: " + entry.imageId());
                }
            }
        }
    }

    private void validate(ImageMetadata image) throws IOException {
        if (image == null || image.imageId() == null || !image.imageId().matches("[a-zA-Z0-9_-]{1,64}")
                || image.width() <= 0 || image.height() <= 0 || image.tileSize() < 64 || image.tileSize() > 512
                || image.maxZoom() != null || !("png".equals(image.format()) || "jpeg".equals(image.format()))) {
            throw new IOException("Metadata inválida: se admiten tiles planos PNG/JPEG de 64 a 512 píxeles");
        }
        long columns = ((long) image.width() + image.tileSize() - 1) / image.tileSize();
        long rows = ((long) image.height() + image.tileSize() - 1) / image.tileSize();
        if (image.totalTiles() != columns * rows) {
            throw new IOException("totalTiles no coincide con las dimensiones de " + image.imageId());
        }
    }

    public List<String> listImages() {
        return List.copyOf(images.keySet());
    }

    public ImageMetadata getImage(String id) {
        ImageMetadata image = images.get(id);
        if (image == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen desconocida");
        return image;
    }

    public ImageMetadata validateTile(TileKey tile) {
        ImageMetadata image = getImage(tile.imageId());
        if (tile.x() < 0 || tile.y() < 0 || (long) tile.x() * image.tileSize() >= image.width()
                || (long) tile.y() * image.tileSize() >= image.height()) {
            throw new IllegalArgumentException("Coordenadas fuera de la imagen");
        }
        return image;
    }

    public Path directory() {
        return directory;
    }
}
