package com.gigapixel.server.controller;

import com.gigapixel.server.service.TileCacheService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class CacheController {

    private final TileCacheService cacheService;

    public CacheController(TileCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/cache/stats")
    public ResponseEntity<TileCacheService.CacheStats> stats() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).noStore().mustRevalidate())
                .body(cacheService.stats());
    }

    @GetMapping("/cache/clear")
    public ResponseEntity<Void> clear() {
        cacheService.clear();
        return ResponseEntity.noContent().build();
    }
}