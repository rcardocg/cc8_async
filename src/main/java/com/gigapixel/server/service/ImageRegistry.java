package com.gigapixel.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigapixel.server.cli.PngInspector;
import com.gigapixel.server.model.ImageMetadata;
import com.gigapixel.server.model.ImageStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registro P2. Guarda cabecera/metadata, nunca el contenido del PNG. */
public final class ImageRegistry {
    public record Source(String kind, String name, long declaredSizeBytes, String headerBase64) { }
    public record Registration(int schemaVersion, ImageMetadata metadata, Source source,
                               long processedTiles, Integer currentLevel, String message, String error) { }
    private static final String WAITING = "Registro pendiente: falta transferir el original y ejecutar el preprocesador de P3";
    private final ObjectMapper mapper;
    private final ImagesLayout layout;
    private Map<String, Registration> registrations = new LinkedHashMap<>();

    public ImageRegistry(ObjectMapper mapper, ImagesLayout layout) throws IOException {
        this.mapper = mapper; this.layout = layout;
        refresh();
    }

    public synchronized void refresh() throws IOException {
        Map<String, Registration> loaded = new LinkedHashMap<>();
        try (var entries = Files.list(layout.workRoot())) {
            for (Path folder : entries.filter(Files::isDirectory).sorted().toList()) {
                Path manifest = folder.resolve("meta.json");
                if (!Files.exists(manifest)) continue;
                ensureInside(manifest);
                if (Files.size(manifest) > 1_048_576) throw new IOException("meta.json excede 1 MiB: " + manifest);
                Registration registration = mapper.readValue(manifest.toFile(), Registration.class);
                validate(registration, folder.getFileName().toString());
                loaded.put(registration.metadata().imageId(), registration);
            }
        }
        registrations = loaded;
    }

    public synchronized List<ImageMetadata> images() {
        return registrations.values().stream().map(Registration::metadata).toList();
    }

    public synchronized ImageMetadata get(String id) {
        Registration value = registrations.get(id);
        return value == null ? null : value.metadata();
    }

    public synchronized ImageMetadata register(String id, PngInspector.Inspection image, byte[] header) throws IOException {
        if (registrations.containsKey(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador ya registrado");
        Path folder = layout.imageRoot(id);
        if (Files.exists(folder)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Directorio de imagen ya existente");
        var metadata = planned(id, image, "pending");
        var source = new Source("browser_header", image.path(), image.sizeBytes(), Base64.getEncoder().encodeToString(header));
        var value = new Registration(1, metadata, source, 0, null, WAITING, null);
        validate(value, id);
        Files.createDirectory(folder);
        try {
            write(folder, value, false);
        } catch (IOException e) {
            try { Files.delete(folder); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        registrations.put(id, value);
        return metadata;
    }

    public synchronized ImageStatus status(String id) {
        Registration value = registrations.get(id);
        if (value == null) return null;
        return new ImageStatus(id, value.metadata().state(), value.processedTiles(), value.metadata().totalTiles(),
                value.currentLevel(), value.metadata().completedLevels(), "awaiting_transfer", value.message(), value.error());
    }

    // P3 podrá registrar un fallo real sin perder la cabecera ni sobrescribir el original.
    public synchronized void recordFailure(String id, String error) throws IOException {
        Registration value = registrations.get(id);
        if (value == null) throw new IllegalArgumentException("Imagen no registrada");
        if (error == null || error.isBlank() || error.length() > 1024) throw new IllegalArgumentException("Causa de fallo inválida");
        var old = value.metadata();
        var failed = new ImageMetadata(id, old.width(), old.height(), old.tileSize(), old.totalTiles(), old.maxZoom(),
                old.format(), "failed", old.levels(), old.completedLevels(), old.availableQualities());
        Registration replacement = new Registration(1, failed, value.source(), 0, null, "Registro fallido", error);
        write(layout.imageRoot(id), replacement, true);
        registrations.put(id, replacement);
    }

    private static ImageMetadata planned(String id, PngInspector.Inspection image, String state) {
        var pyramid = image.pyramid();
        return new ImageMetadata(id, (int) image.width(), (int) image.height(), pyramid.tileSize(), pyramid.totalTiles(),
                pyramid.maxZoom(), "png", state, pyramid.levels(), List.of(), List.of());
    }

    private void validate(Registration value, String folderId) throws IOException {
        try {
            if (value == null || value.schemaVersion() != 1 || value.metadata() == null || value.source() == null
                    || !folderId.matches("[a-zA-Z0-9_-]{1,64}") || MetadataService.DEMO_ID.equals(folderId)
                    || !folderId.equals(value.metadata().imageId()) || !"browser_header".equals(value.source().kind())
                    || value.source().name() == null || value.source().name().isBlank() || value.source().name().length() > 255
                    || !("pending".equals(value.metadata().state()) || "failed".equals(value.metadata().state()))
                    || value.processedTiles() != 0 || value.currentLevel() != null || value.message() == null
                    || ("failed".equals(value.metadata().state()) && (value.error() == null || value.error().isBlank()))
                    || ("pending".equals(value.metadata().state()) && value.error() != null)) {
                throw new IOException("Registro P2 inválido");
            }
            byte[] header = Base64.getDecoder().decode(value.source().headerBase64());
            var inspected = PngInspector.inspectHeader(header, value.source().name(), value.source().declaredSizeBytes(), value.metadata().tileSize());
            if (!planned(folderId, inspected, value.metadata().state()).equals(value.metadata())) {
                throw new IOException("Metadata no coincide con cabecera/niveles o declara tiles aún no generados");
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Registro P2 inválido: " + folderId, e);
        }
    }

    private void write(Path folder, Registration value, boolean replace) throws IOException {
        ensureInside(folder);
        Path manifest = folder.resolve("meta.json");
        if (Files.exists(manifest)) ensureInside(manifest);
        Path temporary = Files.createTempFile(folder, ".meta-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            if (replace) Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void ensureInside(Path path) throws IOException {
        if (!path.toRealPath().startsWith(layout.workRoot())) throw new IOException("Registro fuera del work: " + path);
    }
}
