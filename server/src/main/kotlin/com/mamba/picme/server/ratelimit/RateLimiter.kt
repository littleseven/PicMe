package com.mamba.picme.server.ratelimit

import io.ktor.server.application.ApplicationCall
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory sliding-window rate limiter (per IP).
 * Not suitable for multi-instance deployment without external store.
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long = 60_000L,
) {
    private val log = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val windowStart = now - windowMs
        val entry = log.computeIfAbsent(ip) { mutableListOf() }
        synchronized(entry) {
            entry.removeAll { it <= windowStart }
            if (entry.size >= maxRequests) return false
            entry.add(now)
            return true
        }
    }
}
