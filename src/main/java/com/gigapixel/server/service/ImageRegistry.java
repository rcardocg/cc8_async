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

/** Registro P2/P3. Guarda cabecera/metadata, nunca el contenido del PNG en JSON. */
public final class ImageRegistry {
    public record Source(String kind, String name, long declaredSizeBytes, String headerBase64, String sha256) {
        public Source(String kind, String name, long declaredSizeBytes, String headerBase64) {
            this(kind, name, declaredSizeBytes, headerBase64, null);
        }
    }
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

    public synchronized Registration registration(String id) { return registrations.get(id); }

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
                value.currentLevel(), value.metadata().completedLevels(),
                "ready".equals(value.metadata().state()) ? "verified" : "browser_header".equals(value.source().kind())
                        ? "awaiting_transfer" : "processing".equals(value.metadata().state()) ? "decoding" : "retry_required",
                value.message(), value.error());
    }

    public synchronized void processing(String id, String sourceKind, long count, Integer level) throws IOException {
        Registration old = registrations.get(id);
        Source source = new Source(sourceKind, old.source().name(), old.source().declaredSizeBytes(), old.source().headerBase64());
        update(new Registration(2, withState(old.metadata(), "processing", false), source, count, level,
                "Procesamiento PNG secuencial; niveles aún no publicados", null));
    }

    public synchronized void ready(String id, String sha256) throws IOException {
        Registration old = registrations.get(id);
        Source source = new Source(old.source().kind(), old.source().name(), old.source().declaredSizeBytes(), old.source().headerBase64(), sha256);
        update(new Registration(2, withState(old.metadata(), "ready", true), source, old.metadata().totalTiles(), null,
                "Pirámide PNG completa; CRC/IDAT/IEND y SHA-256 verificados. Lista para navegación multinivel", null));
    }

    private static ImageMetadata withState(ImageMetadata old, String state, boolean ready) {
        return new ImageMetadata(old.imageId(), old.width(), old.height(), old.tileSize(), old.totalTiles(), old.maxZoom(),
                old.format(), state, old.levels(), ready ? old.levels().stream().map(level -> level.z()).toList() : List.of(),
                ready ? List.of(3) : List.of());
    }

    private void update(Registration value) throws IOException {
        validate(value, value.metadata().imageId());
        write(layout.imageRoot(value.metadata().imageId()), value, true);
        registrations.put(value.metadata().imageId(), value);
    }

    // P3 podrá registrar un fallo real sin perder la cabecera ni sobrescribir el original.
    public synchronized void recordFailure(String id, String error) throws IOException {
        Registration value = registrations.get(id);
        if (value == null) throw new IllegalArgumentException("Imagen no registrada");
        if (error == null || error.isBlank() || error.length() > 1024) throw new IllegalArgumentException("Causa de fallo inválida");
        var old = value.metadata();
        var failed = new ImageMetadata(id, old.width(), old.height(), old.tileSize(), old.totalTiles(), old.maxZoom(),
                old.format(), "failed", old.levels(), old.completedLevels(), old.availableQualities());
        Registration replacement = new Registration(value.schemaVersion(), failed, value.source(), 0, null, "Registro fallido; puede reintentarse desde el inicio", error);
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
            if (value != null && value.schemaVersion() == 2) { validateP3(value, folderId); return; }
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

    private void validateP3(Registration value, String id) throws IOException {
        if (value.metadata() == null || value.source() == null || !id.matches("[a-zA-Z0-9_-]{1,64}")
                || MetadataService.DEMO_ID.equals(id) || !id.equals(value.metadata().imageId())
                || !List.of("server_file", "browser_upload").contains(value.source().kind())
                || value.source().name() == null || value.source().name().isBlank() || value.source().name().length() > 255
                || !List.of("processing", "ready", "failed").contains(value.metadata().state()) || value.message() == null) {
            throw new IOException("Registro P3 inválido");
        }
        var image = PngInspector.inspectHeader(Base64.getDecoder().decode(value.source().headerBase64()),
                value.source().name(), value.source().declaredSizeBytes(), value.metadata().tileSize());
        boolean ready = "ready".equals(value.metadata().state());
        if (!withState(planned(id, image, value.metadata().state()), value.metadata().state(), ready).equals(value.metadata())
                || value.processedTiles() < 0 || value.processedTiles() > image.pyramid().totalTiles()
                || value.currentLevel() != null && (value.currentLevel() < 0 || value.currentLevel() > image.pyramid().maxZoom())
                || "failed".equals(value.metadata().state()) != (value.error() != null && !value.error().isBlank())) {
            throw new IOException("Metadata/progreso P3 inconsistente");
        }
        if (ready) {
            Path receipt = layout.imageRoot(id).resolve("tiles/complete.sha256");
            ensureInside(receipt);
            if (value.processedTiles() != image.pyramid().totalTiles() || value.currentLevel() != null
                    || value.source().sha256() == null || !value.source().sha256().matches("[a-f0-9]{64}")
                    || Files.size(receipt) != 64 || !Files.readString(receipt).equals(value.source().sha256())) {
                throw new IOException("Falta comprobante de pirámide completa");
            }
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
