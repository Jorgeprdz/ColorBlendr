package com.drdisagree.colorblendr.utils.samsung.engine

import android.os.Process
import org.json.JSONObject
import com.drdisagree.colorblendr.utils.samsung.engine.SamsungFirmware.Companion.type
import com.drdisagree.colorblendr.utils.samsung.engine.SamsungFirmware.Companion.capability
import com.drdisagree.colorblendr.utils.samsung.engine.SamsungFirmware.Companion.unwrap

/** All registration goes through OMS as shell. A denied firmware stays denied. */
internal class SamsungFirmwareOverlays(private val firmware: SamsungFirmware) {
    private val overlayType = type("android.content.om.FabricatedOverlay")
    private val identifierType = type("android.content.om.OverlayIdentifier")
    private val transactionType = type("android.content.om.OverlayManagerTransaction")
    private val builderType = type("android.content.om.OverlayManagerTransaction\$Builder")
    private fun identifier(name: String) = identifierType.getConstructor(String::class.java, String::class.java)
        .newInstance(OWNER, name)
    private fun builder() = builderType.getConstructor().newInstance()
    private fun enabled(builder: Any, id: Any, value: Boolean, user: Int) {
        builderType.getMethod("setEnabled", identifierType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(builder, id, value, user)
    }
    private fun unregister(builder: Any, id: Any) {
        builderType.getMethod("unregisterFabricatedOverlay", identifierType).invoke(builder, id)
    }
    private fun commit(builder: Any) {
        firmware.call("commit", arrayOf(transactionType), builderType.getMethod("build").invoke(builder))
    }
    private fun info(id: Any, user: Int): Any? = firmware.call("getOverlayInfoByIdentifier",
        arrayOf(identifierType, Int::class.javaPrimitiveType!!), id, user)
    private fun register(builder: Any, name: String, target: String, light: JSONObject, night: JSONObject?) {
        val factory = type("android.content.om.FabricatedOverlay\$Builder")
        val instance = factory.getConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance(OWNER, name, target)
        val set = factory.getMethod("setResourceValue", String::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java)
        for (key in light.keys()) set.invoke(instance, key, 28, light.getInt(key), null)
        if (night != null) for (key in night.keys()) set.invoke(instance, key, 28, night.getInt(key), "night")
        val overlay = factory.getMethod("build").invoke(instance)
        builderType.getMethod("registerFabricatedOverlay", overlayType).invoke(builder, overlay)
    }
    fun probe(user: Int): JSONObject {
        val names = listOf("colorblendr_probe_android_${Process.myPid()}", "colorblendr_probe_systemui_${Process.myPid()}")
        // The entire register/enable/disable/unregister sequence is a single OMS
        // transaction; its final state contains no active or registered probe.
        try {
            val b = builder()
            for ((index, target) in listOf("android", "com.android.systemui").withIndex()) {
                val resource = if (index == 0) "system_accent1_300" else "qs_tile_round_background_on"
                val res = firmware.context.packageManager.getResourcesForApplication(target)
                val id = res.getIdentifier(resource, "color", target)
                check(id != 0) { "Missing probe resource $target:$resource" }
                val color = res.getColor(id, null)
                register(b, names[index], target, JSONObject().put("$target:color/$resource", color), null)
                enabled(b, identifier(names[index]), true, user) // Also exercises priority (OMS setEnabled).
                enabled(b, identifier(names[index]), false, user)
                unregister(b, identifier(names[index]))
            }
            commit(b)
            check(names.all { info(identifier(it), user) == null }) { "Probe overlay survived commit" }
            return capability("SUPPORTED", "Firmware accepted registration, enable/priority and cleanup for both targets")
        } catch (error: Exception) {
            val cause = unwrap(error)
            return capability(if (cause is SecurityException || cause.message.orEmpty().let {
                    it.contains("Non-root shell", true) || it.contains("permission", true) || it.contains("denied", true)
                }) "DENIED" else "UNAVAILABLE", cause.toString())
        } finally {
            val remaining = names.filter { info(identifier(it), user) != null }
            if (remaining.isNotEmpty()) {
                val b = builder(); remaining.forEach { unregister(b, identifier(it)) }; commit(b)
                check(remaining.all { info(identifier(it), user) == null }) { "Probe cleanup failed" }
            }
        }
    }
    fun apply(input: JSONObject) {
        val user = input.getInt("user")
        val framework = input.getJSONObject("framework")
        val b = builder()
        register(b, FRAMEWORK, "android", framework, null)
        val mapping = input.getJSONObject("systemui")
        register(b, SYSTEM_UI, "com.android.systemui", mapping.getJSONObject("light"), mapping.getJSONObject("dark"))
        listOf(FRAMEWORK, SYSTEM_UI).forEach { enabled(b, identifier(it), true, user) }
        commit(b) // Re-registering our fixed identifier replaces stale resource content.
    }
    fun remove(user: Int) {
        val existing = listOf(FRAMEWORK, SYSTEM_UI).filter { info(identifier(it), user) != null }
        if (existing.isEmpty()) return
        val b = builder(); existing.forEach { unregister(b, identifier(it)) }; commit(b)
        check(listOf(FRAMEWORK, SYSTEM_UI).all { info(identifier(it), user) == null }) { "Owned overlay cleanup failed" }
    }
    fun present(user: Int): JSONObject = JSONObject().apply {
        listOf(FRAMEWORK, SYSTEM_UI).forEach { name ->
            val overlay = info(identifier(name), user)
            put(name, if (overlay == null) "absent" else if (overlay.javaClass.getMethod("isEnabled").invoke(overlay) == true) "enabled" else "disabled")
        }
    }
    fun nativeStates(user: Int): JSONObject {
        val all = firmware.call("getAllOverlays", arrayOf(Int::class.javaPrimitiveType!!), user) as Map<*, *>
        return JSONObject().apply {
            all.values.flatMap { it as List<*> }.filterNotNull().forEach { overlay ->
                val id = overlay.javaClass.getMethod("getOverlayIdentifier").invoke(overlay).toString()
                if (id.startsWith("android:SemWT_")) put(id, overlay.javaClass.getMethod("isEnabled").invoke(overlay) == true)
            }
        }
    }
    fun restoreNativeStates(input: JSONObject) {
        val b = builder(); val states = input.getJSONObject("states"); val user = input.getInt("user")
        val current = nativeStates(user)
        var changed = false
        for (name in states.keys()) {
            require(name.startsWith("android:SemWT_"))
            if (current.has(name) && current.getBoolean(name) == states.getBoolean(name)) continue
            val id = identifierType.getConstructor(String::class.java, String::class.java).newInstance("android", name.substringAfter(':'))
            if (info(id, user) != null) { enabled(b, id, states.getBoolean(name), user); changed = true }
        }
        if (changed) commit(b)
    }
    companion object {
        // Shell owns these registrations; never impersonate the android package.
        const val OWNER = "com.android.shell"
        const val FRAMEWORK = "colorblendr_samsung_framework"
        const val SYSTEM_UI = "colorblendr_samsung_systemui"
    }
}
