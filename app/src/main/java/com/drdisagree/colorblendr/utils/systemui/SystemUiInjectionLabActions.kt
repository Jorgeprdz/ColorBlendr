package com.drdisagree.colorblendr.utils.systemui

import android.content.Context
import android.os.Build
import android.os.Process
import com.drdisagree.colorblendr.data.common.Constant.THEME_CUSTOMIZATION_OVERLAY_PACKAGES
import com.drdisagree.colorblendr.data.domain.PreviewController
import com.drdisagree.colorblendr.extension.ThemeOverlayPackage
import com.drdisagree.colorblendr.provider.ShizukuConnectionProvider
import com.drdisagree.colorblendr.service.IShizukuConnection
import com.drdisagree.colorblendr.utils.app.MiscUtil
import com.drdisagree.colorblendr.utils.samsung.SamsungShizukuPaletteBridge
import com.drdisagree.colorblendr.utils.app.SystemUtil
import com.drdisagree.colorblendr.utils.shizuku.ShizukuUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class SystemUiLabOperationResult(
    val status: SystemUiProbeStatus,
    val strategy: String,
    val detail: String,
    val target: String = "SystemUI / Android dynamic colors",
    val color: String = "current ColorBlendr palette",
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = if (status == SystemUiProbeStatus.SUCCESS) 0 else 1,
    val timestamp: Long = System.currentTimeMillis(),
    val command: String = ""
) {
    fun toLogLine(): String =
        "[$timestamp] strategy=$strategy command=$command target=$target color=$color result=$status exit=$exitCode stdout=$stdout stderr=$stderr detail=$detail"
}

internal class SystemUiInjectionLabActions(context: Context) {
    private val appContext = context.applicationContext
    private val journal = appContext.getSharedPreferences("systemui_injection_lab_transaction", Context.MODE_PRIVATE)

