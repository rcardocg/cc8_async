package com.gigapixel.server.controller;

import com.gigapixel.server.service.PngIngestionService;
import com.gigapixel.server.service.MetadataService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/image/{id}")
public class PngIngestionController {
    private final PngIngestionService ingestion;
    private final MetadataService metadata;
    public PngIngestionController(PngIngestionService ingestion, MetadataService metadata) { this.ingestion = ingestion; this.metadata = metadata; }

    @GetMapping("/upload")
    public ResponseEntity<?> uploadStatus(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ingestion.uploadStatus(id));
    }

    @PutMapping(value = "/upload", consumes = "application/octet-stream")
    public ResponseEntity<?> upload(@PathVariable String id, @RequestParam long offset, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > PngIngestionService.MAX_CHUNK) return error(413, "Bloque máximo: 1 MiB");
        byte[] block = request.getInputStream().readNBytes(PngIngestionService.MAX_CHUNK + 1);
        if (block.length > PngIngestionService.MAX_CHUNK) return error(413, "Bloque máximo: 1 MiB");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ingestion.append(id, offset, block));
    }

    @DeleteMapping("/upload")
    public ResponseEntity<?> reset(@PathVariable String id) throws IOException {
        ingestion.resetUpload(id); return ResponseEntity.noContent().build();
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@PathVariable String id, @RequestParam(required = false) String sourceName) throws IOException {
        ingestion.start(id, sourceName);
        return ResponseEntity.accepted().location(URI.create("/api/image/" + id + "/status"))
                .cacheControl(CacheControl.noStore()).body(metadata.status(id));
    }

    @PostMapping("/ingest/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        ingestion.cancel(id); return ResponseEntity.accepted().body(Map.of("message", "Cancelación solicitada"));
    }

    @ExceptionHandler({IOException.class, IllegalArgumentException.class, ResponseStatusException.class})
    public ResponseEntity<?> failure(Exception e) {
        if (e instanceof ResponseStatusException response) return error(response.getStatusCode().value(), response.getReason());
        return error(e instanceof IllegalArgumentException ? 400 : 422, e.getMessage());
    }

    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", message == null ? "Fallo de ingesta" : message));
    }
}
