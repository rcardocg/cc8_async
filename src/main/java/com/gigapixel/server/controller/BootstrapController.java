package com.gigapixel.server.controller;

import com.gigapixel.server.model.ImageMetadata;
import com.gigapixel.server.service.MetadataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api")
public class BootstrapController {
    private final MetadataService metadataService;

    public BootstrapController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/images")
    public ResponseEntity<List<String>> listAvailableImages() {
        return ResponseEntity.ok(metadataService.listImages());
    }

    @GetMapping("/image/{id}/metadata")
    public ResponseEntity<ImageMetadata> getImageMetadata(@PathVariable String id) {
        metadataService.status(id); // Refresca los registros persistidos antes de consultar.
        return ResponseEntity.ok(metadataService.getImage(id));
    }

    @GetMapping("/image/{id}/status")
    public ResponseEntity<com.gigapixel.server.model.ImageStatus> getImageStatus(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(metadataService.status(id));
    }
}
