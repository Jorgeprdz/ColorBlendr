package com.drdisagree.colorblendr.utils.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiCapabilityAnalysisTest {
    @Test fun shellFabricateDenialIsBlockedRatherThanFailedOrSuccessful() {
        assertEquals(
            SystemUiProbeStatus.BLOCKED,
            SystemUiCapabilityAnalysis.classify(
                SystemUiCommandResult("cmd overlay fabricate --help", "", "Error: must be root", 1)
            )
        )
        assertEquals(
            SystemUiProbeStatus.BLOCKED,
            SystemUiCapabilityAnalysis.classify(
                SystemUiCommandResult("cmd overlay fabricate --help", "Error: must be root", "", 0)
            )
        )
    }

    @Test fun missingCandidateResourceIsUnsupported() {
        assertEquals(
            SystemUiProbeStatus.UNSUPPORTED,
            SystemUiCapabilityAnalysis.classify(
                SystemUiCommandResult("lookup", "", "Error: resource not found", 1)
            )
        )
    }

    @Test fun onlySuccessfulCommandIsSuccess() {
        assertEquals(
            SystemUiProbeStatus.SUCCESS,
            SystemUiCapabilityAnalysis.classify(SystemUiCommandResult("lookup", "#ff112233", "", 0))
        )
        assertEquals(
            SystemUiProbeStatus.FAILED,
            SystemUiCapabilityAnalysis.classify(SystemUiCommandResult("lookup", "#ff112233", "denied", 1))
        )
    }

    @Test fun parsesUidOnlyFromIdOutput() {
        assertEquals(2000, SystemUiCapabilityAnalysis.parseUid("uid=2000(shell) gid=2000(shell)"))
        assertEquals(null, SystemUiCapabilityAnalysis.parseUid("id: not found"))
    }

    @Test fun commandPlanUsesValidLookupSyntaxAndOnlyReadCommands() {
        val commands = SystemUiCapabilityAnalysis.commandPlan(0)
        assertTrue(commands.contains("cmd overlay lookup --user 0 android android:color/system_accent1_500"))
        assertTrue(commands.contains("cmd overlay lookup --user 0 com.android.systemui com.android.systemui:color/qs_tile_round_background_on"))
        assertFalse(commands.any { it.startsWith("settings put") || it.startsWith("cmd overlay enable") })
    }

    @Test fun exportedLogContainsCommandStreamsExitCodesAndResult() {
        val report = SystemUiProbeReport(
            device = "Samsung SM-S931B / Android 16",
            shizukuState = "Available and permitted",
            commands = listOf(SystemUiCommandResult("id", "uid=2000(shell)", "", 0))
        )
        val text = SystemUiCapabilityAnalysis.export(report)
        assertTrue(text.contains("uid=2000(shell)"))
        assertTrue(text.contains("exit: 0"))
        assertTrue(text.contains("status: SUCCESS"))
    }

    @Test fun rollbackRefusesToOverwriteASettingChangedAfterApply() {
        assertTrue(SystemUiRollbackPolicy.mayRestore("{\"palette\":\"ours\"}", "{\"palette\":\"ours\"}"))
        assertFalse(SystemUiRollbackPolicy.mayRestore("{\"palette\":\"newer user choice\"}", "{\"palette\":\"ours\"}"))
        assertFalse(SystemUiRollbackPolicy.mayRestore(null, "{\"palette\":\"ours\"}"))
    }
}
