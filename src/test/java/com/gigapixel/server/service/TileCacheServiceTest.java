package com.gigapixel.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TileCacheServiceTest {

    @TempDir Path tempDir;

    @Test
    void putAndGetStoresAndRetrievesData() {
        TileCacheService cache = new TileCacheService(1024 * 1024);
        String key = "test:1:0:0:3";
        byte[] data = "tile-data".getBytes();

        cache.put(key, data);
        byte[] retrieved = cache.get(key);

        assertNotNull(retrieved);
        assertArrayEquals(data, retrieved);
    }

    @Test
    void getNonExistentReturnsNull() {
        TileCacheService cache = new TileCacheService(1024 * 1024);
        assertNull(cache.get("nonexistent"));
    }

    @Test
    void evictionRespectsMaxBytes() {
        long maxBytes = 500;
        TileCacheService cache = new TileCacheService(maxBytes);
        byte[] data = new byte[200];

        cache.put("key1", data);
        cache.put("key2", data);
        cache.put("key3", data);

        assertNull(cache.get("key1"));
        assertNotNull(cache.get("key2"));
        assertNotNull(cache.get("key3"));
    }

    @Test
    void lfuEvictionUsesFrequencyNotRecencyOrInsertionOrder() {
        TileCacheService cache = new TileCacheService(400);
        byte[] data = new byte[150];

        cache.put("a", data);
        cache.get("a");
        cache.put("b", data);
        cache.get("a");
        cache.get("b");
        cache.put("c", data);

        assertNull(cache.get("b"));
        assertNotNull(cache.get("a"));
        assertNotNull(cache.get("c"));
    }

    @Test
    void ttlExpiresWithoutReadsAndAccessRenewsOnlyItsOwnDeadline() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        TileCacheService cache = new TileCacheService(1000, 100, clock::get);
        cache.put("a", new byte[100]);
        cache.put("b", new byte[200]);
        clock.set(90);
        assertNotNull(cache.get("a"));
        clock.set(100);
        cache.expire();
        assertEquals(100, cache.stats().usedBytes());
        assertNull(cache.get("b"));
        clock.set(190);
        cache.expire();
        assertEquals(0, cache.stats().entries());
        assertEquals(0, cache.stats().usedBytes());
    }

    @Test
    void agingAllowsHistoricallyPopularTilesToLoseTheirPriority() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        TileCacheService cache = new TileCacheService(400, 100, clock::get);
        cache.put("a", new byte[150]);
        cache.put("b", new byte[150]);
        for (int i = 0; i < 64; i++) cache.get("a");
        // Both remain alive; b is now used more often, so past popularity must decay.
        for (int i = 1; i <= 24; i++) {
            clock.set(i * 90);
            cache.get("a");
            cache.get("b");
            cache.get("b");
        }
        cache.put("c", new byte[150]);
        assertNull(cache.get("a")); // New usage overtakes historical popularity after aging.
        assertNotNull(cache.get("b"));
        assertNotNull(cache.get("c"));
    }

    @Test
    void cacheStatsTrackHitsMissesAndEvictions() {
        TileCacheService cache = new TileCacheService(400);
        byte[] data = new byte[150];

        cache.put("a", data);
        cache.get("a");
        cache.get("a");
        cache.get("b");
        cache.put("c", data);
        cache.put("d", data);

        TileCacheService.CacheStats stats = cache.stats();
        assertEquals(2, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(1, stats.evictions());
        assertEquals(2, stats.entries());
    }

    @Test
    void evictRemovesSpecificKey() {
        TileCacheService cache = new TileCacheService(1024);
        byte[] data = new byte[100];

        cache.put("a", data);
        cache.put("b", data);
        cache.evict("a");

        assertNull(cache.get("a"));
        assertNotNull(cache.get("b"));
    }

    @Test
    void clearRemovesAllEntries() {
        TileCacheService cache = new TileCacheService(1024);
        cache.put("a", new byte[100]);
        cache.put("b", new byte[100]);

        cache.clear();

        assertNull(cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals(0, cache.stats().entries());
    }

    @Test
    void oversizedTileNotCached() {
        TileCacheService cache = new TileCacheService(1000);
        byte[] oversized = new byte[2000];

        cache.put("big", oversized);

        assertNull(cache.get("big"));
    }

    @Test
    void updateExistingKeyReplacesData() {
        TileCacheService cache = new TileCacheService(1024);
        byte[] v1 = "v1".getBytes();
        byte[] v2 = "v2".getBytes();

        cache.put("key", v1);
        cache.put("key", v2);

        assertArrayEquals(v2, cache.get("key"));
    }

    @Test
    void cacheBytesTrackedCorrectly() {
        TileCacheService cache = new TileCacheService(1000);
        cache.put("a", new byte[100]);
        cache.put("b", new byte[200]);

        TileCacheService.CacheStats stats = cache.stats();
        assertEquals(300, stats.usedBytes());
        assertEquals(1000, stats.maxBytes());
    }
}
