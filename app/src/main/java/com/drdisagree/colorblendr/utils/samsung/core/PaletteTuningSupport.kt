package com.drdisagree.colorblendr.utils.samsung.core

/** Shared UI capability/range contract; AOSP Shizuku still cannot apply full palettes. */
object PaletteTuningSupport {
    const val MIN = 0
    const val MAX = 200
    const val DEFAULT = 100
    const val SCALE = 100f
    fun enabled(root: Boolean, samsung: Boolean, shizuku: Boolean, supported: Boolean) =
        root || (samsung && shizuku && supported)
}