    suspend fun apply(): SystemUiLabOperationResult = withContext(Dispatchers.IO) {
        if (!ShizukuUtil.isShizukuAvailable || !ShizukuUtil.hasShizukuPermission()) {
            return@withContext result(SystemUiProbeStatus.BLOCKED, "preflight", "Shizuku is unavailable or permission is not granted")
        }
        val connection = runCatching { ShizukuConnectionProvider.connect() }.getOrNull()
            ?: return@withContext result(SystemUiProbeStatus.BLOCKED, "preflight", "Shizuku shell UserService is unavailable")

        if (SamsungShizukuPaletteBridge.isSamsungDevice()) return@withContext applySamsung(connection)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return@withContext result(SystemUiProbeStatus.UNSUPPORTED, "theme_customization_overlay_packages", "Android 12 or newer is required")
        }
        applyThemeCustomization(connection)
    }

    suspend fun revert(): SystemUiLabOperationResult = withContext(Dispatchers.IO) {
        if (!ShizukuUtil.isShizukuAvailable || !ShizukuUtil.hasShizukuPermission()) {
            return@withContext result(SystemUiProbeStatus.BLOCKED, "rollback", "Shizuku is unavailable or permission is not granted")
        }
        val connection = runCatching { ShizukuConnectionProvider.connect() }.getOrNull()
            ?: return@withContext result(SystemUiProbeStatus.BLOCKED, "rollback", "Shizuku shell UserService is unavailable")

        if (SamsungShizukuPaletteBridge.isSamsungDevice()) {
            return@withContext try {
                val reverted = SamsungShizukuPaletteBridge.removeFromSystemUiLab(connection)
                if (reverted == true) result(SystemUiProbeStatus.SUCCESS, "Samsung engine rollback", "Engine restored its saved system snapshot")
                else result(SystemUiProbeStatus.UNSUPPORTED, "Samsung engine rollback", "No ColorBlendr-owned Samsung change was found")
            } catch (error: Exception) {
                result(classifyException(error), "Samsung engine rollback", error.message ?: error.javaClass.simpleName)
            }
        }
        revertThemeCustomization(connection)
    }

    private suspend fun applySamsung(connection: IShizukuConnection): SystemUiLabOperationResult = try {
        val applied = SamsungShizukuPaletteBridge.applyFromSystemUiLab(connection)
        when (applied) {
            true -> result(
                SystemUiProbeStatus.SUCCESS,
                "Samsung native/fabricated engine",
                "The Samsung engine verified the applied resources and its timed regression checks",
                command = "IShizukuConnection.samsungEngine(apply)"
            )
            false -> result(SystemUiProbeStatus.FAILED, "Samsung native/fabricated engine", "The engine reported an unverified apply")
            null -> result(SystemUiProbeStatus.UNSUPPORTED, "Samsung native/fabricated engine", "No supported Samsung Shizuku backend is available")
        }
    } catch (error: Exception) {
        result(classifyException(error), "Samsung native/fabricated engine", error.message ?: error.javaClass.simpleName)
    }

    private suspend fun applyThemeCustomization(connection: IShizukuConnection): SystemUiLabOperationResult {
        if (journal.getBoolean(KEY_PENDING, false)) {
            return result(SystemUiProbeStatus.BLOCKED, "theme_customization_overlay_packages", "A prior snapshot is pending; revert it before another apply")
        }
        val userId = Process.myUid() / 100_000
        val beforeSettingResult = command(connection, "settings --user $userId get secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES")
        if (beforeSettingResult.exitCode != 0) return fromCommand("theme_customization_overlay_packages", beforeSettingResult)
        val original = beforeSettingResult.stdout.trim().takeUnless { it == "null" }
        val beforeAccent = lookupAccent(connection, userId)
        if (beforeAccent == null) {
            return result(SystemUiProbeStatus.UNSUPPORTED, "theme_customization_overlay_packages", "The target system_accent1_500 resource could not be read")
        }
        val preview = PreviewController.buildPreviewColors()
        val expectedPalette = if (SystemUtil.isDarkMode) preview.paletteDark else preview.paletteLight
        val expectedAccent = expectedPalette[0][7].toUInt().toString(16).padStart(8, '0').uppercase()
        val proposed = MiscUtil.mergeJsonStrings(
            original ?: JSONObject().toString(),
            ThemeOverlayPackage.themeCustomizationOverlayPackages.toString()
        )
        val journaled = journal.edit()
            .putBoolean(KEY_PENDING, true)
            .putBoolean(KEY_ORIGINAL_WAS_NULL, original == null)
            .putString(KEY_ORIGINAL, original.orEmpty())
            .putString(KEY_BEFORE_ACCENT, beforeAccent)
            .putString(KEY_APPLIED, proposed)
            .commit()
        if (!journaled) {
            return result(SystemUiProbeStatus.FAILED, "rollback snapshot", "Could not persist the pre-apply snapshot; no system setting was changed")
        }
        val write = command(
            connection,
            "settings --user $userId put secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES " + shellQuote(proposed)
        )
        if (write.exitCode != 0) {
            val restored = restoreGenericSnapshot(connection)
            return fromCommand(
                "theme_customization_overlay_packages",
                write,
                if (restored) SystemUiProbeStatus.BLOCKED else SystemUiProbeStatus.FAILED,
                if (restored) "System setting rejected the change; original snapshot restored" else "Write failed and rollback remains pending"
            )
        }
        val readback = command(connection, "settings --user $userId get secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES")
        val accepted = readback.exitCode == 0 && readback.stdout.trim() == proposed
        if (accepted) {
            repeat(16) {
                delay(250)
                val accent = lookupAccent(connection, userId)
                if (accent == expectedAccent) {
                    return result(
                        SystemUiProbeStatus.SUCCESS,
                        "ThemeCustomizationStrategy",
                        "Secure setting persisted and framework system_accent1_500 matches ColorBlendr's generated accent1 500; Samsung/OEM QS resources still require separate verification",
                        target = "android:color/system_accent1_500",
                        color = expectedAccent,
                        stdout = readback.stdout,
                        exitCode = 0,
                        command = write.command
                    )
                }
            }
        }
        val restored = restoreGenericSnapshot(connection)
        return result(
            if (restored) SystemUiProbeStatus.BLOCKED else SystemUiProbeStatus.FAILED,
            "ThemeCustomizationStrategy",
            if (restored) "Setting was not observably applied to the framework accent; previous setting restored" else "Apply verification failed and rollback remains pending",
            target = "android:color/system_accent1_500",
            color = "expected $expectedAccent; observed $beforeAccent",
            stdout = readback.stdout,
            stderr = readback.stderr,
            exitCode = readback.exitCode,
            command = write.command
        )
    }

    private suspend fun revertThemeCustomization(connection: IShizukuConnection): SystemUiLabOperationResult {
        if (!journal.getBoolean(KEY_PENDING, false)) {
            return result(SystemUiProbeStatus.UNSUPPORTED, "rollback", "No ColorBlendr snapshot is pending")
        }
        val userId = Process.myUid() / 100_000
        val current = command(connection, "settings --user $userId get secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES")
        if (current.exitCode != 0) return fromCommand("rollback guard", current)
        if (!SystemUiRollbackPolicy.mayRestore(current.stdout.trim(), journal.getString(KEY_APPLIED, null))) {
            return result(SystemUiProbeStatus.BLOCKED, "rollback guard", "The setting changed after ColorBlendr applied it; refusing to overwrite a newer value")
        }
        return if (restoreGenericSnapshot(connection)) {
            result(SystemUiProbeStatus.SUCCESS, "ThemeCustomizationStrategy rollback", "Original secure setting restored and read back")
        } else {
            result(SystemUiProbeStatus.FAILED, "ThemeCustomizationStrategy rollback", "Original setting could not be verified; snapshot retained for retry")
        }
    }

    private suspend fun restoreGenericSnapshot(connection: IShizukuConnection): Boolean {
        if (!journal.getBoolean(KEY_PENDING, false)) return true
        val userId = Process.myUid() / 100_000
        val wasNull = journal.getBoolean(KEY_ORIGINAL_WAS_NULL, true)
        val original = journal.getString(KEY_ORIGINAL, "").orEmpty()
        val expectedOriginal = if (wasNull) "null" else original
        val applied = journal.getString(KEY_APPLIED, null)
        val current = command(connection, "settings --user $userId get secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES")
        if (current.exitCode != 0) return false
        if (current.stdout.trim() == expectedOriginal) {
            journal.edit().clear().commit()
            return true
        }
        if (!SystemUiRollbackPolicy.mayRestore(current.stdout.trim(), applied)) return false
        val write = if (wasNull) {
            command(connection, "settings --user $userId delete secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES")
        } else {
            command(connection, "settings --user $userId put secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES " + shellQuote(original))
        }
        if (write.exitCode != 0) return false
        val readback = command(connection, "settings --user $userId get secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES")
        if (readback.exitCode != 0 || readback.stdout.trim() != expectedOriginal) return false
        journal.edit().clear().commit()
        return true
    }

    private suspend fun lookupAccent(connection: IShizukuConnection, userId: Int): String? {
        val lookup = command(
            connection,
            "cmd overlay lookup --user $userId com.drdisagree.colorblendr android:color/system_accent1_500"
        )
        if (lookup.exitCode != 0) return null
        return Regex("#([0-9a-fA-F]{8})").find(lookup.stdout)?.groupValues?.get(1)?.uppercase()
    }

    private fun command(connection: IShizukuConnection, command: String): SystemUiCommandResult {
        val response = runCatching { connection.runChecked(command) }.getOrElse {
            return SystemUiCommandResult(command, "", it.message ?: it.javaClass.simpleName, 255)
        }
        if (response.size != 3) return SystemUiCommandResult(command, "", "Invalid command response", 255)
        return SystemUiCommandResult(command, response[1], response[2], response[0].toIntOrNull() ?: 255)
    }

    private fun fromCommand(
        strategy: String,
        command: SystemUiCommandResult,
        status: SystemUiProbeStatus = SystemUiCapabilityAnalysis.classify(command),
        detail: String = ""
    ) = result(status, strategy, detail, command = command.command, stdout = command.stdout, stderr = command.stderr, exitCode = command.exitCode)

    private fun result(
        status: SystemUiProbeStatus,
        strategy: String,
        detail: String,
        target: String = "SystemUI / Android dynamic colors",
        color: String = "current ColorBlendr palette",
        command: String = "",
        stdout: String = "",
        stderr: String = "",
        exitCode: Int = if (status == SystemUiProbeStatus.SUCCESS) 0 else 1
    ) = SystemUiLabOperationResult(
        status = status,
        strategy = strategy,
        detail = detail,
        target = target,
        color = color,
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        command = command
    )

    private fun classifyException(error: Exception): SystemUiProbeStatus {
        val message = error.message.orEmpty().lowercase()
        return if (listOf("denied", "permission", "non-root shell", "must be root", "blocked").any(message::contains)) {
            SystemUiProbeStatus.BLOCKED
        } else {
            SystemUiProbeStatus.FAILED
        }
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val KEY_PENDING = "pending"
        private const val KEY_ORIGINAL_WAS_NULL = "original_was_null"
        private const val KEY_ORIGINAL = "original"
        private const val KEY_BEFORE_ACCENT = "before_accent"
        private const val KEY_APPLIED = "applied"
    }
}
