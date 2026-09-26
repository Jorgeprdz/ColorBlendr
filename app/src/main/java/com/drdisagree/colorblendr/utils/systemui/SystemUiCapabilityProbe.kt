package com.drdisagree.colorblendr.utils.systemui

import android.os.Build
import android.os.UserHandle
import com.drdisagree.colorblendr.provider.ShizukuConnectionProvider
import com.drdisagree.colorblendr.service.IShizukuConnection
import com.drdisagree.colorblendr.utils.shizuku.ShizukuUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ShizukuShellCommandRunner(private val connection: IShizukuConnection) {
    fun run(command: String): SystemUiCommandResult = try {
        val result = connection.runCheckedLimited(command, 18_000)
        if (result.size != 3) {
            SystemUiCommandResult(command, "", "Invalid Shizuku command response", 255)
        } else {
            SystemUiCommandResult(command, result[1].limitOutput(), result[2].limitOutput(), result[0].toIntOrNull() ?: 255)
        }
    } catch (error: Exception) {
        SystemUiCommandResult(command, "", error.message ?: error.javaClass.simpleName, 255)
    }

    private fun String.limitOutput(max: Int = 20_000): String =
        if (length <= max) this else take(max) + "\n<output truncated at $max characters>"
}

internal class SystemUiCapabilityProbe {
    suspend fun run(): SystemUiProbeReport = withContext(Dispatchers.IO) {
        val available = ShizukuUtil.isShizukuAvailable
        val permitted = available && ShizukuUtil.hasShizukuPermission()
        val device = listOf(
            Build.MANUFACTURER,
            Build.MODEL,
            "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        ).joinToString(" / ")
        val state = when {
            !available -> "Shizuku service unavailable"
            !permitted -> "Shizuku permission not granted to ColorBlendr"
            else -> "Shizuku active; connecting UserService"
        }
        if (!permitted) return@withContext SystemUiProbeReport(device, state, emptyList())

        val connection = runCatching { ShizukuConnectionProvider.connect() }.getOrNull()
            ?: return@withContext SystemUiProbeReport(device, "Shizuku active, but shell UserService did not connect", emptyList())

        val runner = ShizukuShellCommandRunner(connection)
        val results = SystemUiCapabilityAnalysis.commandPlan(UserHandle.myUserId()).map(runner::run)
        val uid = results.firstOrNull { it.command == "id" }?.let {
            SystemUiCapabilityAnalysis.parseUid(it.stdout)
        }
        val completedState = when {
            uid == 2000 -> "Shizuku UserService connected as shell UID 2000"
            uid != null -> "Shizuku UserService connected as UID " + uid + " (expected shell UID 2000)"
            else -> "Shizuku UserService connected; could not parse shell UID"
        }
        SystemUiProbeReport(device, completedState, results)
    }
}
