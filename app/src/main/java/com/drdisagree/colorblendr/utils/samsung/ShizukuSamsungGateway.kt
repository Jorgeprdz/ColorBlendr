package com.drdisagree.colorblendr.utils.samsung

import com.drdisagree.colorblendr.service.IShizukuConnection
import com.drdisagree.colorblendr.utils.samsung.core.SamsungGateway
import com.drdisagree.colorblendr.utils.samsung.core.SamsungShell.quote

internal class ShizukuSamsungGateway(
    private val connection: IShizukuConnection,
    private val user: Int
) : SamsungGateway {
    fun command(command: String): String {
        val result = connection.runChecked(command)
        check(result.size == 3 && result[0] == "0") {
            "Shizuku command failed: ${result.getOrNull(2) ?: "invalid response"}"
        }
        return result[1].trim()
    }

    override suspend fun get(key: String): String? =
        command("settings --user $user get system ${quote(key)}").takeUnless { it == "null" }

    override suspend fun put(key: String, value: String?) {
        if (value == null) command("settings --user $user delete system ${quote(key)}")
        else command("settings --user $user put system ${quote(key)} ${quote(value)}")
        check(get(key) == value) { "SettingsProvider did not persist $key" }
    }

    override suspend fun overlayEnabled(name: String): Boolean? {
        val line = command("cmd overlay list --user $user").lineSequence()
            .map { it.trim() }.firstOrNull { it.substringAfter(' ', "") == name }
            ?: return null
        return line.startsWith("[x]")
    }

    override suspend fun enableOverlay(name: String) {
        command("cmd overlay enable --user $user ${quote(name)}")
    }
}
