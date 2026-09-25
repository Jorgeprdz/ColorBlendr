package com.drdisagree.colorblendr.utils.samsung

import android.app.WallpaperManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.drdisagree.colorblendr.BuildConfig
import com.drdisagree.colorblendr.ColorBlendr.Companion.appContext
import com.drdisagree.colorblendr.data.common.Utilities.customColorEnabled
import com.drdisagree.colorblendr.data.common.Utilities.getCurrentMonetStyle
import com.drdisagree.colorblendr.data.common.Utilities.getAccentSaturation
import com.drdisagree.colorblendr.data.common.Utilities.getBackgroundSaturation
import com.drdisagree.colorblendr.data.common.Utilities.getBackgroundLightness
import com.drdisagree.colorblendr.data.common.Utilities.getSeedColorValue
import com.drdisagree.colorblendr.data.common.Utilities.getWallpaperColorList
import com.drdisagree.colorblendr.data.common.Utilities.isShizukuMode
import com.drdisagree.colorblendr.data.domain.PreviewController
import com.drdisagree.colorblendr.data.domain.RefreshCoordinator
import com.drdisagree.colorblendr.provider.ShizukuConnectionProvider
import com.drdisagree.colorblendr.service.IShizukuConnection
import com.drdisagree.colorblendr.utils.app.SystemUtil
import com.drdisagree.colorblendr.utils.colors.ColorUtil.systemPaletteNames
import com.drdisagree.colorblendr.utils.samsung.core.*
import com.drdisagree.colorblendr.utils.samsung.engine.*
import com.drdisagree.colorblendr.utils.shizuku.ShizukuUtil
import com.drdisagree.colorblendr.utils.wallpaper.WallpaperColorUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

object SamsungShizukuPaletteBridge {
    private const val TAG = "SamsungPaletteBridge"
    private val support = MutableStateFlow(false)
    val supported = support.asStateFlow()
    private val wallpaperGuard = SamsungWallpaperGuard()
    private val sequence = AtomicLong()
    @Volatile private var activeTransaction: String = "none"
    fun isSamsungDevice() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    fun isSupported() = isSamsungDevice() && isShizukuMode() && support.value

    internal fun generatePalette(isDark: Boolean): SamsungPalette {
        val generated = PreviewController.buildPreviewColors()
        val rows = if (isDark) generated.paletteDark else generated.paletteLight
        return SamsungPalette.fromGenerated(rows, systemPaletteNames.map { it.toList() })
    }

