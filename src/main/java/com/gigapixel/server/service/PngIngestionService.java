package com.gigapixel.server.service;

import com.gigapixel.server.cli.PngInspector;
import com.gigapixel.server.image.PngPyramidBuilder;
import com.gigapixel.server.image.SequentialPngDecoder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Una ingesta por work, también entre procesos CLI/servidor. Subida persistida en bloques de 1 MiB. */
@Service
public final class PngIngestionService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PngIngestionService.class);
    private static final ConcurrentHashMap<Path, Semaphore> LOCAL_LOCKS = new ConcurrentHashMap<>();
    public static final int MAX_CHUNK = 1024 * 1024;
    public record Upload(long receivedBytes, long declaredSizeBytes, int maxChunkBytes) { }
    private final ImagesLayout layout;
    private final MetadataService metadata;
    private final long memoryBytes;
    private final Semaphore localWrite;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String active;
    private Thread worker;
    private boolean cancelled;

    public PngIngestionService(ImagesLayout layout, MetadataService metadata,
                               @Value("${images.ingest-memory-mib:128}") long memoryMib) throws IOException {
        this.layout = layout; this.metadata = metadata;
        localWrite = LOCAL_LOCKS.computeIfAbsent(layout.workRoot(), path -> new Semaphore(1));
        if (memoryMib <= 0) throw new IllegalArgumentException("Presupuesto de ingesta debe ser positivo");
        memoryBytes = Math.min(Math.multiplyExact(memoryMib, 1024 * 1024), Runtime.getRuntime().maxMemory());
        // Un proceso anterior pudo terminar entre processing y ready. Nunca inventar éxito al reiniciar.
        if (metadata.registry().images().stream().anyMatch(image -> "processing".equals(image.state()))) {
            try (Lease ignored = acquire()) {
                for (var image : metadata.registry().images()) if ("processing".equals(image.state())) {
                    metadata.registry().recordFailure(image.imageId(), "Proceso interrumpido; reintentar desde el inicio del PNG");
                }
            } catch (ResponseStatusException busy) { /* Otro proceso sigue siendo propietario del work. */ }
        }
    }

    private ImageRegistry.Registration registration(String id) throws IOException {
        metadata.refreshRegistrations();
        var value = metadata.registry().registration(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro P2 desconocido");
        safe(layout.imageRoot(id));
        return value;
    }

    public synchronized Upload uploadStatus(String id) throws IOException {
        var value = registration(id);
        Path part = safe(layout.imageRoot(id).resolve("source.part"));
        return new Upload(Files.exists(part) ? Files.size(part) : 0, value.source().declaredSizeBytes(), MAX_CHUNK);
    }

    public synchronized Upload append(String id, long offset, byte[] block) throws IOException {
        try (Lease ignored = acquire()) {
            var value = registration(id); mutable(value);
            var status = uploadStatus(id);
            if (block.length == 0 || block.length > MAX_CHUNK || offset < 0 || offset > status.declaredSizeBytes() - block.length) {
                throw new IllegalArgumentException("Bloque vacío, excesivo o fuera del tamaño declarado");
            }
            if (offset != status.receivedBytes()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Offset incorrecto; consultar /upload antes de reintentar");
            if (offset == 0 && (block.length < 33 || !Arrays.equals(Arrays.copyOf(block, 33), Base64.getDecoder().decode(value.source().headerBase64())))) {
                throw new IllegalArgumentException("Primer bloque no coincide con la cabecera registrada");
            }
            PngPyramidBuilder.checkDisk(layout.workRoot(), block.length);
            Path path = safe(layout.imageRoot(id).resolve("source.part"));
            try (var output = new RandomAccessFile(path.toFile(), "rw")) {
                try { output.seek(offset); output.write(block); output.getChannel().force(true); }
                catch (IOException e) { output.setLength(offset); throw e; }
            }
            return uploadStatus(id);
        }
    }

    public synchronized void resetUpload(String id) throws IOException {
        try (Lease ignored = acquire()) {
            mutable(registration(id));
            Files.deleteIfExists(safe(layout.imageRoot(id).resolve("source.part")));
        }
    }

    public synchronized void start(String id, String serverName) throws IOException {
        if (active != null || executor.isShutdown()) throw busy();
        Lease lease = acquire();
        try {
            var source = prepare(id, serverName);
            active = id; cancelled = false;
            executor.execute(() -> {
                synchronized (this) { worker = Thread.currentThread(); if (cancelled) worker.interrupt(); }
                try { process(id, source); }
                catch (IOException | RuntimeException e) { log.warn("Ingesta {} fallida: {}", id, e.toString()); }
                finally {
                    Thread.interrupted();
                    synchronized (this) { worker = null; active = null; }
                    lease.close();
                }
            });
        } catch (IOException | RuntimeException e) { active = null; lease.close(); throw e; }
    }

    /** CLI síncrono: usa el mismo pipeline, el mismo lock y el mismo registro que HTTP. */
    public void ingest(String id, String serverName) throws IOException {
        try (Lease ignored = acquire()) { process(id, prepare(id, serverName)); }
    }

    private record Input(Path path, PngInspector.Inspection inspection, String kind) { }

    private Input prepare(String id, String serverName) throws IOException {
        var registration = registration(id); mutable(registration);
        Path source;
        String kind;
        if (serverName == null) {
            source = safe(layout.imageRoot(id).resolve("source.part")); kind = "browser_upload";
        } else {
            Path relative = Path.of(serverName);
            if (relative.isAbsolute()) throw new IllegalArgumentException("Usar nombre relativo a GTP_IMAGES");
            source = layout.originalsRoot().resolve(relative).normalize().toRealPath();
            if (!source.startsWith(layout.originalsRoot()) || !Files.isRegularFile(source)) throw new IllegalArgumentException("Fuente fuera de GTP_IMAGES");
            kind = "server_file";
        }
        var inspection = PngInspector.inspect(source, registration.metadata().tileSize());
        byte[] header;
        try (var input = Files.newInputStream(source)) { header = input.readNBytes(33); }
        if (inspection.sizeBytes() != registration.source().declaredSizeBytes()
                || !Arrays.equals(header, Base64.getDecoder().decode(registration.source().headerBase64()))) {
            throw new IllegalArgumentException("Tamaño/cabecera no coinciden con registro; completar transferencia o elegir el original correcto");
        }
        if (SequentialPngDecoder.requiredBufferBytes(inspection) > memoryBytes - 32L * 1024 * 1024) throw new IOException("Memoria de filas excede presupuesto de ingesta");
        long band = Math.multiplyExact(inspection.width() * 4, Math.min(inspection.height(), inspection.pyramid().tileSize()));
        PngPyramidBuilder.checkDisk(layout.workRoot(), Math.addExact(inspection.pyramid().estimatedWorkBytes(), band));
        metadata.registry().processing(id, kind, 0, inspection.pyramid().maxZoom());
        return new Input(source, inspection, kind);
    }

    private void process(String id, Input input) throws IOException {
        Path folder = layout.imageRoot(id), staging = folder.resolve(".p3-tiles"), target = folder.resolve("tiles");
        try {
            // Salida no publicada de un intento anterior: únicamente directorios propios de este registro P3.
            deleteTree(safe(staging));
            if (Files.exists(target)) {
                if (!Files.exists(safe(target.resolve("complete.sha256")))) throw new IOException("Directorio tiles ajeno o incompleto; no se sobrescribe");
                deleteTree(safe(target));
            }
            var result = new PngPyramidBuilder().build(input.path(), input.inspection(), staging, memoryBytes,
                    (level, count) -> metadata.registry().processing(id, input.kind(), count, level));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Procesamiento cancelado");
            Files.writeString(staging.resolve("complete.sha256"), result.sha256(), StandardOpenOption.CREATE_NEW);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            metadata.registry().ready(id, result.sha256());
        } catch (IOException | RuntimeException e) {
            boolean interrupted = Thread.interrupted();
            try {
                try { deleteTree(safe(staging)); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                metadata.registry().recordFailure(id, reason.substring(0, Math.min(1024, reason.length())));
            } catch (IOException failure) { e.addSuppressed(failure); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw e;
        }
    }

    public synchronized void cancel(String id) {
        if (!id.equals(active)) throw new ResponseStatusException(HttpStatus.CONFLICT, "No hay una ingesta activa de esa imagen en este proceso");
        cancelled = true;
        if (worker != null) worker.interrupt();
    }

    private void mutable(ImageRegistry.Registration value) {
        if ("ready".equals(value.metadata().state()) || "processing".equals(value.metadata().state())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Imagen lista o en procesamiento");
    }

    private Path safe(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(layout.workRoot())) throw new IOException("Ruta fuera del work o enlace no permitido");
        } else if (!path.getParent().toRealPath().startsWith(layout.workRoot())) throw new IOException("Ruta fuera del work");
        return path;
    }

    private Lease acquire() throws IOException {
        // En POSIX cerrar otro descriptor del mismo archivo puede liberar locks del proceso.
        // Evitar incluso abrir un segundo canal mientras esta JVM es propietaria del lock.
        if (!localWrite.tryAcquire()) throw busy();
        FileChannel channel = null;
        try {
            Path lockPath = layout.workRoot().resolve(".p3.lock"); safe(lockPath);
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) throw busy();
            return new Lease(channel, lock, localWrite);
        } catch (IOException | RuntimeException e) {
            try { if (channel != null) channel.close(); } finally { localWrite.release(); }
            if (e instanceof OverlappingFileLockException) throw busy(); throw e;
        }
    }

    private static ResponseStatusException busy() { return new ResponseStatusException(HttpStatus.CONFLICT, "Hay otra ingesta o escritura activa en este work"); }
    private record Lease(FileChannel channel, FileLock lock, Semaphore localWrite) implements AutoCloseable {
        @Override public void close() {
            try { lock.release(); } catch (IOException ignored) { }
            try { channel.close(); } catch (IOException ignored) { }
            localWrite.release();
        }
    }

    public static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        // walkFileTree no sigue enlaces y no acumula la lista de millones de tiles en memoria.
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override @PreDestroy public void close() {
        synchronized (this) { cancelled = true; if (worker != null) worker.interrupt(); }
        executor.shutdown();
        try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
