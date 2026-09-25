package com.drdisagree.colorblendr.utils.samsung.core

import java.util.concurrent.atomic.AtomicReference

/** Content-based guard: no time window that could swallow a real wallpaper change. */
class SamsungWallpaperGuard {
    private val fingerprint = AtomicReference<String?>(null)
    fun record(value: String) { fingerprint.set(value) }
    fun recordVerified(before: String, after: String, matchesStoredColors: Boolean) {
        if (before != "unavailable" && before == after && matchesStoredColors) record(before)
        else clear()
    }
    fun isDuplicate(value: String): Boolean {
        // A failed permission/IPC probe is not evidence that wallpaper is unchanged.
        if (value == "unavailable") { clear(); return false }
        return fingerprint.getAndSet(value) == value
    }
    fun clear() { fingerprint.set(null) }
}
