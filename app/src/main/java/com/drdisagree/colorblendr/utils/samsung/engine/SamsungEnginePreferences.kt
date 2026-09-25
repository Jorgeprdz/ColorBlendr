package com.drdisagree.colorblendr.utils.samsung.engine

import android.content.Context
import com.drdisagree.colorblendr.utils.samsung.core.*
import org.json.JSONObject

internal class SamsungEnginePreferences(context: Context, user: Int) : SamsungEngineStore {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("samsung_engine_$user", Context.MODE_PRIVATE)
    override fun load(): SamsungEngineBackup? = prefs.getString("journal", null)?.let {
        val json = JSONObject(it)
        SamsungEngineBackup(SamsungEngineKind.valueOf(json.getString("kind")), json.getString("original"), json.getBoolean("pending"))
    }
    override fun save(backup: SamsungEngineBackup) {
        val json = JSONObject().put("kind", backup.kind.name).put("original", backup.original).put("pending", backup.pending)
        check(prefs.edit().putString("journal", json.toString()).commit()) { "Cannot persist engine recovery journal" }
    }
    override fun clear() { check(prefs.edit().remove("journal").commit()) }
    var fabricatedPayload: String?
        get() = prefs.getString("fabricated", null)
        set(value) { check(prefs.edit().putString("fabricated", value).commit()) }
}
