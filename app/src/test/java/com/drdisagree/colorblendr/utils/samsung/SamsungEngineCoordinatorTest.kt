package com.drdisagree.colorblendr.utils.samsung

import com.drdisagree.colorblendr.utils.samsung.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SamsungEngineCoordinatorTest {
    private val request = SamsungEngineRequest(List(65) { -100 - it }, List(65) { -500 - it })
    private class Store : SamsungEngineStore {
        var entry: SamsungEngineBackup? = null
        override fun load() = entry
        override fun save(backup: SamsungEngineBackup) { entry = backup }
        override fun clear() { entry = null }
    }
    private class Engine(override val kind: SamsungEngineKind) : SamsungPaletteEngine {
        var capability = SamsungEngineCapability.SUPPORTED
        var current = "original"
        var applies = 0
        var restores = 0
        var noOp = false
        var stale = false
        var restoreFails = false
        var captureFails = false
        override suspend fun probe() = capability
        override suspend fun capture(): String { check(!captureFails); return current }
        override suspend fun apply(request: SamsungEngineRequest) {
            applies++
            if (!noOp) current = request.main.toString()
        }
        override suspend fun verify(request: SamsungEngineRequest): Boolean = !stale && current == request.main.toString()
        override suspend fun restore(snapshot: String) { restores++; if (restoreFails) error("cleanup denied"); current = snapshot }
        override suspend fun verifyRestored(snapshot: String) = current == snapshot
    }
    private fun coordinator(n: Engine, f: Engine, s: Store = Store(), pause: suspend (Long) -> Unit = {}) =
        SamsungEngineCoordinator(listOf(n, f), s, pause)
    @Test fun nativeAllowedUsesNative() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); val f = Engine(SamsungEngineKind.FABRICATED)
        assertEquals(SamsungEngineKind.NATIVE, coordinator(n, f).apply(request))
        assertEquals(0, f.applies)
    }
    @Test fun nativeDeniedFallsBackToFabricated() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); n.capability = SamsungEngineCapability.DENIED
        val f = Engine(SamsungEngineKind.FABRICATED)
        assertEquals(SamsungEngineKind.FABRICATED, coordinator(n, f).apply(request))
        assertEquals(0, n.applies)
    }
    @Test fun nativeCaptureFailureFallsBackWithoutMutation() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); n.captureFails = true
        val f = Engine(SamsungEngineKind.FABRICATED)
        assertEquals(SamsungEngineKind.FABRICATED, coordinator(n, f).apply(request))
        assertEquals(0, n.applies + n.restores)
    }
    @Test fun temporaryRegressionIsNotSuccessEvenIfFinalSampleMatches() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); val f = Engine(SamsungEngineKind.FABRICATED)
        f.capability = SamsungEngineCapability.DENIED
        var elapsed = 0L
        assertNotNull(runCatching { coordinator(n, f, pause = {
            elapsed += it; n.stale = elapsed in 2000L..4999L
        }).apply(request) }.exceptionOrNull())
        assertEquals("original", n.current)
    }
    @Test fun bothDeniedNeverWrite() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); val f = Engine(SamsungEngineKind.FABRICATED)
        n.capability = SamsungEngineCapability.DENIED; f.capability = SamsungEngineCapability.DENIED
        assertNotNull(runCatching { coordinator(n, f).apply(request) }.exceptionOrNull())
        assertEquals(0, n.applies + f.applies)
    }
    @Test fun nativeNoOpRequiresCleanupBeforeFallback() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); n.noOp = true
        val f = Engine(SamsungEngineKind.FABRICATED)
        assertEquals(SamsungEngineKind.FABRICATED, coordinator(n, f).apply(request))
        assertEquals(1, n.restores)
    }
    @Test fun staleNativeResourcesAreNotSuccess() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); n.stale = true
        val f = Engine(SamsungEngineKind.FABRICATED); f.capability = SamsungEngineCapability.DENIED
        assertNotNull(runCatching { coordinator(n, f).apply(request) }.exceptionOrNull())
        assertEquals("original", n.current)
    }
    @Test fun failedCleanupBlocksFallbackAndKeepsPending() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); n.stale = true; n.restoreFails = true
        val f = Engine(SamsungEngineKind.FABRICATED); val store = Store()
        assertNotNull(runCatching { coordinator(n, f, store).apply(request) }.exceptionOrNull())
        assertEquals(0, f.applies); assertTrue(store.entry!!.pending)
    }
    @Test fun resetRestoresOriginalAfterReplacement() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); val f = Engine(SamsungEngineKind.FABRICATED); val s = Store()
        val c = coordinator(n, f, s)
        c.apply(request); c.apply(SamsungEngineRequest(List(65) { -99 }, List(65) { -555 }))
        assertTrue(c.reset()); assertEquals("original", n.current); assertNull(s.entry)
    }
    @Test fun failedReplacementRestoresLastSuccessfulPalette() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); val f = Engine(SamsungEngineKind.FABRICATED); val s = Store()
        val c = coordinator(n, f, s); c.apply(request); n.stale = true
        assertNotNull(runCatching { c.apply(SamsungEngineRequest(List(65) { -99 }, List(65) { -555 })) }.exceptionOrNull())
        assertEquals(request.main.toString(), n.current); assertFalse(s.entry!!.pending)
        assertEquals(0, f.applies)
    }
    @Test fun unavailableGoogleNeverCopiesMain() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); val f = Engine(SamsungEngineKind.FABRICATED)
        assertEquals(SamsungEngineKind.FABRICATED, coordinator(n, f).apply(SamsungEngineRequest(request.main, null)))
        assertEquals(0, n.applies)
    }
    @Test fun observesTenSecondsAndDetectsDelayedStaleness() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); val f = Engine(SamsungEngineKind.FABRICATED)
        f.capability = SamsungEngineCapability.DENIED
        var elapsed = 0L
        assertNotNull(runCatching { coordinator(n, f, pause = { elapsed += it; if (elapsed >= 2000) n.stale = true }).apply(request) }.exceptionOrNull())
        assertTrue(elapsed >= 10000)
    }
    @Test fun concurrentInstancesSerializeTransactions() = runBlocking {
        val n = Engine(SamsungEngineKind.NATIVE); val f = Engine(SamsungEngineKind.FABRICATED); val s = Store()
        coroutineScope { repeat(3) { launch { coordinator(n, f, s, pause = { delay(1) }).apply(request) } } }
        assertEquals(3, n.applies); assertEquals("original", s.entry!!.original)
    }
    @Test(expected = IllegalArgumentException::class) fun malformedPairRejected() { SamsungEngineRequest(List(64) { 0 }, List(65) { 0 }) }
}
