package com.drdisagree.colorblendr.utils.samsung.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONObject
import com.drdisagree.colorblendr.utils.samsung.engine.SamsungFirmware.Companion.type
import com.drdisagree.colorblendr.utils.samsung.core.SamsungResourceMapping

/** Execute the firmware's own metadata/template mapping, including UID references,
 * light/night variants, gray templates and both layers of opacity. No color matching
 * against old resolved colors, no guessed QS resource list. */
internal class SamsungFirmwareMapping(private val context: Context) {
    fun colors(main: List<Int>, google: List<Int>?): JSONObject {
        require(main.size == 65)
        val paletteType = type("android.content.om.wallpapertheme.ThemePalette")
        val metadataType = type("android.content.om.wallpapertheme.MetaDataManager")
        val templateType = type("android.content.om.wallpapertheme.TemplateManager")
        val palette = paletteType.getConstructor().newInstance()
        val gray = main.all { (it shr 16 and 255) == (it shr 8 and 255) && (it shr 8 and 255) == (it and 255) }
        paletteType.getMethod("setPalette", List::class.java, List::class.java, Boolean::class.javaPrimitiveType)
            .invoke(palette, main, google ?: emptyList<Int>(), gray)
        val metadata = metadataType.getConstructor().newInstance()
        metadataType.getMethod("loadStaticMetadata", Context::class.java).invoke(metadata, context)
        val template = templateType.getConstructor(metadataType, paletteType).newInstance(metadata, palette)
        templateType.getMethod("loadStaticTemplate", Context::class.java).invoke(template, context)
        val app = context.packageManager.getApplicationInfo("com.android.systemui", PackageManager.GET_META_DATA)
        val utils = type("android.content.om.WallpaperThemeUtils")
        if (utils.getMethod("hasWallpaperThemeMeta", ApplicationInfo::class.java).invoke(null, app) == true) {
            metadataType.getMethod("update", ApplicationInfo::class.java).invoke(metadata, app)
        }
        if (utils.getMethod("hasWallpaperThemeTemplate", ApplicationInfo::class.java).invoke(null, app) == true) {
            templateType.getMethod("update", ApplicationInfo::class.java).invoke(template, app)
        }
        val resources = context.packageManager.getResourcesForApplication(app)
        val light = JSONObject(); val dark = JSONObject()
        val packages = metadataType.getMethod("getPackageList").invoke(metadata) as List<*>
        for (pkg in packages.filterNotNull()) {
            if (pkg.javaClass.getMethod("getPackageName").invoke(pkg) != "com.android.systemui") continue
            val uids = pkg.javaClass.getMethod("getUidList").invoke(pkg) as List<*>
            for (uid in uids.filterNotNull()) {
                if (uid.javaClass.getMethod("getType").invoke(uid) != 1) continue
                val id = uid.javaClass.getMethod("getUidValue").invoke(uid) as String
                val dest = uid.javaClass.getMethod("getDestAttribName").invoke(uid) as? String ?: continue
                val colors = templateType.getMethod("getColors", String::class.java).invoke(template, id) as? List<*> ?: continue
                require(colors.size == 2)
                val opacity = (uid.javaClass.getMethod("getOpacity").invoke(uid) as? String)?.toInt() ?: 100
                for (name in dest.split(',').map(String::trim).filter(String::isNotEmpty)) {
                    if (resources.getIdentifier(name, "color", "com.android.systemui") == 0) continue
                    val key = "com.android.systemui:color/$name"
                    light.put(key, SamsungResourceMapping.opacity(colors[0] as Int, opacity))
                    dark.put(key, SamsungResourceMapping.opacity(colors[1] as Int, opacity))
                }
            }
        }
        check(light.length() > 2 && light.has("com.android.systemui:color/qs_tile_round_background_on") &&
            light.has("com.android.systemui:color/volume_seekbar_progress_color")) { "Firmware SystemUI metadata incomplete" }
        return JSONObject().put("light", light).put("dark", dark)
    }
}
