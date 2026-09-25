package com.drdisagree.colorblendr.utils.colors

import android.app.Application
import android.graphics.Color
import com.drdisagree.colorblendr.ColorBlendr
import com.drdisagree.colorblendr.data.common.Utilities.customColorEnabled
import com.drdisagree.colorblendr.data.common.Utilities.getAccentSaturation
import com.drdisagree.colorblendr.data.common.Utilities.getBackgroundLightness
import com.drdisagree.colorblendr.data.common.Utilities.getBackgroundSaturation
import com.drdisagree.colorblendr.data.common.Utilities.resetAccentSaturation
import com.drdisagree.colorblendr.data.common.Utilities.resetBackgroundLightness
import com.drdisagree.colorblendr.data.common.Utilities.resetBackgroundSaturation
import com.drdisagree.colorblendr.data.common.Utilities.setAccentSaturation
import com.drdisagree.colorblendr.data.common.Utilities.setBackgroundLightness
import com.drdisagree.colorblendr.data.common.Utilities.setBackgroundSaturation
import com.drdisagree.colorblendr.data.common.Utilities.setColorSpecVersion
import com.drdisagree.colorblendr.data.common.Utilities.setCurrentCustomStyle
import com.drdisagree.colorblendr.data.common.Utilities.setCurrentMonetStyle
import com.drdisagree.colorblendr.data.common.Utilities.setCustomColorEnabled
import com.drdisagree.colorblendr.data.common.Utilities.setSecondaryColorValue
import com.drdisagree.colorblendr.data.common.Utilities.setSeedColorValue
import com.drdisagree.colorblendr.data.common.Utilities.setTertiaryColorValue
import com.drdisagree.colorblendr.data.common.Utilities.setWallpaperColorJson
import com.drdisagree.colorblendr.data.common.Utilities.setWallpaperColorList
import com.drdisagree.colorblendr.data.common.Constant.MONET_SEED_COLOR
import com.drdisagree.colorblendr.data.config.Prefs
import com.drdisagree.colorblendr.data.domain.PreviewController
import com.drdisagree.colorblendr.data.enums.MONET
import com.drdisagree.colorblendr.utils.samsung.core.SamsungPalette
import com.drdisagree.colorblendr.utils.samsung.SamsungShizukuPaletteBridge
import com.drdisagree.colorblendr.utils.samsung.engine.SamsungGooglePalette
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SamsungGeneratedPaletteTest {
    @Before fun setup() {
        ColorBlendr.initializeForPreview(RuntimeEnvironment.getApplication())
        Prefs.discardStaged()
        Prefs.clearAllPrefs()
        setCurrentMonetStyle(MONET.EXPRESSIVE)
        setSeedColorValue(Color.rgb(243, 237, 200)) // Device reproduction: #F3EDC8
        setWallpaperColorJson("[-3947972]")
    }

    private fun generated(): SamsungPalette {
        return SamsungShizukuPaletteBridge.generatePalette(isDark = false)
    }

    @Test fun accent150ChangesActualGeneratedPalette65() {
        setAccentSaturation(100); val normal = generated()
        setAccentSaturation(150); val saturated = generated()
        assertEquals(65, saturated.colors.size)
        assertNotEquals(normal.colors.take(39), saturated.colors.take(39))
        assertEquals(normal.colors.drop(39), saturated.colors.drop(39))
    }
    @Test fun identicalConfigurationProducesPreviewTonesExactly() {
        setAccentSaturation(150); setBackgroundSaturation(125); setBackgroundLightness(90)
        val preview = PreviewController.buildPreviewColors()
        assertEquals(preview.paletteLight.take(5).flatten(), generated().colors)
        assertEquals(preview.paletteDark.take(5).flatten(),
            SamsungShizukuPaletteBridge.generatePalette(isDark = true).colors)
        assertEquals(generated().colors, generated().colors)
    }
    @Test fun backgroundSaturation150ChangesNeutralsOnly() {
        setBackgroundSaturation(100); val normal = generated().colors
        setBackgroundSaturation(150); val saturated = generated().colors
        assertNotEquals(normal.drop(39), saturated.drop(39))
        assertEquals(normal.take(39), saturated.take(39))
    }
    @Test fun backgroundLightness150ChangesNeutralsOnly() {
        setBackgroundLightness(100); val normal = generated().colors
        setBackgroundLightness(150); val lighter = generated().colors
        assertNotEquals(normal.drop(39), lighter.drop(39))
        assertEquals(normal.take(39), lighter.take(39))
    }
    @Test fun stagedPreviewSurvivesCommitWithIdenticalAppliedPalette() {
        val original = generated().colors
        PreviewController.beginPreview()
        setAccentSaturation(150)
        val preview = PreviewController.buildPreviewColors().paletteLight.take(5).flatten()
        assertNotEquals(original, preview)
        Prefs.commitStaged()
        assertEquals(preview, generated().colors)
    }
    @Test fun resetReturns100AndOriginalPalette() {
        val original = generated().colors
        setAccentSaturation(150); setBackgroundSaturation(120); setBackgroundLightness(80)
        resetAccentSaturation(); resetBackgroundSaturation(); resetBackgroundLightness()
        assertEquals(100, getAccentSaturation()); assertEquals(100, getBackgroundSaturation())
        assertEquals(100, getBackgroundLightness()); assertEquals(original, generated().colors)
    }
    @Test fun manualAndWallpaperModesUseSelectedSeed() {
        setCustomColorEnabled(true); val manual = generated().colors
        setCustomColorEnabled(false)
        Prefs.preferenceEditor.remove(MONET_SEED_COLOR).commit()
        setWallpaperColorList(arrayListOf(Color.rgb(40, 160, 210)))
        val wallpaper = generated().colors
        assertNotEquals(manual, wallpaper)
        setCustomColorEnabled(true)
        setSeedColorValue(Color.rgb(40, 160, 210))
        assertEquals(wallpaper, generated().colors)
        setCustomColorEnabled(false)
        assertFalse(customColorEnabled())
    }
    @Test fun secondaryTertiaryAndCustomPrefOverridesReachPalette() {
        val original = generated().colors
        setSecondaryColorValue(Color.rgb(200, 50, 90)); setTertiaryColorValue(Color.rgb(30, 210, 70))
        setCurrentCustomStyle("test-preference-bundle")
        Prefs.putInt("system_neutral1_500", -123456)
        val changed = generated().colors
        assertNotEquals(original.subList(13, 26), changed.subList(13, 26))
        assertNotEquals(original.subList(26, 39), changed.subList(26, 39))
        assertEquals(-123456, changed[46])
    }
    @Test fun allSupportedStylesMapWithoutSpecialCases() {
        MONET.entries.forEach { style ->
            setColorSpecVersion(if (style == MONET.CMF) 2 else 0)
            setCurrentMonetStyle(style)
            assertEquals(65, generated().colors.size)
        }
    }
    @Test fun realMonochromeGenerationSetsGray() {
        setCurrentMonetStyle(MONET.MONOCHROMATIC)
        assertEquals("1", generated().grayFlag)
    }

    private fun googleBase(seed: Int, style: String): List<Int> {
        require(style == "EXPRESSIVE")
        return List(65) { index -> Color.rgb((40 + index + (seed and 7)) % 255, 100, 140) }
    }
    @Test fun googleUsesIndependentOemGeneratorWithSelectedSeedAndStyle() {
        setColorSpecVersion(0)
        var calls = 0
        val gg = SamsungGooglePalette.generate { seed, style ->
            calls++
            assertEquals(Color.rgb(243, 237, 200), seed)
            assertEquals("EXPRESSIVE", style)
            googleBase(seed, style)
        }
        assertEquals(1, calls)
        assertEquals(65, gg.size)
        assertNotEquals(generated().colors, gg)
    }
    @Test fun sameTuningPipelineChangesGoogleAccentsWithoutChangingNeutrals() {
        setColorSpecVersion(0)
        setAccentSaturation(100); val normal = SamsungGooglePalette.generate(::googleBase)
        setAccentSaturation(150); val tuned = SamsungGooglePalette.generate(::googleBase)
        assertNotEquals(normal.take(39), tuned.take(39))
        assertEquals(normal.drop(39), tuned.drop(39))
    }
    @Test fun explicitOverridesAlsoReachGoogleInsteadOfBeingLost() {
        setColorSpecVersion(0)
        setCurrentCustomStyle("test-google")
        Prefs.putInt("system_accent2_300", -123456)
        Prefs.putInt("system_neutral2_900", -765432)
        val gg = SamsungGooglePalette.generate(::googleBase)
        assertEquals(-123456, gg[18]); assertEquals(-765432, gg[63])
    }
    @Test fun secondaryAndTertiarySeedsAreGeneratedIndependentlyForGoogle() {
        setColorSpecVersion(0)
        setSecondaryColorValue(Color.RED); setTertiaryColorValue(Color.GREEN)
        val seeds = mutableListOf<Int>()
        SamsungGooglePalette.generate { seed, style -> seeds.add(seed); googleBase(seed, style) }
        assertEquals(listOf(Color.rgb(243, 237, 200), Color.RED, Color.GREEN), seeds)
    }
    @Test(expected = IllegalStateException::class) fun unsupportedGoogleSpecIsExplicitFailure() {
        setColorSpecVersion(1)
        SamsungGooglePalette.generate(::googleBase)
    }
}
