package com.gigapixel.server.controller;

import com.gigapixel.server.cli.PngInspector;
import com.gigapixel.server.service.MetadataService;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
public class PngInspectionController {
    private final MetadataService metadata;

    public PngInspectionController(MetadataService metadata) { this.metadata = metadata; }

    @PostMapping(value = "/api/png/inspect", consumes = "application/octet-stream")
    public ResponseEntity<?> inspect(@RequestParam String name, @RequestParam long sizeBytes,
                                     @RequestParam(defaultValue = "256") int tileSize,
                                     HttpServletRequest request) {
        if (name.isBlank() || name.length() > 255 || sizeBytes < 33 || tileSize < 64 || tileSize > 512) {
            return error(400, "Nombre, tamaño declarado o tileSize inválidos");
        }
        if (request.getContentLengthLong() > 33) return error(413, "Enviar solo los 33 bytes de cabecera, no el PNG completo");
        try {
            byte[] header = request.getInputStream().readNBytes(34);
            if (header.length > 33) return error(413, "Enviar solo los 33 bytes de cabecera, no el PNG completo");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(PngInspector.inspectHeader(header, name, sizeBytes, tileSize));
        } catch (IOException | IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    @PostMapping(value = "/api/images", consumes = "application/octet-stream")
    public ResponseEntity<?> register(@RequestParam String imageId, @RequestParam String name,
                                      @RequestParam long sizeBytes, @RequestParam(defaultValue = "256") int tileSize,
                                      HttpServletRequest request) {
        if (!imageId.matches("[a-zA-Z0-9_-]{1,64}") || name.isBlank() || name.length() > 255
                || sizeBytes < 33 || tileSize < 64 || tileSize > 512) return error(400, "Parámetros de registro inválidos");
        if (request.getContentLengthLong() > 33) return error(413, "El registro recibe solo 33 bytes de cabecera");
        try {
            byte[] header = request.getInputStream().readNBytes(34);
            if (header.length > 33) return error(413, "El registro recibe solo 33 bytes de cabecera");
            PngInspector.Inspection image;
            try { image = PngInspector.inspectHeader(header, name, sizeBytes, tileSize); }
            catch (IOException e) { return error(400, e.getMessage()); }
            var registered = metadata.register(imageId, image, header);
            return ResponseEntity.accepted().location(URI.create("/api/image/" + imageId + "/status"))
                    .cacheControl(CacheControl.noStore()).body(registered);
        } catch (ResponseStatusException e) {
            return error(e.getStatusCode().value(), e.getReason());
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (IOException e) {
            return error(500, "No se pudo validar o guardar el registro: " + e.getMessage());
        }
    }

    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("status", "error", "error", message));
    }
}
