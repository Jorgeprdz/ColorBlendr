package com.drdisagree.colorblendr.utils.samsung.core

import java.security.MessageDigest

/** Finite observation owned by Apply. Never schedules work after returning. */
internal class SamsungPaletteObservation(
    private val gateway: SamsungGateway,
    private val pause: suspend (Long) -> Unit,
    private val log: (String) -> Unit
) {
    suspend fun verify(
        expected: String?,
        gray: String?,
        resources: Map<String, Int>,
        phase: String
    ) {
        val started = System.nanoTime()
        var previous = 0L
        var overwritten = false
        var finalProblems = emptyList<String>()
        for (offset in listOf(0L, 250L, 1000L, 2000L, 5000L, 10000L)) {
            if (offset > previous) pause(offset - previous)
            previous = offset
            val actual = gateway.get(SamsungPaletteTransaction.COLOR)
            val state = gateway.get(SamsungPaletteTransaction.STATE)
            val actualGray = gateway.get(SamsungPaletteTransaction.GRAY)
            val overlays = SamsungPaletteTransaction.requiredOverlays.associateWith { gateway.overlayEnabled(it) }
            val resolved = resources.mapValues { (resource, _) ->
                // Resolve framework tones in a third-party app context, where Samsung
                // may choose FOR_G instead of MAIN. Framework's own context is insufficient.
                val target = if (resource.startsWith("android:")) "com.drdisagree.colorblendr" else "com.android.systemui"
                gateway.lookup(target, resource)
            }
            overwritten = overwritten || actual != expected
            finalProblems = buildList {
                if (actual != expected) add("MAIN overwritten")
                if (state != "1") add("state=$state")
                if (actualGray != gray) add("gray=$actualGray")
                overlays.filterValues { it != true }.keys.forEach { add("overlay unavailable: $it") }
                resources.forEach { (name, color) ->
                    if (resolved[name] != color) add("stale $name expected=${hex(color)} actual=${resolved[name]?.let(::hex)}")
                }
            }
            log("phase=$phase sampleMs=$offset elapsedMs=${(System.nanoTime() - started) / 1000000} expectedSha=${sha(expected)} mainSha=${sha(actual)} " +
                "forGSha=${sha(gateway.get(SamsungPaletteTransaction.FOR_G))} state=$state gray=$actualGray " +
                "overlays=$overlays dynamic=${gateway.overlayEnabled("com.android.systemui:dynamic")} " +
                "resources=${resolved.mapValues { hex(it.value) }} problems=$finalProblems")
        }
        check(!overwritten && finalProblems.isEmpty()) {
            "Samsung $phase did not persist for 10s: overwritten=$overwritten $finalProblems"
        }
    }

    companion object {
        fun sha(value: String?): String = value?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        } ?: "absent"

        private fun hex(color: Int) = "#%08x".format(color)
    }
}
