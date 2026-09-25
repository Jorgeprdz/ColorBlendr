package com.drdisagree.colorblendr.utils.samsung.core

import kotlin.math.roundToInt

object SamsungResourceMapping {
    private val families = listOf("accent1", "accent2", "accent3", "neutral1", "neutral2")
    private val shades = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
    fun framework(colors: List<Int>): Map<String, Int> {
        require(colors.size == 65)
        return buildMap {
            families.forEachIndexed { family, name -> shades.forEachIndexed { tone, shade ->
                put("android:color/system_${name}_$shade", colors[family * 13 + tone])
            } }
        }
    }
    fun probes(colors: List<Int>): Map<String, Int> = framework(colors).filterKeys {
        it.endsWith("_300") || it.endsWith("_500") || it.endsWith("_900")
    }
    // Samsung ThemeUtil.adjustAlpha multiplies existing alpha, preserving RGB.
    fun opacity(color: Int, percent: Int): Int {
        require(percent in 0..100)
        return (color and 0x00ffffff) or (((color ushr 24) * (percent / 100f)).roundToInt() shl 24)
    }
}
