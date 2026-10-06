package com.gigapixel.server.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigapixel.server.image.SequentialPngDecoder;
import com.gigapixel.server.service.ImagesLayout;
import com.gigapixel.server.service.MetadataService;
import com.gigapixel.server.service.PngIngestionService;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GtpCli {
    private static final long MIB = 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    public record Preflight(String status, boolean dryRun, boolean ingestionImplemented, String imageId,
                            String workDirectory, long usableBytes, long estimatedWorkBytes,
                            long memoryBudgetBytes, long minimumRowBuffersBytes,
                            PngInspector.Inspection inspection, List<String> notes) { }

    public int run(String[] args, PrintStream out, PrintStream err) {
        boolean pretty = List.of(args).contains("--pretty");
        try {
            if (args.length == 0 || (args.length == 1 && (args[0].equals("help") || args[0].equals("--help")))) {
                out.println("""
                        GTP CLI · PNG · no inicia Spring
                        inspect <archivo> [--tile-size 256] [--pretty]
                        ladder <carpeta> [--tile-size 256] [--pretty]
                        ingest <archivo> [--dry-run] [--image-id ID] [--work RUTA]
                               [--max-memory-mib 256] [--tile-size 256] [--pretty]
                        help
                        Rutas relativas al directorio actual; con lanzadores, a la raíz del proyecto.
                        GTP_WORK por defecto: ./data/work. --dry-run no crea directorios ni tiles.
                        Ingesta real requiere --image-id; PNG no Adam7, hasta 8 bits. Un dataset por work.
                        Salidas: 0 inspección/preflight correcto; 1 error/revisión necesaria; 2 disco insuficiente.
                        """);
                return 0;
            }
            String command = args[0];
            if (!List.of("inspect", "ladder", "ingest").contains(command)) {
                throw new IllegalArgumentException("Subcomando desconocido: " + command);
            }
            Path input = null;
            Path work = Path.of(System.getenv().getOrDefault("GTP_WORK", "./data/work"));
            int tileSize = 256;
            long memory = 256 * MIB;
            String imageId = null;
            boolean dryRun = false;
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--pretty" -> { }
                    case "--dry-run" -> {
                        requireIngest(command, arg); dryRun = true;
                    }
                    case "--tile-size", "--work", "--max-memory-mib", "--image-id" -> {
                        if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Falta valor de " + arg);
                        switch (arg) {
                            case "--tile-size" -> tileSize = Integer.parseInt(args[i]);
                            case "--work" -> { requireIngest(command, arg); work = Path.of(args[i]); }
                            case "--max-memory-mib" -> {
                                requireIngest(command, arg); memory = Math.multiplyExact(Long.parseLong(args[i]), MIB);
                            }
                            case "--image-id" -> { requireIngest(command, arg); imageId = args[i]; }
                        }
                    }
                    default -> {
                        if (arg.startsWith("--")) throw new IllegalArgumentException("Opción desconocida: " + arg);
                        if (input != null) throw new IllegalArgumentException("Se requiere una sola ruta; usa comillas si contiene espacios");
                        input = Path.of(arg);
                    }
                }
            }
            if (input == null) throw new IllegalArgumentException("Falta ruta del archivo o carpeta");
            if (tileSize < 64 || tileSize > 512 || memory <= 0) throw new IllegalArgumentException("tile-size: 64–512; max-memory-mib positivo");
            if (imageId != null && !imageId.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("image-id inválido");
            if (command.equals("ingest") && !dryRun && imageId == null) throw new IllegalArgumentException("Ingesta real requiere --image-id ID");
            Object report;
            int code = 0;
            switch (command) {
                case "inspect" -> report = PngInspector.inspect(input, tileSize);
                case "ladder" -> {
                    var ladder = LadderReport.inspect(input, tileSize); report = ladder;
                    if (ladder.failures() > 0) code = 1;
                }
                default -> {
                    var preflight = preflight(input, work, tileSize, memory, imageId); report = preflight;
                    code = switch (preflight.status()) { case "insufficient_disk" -> 2; case "preflight_ok" -> 0; default -> 1; };
                    if (!dryRun && code == 0) report = ingest(input, work, tileSize, memory, imageId);
                }
            }
            write(report, pretty, out);
            return code;
        } catch (IOException | IllegalArgumentException | ArithmeticException | SecurityException | org.springframework.web.server.ResponseStatusException e) {
            try { write(Map.of("status", "error", "error", String.valueOf(e.getMessage())), pretty, err); }
            catch (IOException ignored) { err.println("Error al serializar informe: " + e.getMessage()); }
            return 1;
        }
    }

    private Object ingest(Path input, Path work, int tileSize, long memory, String id) throws IOException {
        Path source = input.toRealPath();
        Path originals = Path.of(System.getenv().getOrDefault("GTP_IMAGES", source.getParent().toString())).toRealPath();
        if (!source.startsWith(originals)) throw new IOException("El original debe estar bajo GTP_IMAGES");
        var layout = new ImagesLayout(originals.toString(), work.toString());
        var metadata = new MetadataService(mapper, layout, false);
        var image = PngInspector.inspect(source, tileSize);
        SequentialPngDecoder.requiredBufferBytes(image);
        if (metadata.registry().get(id) == null) {
            byte[] header;
            try (var stream = Files.newInputStream(source)) { header = stream.readNBytes(33); }
            metadata.register(id, PngInspector.inspectHeader(header, source.getFileName().toString(), image.sizeBytes(), tileSize), header);
        }
        try (var ingestion = new PngIngestionService(layout, metadata, memory / MIB)) {
            ingestion.ingest(id, originals.relativize(source).toString());
        }
        return metadata.status(id);
    }

    private static void requireIngest(String command, String option) {
        if (!command.equals("ingest")) throw new IllegalArgumentException(option + " solo se admite con ingest");
    }

    private Preflight preflight(Path input, Path work, int tileSize, long memory, String imageId) throws IOException {
        memory = Math.min(memory, Runtime.getRuntime().maxMemory());
        var image = PngInspector.inspect(input, tileSize);
        Path target = work.toAbsolutePath().normalize();
        Path existing = target;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null || !Files.isDirectory(existing)) throw new IOException("Ruta work inválida: " + target);
        Path canonical = existing.toRealPath();
        Path resolvedTarget = canonical.resolve(existing.relativize(target)).normalize();
        if (Path.of(image.path()).toRealPath().startsWith(resolvedTarget)) {
            throw new IOException("El original no puede estar dentro del work descartable: " + target);
        }
        long usable = Files.getFileStore(canonical).getUsableSpace();
        List<String> notes = new ArrayList<>();
        notes.add("Solo cabecera validada: no garantiza que IDAT/IEND estén completos o sean decodificables.");
        notes.add("P3 decodifica secuencialmente PNG no entrelazados hasta 8 bits; sin acceso aleatorio a IDAT.");
        notes.add("Memoria de filas es un mínimo; faltan buffers de tiles, Inflater y JVM. Se exige margen de 32 MiB.");
        notes.add("Disco estimado sin ratio de compresión ni ocupación real del sistema de archivos; no es una garantía.");
        if (!Files.exists(target)) notes.add("Work no existe; espacio consultado en su ancestro existente. No se creó nada.");
        notes.add("La ingesta real también requiere una banda RGBA en disco y reserva de 16 MiB; preflight no reserva espacio.");
        notes.add("Presupuesto efectivo limitado por -Xmx; el margen no mide el RSS ni toda la memoria de Spring/WebSocket.");
        boolean memoryOk = image.minimumRowBuffersBytes() + image.width() * 4 <= memory - 32 * MIB;
        String status = !Files.isWritable(canonical) ? "work_not_writable"
                : usable < image.pyramid().estimatedWorkBytes() ? "insufficient_disk"
                : !memoryOk ? "memory_review" : image.interlaced() ? "interlace_review" : image.bitDepth() == 16 ? "depth_review" : "preflight_ok";
        if (image.interlaced()) notes.add("Adam7 requiere varias pasadas y planificación adicional; no aprobado para el primer decoder.");
        return new Preflight(status, true, !image.interlaced() && image.bitDepth() != 16, imageId, target.toString(), usable,
                image.pyramid().estimatedWorkBytes(), memory, image.minimumRowBuffersBytes(), image, List.copyOf(notes));
    }

    private void write(Object report, boolean pretty, PrintStream stream) throws IOException {
        // writeValueAsString evita cerrar stdout/stderr del proceso llamador.
        stream.println(pretty ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) : mapper.writeValueAsString(report));
    }
}
