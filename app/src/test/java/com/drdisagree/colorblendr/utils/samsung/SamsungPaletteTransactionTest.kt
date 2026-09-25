package com.drdisagree.colorblendr.utils.samsung

import com.drdisagree.colorblendr.utils.samsung.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SamsungPaletteTransactionTest {
    private val names = listOf("accent1", "accent2", "accent3", "neutral1", "neutral2", "error")
        .map { family -> listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
            .map { "system_${family}_$it" } }
    private fun rows() = List(6) { family -> List(13) { shade -> -16777216 + family * 100 + shade } }
    private fun palette() = SamsungPalette.fromGenerated(rows(), names)

    @Test fun samsungDetection() { assertTrue(SamsungPalette.isEligible("SaMsUnG", true, true, "1")) }
    @Test fun otherManufacturersStayOnFallback() {
        listOf("Google", "Xiaomi", "Nothing", "OnePlus", "").forEach {
            assertFalse(SamsungPalette.isEligible(it, true, true, "1"))
        }
    }
    @Test fun onlyShizuku() { assertFalse(SamsungPalette.isEligible("samsung", false, true, "1")) }
    @Test fun requiresConnectionAndSamsungSetting() {
        assertFalse(SamsungPalette.isEligible("samsung", true, false, "1"))
        assertFalse(SamsungPalette.isEligible("samsung", true, true, null))
    }
    @Test fun exactly65AndExcludesError() { assertEquals(rows().take(5).flatten(), palette().colors) }
    @Test fun familyOrdering() { (0..4).forEach { assertEquals(rows()[it], palette().colors.chunked(13)[it]) } }
    @Test fun shadeOrdering() { assertEquals(rows()[0][5], palette().colors[5]); assertEquals(rows()[1][7], palette().colors[20]) }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingShade() {
        SamsungPalette.fromGenerated(rows().map { it.dropLast(1) }, names)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsReorderedNames() {
        SamsungPalette.fromGenerated(rows(), names.reversed())
    }
    @Test fun signedSerialization() {
        val p = SamsungPalette.fromGenerated(List(6) { List(13) { -1 } }, names)
        assertEquals(List(65) { -1 }.joinToString(prefix = "[", postfix = "]"), p.serialized)
        assertFalse(p.serialized.contains("4294967295"))
    }
    @Test fun monochromeUsesResultIncludingOverrides() {
        val gray = List(6) { List(13) { -8355712 } }
        assertEquals("1", SamsungPalette.fromGenerated(gray, names).grayFlag)
        assertEquals("0", palette().grayFlag)
        val overridden = gray.map { it.toMutableList() }; overridden[1][5] = -65536
        assertEquals("0", SamsungPalette.fromGenerated(overridden, names).grayFlag)
    }
    @Test fun shellQuoting() {
        assertEquals("'a'\"'\"'b\n\$(id)'", SamsungShell.quote("a'b\n\$(id)"))
    }
    @Test fun quotedTextIsAnExactShellArgument() {
        val value = "a'b\n\$(echo broken); echo injected"
        val process = ProcessBuilder("sh", "-c", "printf '%s' " + SamsungShell.quote(value)).start()
        assertEquals(value, process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
    }
    @Test fun customAndSecondaryTertiaryOverridesArePreserved() {
        val custom = rows().map { it.toMutableList() }
        custom[1][5] = -123456; custom[2][7] = -765432; custom[4][11] = -16777216
        val p = SamsungPalette.fromGenerated(custom, names)
        assertEquals(-123456, p.colors[18]); assertEquals(-765432, p.colors[33])
        assertEquals(-16777216, p.colors[63])
    }

    private class MemoryBackup : SamsungBackupStore {
        var value: SamsungBackup? = null
        override fun load() = value
        override fun save(backup: SamsungBackup) { value = backup }
        override fun clear() { value = null }
    }
    private class FakeGateway : SamsungGateway {
        val settings = mutableMapOf<String, String?>(
            "wallpapertheme_color" to "old palette", "wallpapertheme_color_isgray" to "0", "wallpapertheme_state" to "1")
        val overlays = SamsungPaletteTransaction.requiredOverlays.associateWith { true }.toMutableMap()
        val writes = mutableListOf<Pair<String, String?>>()
        var failKey: String? = null
        var failValue: String? = null
        var failures = 0
        var enableFailures = 0
        var permanentlyFailOverlay: String? = null
        var staleResources = false
        var staleGoogleResources = false
        var brokenRollbackResources = false
        var brokenRollbackMaterial = false
        override suspend fun lookup(packageName: String, resource: String): Int {
            val colors = settings["wallpapertheme_color"]?.removeSurrounding("[", "]")
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            if (colors?.size != 65) return if (writes.isNotEmpty() && (brokenRollbackResources ||
                (brokenRollbackMaterial && packageName == "com.drdisagree.colorblendr"))) -2 else -1
            if (staleResources || (staleGoogleResources && packageName == "com.drdisagree.colorblendr")) return -1
            return colors[if (resource.endsWith("qs_tile_round_background_on") || resource.endsWith("system_accent1_300")) 5 else 18]
        }
        override suspend fun get(key: String) = settings[key]
        override suspend fun put(key: String, value: String?) {
            writes.add(key to value)
            if (key == failKey && value == failValue && failures-- > 0) error("injected write failure")
            settings[key] = value
        }
        override suspend fun overlayEnabled(name: String) = overlays[name]
        override suspend fun enableOverlay(name: String) {
            if (name == permanentlyFailOverlay) error("overlay unavailable")
            if (enableFailures-- > 0) error("injected enable failure")
            check(overlays.containsKey(name)); overlays[name] = true
        }
    }
    private fun transaction(g: FakeGateway, b: MemoryBackup = MemoryBackup(), pause: suspend (Long) -> Unit = {}) =
        SamsungPaletteTransaction(g, b, pause)

    @Test fun writesInternalPaletteAndFinishesEnabled() = runBlocking {
        val g = FakeGateway(); transaction(g).apply(palette())
        assertEquals(palette().serialized, g.settings["wallpapertheme_color"])
        assertEquals("1", g.settings["wallpapertheme_state"])
        assertEquals(listOf("0", "1"), g.writes.filter { it.first == "wallpapertheme_state" }.map { it.second })
    }
    @Test fun samsungOverwriteAtTwoSecondsCannotBeReportedAsSuccess() = runBlocking {
        val g = FakeGateway()
        val b = MemoryBackup()
        var elapsed = 0L
        val result = runCatching {
            transaction(g, b, pause = {
                elapsed += it
                if (elapsed >= 2000) g.settings["wallpapertheme_color"] = "old palette"
            }).apply(palette())
        }
        assertNotNull("A successful write is not a persistent apply", result.exceptionOrNull())
        assertEquals("old palette", g.settings["wallpapertheme_color"])
    }
    @Test fun successWaitsForReconciliationWindowBeforeConfirmingBackup() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup()
        var elapsed = 0L
        transaction(g, b, pause = {
            assertTrue(b.value!!.pending)
            elapsed += it
        }).apply(palette())
        assertTrue("Must observe at least ten seconds", elapsed >= 10000)
        assertFalse(b.value!!.pending)
    }
    @Test fun enabledOverlaysWithStaleQsResourcesAreFailure() = runBlocking {
        val g = FakeGateway(); g.staleResources = true
        assertNotNull(runCatching { transaction(g).apply(palette()) }.exceptionOrNull())
        assertEquals("old palette", g.settings["wallpapertheme_color"])
    }
    @Test fun delayedResourceRegenerationCanSucceed() = runBlocking {
        val g = FakeGateway(); g.staleResources = true
        var elapsed = 0L
        transaction(g, pause = { elapsed += it; if (elapsed >= 2000) g.staleResources = false }).apply(palette())
        assertEquals(palette().serialized, g.settings["wallpapertheme_color"])
    }
    @Test fun staleThirdPartyMonetIsFailureEvenWhenQsMatches() = runBlocking {
        val g = FakeGateway(); g.staleGoogleResources = true
        assertNotNull(runCatching { transaction(g).apply(palette()) }.exceptionOrNull())
        assertEquals("old palette", g.settings["wallpapertheme_color"])
    }
    @Test fun unverifiedRollbackKeepsPendingBackup() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup()
        g.staleResources = true; g.brokenRollbackResources = true
        val failure = runCatching { transaction(g, b).apply(palette()) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.suppressed.isNotEmpty())
        assertTrue(b.value!!.pending)
    }
    @Test fun separateTransactionInstancesDoNotInterleaveWrites() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup()
        coroutineScope {
            repeat(3) { launch { transaction(g, b, pause = { delay(1) }).apply(palette()) } }
        }
        val keys = g.writes.map { it.first }
        assertEquals(List(3) { listOf("wallpapertheme_color", "wallpapertheme_color_isgray",
            "wallpapertheme_state", "wallpapertheme_state") }.flatten(), keys)
        assertEquals("old palette", b.value!!.original.palette)
    }
    @Test fun transientMainOverwriteIsNotHiddenByLaterRecovery() = runBlocking {
        val g = FakeGateway(); var elapsed = 0L
        val result = runCatching {
            transaction(g, pause = {
                elapsed += it
                if (elapsed in 2000..4999) g.settings["wallpapertheme_color"] = "old palette"
                else if (elapsed in 5000..10100) g.settings["wallpapertheme_color"] = palette().serialized
            }).apply(palette())
        }
        assertNotNull(result.exceptionOrNull())
    }
    @Test fun missingGoogleOverlayIsNotSuccess() = runBlocking {
        val g = FakeGateway(); g.overlays.remove(SamsungPaletteTransaction.G_MONET)
        assertNotNull(runCatching { transaction(g).apply(palette()) }.exceptionOrNull())
    }
    @Test fun rollbackMustRestoreMaterialEvenWhenQsIsRestored() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup()
        g.staleResources = true; g.brokenRollbackMaterial = true
        val failure = runCatching { transaction(g, b).apply(palette()) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.suppressed.isNotEmpty())
        assertTrue(b.value!!.pending)
    }
    @Test fun legacyPendingBackupWithMissingPaletteStillRecoversEnabledState() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup()
        b.value = SamsungBackup(SamsungSnapshot(null, null, "0"), "interrupted", true)
        g.settings["wallpapertheme_state"] = "0"
        assertNotNull(runCatching { transaction(g, b).restore() }.exceptionOrNull())
        assertEquals("1", g.settings["wallpapertheme_state"])
        assertNull(g.settings["wallpapertheme_color"])
        assertTrue(b.value!!.pending)
    }
    @Test fun neverTouchesGoogleArrayOrSecureJson() = runBlocking {
        val g = FakeGateway(); transaction(g).apply(palette())
        assertEquals(setOf("wallpapertheme_color", "wallpapertheme_color_isgray", "wallpapertheme_state"), g.writes.map { it.first }.toSet())
    }
    @Test fun restoresStateAfterFailureFollowingDisable() = runBlocking {
        val g = FakeGateway(); g.failKey = "wallpapertheme_state"; g.failValue = "1"; g.failures = 3
        assertNotNull(runCatching { transaction(g).apply(palette()) }.exceptionOrNull())
        assertEquals("1", g.settings["wallpapertheme_state"])
        assertEquals("old palette", g.settings["wallpapertheme_color"])
    }
    @Test fun failureBeforeStateRestoresPaletteWithoutDisablingTheme() = runBlocking {
        val g = FakeGateway(); g.failKey = "wallpapertheme_color_isgray"; g.failValue = "0"; g.failures = 1
        assertNotNull(runCatching { transaction(g).apply(palette()) }.exceptionOrNull())
        assertEquals("old palette", g.settings["wallpapertheme_color"])
        assertEquals("1", g.settings["wallpapertheme_state"])
        assertTrue(g.writes.none { it == ("wallpapertheme_state" to "0") })
    }
    @Test fun repairsDisabledSystemUiAndGMonet() = runBlocking {
        val g = FakeGateway(); g.overlays.keys.toList().forEach { g.overlays[it] = false }; g.enableFailures = 1
        transaction(g).apply(palette()); assertTrue(g.overlays.values.all { it })
    }
    @Test fun missingSystemUiIsNotSuccess() = runBlocking {
        val g = FakeGateway(); g.overlays.remove("android:SemWT_com.android.systemui")
        assertNotNull(runCatching { transaction(g).apply(palette()) }.exceptionOrNull())
        assertEquals("1", g.settings["wallpapertheme_state"])
    }
    @Test fun failedAndroidOverlayDoesNotPreventSystemUiAndGRecovery() = runBlocking {
        val g = FakeGateway()
        g.overlays.keys.toList().forEach { g.overlays[it] = false }
        g.permanentlyFailOverlay = SamsungPaletteTransaction.ANDROID
        assertNotNull(runCatching { transaction(g).apply(palette()) }.exceptionOrNull())
        assertEquals(true, g.overlays[SamsungPaletteTransaction.SYSTEM_UI])
        assertEquals(true, g.overlays[SamsungPaletteTransaction.G_MONET])
        assertEquals("1", g.settings["wallpapertheme_state"])
    }
    @Test fun concurrentAppliesAreSerialized() = runBlocking {
        val g = FakeGateway(); val t = transaction(g, pause = { delay(2) })
        coroutineScope { repeat(8) { launch { t.apply(palette()) } } }
        assertEquals(List(8) { listOf("0", "1") }.flatten(), g.writes.filter { it.first == "wallpapertheme_state" }.map { it.second })
    }
    @Test fun cancellationAfterStateZeroRecovers() = runBlocking {
        val g = FakeGateway(); val t = transaction(g, pause = { throw CancellationException("cancel") })
        assertTrue(runCatching { t.apply(palette()) }.exceptionOrNull() is CancellationException)
        assertEquals("1", g.settings["wallpapertheme_state"])
    }
    @Test fun sameSeedCanBeReappliedAndOriginalBackupSurvives() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup(); val t = transaction(g, b)
        t.apply(palette()); t.apply(palette())
        assertEquals("old palette", b.value!!.original.palette)
        assertEquals(palette().serialized, g.settings["wallpapertheme_color"])
    }
    @Test fun resetAfterNewInstanceRestoresOriginal() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup(); transaction(g, b).apply(palette())
        assertTrue(transaction(g, b).restore())
        assertEquals("old palette", g.settings["wallpapertheme_color"]); assertNull(b.value)
    }
    @Test fun resetDoesNotOverwriteLaterSamsungUserSelection() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup(); val t = transaction(g, b); t.apply(palette())
        g.settings["wallpapertheme_color"] = "external selection"
        assertTrue(t.restore()); assertEquals("external selection", g.settings["wallpapertheme_color"])
    }
    @Test fun interruptedResetCanBeRetriedInsteadOfBeingMistakenForExternalSelection() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup(); val t = transaction(g, b); t.apply(palette())
        g.failKey = "wallpapertheme_color_isgray"; g.failValue = "0"; g.failures = 1
        assertNotNull(runCatching { t.restore() }.exceptionOrNull())
        assertTrue(b.value!!.pending)
        assertTrue(transaction(g, b).restore())
        assertEquals("old palette", g.settings["wallpapertheme_color"])
        assertNull(b.value)
    }
    @Test fun duplicateCallbacksDoNotReapplyButRealWallpaperChangeDoes() {
        val guard = SamsungWallpaperGuard(); guard.record("wallpaper1")
        repeat(100) { assertTrue(guard.isDuplicate("wallpaper1")) }
        assertFalse(guard.isDuplicate("wallpaper2"))
        assertTrue(guard.isDuplicate("wallpaper2"))
    }

    @Test fun resetFromInitiallyDisabledStateStillFinishesEnabled() = runBlocking {
        val g = FakeGateway(); val b = MemoryBackup()
        g.settings["wallpapertheme_state"] = "0"
        val t = transaction(g, b); t.apply(palette()); t.restore()
        assertEquals("old palette", g.settings["wallpapertheme_color"])
        assertEquals("1", g.settings["wallpapertheme_state"])
    }

    @Test fun failureBeforeStateZeroStillRecoversInitiallyDisabledState() = runBlocking {
        val g = FakeGateway(); g.settings["wallpapertheme_state"] = "0"
        g.failKey = "wallpapertheme_color_isgray"; g.failValue = "0"; g.failures = 1
        assertNotNull(runCatching { transaction(g).apply(palette()) }.exceptionOrNull())
        assertEquals("1", g.settings["wallpapertheme_state"])
    }

    @Test fun unavailableFingerprintDoesNotSwallowWallpaperChanges() {
        val guard = SamsungWallpaperGuard()
        guard.record("unavailable")
        assertFalse(guard.isDuplicate("unavailable"))
    }

    @Test fun baselineRequiresStableSourceMatchingConsumedColors() {
        val guard = SamsungWallpaperGuard()
        guard.recordVerified("new", "new", false) // New wallpaper, seed still old.
        assertFalse(guard.isDuplicate("new"))
        guard.recordVerified("old", "new", true) // Changed while extracting.
        assertFalse(guard.isDuplicate("new"))
        guard.recordVerified("new", "new", true)
        assertTrue(guard.isDuplicate("new"))
        assertFalse(guard.isDuplicate("newer")) // Change before first callback.
    }

    @Test fun duplicateGuardIsAtomicAcrossThreads() = runBlocking {
        val guard = SamsungWallpaperGuard()
        val results = coroutineScope {
            List(32) { async(Dispatchers.Default) { guard.isDuplicate("new wallpaper") } }.awaitAll()
        }
        assertEquals(1, results.count { !it })
    }
}
