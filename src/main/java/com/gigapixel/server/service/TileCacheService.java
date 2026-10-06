package com.gigapixel.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public final class TileCacheService {

    private static final Logger log = LoggerFactory.getLogger(TileCacheService.class);

    private final long maxBytes;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();
    private final AtomicLong currentBytes = new AtomicLong(0);
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final ReentrantLock lock = new ReentrantLock();

    public TileCacheService(@org.springframework.beans.factory.annotation.Value("${tiles.cache-bytes:52428800}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public byte[] get(String key) {
        lock.lock();
        try {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                misses.incrementAndGet();
                return null;
            }
            hits.incrementAndGet();
            cache.remove(key);
            cache.put(key, entry);
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
            CacheEntry existing = cache.get(key);
            if (existing != null) {
                currentBytes.addAndGet(-existing.data.length);
                cache.remove(key);
            }
            ensureCapacity(data.length);
            cache.put(key, new CacheEntry(data));
            currentBytes.addAndGet(data.length);
        } finally {
            lock.unlock();
        }
    }

    private void ensureCapacity(long requiredBytes) {
        while (currentBytes.get() + requiredBytes > maxBytes && !cache.isEmpty()) {
            String oldestKey = cache.keySet().iterator().next();
            evictInternal(oldestKey);
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
            return new CacheStats(
                    cache.size(),
                    currentBytes.get(),
                    maxBytes,
                    hits.get(),
                    misses.get(),
                    evictions.get()
            );
        } finally {
            lock.unlock();
        }
    }

    public record CacheEntry(byte[] data) {}
    public record CacheStats(int entries, long usedBytes, long maxBytes, long hits, long misses, long evictions) {}
}