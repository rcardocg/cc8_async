package com.gigapixel.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public final class TileCacheService {

    private static final Logger log = LoggerFactory.getLogger(TileCacheService.class);

    private final long maxBytes;
    private final long ttlMs;
    private final LongSupplier clock;
    private long lastAging;
    private final Map<String, CacheEntry> cache = new HashMap<>();
    private final AtomicLong currentBytes = new AtomicLong(0);
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public TileCacheService(@Value("${tiles.cache-bytes:52428800}") long maxBytes,
                            @Value("${tiles.cache-ttl-ms:30000}") long ttlMs) {
        this(maxBytes, ttlMs, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public TileCacheService(long maxBytes) { this(maxBytes, 30000); }

    TileCacheService(long maxBytes, long ttlMs, LongSupplier clock) {
        if (maxBytes <= 0 || ttlMs <= 0) throw new IllegalArgumentException("Presupuesto y TTL deben ser positivos");
        this.maxBytes = maxBytes;
        this.ttlMs = ttlMs;
        this.clock = clock;
        this.lastAging = clock.getAsLong();
    }

    public byte[] get(String key) {
        lock.lock();
        try {
            expireInternal();
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                misses.incrementAndGet();
                return null;
            }
            hits.incrementAndGet();
            entry.frequency = Math.min(Integer.MAX_VALUE, entry.frequency + 1L);
            entry.lastUse = clock.getAsLong();
            return entry.data;
        } finally {
            lock.unlock();
        }
    }

    public void put(String key, byte[] data) {
        if (data == null || data.length == 0) return;
        if (data.length > maxBytes) {
            log.warn("Tile {} excede el tamaño máximo de caché ({} > {}), no se almacena", key, data.length, maxBytes);
            return;
        }

        lock.lock();
        try {
            expireInternal();
            CacheEntry existing = cache.get(key);
            if (existing != null) {
                currentBytes.addAndGet(-existing.data.length);
                cache.remove(key);
            }
            ensureCapacity(data.length);
            cache.put(key, new CacheEntry(data, clock.getAsLong()));
            currentBytes.addAndGet(data.length);
        } finally {
            lock.unlock();
        }
    }

    private void ensureCapacity(long requiredBytes) {
        while (currentBytes.get() + requiredBytes > maxBytes && !cache.isEmpty()) {
            String victim = cache.entrySet().stream().min(Comparator
                    .<Map.Entry<String, CacheEntry>>comparingLong(e -> e.getValue().frequency)
                    .thenComparing(Map.Entry::getKey)).orElseThrow().getKey();
            evictInternal(victim);
        }
    }

    public void evict(String key) {
        lock.lock();
        try {
            evictInternal(key);
        } finally {
            lock.unlock();
        }
    }

    private void evictInternal(String key) {
        CacheEntry entry = cache.remove(key);
        if (entry != null) {
            currentBytes.addAndGet(-entry.data.length);
            evictions.incrementAndGet();
        }
    }

    public void clear() {
        lock.lock();
        try {
            cache.clear();
            currentBytes.set(0);
        } finally {
            lock.unlock();
        }
    }

    public CacheStats stats() {
        lock.lock();
        try {
            expireInternal();
            return new CacheStats(
                    cache.size(),
                    currentBytes.get(),
                    maxBytes,
                    hits.get(),
                    misses.get(),
                    evictions.get(), ttlMs, "LFU_AGING_TTL"
            );
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void expire() {
        lock.lock();
        try { expireInternal(); } finally { lock.unlock(); }
    }

    private void expireInternal() {
        long now = clock.getAsLong();
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            CacheEntry entry = iterator.next().getValue();
            if (now - entry.lastUse >= ttlMs) {
                iterator.remove();
                currentBytes.addAndGet(-entry.data.length);
                evictions.incrementAndGet();
            }
        }
        if (now - lastAging >= ttlMs) {
            cache.values().forEach(entry -> entry.frequency = Math.max(1, entry.frequency / 2));
            lastAging = now;
        }
    }

    private static final class CacheEntry {
        final byte[] data;
        long frequency = 1;
        long lastUse;
        CacheEntry(byte[] data, long now) { this.data = data; this.lastUse = now; }
    }
    public record CacheStats(int entries, long usedBytes, long maxBytes, long hits, long misses,
                             long evictions, long ttlMs, String policy) {}
}