    fun trace(event: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "t=${SystemClock.elapsedRealtime()} tx=$activeTransaction $event")
    }

    /** IO-only capability probe; called on screen entry and again at every apply. */
    suspend fun refreshSupport(): Boolean = withContext(Dispatchers.IO) {
        if (!isSamsungDevice() || !isShizukuMode() || !ShizukuUtil.isShizukuAvailable ||
            !ShizukuUtil.hasShizukuPermission()) {
            support.value = false
            return@withContext false
        }
        try {
            val connection = ShizukuConnectionProvider.connect() ?: run {
                support.value = false
                return@withContext false
            }
            probe(ShizukuSamsungGateway(connection, Process.myUid() / 100000))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            support.value = false
            trace("capability unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun probe(gateway: ShizukuSamsungGateway): Boolean {
        return try {
            val state = gateway.get(SamsungPaletteTransaction.STATE)
            SamsungPalette.isEligible(Build.MANUFACTURER, isShizukuMode(), true, state)
                .also { support.value = it; trace("Samsung detected=$it sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} method=SHIZUKU") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            support.value = false
            trace("capability unavailable: ${e.javaClass.simpleName}")
            // A failed command is not evidence of an unsupported device. The apply
            // caller must report failure instead of silently writing secure JSON.
            throw e
        }
    }

    /** null = unsupported, false = failed (do NOT then run another theme pipeline). */
    suspend fun applyIfSupported(connection: IShizukuConnection): Boolean? {
        if (!isSamsungDevice() || !isShizukuMode()) return null
        val user = Process.myUid() / 100000
        val gateway = ShizukuSamsungGateway(connection, user)
        probe(gateway)
        val start = SystemClock.elapsedRealtime()
        activeTransaction = "${Process.myPid()}-${sequence.incrementAndGet()}"
        trace("T4 bridge begin seed=${getSeedColorValue()} style=${getCurrentMonetStyle()} manual=${customColorEnabled()} " +
            "accentSaturation=${getAccentSaturation()} backgroundSaturation=${getBackgroundSaturation()} backgroundLightness=${getBackgroundLightness()}")
        try {
            // Exact live-preview generator, including current spec, tuning and pref overrides.
            // Samsung has a single tonal matrix; choose the active light/dark palette.
            val palette = generatePalette(SystemUtil.isDarkMode)
            val rows = palette.colors.chunked(13)
            val before = wallpaperFingerprint()
            val sourceColors = WallpaperColorUtil.getWallpaperColorsFromSource(appContext)
            wallpaperGuard.recordVerified(before, wallpaperFingerprint(),
                customColorEnabled() || (sourceColors != null && sourceColors == getWallpaperColorList()))
            val rpc = SamsungEngineRpc(connection, user, ::trace)
            val google = if (rpc.probe("nativeProbe") == SamsungEngineCapability.SUPPORTED) {
                runCatching { SamsungGooglePalette.generate(rpc::google) }
                    .onFailure { trace("GG unavailable for selected configuration: ${it.message}") }.getOrNull()
            } else null
            trace("expected mainSha=${SamsungPaletteObservation.sha(palette.serialized)} ggSha=${SamsungPaletteObservation.sha(google?.toString())}")
            trace("T5 G Monet=${gateway.overlayEnabled(SamsungPaletteTransaction.G_MONET)}; T6 secure JSON skipped (Samsung authoritative)")
            trace("size=${palette.colors.size} A1_300=${rows[0][5]} A1_500=${rows[0][7]} A2_300=${rows[1][5]} A2_500=${rows[1][7]} A3_300=${rows[2][5]} N1_500=${rows[3][7]} N2_500=${rows[4][7]}")
            val store = SamsungEnginePreferences(appContext, user)
            val preview = PreviewController.buildPreviewColors()
            val roles = (preview.lightMap + preview.darkMap).filterKeys {
                appContext.resources.getIdentifier(it, "color", "android") != 0
            }
            val engines = SamsungEngineKind.entries.map {
                ShizukuPaletteEngine(it, rpc, gateway, store, SystemUtil.isDarkMode, roles, ::trace)
            }
            val backend = SamsungEngineCoordinator(engines, store, log = ::trace)
                .apply(SamsungEngineRequest(palette.colors, google))
            support.value = true
            trace("backend finally used=$backend")
            RefreshCoordinator.triggerRefresh()
            trace("apply verified")
            return true
        } finally {
            trace("T14 bridge end durationMs=${SystemClock.elapsedRealtime() - start}")
            activeTransaction = "none"
        }
    }

    suspend fun removeIfOwned(connection: IShizukuConnection): Boolean? {
        if (!isSamsungDevice() || !isShizukuMode()) return null
        val user = Process.myUid() / 100000
        val store = SamsungEnginePreferences(appContext, user)
        activeTransaction = "${Process.myPid()}-${sequence.incrementAndGet()}-reset"
        try {
            val rpc = SamsungEngineRpc(connection, user, ::trace)
            val gateway = ShizukuSamsungGateway(connection, user)
            val engines = SamsungEngineKind.entries.map {
                ShizukuPaletteEngine(it, rpc, gateway, store, SystemUtil.isDarkMode, emptyMap(), ::trace)
            }
            val result = if (store.load() != null) SamsungEngineCoordinator(engines, store, log = ::trace).reset() else {
                // No engine journal: clean only our fixed IDs (e.g. after reinstall).
                // Never fall through to the old secure JSON reset on this route.
                val present = rpc.call("fabricatedPresent")
                if (present.keys().asSequence().any { present.getString(it) != "absent" }) rpc.call("fabricatedRemove")
                true
            }
            RefreshCoordinator.triggerRefresh()
            return result
        } finally {
            trace("reset end")
            activeTransaction = "none"
        }
    }

    /** Called before BroadcastListener changes seed prefs. A true wallpaper change is retained. */
    fun ignoreUnchangedWallpaper(): Boolean {
        if (!isSupported()) return false
        return wallpaperGuard.isDuplicate(wallpaperFingerprint()).also {
            trace("T11 wallpaper callback duplicate=$it")
        }
    }

    fun forgetWallpaperFingerprint() { wallpaperGuard.clear() }

    private fun wallpaperFingerprint(): String {
        return runCatching {
            val manager = WallpaperManager.getInstance(appContext)
            // Never fingerprint ColorUtil.monetAccentColors: the wallpaper extractor uses
            // framework colors as an error fallback, which changes when WE apply the theme.
            val liveColors = if (manager.wallpaperInfo != null) {
                manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let {
                    "${it.primaryColor}:${it.secondaryColor}:${it.tertiaryColor}"
                }
            } else null
            "${manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}:${manager.getWallpaperId(WallpaperManager.FLAG_LOCK)}:$liveColors"
        }.getOrDefault("unavailable")
    }

}
