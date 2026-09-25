package com.drdisagree.colorblendr.utils.samsung
import com.drdisagree.colorblendr.utils.samsung.core.SamsungResourceMapping
import org.junit.Assert.*
import org.junit.Test
class SamsungResourceMappingTest {
    @Test fun allFiveFamiliesAndThirteenShadesKeepExactOverrides() {
        val colors = List(65) { -1000 + it }.toMutableList(); colors[18] = 0xff123456.toInt()
        val result = SamsungResourceMapping.framework(colors)
        assertEquals(65, result.size)
        assertEquals(-995, result["android:color/system_accent1_300"])
        assertEquals(0xff123456.toInt(), result["android:color/system_accent2_300"])
        assertEquals(-967, result["android:color/system_accent3_500"])
        assertEquals(-950, result["android:color/system_neutral1_900"])
        assertEquals(-936, result["android:color/system_neutral2_1000"])
    }
    @Test fun probesCoverEveryFamilyBeyondAccent300() {
        val probes = SamsungResourceMapping.probes(List(65) { -1 })
        assertEquals(15, probes.size)
        assertTrue(probes.containsKey("android:color/system_neutral2_900"))
    }
    @Test fun templateAndMetadataOpacityComposeWithoutChangingRgb() {
        assertEquals(0x40123456, SamsungResourceMapping.opacity(SamsungResourceMapping.opacity(0xff123456.toInt(), 50), 50))
    }
}
