package com.drdisagree.colorblendr.utils.samsung.engine

import com.drdisagree.colorblendr.service.IShizukuConnection
import com.drdisagree.colorblendr.utils.samsung.ShizukuSamsungGateway
import com.drdisagree.colorblendr.utils.samsung.core.*
import org.json.JSONArray
import org.json.JSONObject

internal class SamsungEngineRpc(private val connection: IShizukuConnection, private val user: Int, private val log: (String) -> Unit) {
    fun call(operation: String, payload: JSONObject = JSONObject()): JSONObject {
        val response = JSONObject(connection.samsungEngine(operation, payload.put("user", user).toString()))
        check(response.getString("status") == "OK") { "$operation ${response.optString("status")}: ${response.optString("error")}" }
        return response.getJSONObject("data")
    }
    fun probe(operation: String): SamsungEngineCapability {
        val response = JSONObject(connection.samsungEngine(operation, JSONObject().put("user", user).toString()))
        val data = response.optJSONObject("data")
        val status = data?.optString("capability") ?: response.getString("status")
        log("$operation capability=$status reason=${data?.optString("reason") ?: response.optString("error")}")
        return SamsungEngineCapability.valueOf(status)
    }
    fun google(seed: Int, style: String): List<Int> = call("google", JSONObject().put("seed", seed).put("style", style)).list("colors")
}

