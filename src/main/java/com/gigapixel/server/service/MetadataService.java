package com.gigapixel.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigapixel.server.model.ImageMetadata;
import com.gigapixel.server.model.TileKey;
import com.gigapixel.server.model.ImageStatus;
import com.gigapixel.server.cli.PngInspector;
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
    private final ImageRegistry registry;

    // El catálogo se carga al arrancar. Ningún archivo de imagen completo se abre aquí.
    public MetadataService(ObjectMapper mapper,
                           ImagesLayout layout,
                           @Value("${images.demo-enabled:true}") boolean demoEnabled) throws IOException {
        this.directory = layout.workRoot();
        this.registry = new ImageRegistry(mapper, layout);
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
                entry = new ImageMetadata(entry.imageId(), entry.width(), entry.height(), entry.tileSize(),
                        entry.totalTiles(), null, entry.format());
                if (images.putIfAbsent(entry.imageId(), entry) != null || DEMO_ID.equals(entry.imageId())) {
                    throw new IOException("Identificador duplicado o reservado: " + entry.imageId());
                }
            }
        }
        refreshRegistrations();
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

    public synchronized List<String> listImages() {
        refreshForRequest();
        var ids = new java.util.ArrayList<>(images.keySet());
        registry.images().forEach(image -> ids.add(image.imageId()));
        return List.copyOf(ids);
    }

    public synchronized ImageMetadata getImage(String id) {
        ImageMetadata image = images.get(id);
        if (image == null) image = registry.get(id);
        if (image == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen desconocida");
        return image;
    }

    public ImageMetadata validateTile(TileKey tile) {
        ImageMetadata image = getImage(tile.imageId());
        if (!"ready".equals(image.state())) throw new IllegalArgumentException("Imagen no lista: " + image.state());
        validateTileCoordinates(tile);
        return image;
    }

    public ImageMetadata validateTileCoordinates(TileKey tile) {
        ImageMetadata image = getImage(tile.imageId());
        long width = image.width(), height = image.height();
        if (tile.q() < 0 || tile.q() > 3) throw new IllegalArgumentException("Calidad fuera de rango");
        if (image.maxZoom() == null) {
            if (tile.z() != null || tile.q() != 3) throw new IllegalArgumentException("Catálogo plano: z=null, q=3");
        } else {
            if (tile.z() == null || tile.z() < 0 || tile.z() > image.maxZoom()) throw new IllegalArgumentException("Nivel fuera de rango");
            var level = image.levels().get(tile.z()); width = level.width(); height = level.height();
        }
        if (tile.x() < 0 || tile.y() < 0 || (long) tile.x() * image.tileSize() >= width
                || (long) tile.y() * image.tileSize() >= height) {
            throw new IllegalArgumentException("Coordenadas fuera de la imagen");
        }
        return image;
    }

    public synchronized ImageMetadata register(String id, PngInspector.Inspection inspection, byte[] header) throws IOException {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Identificador inválido");
        refreshRegistrations();
        if (DEMO_ID.equals(id) || images.containsKey(id) || registry.get(id) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador duplicado o reservado");
        }
        return registry.register(id, inspection, header);
    }

    public synchronized ImageStatus status(String id) {
        refreshForRequest();
        ImageMetadata image = getImage(id);
        ImageStatus registered = registry.status(id);
        if (registered != null) return registered;
        return new ImageStatus(id, "ready", image.totalTiles(), image.totalTiles(), null,
                image.completedLevels(), "prepared_tiles", "Catálogo plano preparado; tiles verificados al leer", null);
    }

    public synchronized void refreshRegistrations() throws IOException {
        registry.refresh();
        for (var image : registry.images()) {
            if (images.containsKey(image.imageId())) throw new IOException("Identificador duplicado con catálogo plano: " + image.imageId());
        }
    }

    private void refreshForRequest() {
        try { refreshRegistrations(); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo actualizar el registro de imágenes", e); }
    }

    public Path directory() {
        return directory;
    }
}
