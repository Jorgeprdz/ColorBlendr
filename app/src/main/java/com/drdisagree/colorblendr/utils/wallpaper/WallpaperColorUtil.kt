package com.drdisagree.colorblendr.utils.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import com.drdisagree.colorblendr.ColorBlendr.Companion.appContext
import com.drdisagree.colorblendr.data.common.Constant
import com.drdisagree.colorblendr.data.common.Utilities.customColorEnabled
import com.drdisagree.colorblendr.data.common.Utilities.isShizukuMode
import com.drdisagree.colorblendr.data.common.Utilities.getWallpaperColorJson
import com.drdisagree.colorblendr.data.common.Utilities.setSeedColorValue
import com.drdisagree.colorblendr.data.common.Utilities.setWallpaperColorJson
import com.drdisagree.colorblendr.service.BroadcastListener
import com.drdisagree.colorblendr.utils.app.AppUtil
import com.drdisagree.colorblendr.utils.colors.ColorUtil
import com.drdisagree.colorblendr.utils.samsung.SamsungShizukuPaletteBridge
import com.drdisagree.materialcolorutilities.quantize.QuantizerCelebi
import com.drdisagree.materialcolorutilities.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sqrt

object WallpaperColorUtil {

    private val TAG: String = WallpaperColorUtil::class.java.simpleName
    private const val SMALL_SIDE = 128
    private const val MAX_BITMAP_SIZE = 112
    private const val MAX_WALLPAPER_EXTRACTION_AREA = MAX_BITMAP_SIZE * MAX_BITMAP_SIZE