internal class ShizukuPaletteEngine(
    override val kind: SamsungEngineKind,
    private val rpc: SamsungEngineRpc,
    private val gateway: ShizukuSamsungGateway,
    private val store: SamsungEnginePreferences,
    private val isDark: Boolean,
    private val roles: Map<String, Int>,
    private val log: (String) -> Unit
) : SamsungPaletteEngine {
    private var expected = emptyMap<String, Int>()
    private var before: JSONObject? = null
    override suspend fun probe() = rpc.probe(if (kind == SamsungEngineKind.NATIVE) "nativeProbe" else "fabricatedProbe")

    override suspend fun capture(): String {
        val resources = read(probeKeys())
        val state = JSONObject().put("settings", settings()).put("resources", JSONObject(resources))
        if (kind == SamsungEngineKind.NATIVE) {
            state.put("pair", rpc.call("nativeCapture"))
            state.put("overlays", rpc.call("nativeOverlayStates"))
            check(state.getJSONObject("settings").optString(SamsungPaletteTransaction.STATE) in listOf("0", "1")) { "Unknown Samsung engine state" }
            if (state.getJSONObject("settings").optString(SamsungPaletteTransaction.STATE) == "1") {
                check(state.getJSONObject("pair").list("main").size == 65 && state.getJSONObject("pair").list("google").size == 65) {
                    "Cannot snapshot enabled Samsung engine without its canonical pair"
                }
            }
        } else {
            val presence = rpc.call("fabricatedPresent")
            if (store.fabricatedPayload == null) check(presence.keys().asSequence().all { presence.getString(it) == "absent" }) {
                "Orphan ColorBlendr overlay found without its payload; remove owned overlays with Reset before applying"
            }
            state.put("payload", store.fabricatedPayload ?: JSONObject.NULL).put("presence", presence)
        }
        before = state
        return state.toString()
    }

    override suspend fun apply(request: SamsungEngineRequest) {
        val main = JSONArray(request.main)
        val input = JSONObject().put("main", main).put("google", request.google?.let(::JSONArray) ?: JSONObject.NULL)
            .put("gray", isGray(request.main))
        val mapping = rpc.call("mapping", input)
        val systemColors = mapping.getJSONObject(if (isDark) "dark" else "light").colorMap()
        val systemProbes = systemColors.filterKeys { it in listOf(SamsungPaletteTransaction.QS, SamsungPaletteTransaction.VOLUME) } +
            systemColors.toSortedMap().entries.take(6).associate { it.toPair() }
        expected = systemProbes + SamsungResourceMapping.probes(if (kind == SamsungEngineKind.NATIVE) checkNotNull(request.google) else request.main)
        log("backend=$kind SystemUI mapped=${systemColors.size} expectedMainSha=${SamsungPaletteObservation.sha(request.main.toString())} " +
            "expectedGGSha=${SamsungPaletteObservation.sha(request.google?.toString())}")
        if (kind == SamsungEngineKind.NATIVE) {
            rpc.call("nativeApply", input)
        } else {
            val framework = SamsungResourceMapping.framework(request.main) + roles.mapKeys { "android:color/${it.key}" }
            val payload = JSONObject().put("framework", JSONObject(framework)).put("systemui", mapping)
            // Persist before IPC: process death may follow remote mutation without reply.
            store.fabricatedPayload = payload.toString()
            rpc.call("fabricatedApply", payload)
        }
    }

    override suspend fun verify(request: SamsungEngineRequest): Boolean {
        val actual = read(expected.keys)
        val stale = expected.filter { (key, value) -> actual[key] != value }
        val coherent = if (kind == SamsungEngineKind.NATIVE) {
            val pair = rpc.call("nativeCapture")
            val states = rpc.call("nativeOverlayStates")
            pair.list("main") == request.main && pair.list("google") == request.google &&
                parsePalette(gateway.get(SamsungPaletteTransaction.COLOR)) == request.main &&
                parsePalette(gateway.get(SamsungPaletteTransaction.FOR_G)) == request.google &&
                gateway.get(SamsungPaletteTransaction.STATE) == "1" &&
                gateway.get(SamsungPaletteTransaction.GRAY) == (if (isGray(request.main)) "1" else "0") &&
                SamsungPaletteTransaction.requiredOverlays.all { states.optBoolean(it, false) }
        } else {
            val presence = rpc.call("fabricatedPresent")
            sameSettings(checkNotNull(before).getJSONObject("settings")) &&
                presence.keys().asSequence().all { presence.getString(it) == "enabled" }
        }
        log("backend=$kind coherent=$coherent stale=${stale.map { (key, value) -> "$key expected=${hex(value)} actual=${actual[key]?.let(::hex)}" }}")
        return coherent && stale.isEmpty() && expected.isNotEmpty()
    }

    override suspend fun restore(snapshot: String) {
        val saved = JSONObject(snapshot)
        if (verifyRestored(snapshot)) {
            if (kind == SamsungEngineKind.FABRICATED) store.fabricatedPayload = saved.optString("payload").takeUnless { it.isEmpty() || it == "null" }
            return // Includes native silent no-op/denial without a second mutating call.
        }
        if (kind == SamsungEngineKind.NATIVE) {
            val pair = saved.getJSONObject("pair")
            val enabled = saved.getJSONObject("settings").optString(SamsungPaletteTransaction.STATE) == "1"
            val payload = if (enabled) pair else JSONObject().put("main", JSONObject.NULL).put("google", JSONObject.NULL).put("gray", false)
            rpc.call("nativeRestore", payload)
            rpc.call("nativeRestoreOverlays", JSONObject().put("states", saved.getJSONObject("overlays")))
        } else {
            val old = saved.optString("payload").takeUnless { it.isEmpty() || it == "null" }
            if (old == null) rpc.call("fabricatedRemove") else rpc.call("fabricatedApply", JSONObject(old))
            store.fabricatedPayload = old
        }
    }

    override suspend fun verifyRestored(snapshot: String): Boolean {
        val saved = JSONObject(snapshot)
        val oldResources = saved.getJSONObject("resources").colorMap()
        val disabledNative = kind == SamsungEngineKind.NATIVE &&
            saved.getJSONObject("settings").optString(SamsungPaletteTransaction.STATE) == "0"
        // OEM disable may clear its persisted color mirrors while retaining last_palette.
        // In the disabled case the authoritative state and original resolved resources
        // establish restoration, rather than requiring stale mirrors to be resurrected.
        val restoredSettings = if (disabledNative) gateway.get(SamsungPaletteTransaction.STATE) == "0"
            else sameSettings(saved.getJSONObject("settings"))
        if (read(oldResources.keys) != oldResources || !restoredSettings) return false
        return if (kind == SamsungEngineKind.NATIVE) {
            val oldPair = saved.getJSONObject("pair"); val current = rpc.call("nativeCapture")
            // Disabled engine can retain last_palette.txt. Restoring null palettes is the OEM disable API.
            val disabled = saved.getJSONObject("settings").optString(SamsungPaletteTransaction.STATE) == "0"
            (disabled || (oldPair.list("main") == current.list("main") && oldPair.list("google") == current.list("google") &&
                oldPair.getBoolean("gray") == current.getBoolean("gray"))) &&
                equivalentStates(saved.getJSONObject("overlays").booleanMap(), rpc.call("nativeOverlayStates").booleanMap())
        } else rpc.call("fabricatedPresent").toString() == saved.getJSONObject("presence").toString()
    }

    private suspend fun settings() = JSONObject().apply {
        for (key in settingKeys) put(key, gateway.get(key) ?: JSONObject.NULL)
    }
    private fun equivalentStates(saved: Map<String, Boolean>, current: Map<String, Boolean>): Boolean =
        saved.all { (id, enabled) -> current[id] == enabled || (!enabled && id !in current) } &&
            current.all { (id, enabled) -> id in saved || !enabled }
    private suspend fun sameSettings(saved: JSONObject): Boolean = settingKeys.all { key ->
        gateway.get(key) == if (saved.isNull(key)) null else saved.getString(key)
    }
    private suspend fun read(keys: Collection<String>): Map<String, Int> = keys.associateWith { key ->
        gateway.lookup(if (key.startsWith("android:")) "com.drdisagree.colorblendr" else "com.android.systemui", key)
    }
    private fun probeKeys() = SamsungResourceMapping.probes(List(65) { 0 }).keys + setOf(SamsungPaletteTransaction.QS, SamsungPaletteTransaction.VOLUME)
    private fun isGray(colors: List<Int>) = colors.all { (it shr 16 and 255) == (it shr 8 and 255) && (it shr 8 and 255) == (it and 255) }
    private fun hex(value: Int) = "#%08x".format(value)
    private fun parsePalette(value: String?): List<Int>? = runCatching { value?.let { a -> JSONArray(a).let { list -> List(list.length()) { list.getInt(it) } } } }.getOrNull()
    companion object {
        private val settingKeys = listOf(SamsungPaletteTransaction.COLOR, SamsungPaletteTransaction.FOR_G, SamsungPaletteTransaction.GRAY, SamsungPaletteTransaction.STATE)
    }
}
internal fun JSONObject.list(key: String): List<Int> = getJSONArray(key).let { a -> List(a.length()) { a.getInt(it) } }
internal fun JSONObject.colorMap(): Map<String, Int> = keys().asSequence().associateWith { getInt(it) }
internal fun JSONObject.booleanMap(): Map<String, Boolean> = keys().asSequence().associateWith { getBoolean(it) }
