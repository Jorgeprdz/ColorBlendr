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
import com.drdisagree.colorblendr.utils.shizuku.ShizukuUtil
import com.drdisagree.colorblendr.utils.wallpaper.WallpaperColorUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

object SamsungShizukuPaletteBridge {
    private const val TAG = "SamsungPaletteBridge"
    private val support = MutableStateFlow(false)
    val supported = support.asStateFlow()
    private val wallpaperGuard = SamsungWallpaperGuard()
    fun isSamsungDevice() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    fun isSupported() = isSamsungDevice() && isShizukuMode() && support.value

    internal fun generatePalette(isDark: Boolean): SamsungPalette {
        val generated = PreviewController.buildPreviewColors()
        val rows = if (isDark) generated.paletteDark else generated.paletteLight
        return SamsungPalette.fromGenerated(rows, systemPaletteNames.map { it.toList() })
    }

    fun trace(event: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "t=${SystemClock.elapsedRealtime()} $event")
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
        if (!probe(gateway)) return null
        val start = SystemClock.elapsedRealtime()
        trace("T4 bridge begin seed=${getSeedColorValue()} style=${getCurrentMonetStyle()} manual=${customColorEnabled()}")
        try {
            // Exact live-preview generator, including current spec, tuning and pref overrides.
            // Samsung has a single tonal matrix; choose the active light/dark palette.
            val palette = generatePalette(SystemUtil.isDarkMode)
            val rows = palette.colors.chunked(13)
            val before = wallpaperFingerprint()
            val sourceColors = WallpaperColorUtil.getWallpaperColorsFromSource(appContext)
            wallpaperGuard.recordVerified(before, wallpaperFingerprint(),
                sourceColors != null && sourceColors == getWallpaperColorList())
            trace("T5 G Monet=${gateway.overlayEnabled(SamsungPaletteTransaction.G_MONET)}; T6 secure JSON skipped (Samsung authoritative)")
            trace("size=${palette.colors.size} A1_300=${rows[0][5]} A1_500=${rows[0][7]} A2_300=${rows[1][5]} A2_500=${rows[1][7]} A3_300=${rows[2][5]} N1_500=${rows[3][7]} N2_500=${rows[4][7]}")
            SamsungPaletteTransaction(gateway, SamsungBackupPreferences(appContext, user), log = ::trace).apply(palette)
            if (BuildConfig.DEBUG) diagnosticLookups(gateway, palette)
            RefreshCoordinator.triggerRefresh()
            return true
        } finally {
            trace("T14 bridge end durationMs=${SystemClock.elapsedRealtime() - start}")
        }
    }

    suspend fun removeIfOwned(connection: IShizukuConnection): Boolean? {
        if (!isSamsungDevice() || !isShizukuMode()) return null
        val user = Process.myUid() / 100000
        val backups = SamsungBackupPreferences(appContext, user)
        if (backups.load() == null) return null
        val result = SamsungPaletteTransaction(
            ShizukuSamsungGateway(connection, user), backups, log = ::trace
        ).restore()
        RefreshCoordinator.triggerRefresh()
        return result
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

    private fun diagnosticLookups(gateway: ShizukuSamsungGateway, palette: SamsungPalette) {
        listOf("qs_tile_round_background_on" to palette.colors[5], "volume_seekbar_progress_color" to palette.colors[18])
            .forEach { (resource, expected) ->
                runCatching {
                    val actual = gateway.command("cmd overlay lookup --user ${Process.myUid() / 100000} --verbose com.android.systemui com.android.systemui:color/$resource")
                    trace("diagnostic $resource expected=${String.format("#%08x", expected)} actual=$actual")
                }.onFailure { trace("diagnostic lookup unavailable: $resource") }
            }
    }
}
