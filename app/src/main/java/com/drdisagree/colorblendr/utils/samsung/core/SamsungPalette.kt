package com.drdisagree.colorblendr.utils.samsung.core

/** Only transports the existing generator's tones. Never generates Monet or reads resources. */
class SamsungPalette private constructor(val colors: List<Int>) {
    val serialized: String get() = colors.joinToString(prefix = "[", postfix = "]")

    // Inspect the final colors, so a monochrome style with chromatic manual overrides
    // is not incorrectly marked gray. No style-name/localization heuristics.
    val grayFlag: String get() = if (colors.all {
        val r = it ushr 16 and 255
        val g = it ushr 8 and 255
        val b = it and 255
        r == g && g == b
    }) "1" else "0"

    companion object {
        fun isEligible(manufacturer: String, shizuku: Boolean, available: Boolean, state: String?) =
            manufacturer.equals("samsung", ignoreCase = true) && shizuku && available &&
                state in listOf("0", "1")

        fun fromGenerated(palette: List<List<Int>>, names: List<List<String>>): SamsungPalette {
            val families = listOf("accent1", "accent2", "accent3", "neutral1", "neutral2")
            val shades = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
            require(palette.size >= families.size && names.size >= families.size)
            families.forEachIndexed { i, family ->
                require(palette[i].size == shades.size)
                require(names[i] == shades.map { "system_${family}_$it" }) {
                    "Unexpected ColorBlendr palette layout for $family"
                }
            }
            return SamsungPalette(palette.take(5).flatMap { it.toList() })
        }
    }
}

object SamsungShell {
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
