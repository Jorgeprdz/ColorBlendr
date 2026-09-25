package com.drdisagree.colorblendr.utils.samsung

import android.content.Context
import com.drdisagree.colorblendr.utils.samsung.core.SamsungBackup
import com.drdisagree.colorblendr.utils.samsung.core.SamsungBackupStore
import com.drdisagree.colorblendr.utils.samsung.core.SamsungSnapshot
import org.json.JSONObject

/** Separate from staged/resettable app prefs; survives process death and boot. */
internal class SamsungBackupPreferences(context: Context, user: Int) : SamsungBackupStore {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("samsung_palette_backup_$user", Context.MODE_PRIVATE)

    override fun load(): SamsungBackup? {
        val json = prefs.getString("backup", null)?.let(::JSONObject) ?: return null
        fun nullable(key: String) = if (json.isNull(key)) null else json.getString(key)
        return SamsungBackup(
            SamsungSnapshot(nullable("palette"), nullable("gray"), nullable("state")),
            json.getString("applied"), json.optBoolean("pending", false)
        )
    }

    override fun save(backup: SamsungBackup) {
        val json = JSONObject().apply {
            put("palette", backup.original.palette ?: JSONObject.NULL)
            put("gray", backup.original.gray ?: JSONObject.NULL)
            put("state", backup.original.state ?: JSONObject.NULL)
            put("applied", backup.appliedPalette)
            put("pending", backup.pending)
        }
        check(prefs.edit().putString("backup", json.toString()).commit()) { "Cannot save Samsung rollback snapshot" }
    }

    override fun clear() {
        check(prefs.edit().clear().commit()) { "Cannot clear Samsung rollback snapshot" }
    }
}
