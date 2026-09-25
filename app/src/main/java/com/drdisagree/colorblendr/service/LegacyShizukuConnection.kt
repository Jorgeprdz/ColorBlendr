package com.drdisagree.colorblendr.service

import android.os.ParcelFileDescriptor
import android.util.Log
import com.drdisagree.colorblendr.data.common.Constant.THEME_CUSTOMIZATION_OVERLAY_PACKAGES
import com.drdisagree.colorblendr.extension.ThemeOverlayPackage
import com.drdisagree.colorblendr.utils.samsung.core.SamsungShell
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class LegacyShizukuConnection : IShizukuConnection.Stub() {

    companion object {
        private const val TAG = "LegacyShizukuConnection"
    }

    private class ShellResult(val code: Int, val out: String, val err: String)

    override fun destroy() {}

    override fun exit() {}

    override fun applyFabricatedColors(jsonString: String): String? {
        val result = exec("settings put secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES ${SamsungShell.quote(jsonString)}")

        return if (result.code == 0) {
            null
        } else {
            result.err.ifBlank { "Command failed (exit ${result.code})" }
        }
    }

    override fun removeFabricatedColors(): String? {
        return try {
            applyFabricatedColors(
                ThemeOverlayPackage.getOriginalSettings(currentSettings).toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "removeFabricatedColors: ", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    override fun getCurrentSettings(): String {
        val currentSettings = exec("settings get secure $THEME_CUSTOMIZATION_OVERLAY_PACKAGES")
            .out.lineSequence().firstOrNull().orEmpty().trim()

        return if (currentSettings == "null" || currentSettings.isEmpty()) {
            JSONObject().toString()
        } else {
            currentSettings
        }
    }

    override fun run(command: String): String = exec(command).out

    override fun samsungEngine(operation: String, payload: String): String = JSONObject()
        .put("status", "UNAVAILABLE").put("error", "Samsung Binder engines require the Shizuku UserService; reconnect Shizuku").toString()

    override fun runChecked(command: String): Array<String> = exec(command).let {
        arrayOf(it.code.toString(), it.out, it.err)
    }

    private fun exec(command: String): ShellResult {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku binder is not available")
        val process = IShizukuService.Stub.asInterface(binder)
            .newProcess(arrayOf("sh", "-c", command), null, null)
            ?: throw IllegalStateException("Shizuku server refused to start the process")

        var err = ""
        val errReader = thread(name = "ShizukuStderr") {
            err = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
                .bufferedReader().use { it.readText() }
        }
        val out = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
            .bufferedReader().use { it.readText() }
        val code = process.waitFor()
        errReader.join()

        return ShellResult(code, out.trimEnd('\n'), err.trimEnd('\n'))
    }
}
