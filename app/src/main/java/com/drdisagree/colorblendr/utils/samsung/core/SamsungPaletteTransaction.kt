package com.drdisagree.colorblendr.utils.samsung.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SamsungGateway {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String?)
    /** null means absent; false means present but disabled. */
    suspend fun overlayEnabled(name: String): Boolean?
    suspend fun enableOverlay(name: String)
    suspend fun lookup(packageName: String, resource: String): Int
}

data class SamsungSnapshot(
    val palette: String?, val gray: String?, val state: String?,
    val qs: Int? = null, val volume: Int? = null,
    val materialA1: Int? = null, val materialA2: Int? = null
)
data class SamsungBackup(val original: SamsungSnapshot, val appliedPalette: String, val pending: Boolean = false)
interface SamsungBackupStore {
    fun load(): SamsungBackup?
    fun save(backup: SamsungBackup)
    fun clear()
}

/** Bounded, serialized transaction. All gateway failures throw; none are silent success. */
class SamsungPaletteTransaction(
    private val gateway: SamsungGateway,
    private val backups: SamsungBackupStore,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {}
) {
    suspend fun apply(palette: SamsungPalette) = mutex.withLock {
        val before = snapshot()
        val beforeResources = mapOf(QS to checkNotNull(before.qs), VOLUME to checkNotNull(before.volume),
            MATERIAL_A1 to checkNotNull(before.materialA1), MATERIAL_A2 to checkNotNull(before.materialA2))
        val oldBackup = backups.load()
        // Capture before the first write, durably. Do not replace the original on reapply.
        val backup = SamsungBackup(oldBackup?.original ?: before, palette.serialized, pending = true)
        backups.save(backup)
        try {
            log("before mainSha=${SamsungPaletteObservation.sha(before.palette)} forGSha=${SamsungPaletteObservation.sha(gateway.get(FOR_G))} state=${before.state}")
            gateway.put(COLOR, palette.serialized)
            log("T7 wallpapertheme_color written size=${palette.colors.size}")
            log("immediate mainSha=${SamsungPaletteObservation.sha(gateway.get(COLOR))}")
            gateway.put(GRAY, palette.grayFlag)
            log("gray=${palette.grayFlag}")
            gateway.put(STATE, "0")
            log("T8 state=0")
            pause(100)
            retry { gateway.put(STATE, "1") }
            log("T9 state=1")
            verifyOverlays()
            observe(palette.serialized, palette.grayFlag, mapOf(QS to palette.colors[5], VOLUME to palette.colors[18],
                MATERIAL_A1 to palette.colors[5], MATERIAL_A2 to palette.colors[18]), "apply")
            backups.save(backup.copy(pending = false))
        } catch (failure: Throwable) {
            // Cleanup survives caller cancellation. A dead binder can still prevent recovery;
            // propagate both errors and retain the durable backup for a later reset/retry.
            withContext(NonCancellable) {
                log("rollback ${failure.javaClass.simpleName}")
                val recoveryErrors = mutableListOf<Throwable>()
                suspend fun recover(block: suspend () -> Unit) {
                    try { block() } catch (error: Throwable) { recoveryErrors.add(error) }
                }
                recover { gateway.put(COLOR, before.palette) }
                recover { gateway.put(GRAY, before.gray) }
                // A failed IPC may already have changed remote state. Always recover,
                // including when the previous process left state=0 before this apply.
                recover { retry { gateway.put(STATE, "1") } }
                recover { repairPresentOverlays() }
                recover { observe(before.palette, before.gray, beforeResources, "rollback") }
                if (recoveryErrors.isEmpty()) {
                    recover { if (oldBackup == null) backups.clear() else backups.save(oldBackup) }
                }
                recoveryErrors.forEach { failure.addSuppressed(it); log("rollback failed: ${it.message}") }
            }
            throw failure
        }
    }

    /** True means a bridge-owned reset was handled, including a later external selection. */
    suspend fun restore(): Boolean = mutex.withLock {
        val backup = backups.load() ?: return@withLock false
        if (!backup.pending && gateway.get(COLOR) != backup.appliedPalette) {
            log("reset: external palette selected; preserve it")
            backups.clear()
            return@withLock true
        }
        val original = backup.original
        val originalColors = original.palette?.removeSurrounding("[", "]")?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 65 }
        val originalResources = listOf(
            QS to (original.qs ?: originalColors?.get(5)),
            VOLUME to (original.volume ?: originalColors?.get(18)),
            MATERIAL_A1 to original.materialA1, MATERIAL_A2 to original.materialA2
        ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        backups.save(backup.copy(pending = true))
        try {
            gateway.put(COLOR, original.palette)
            gateway.put(GRAY, original.gray)
            gateway.put(STATE, "0")
            pause(100)
            retry { gateway.put(STATE, "1") }
            verifyOverlays()
            observe(original.palette, original.gray, originalResources, "reset")
            // Legacy backups have no historical resolved resources. Attempt recovery
            // first (especially state=0), but retain evidence instead of claiming it
            // is fully verified or inventing the missing Material baseline.
            check(originalResources.size == 4) { "Legacy Samsung backup restored settings; resource baseline unavailable, backup retained" }
            backups.clear()
            log("reset: original Samsung palette restored; state=1")
            true
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { retry { gateway.put(STATE, "1") } }
                catch (recovery: Throwable) { failure.addSuppressed(recovery) }
                try { repairPresentOverlays() }
                catch (recovery: Throwable) { failure.addSuppressed(recovery) }
            }
            throw failure
        }
    }

    private suspend fun snapshot() = SamsungSnapshot(gateway.get(COLOR), gateway.get(GRAY), gateway.get(STATE),
        gateway.lookup("com.android.systemui", QS), gateway.lookup("com.android.systemui", VOLUME),
        gateway.lookup("com.drdisagree.colorblendr", MATERIAL_A1), gateway.lookup("com.drdisagree.colorblendr", MATERIAL_A2))

    private suspend fun observe(palette: String?, gray: String?, resources: Map<String, Int>, phase: String) {
        SamsungPaletteObservation(gateway, pause, log).verify(palette, gray, resources, phase)
    }

    private suspend fun verifyOverlays() {
        // Samsung registration is asynchronous; bounded observation, not a background poller.
        repeat(8) { attempt ->
            repairPresentOverlays()
            val android = gateway.overlayEnabled(ANDROID)
            val systemUi = gateway.overlayEnabled(SYSTEM_UI)
            log("T10 SemWT_android=$android SemWT_SystemUI=$systemUi G=${gateway.overlayEnabled(G_MONET)}")
            if (android == true && systemUi == true) return
            if (attempt < 7) pause(150)
        }
        error("Samsung did not enable required SemWT Android/SystemUI overlays")
    }

    private suspend fun repairPresentOverlays() {
        var failure: Exception? = null
        requiredOverlays.forEach { name ->
            try {
                if (gateway.overlayEnabled(name) == false) {
                    retry { gateway.enableOverlay(name) }
                    check(gateway.overlayEnabled(name) == true) { "Could not enable $name" }
                    log("recovered overlay=$name")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun retry(block: suspend () -> Unit) {
        var last: Exception? = null
        repeat(3) {
            try { block(); return } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { last = e }
        }
        throw checkNotNull(last)
    }

    companion object {
        // Transactions are recreated for every Apply/reset; an instance mutex did not
        // protect callers using distinct transaction instances.
        private val mutex = Mutex()
        const val COLOR = "wallpapertheme_color"
        const val FOR_G = "wallpapertheme_color_for_g"
        const val GRAY = "wallpapertheme_color_isgray"
        const val STATE = "wallpapertheme_state"
        const val ANDROID = "android:SemWT_android"
        const val SYSTEM_UI = "android:SemWT_com.android.systemui"
        const val G_MONET = "android:SemWT_G_MonetPalette"
        const val QS = "com.android.systemui:color/qs_tile_round_background_on"
        const val VOLUME = "com.android.systemui:color/volume_seekbar_progress_color"
        const val MATERIAL_A1 = "android:color/system_accent1_300"
        const val MATERIAL_A2 = "android:color/system_accent2_300"
        val requiredOverlays = listOf(ANDROID, SYSTEM_UI, G_MONET, "android:SemWT_MonetPalette")
    }
}
