package com.drdisagree.colorblendr.utils.systemui

internal enum class SystemUiProbeStatus {
    SUCCESS, FAILED, BLOCKED, UNSUPPORTED, NEEDS_REBOOT, NEEDS_SYSTEMUI_RESTART
}

internal data class SystemUiCommandResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timestamp: Long = System.currentTimeMillis()
)

internal data class SystemUiProbeReport(
    val device: String,
    val shizukuState: String,
    val commands: List<SystemUiCommandResult>,
    val timestamp: Long = System.currentTimeMillis()
)

internal object SystemUiCapabilityAnalysis {
    val candidateResources = listOf(
        "android:color/system_accent1_500",
        "android:color/system_accent2_500",
        "android:color/system_accent3_500",
        "android:color/system_neutral1_500",
        "android:color/system_neutral2_500",
        "com.android.systemui:color/qs_tile_round_background_on",
        "com.android.systemui:color/qs_tile_round_background_off",
        "com.android.systemui:color/volume_seekbar_progress_color"
    )

    fun commandPlan(userId: Int): List<String> = buildList {
        add("getprop ro.build.version.oneui")
        add("getprop ro.build.display.id")
        add("id")
        add("command -v rish")
        add("cmd overlay list")
        add("cmd overlay list --user $userId")
        add("cmd overlay dump android")
        add("cmd overlay dump com.android.systemui")
        add("cmd overlay fabricate --help")
        candidateResources.forEach { resource ->
            val target = resource.substringBefore(':')
            add("cmd overlay lookup --user $userId $target $resource")
        }
        add("settings get secure theme_customization_overlay_packages")
        add("settings get system accent_color")
        add("settings list secure")
        add("settings list system")
        add("settings list global")
    }

    fun classify(result: SystemUiCommandResult): SystemUiProbeStatus {
        val error = result.stderr.lowercase() + "\n" + result.stdout.lowercase()
        val denial = when {
            listOf("must be root", "permission denial", "permission denied", "not allowed", "securityexception")
                .any(error::contains) -> SystemUiProbeStatus.BLOCKED
            listOf("bad resource name", "resource not found", "unknown resource", "no such file", "not found")
                .any(error::contains) -> SystemUiProbeStatus.UNSUPPORTED
            else -> null
        }
        if (denial != null) return denial
        return if (result.exitCode == 0) SystemUiProbeStatus.SUCCESS else SystemUiProbeStatus.FAILED
    }

    fun parseUid(idOutput: String): Int? =
        Regex("(?:^|\\s)uid=(\\d+)").find(idOutput)?.groupValues?.get(1)?.toIntOrNull()

    fun export(report: SystemUiProbeReport, actionLog: List<String> = emptyList()): String = buildString {
        appendLine("ColorBlendr SystemUI Injection Lab diagnostic")
        appendLine("Device: " + report.device)
        appendLine("Shizuku: " + report.shizukuState)
        appendLine("Started: " + report.timestamp)
        appendLine()
        report.commands.forEach { result ->
            appendLine("[" + result.timestamp + "] " + result.command)
            appendLine("status: " + classify(result))
            appendLine("exit: " + result.exitCode)
            appendLine("stdout:")
            appendLine(result.stdout.ifBlank { "<empty>" })
            appendLine("stderr:")
            appendLine(result.stderr.ifBlank { "<empty>" })
            appendLine()
        }
        if (actionLog.isNotEmpty()) {
            appendLine("Apply / rollback attempts")
            actionLog.forEach(::appendLine)
        }
    }
}

internal object SystemUiRollbackPolicy {
    /** Roll back only while the system setting still contains our applied value. */
    fun mayRestore(currentValue: String?, appliedValue: String?): Boolean =
        appliedValue != null && currentValue == appliedValue
}