    /** Provenance-sensitive Samsung path: never returns a framework Monet fallback. */
    suspend fun getWallpaperColorsFromSource(context: Context): ArrayList<Int>? =
        withContext(Dispatchers.IO) {
            try {
                val manager = WallpaperManager.getInstance(context)
                if (manager.wallpaperInfo != null) {
                    manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let { colors ->
                        arrayListOf(colors.primaryColor.toArgb()).apply {
                            colors.secondaryColor?.let { add(it.toArgb()) }
                            colors.tertiaryColor?.let { add(it.toArgb()) }
                        }
                    }
                } else {
                    manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use { file ->
                        // Decode bounds first so a full-resolution wallpaper is never allocated.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, bounds)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            while (bounds.outWidth / inSampleSize > SMALL_SIDE * 2 ||
                                bounds.outHeight / inSampleSize > SMALL_SIDE * 2) inSampleSize *= 2
                        }
                        BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options)
                            ?.extractColors(allowFrameworkFallback = false)?.takeIf { it.isNotEmpty() }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                Log.d(TAG, "Wallpaper source unavailable", e)
                null
            }
        }

    suspend fun updateWallpaperColorList(context: Context) {
        if (!AppUtil.permissionsGranted(context)) return

        val wallpaperColors = if (SamsungShizukuPaletteBridge.isSamsungDevice() && isShizukuMode()) {
            // Activity recreation after SemWT must not replace the seed with framework fallback.
            getWallpaperColorsFromSource(context) ?: return
        } else getWallpaperColors(context)
        val currentWallpaperColors = Constant.GSON.toJson(wallpaperColors)

        if (getWallpaperColorJson() != currentWallpaperColors) {
            BroadcastListener.requiresUpdate = true
            setWallpaperColorJson(currentWallpaperColors)

            if (!customColorEnabled()) {
                setSeedColorValue(wallpaperColors[0])
            }
        }
    }

    suspend fun getWallpaperColors(context: Context): ArrayList<Int> {
        if (!AppUtil.permissionsGranted(context)) {
            return ColorUtil.monetAccentColors
        }

        val mergedColors = mutableSetOf<Int>()

        val wallpaperManager = WallpaperManager.getInstance(context)
        var isLiveWallpaper = false

        wallpaperManager.wallpaperInfo?.let {
            isLiveWallpaper = true
            wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?.let { wallpaperColors ->
                    mergedColors.add(wallpaperColors.primaryColor.toArgb())
                    wallpaperColors.secondaryColor?.let { mergedColors.add(it.toArgb()) }
                    wallpaperColors.tertiaryColor?.let { mergedColors.add(it.toArgb()) }
                }
        }

        return try {
            if (!isLiveWallpaper || mergedColors.isEmpty()) {
                withContext(Dispatchers.IO) {
                    WallpaperLoader.loadWallpaperAsync(context, WallpaperManager.FLAG_SYSTEM)
                }?.let { bitmap ->
                    mergedColors.addAll(bitmap.extractColors())
                }
            }

            if (mergedColors.isEmpty()) ColorUtil.monetAccentColors else ArrayList(mergedColors)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting wallpaper color", e)
            ColorUtil.monetAccentColors
        }
    }

    private fun createMiniBitmap(bitmap: Bitmap): Bitmap {
        val smallestSide = min(bitmap.width.toDouble(), bitmap.height.toDouble()).toInt()
        val scale = min(1.0, (SMALL_SIDE.toFloat() / smallestSide).toDouble()).toFloat()
        return createMiniBitmap(
            bitmap,
            (scale * bitmap.width).toInt(),
            (scale * bitmap.height).toInt()
        )
    }

    private fun createMiniBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        return bitmap.scale(width, height, false)
    }

    private fun calculateOptimalSize(width: Int, height: Int): Size {
        val requestedArea = width * height
        var scale = 1.0

        if (requestedArea > MAX_WALLPAPER_EXTRACTION_AREA) {
            scale = sqrt(MAX_WALLPAPER_EXTRACTION_AREA / requestedArea.toDouble())
        }

        var newWidth = (width * scale).toInt()
        var newHeight = (height * scale).toInt()

        if (newWidth == 0) {
            newWidth = 1
        }
        if (newHeight == 0) {
            newHeight = 1
        }

        return Size(newWidth, newHeight)
    }

    private fun Bitmap?.extractColors(allowFrameworkFallback: Boolean = true): ArrayList<Int> {
        fun fallback() = if (allowFrameworkFallback) ColorUtil.monetAccentColors else arrayListOf<Int>()
        var bitmapTemp = this ?: return fallback()

        val bitmapArea = bitmapTemp.width * bitmapTemp.height
        if (bitmapArea > MAX_WALLPAPER_EXTRACTION_AREA) {
            val optimalSize = calculateOptimalSize(
                bitmapTemp.width,
                bitmapTemp.height
            )
            bitmapTemp = bitmapTemp.scale(optimalSize.width, optimalSize.height, false)
        }

        val width = bitmapTemp.width
        val height = bitmapTemp.height
        val pixels = IntArray(width * height)

        bitmapTemp.getPixels(pixels, 0, width, 0, 0, width, height)

        val wallpaperColors = ArrayList(
            Score.score(QuantizerCelebi.quantize(pixels, 128), 12)
        )

        return if (wallpaperColors.isEmpty()) fallback() else wallpaperColors
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) {
            return bitmap
        }

        val intrinsicWidth = intrinsicWidth
        val intrinsicHeight = intrinsicHeight

        return if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            val colors = ColorUtil.monetAccentColors
            val colorCount = colors.size

            createBitmap(colorCount, 1).apply {
                val canvas = Canvas(this)
                val rectWidth = canvas.width / colorCount

                colors.forEachIndexed { colorIndex, color ->
                    canvas.withClip(
                        left = colorIndex * rectWidth,
                        top = 0,
                        right = (colorIndex + 1) * rectWidth,
                        bottom = canvas.height
                    ) {
                        drawColor(color)
                    }
                }
            }
        } else {
            createMiniBitmap(
                createBitmap(intrinsicWidth, intrinsicHeight).apply {
                    val canvas = Canvas(this)
                    setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                    draw(canvas)
                }
            )
        }
    }

    private object WallpaperLoader {

        private val TAG: String = WallpaperLoader::class.java.simpleName

        suspend fun loadWallpaperAsync(
            context: Context,
            which: Int
        ): Bitmap? = withContext(Dispatchers.IO) {
            loadWallpaper(context, which)
        }

        private fun loadWallpaper(context: Context, which: Int): Bitmap? {
            return try {
                val wallpaperManager = WallpaperManager.getInstance(context)

                // Live wallpaper
                wallpaperManager.wallpaperInfo?.let { info ->
                    return info.loadThumbnail(appContext.packageManager).toBitmap()
                }

                // Static wallpaper
                wallpaperManager.getWallpaperFile(which)?.use { file ->
                    return createMiniBitmap(BitmapFactory.decodeFileDescriptor(file.fileDescriptor))
                }

                // Built-in wallpaper (fallback)
                wallpaperManager.getBuiltInDrawable(which)?.let { drawable ->
                    return drawable.toBitmap()
                }

                Log.e(TAG, "Error getting wallpaper bitmap: all sources returned null")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting wallpaper bitmap", e)
                null
            }
        }
    }
}
