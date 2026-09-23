package com.gigapixel.server.controller;

import com.gigapixel.server.model.ImageMetadata;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api")
public class BootstrapController {

    @GetMapping("/images")
    public ResponseEntity<List<String>> listAvailableImages() {
        List<String> mockImages = Arrays.asList("galaxia_gigapixel_01", "mapa_topografico_02");
        return ResponseEntity.ok(mockImages); 
    }

    @GetMapping("/image/{id}/metadata")
    public ResponseEntity<ImageMetadata> getImageMetadata(@PathVariable String id) {
        ImageMetadata metadata = new ImageMetadata(
            id, 65536, 65536, 256, 65536, null, "jpeg"
        );
        return ResponseEntity.ok(metadata);
    }
}