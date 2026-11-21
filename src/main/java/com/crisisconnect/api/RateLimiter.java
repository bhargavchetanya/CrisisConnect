package com.crisisconnect.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter (per client IP + bucket name), kept in memory.
 * Protects login / sign-up from password guessing and the public SOS form from spam.
 */
public final class RateLimiter {

    private final int limit;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    /** @return true if this call is allowed (and counts it) */
    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
        }
        if (hits.size() > 10_000) hits.clear();          // crude memory cap
        return true;
    }
}
