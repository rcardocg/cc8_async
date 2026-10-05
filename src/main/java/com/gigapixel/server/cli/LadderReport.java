package com.gigapixel.server.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LadderReport {
    public record Entry(String path, long sizeBytes, String status, PngInspector.Inspection inspection, String error) { }
    public record Report(String directory, boolean recursive, List<Entry> files, long failures) { }

    private LadderReport() { }

    public static Report inspect(Path directory, int tileSize) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("No es un directorio: " + root);
        List<Entry> result = new ArrayList<>();
        // Un solo nivel; no seguimos subdirectorios ni recorremos accidentalmente millones de tiles.
        try (var stream = Files.list(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                try {
                    var image = PngInspector.inspect(file, tileSize);
                    result.add(new Entry(image.path(), image.sizeBytes(), "inspected", image, null));
                } catch (IOException e) {
                    result.add(new Entry(file.toString(), Files.size(file), "error", null, e.getMessage()));
                }
            }
        }
        result.sort(Comparator.comparingLong(Entry::sizeBytes).thenComparing(Entry::path));
        return new Report(root.toString(), false, List.copyOf(result),
                result.stream().filter(entry -> entry.error() != null).count());
    }
}
