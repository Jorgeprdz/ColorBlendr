package com.drdisagree.colorblendr.utils.samsung.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SamsungEngineCapability { SUPPORTED, DENIED, UNAVAILABLE }
enum class SamsungEngineKind { NATIVE, FABRICATED }
data class SamsungEngineRequest(val main: List<Int>, val google: List<Int>?) {
    init { require(main.size == 65); require(google == null || google.size == 65) }
}
interface SamsungPaletteEngine {
    val kind: SamsungEngineKind
    suspend fun probe(): SamsungEngineCapability
    suspend fun capture(): String
    suspend fun apply(request: SamsungEngineRequest)
    suspend fun verify(request: SamsungEngineRequest): Boolean
    suspend fun restore(snapshot: String)
    suspend fun verifyRestored(snapshot: String): Boolean
}
data class SamsungEngineBackup(val kind: SamsungEngineKind, val original: String, val pending: Boolean)
interface SamsungEngineStore {
    fun load(): SamsungEngineBackup?
    fun save(backup: SamsungEngineBackup)
    fun clear()
}

/** The settings-only transaction is intentionally not an engine in this router. */
class SamsungEngineCoordinator(
    private val engines: List<SamsungPaletteEngine>,
    private val store: SamsungEngineStore,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {}
) {
    suspend fun apply(request: SamsungEngineRequest): SamsungEngineKind = mutex.withLock {
        val old = store.load()
        check(old?.pending != true) { "Samsung recovery is pending; reset before applying another palette" }
        val failures = mutableListOf<Throwable>()
        // Once a backend owns the theme, changing ownership requires reset. Otherwise
        // a previous fabricated overlay could conceal a failed native transaction.
        for (engine in engines.sortedBy { it.kind.ordinal }.filter { old == null || it.kind == old.kind }) {
            if (engine.kind == SamsungEngineKind.NATIVE && request.google == null) {
                log("NATIVE UNAVAILABLE: no lossless supported GG generator for configuration")
                continue
            }
            val before = try {
                val capability = engine.probe()
                log("${engine.kind} capability=$capability")
                if (capability != SamsungEngineCapability.SUPPORTED) continue
                engine.capture()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failures.add(failure)
                log("${engine.kind} preparation failed: ${failure.message}")
                continue
            }
            val backup = SamsungEngineBackup(engine.kind, old?.original ?: before, true)
            store.save(backup)
            try {
                engine.apply(request)
                observe("${engine.kind} apply") { engine.verify(request) }
                store.save(backup.copy(pending = false))
                log("SUCCESS backend=${engine.kind}")
                return@withLock engine.kind
            } catch (failure: Throwable) {
                failures.add(failure)
                withContext(NonCancellable) {
                    try {
                        log("${engine.kind} rollback: ${failure.message}")
                        engine.restore(before)
                        observe("${engine.kind} rollback") { engine.verifyRestored(before) }
                        if (old == null) store.clear() else store.save(old)
                    } catch (recovery: Throwable) {
                        failure.addSuppressed(recovery)
                        // Never run another backend on top of unverified recovery.
                        throw failure
                    }
                }
                if (failure is CancellationException) throw failure
            }
        }
        throw IllegalStateException("No Samsung engine applied verified resources. " +
            "Native/Fabricated denied, unavailable or stale. ${failures.joinToString { it.message.orEmpty() }}")
            .also { error -> failures.forEach(error::addSuppressed) }
    }

    suspend fun reset(): Boolean = mutex.withLock {
        val saved = store.load() ?: return@withLock false
        val engine = engines.first { it.kind == saved.kind }
        store.save(saved.copy(pending = true))
        engine.restore(saved.original)
        observe("${engine.kind} reset") { engine.verifyRestored(saved.original) }
        store.clear()
        true
    }

    private suspend fun observe(phase: String, verify: suspend () -> Boolean) {
        var previous = 0L
        var final = false
        var matched = false
        var regressed = false
        for (offset in listOf(0L, 250L, 1000L, 2000L, 5000L, 10000L)) {
            if (offset > previous) pause(offset - previous)
            previous = offset
            final = verify()
            if (matched && !final) regressed = true
            matched = matched || final
            log("phase=$phase sampleMs=$offset resourcesMatch=$final")
        }
        check(final && !regressed) { "$phase resources/state stale or regressed during 10s" }
    }

    companion object { private val mutex = Mutex() }
}
