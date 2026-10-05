package com.gigapixel.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@Service
public class ImagesLayout {
    private final Path originalsRoot;
    private final Path workRoot;

    public ImagesLayout(@Value("${images.originals-directory:./data/originales}") String originals,
                        @Value("${images.directory:./data/work}") String work) throws IOException {
        originalsRoot = prepare(originals, "originales");
        workRoot = prepare(work, "trabajo");
        if (workRoot.startsWith(originalsRoot) || originalsRoot.startsWith(workRoot)) {
            throw new IOException("Los directorios de originales y trabajo deben estar separados: "
                    + originalsRoot + " / " + workRoot);
        }
        // No se escribe en originales: un directorio existente puede ser de solo lectura.
        try {
            Path probe = Files.createTempFile(workRoot, ".gtp-write-", ".tmp");
            Files.delete(probe);
        } catch (IOException | SecurityException e) {
            throw new IOException("Directorio de trabajo no escribible: " + workRoot + " (" + e.getMessage() + ")", e);
        }
    }

    private static Path prepare(String value, String role) throws IOException {
        try {
            if (value.isBlank()) throw new IOException("La ruta no puede estar vacía");
            Path path = Path.of(value).toAbsolutePath().normalize();
            Files.createDirectories(path);
            Path real = path.toRealPath();
            if (!Files.isDirectory(real) || !Files.isReadable(real)) {
                throw new IOException("No es un directorio legible");
            }
            return real;
        } catch (IOException | InvalidPathException | SecurityException e) {
            throw new IOException("No se puede preparar el directorio de " + role + ": " + value
                    + " (" + e.getMessage() + ")", e);
        }
    }

    public Path originalsRoot() {
        return originalsRoot;
    }

    public Path workRoot() {
        return workRoot;
    }

    public Path imageRoot(String imageId) {
        if (imageId == null || !imageId.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Identificador de imagen inválido");
        }
        return workRoot.resolve(imageId);
    }

    public long freeBytes() throws IOException {
        return Files.getFileStore(workRoot).getUsableSpace();
    }
}
