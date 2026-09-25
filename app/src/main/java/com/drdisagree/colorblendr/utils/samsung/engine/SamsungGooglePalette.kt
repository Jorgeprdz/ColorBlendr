package com.drdisagree.colorblendr.utils.samsung.engine

import android.graphics.Color
import com.drdisagree.colorblendr.data.common.Utilities.getColorSpecVersion
import com.drdisagree.colorblendr.data.common.Utilities.getCurrentMonetStyle
import com.drdisagree.colorblendr.data.common.Utilities.getSeedColorValue
import com.drdisagree.colorblendr.data.common.Utilities.getWallpaperColorList
import com.drdisagree.colorblendr.data.common.Utilities.secondaryColorEnabled
import com.drdisagree.colorblendr.data.common.Utilities.tertiaryColorEnabled
import com.drdisagree.colorblendr.data.common.Utilities.getSecondaryColorValue
import com.drdisagree.colorblendr.data.common.Utilities.getTertiaryColorValue
import com.drdisagree.colorblendr.data.common.Utilities.getAccentSaturation
import com.drdisagree.colorblendr.data.common.Utilities.getBackgroundSaturation
import com.drdisagree.colorblendr.data.common.Utilities.getBackgroundLightness
import com.drdisagree.colorblendr.data.common.Utilities.pitchBlackThemeEnabled
import com.drdisagree.colorblendr.data.common.Utilities.accurateShadesEnabled
import com.drdisagree.colorblendr.data.enums.MONET
import com.drdisagree.colorblendr.utils.colors.ColorModifiers
import java.util.concurrent.atomic.AtomicInteger

/** OEM GG is a separately generated Monet table, not a conversion/copy of SS.
 * All user tuning and explicit per-tone overrides use the existing ColorBlendr modifier. */
internal object SamsungGooglePalette {
    fun generate(base: (Int, String) -> List<Int>): List<Int> {
        check(getColorSpecVersion() == 0) { "Samsung GG generator has no equivalent for the selected ColorSpec" }
        val style = getCurrentMonetStyle()
        val seed = getSeedColorValue(getWallpaperColorList().firstOrNull() ?: Color.BLUE)
        fun rows(seedColor: Int): MutableList<MutableList<Int>> {
            val colors = base(seedColor, style.name)
            require(colors.size == 65)
            return colors.chunked(13).map { it.toMutableList() }.toMutableList()
        }
        val palette = rows(seed)
        if (secondaryColorEnabled()) palette[1] = rows(getSecondaryColorValue())[0]
        if (tertiaryColorEnabled()) palette[2] = rows(getTertiaryColorValue())[0]
        return tune(palette, style)
    }
    private fun tune(rows: List<List<Int>>, style: MONET): List<Int> = rows.flatMapIndexed { family, row ->
        listOf(row[0]) + ColorModifiers.modifyColors(ArrayList(row.drop(1)), AtomicInteger(family), style,
            getAccentSaturation(), getBackgroundSaturation(), getBackgroundLightness(), pitchBlackThemeEnabled(),
            accurateShadesEnabled(), modifyPitchBlack = false, overrideColors = true)
    }
}
