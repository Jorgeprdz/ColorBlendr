package com.drdisagree.colorblendr.utils.samsung

import com.drdisagree.colorblendr.utils.samsung.core.PaletteTuningSupport
import org.junit.Assert.*
import org.junit.Test

class PaletteTuningSupportTest {
    @Test fun slidersDisabledOutsideSamsungShizuku() {
        assertFalse(PaletteTuningSupport.enabled(false, false, true, false))
        assertFalse(PaletteTuningSupport.enabled(false, false, true, true))
        assertFalse(PaletteTuningSupport.enabled(false, true, true, false))
        assertFalse(PaletteTuningSupport.enabled(false, true, false, true))
    }
    @Test fun slidersEnabledForRoot() { assertTrue(PaletteTuningSupport.enabled(true, false, false, false)) }
    @Test fun slidersEnabledForSupportedSamsungShizuku() { assertTrue(PaletteTuningSupport.enabled(false, true, true, true)) }
    @Test fun unchangedRangeAndResetContract() {
        assertEquals(0, PaletteTuningSupport.MIN)
        assertEquals(200, PaletteTuningSupport.MAX)
        assertEquals(100, PaletteTuningSupport.DEFAULT)
        assertEquals(1.5f, 150 / PaletteTuningSupport.SCALE, 0f)
    }
}
