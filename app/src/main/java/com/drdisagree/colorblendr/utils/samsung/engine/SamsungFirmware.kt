package com.drdisagree.colorblendr.utils.samsung.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException

/** Lives only in the Shizuku UserService. Uses the boot framework's own Proxy. */
internal class SamsungFirmware(private val serviceContext: Context?) {
    private val capabilities = mutableMapOf<String, JSONObject>()
    private val fabricated by lazy { SamsungFirmwareOverlays(this) }
    internal val context: Context by lazy {
        val source = serviceContext ?: run {
            val thread = type("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
                ?: error("No UserService Context")
            thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        }
        source.createPackageContext("android", 0)
    }
    private val iface by lazy { type("android.content.om.IOverlayManager") }
    internal val manager: Any by lazy {
        val binder = type("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "overlay") as IBinder
        type("android.content.om.IOverlayManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: error("Overlay service unavailable")
    }
    internal fun method(name: String, vararg types: Class<*>) = iface.getMethod(name, *types)
    internal fun call(name: String, types: Array<Class<*>>, vararg args: Any?): Any? =
        method(name, *types).invoke(manager, *args)

    @Synchronized fun execute(operation: String, payload: String): String {
        val caller = Binder.getCallingUid()
        // This resets the incoming application's Binder identity to THIS process's
        // shell identity. It does not impersonate system_server or elevate to root.
        val token = Binder.clearCallingIdentity()
        try {
            check(Process.myUid() == 2000) { "Samsung engine requires Shizuku shell UID 2000" }
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/om/", "Landroid/os/ServiceManager;", "Landroid/app/ActivityThread;", "Lcom/samsung/android/wallpaper/")
            Log.d("SamsungPaletteBridge", "UserService op=$operation incomingUid=$caller processUid=${Process.myUid()} callingUid=${Binder.getCallingUid()}")
            val input = JSONObject(payload)
            val data = when (operation) {
                "nativeProbe" -> nativeProbe()
                "fabricatedProbe" -> capabilities.getOrPut("fabricated") { fabricated.probe(input.getInt("user")) }
                "google" -> google(input.getInt("seed"), input.getString("style"))
                "mapping" -> SamsungFirmwareMapping(context).colors(input.ints("main"), input.optInts("google"))
                "nativeCapture" -> nativeCapture()
                "nativeApply" -> { nativeApply(input.optInts("main"), input.optInts("google"), input.getBoolean("gray")); JSONObject() }
                "nativeRestore" -> { nativeApply(input.optInts("main"), input.optInts("google"), input.getBoolean("gray"), restoring = true); JSONObject() }
                "fabricatedApply" -> { fabricated.apply(input); JSONObject() }
                "fabricatedRemove" -> { fabricated.remove(input.getInt("user")); JSONObject() }
                "fabricatedPresent" -> fabricated.present(input.getInt("user"))
                "nativeOverlayStates" -> fabricated.nativeStates(input.getInt("user"))
                "nativeRestoreOverlays" -> { fabricated.restoreNativeStates(input); JSONObject() }
                else -> error("Unknown Samsung operation: $operation")
            }
            return JSONObject().put("status", "OK").put("data", data).toString()
        } catch (error: Exception) {
            val cause = unwrap(error)
            val denied = cause is SecurityException || cause.message.orEmpty().let {
                it.contains("permission", true) || it.contains("Non-root shell", true) || it.contains("denied", true)
            }
            val state = if (denied) "DENIED" else "UNAVAILABLE"
            Log.w("SamsungPaletteBridge", "op=$operation $state: ${cause.message}")
            if (operation == "nativeApply") capabilities["native"] = capability(state, cause.toString())
            return JSONObject().put("status", state).put("error", cause.toString()).toString()
        } finally { Binder.restoreCallingIdentity(token) }
    }

    private fun nativeProbe(): JSONObject = capabilities.getOrPut("native") {
        method("applyWallpaperColor", List::class.java, List::class.java, Boolean::class.javaPrimitiveType!!)
        method("getLastPalette", List::class.java, List::class.java)
        val matches = context.packageManager.checkSignatures(1000, Process.myUid()) == PackageManager.SIGNATURE_MATCH
        if (!matches) capability("DENIED", "checkSignatures(1000,2000) != SIGNATURE_MATCH")
        else {
            nativeCapture() // Actually exercise the read API, not just method discovery.
            capability("SUPPORTED", "Firmware Proxy + platform signature + getLastPalette; Apply still requires resource verification")
        }
    }
    private fun nativeApply(main: List<Int>?, google: List<Int>?, gray: Boolean, restoring: Boolean = false) {
        if (!restoring) check(nativeProbe().getString("capability") == "SUPPORTED") { "Native palette API denied or unavailable" }
        checkRestorableTheme()
        require((main == null && google == null) || (main?.size == 65 && google?.size == 65))
        call("applyWallpaperColor", arrayOf(List::class.java, List::class.java, Boolean::class.javaPrimitiveType!!), main, google, gray)
    }
    private fun nativeCapture(): JSONObject {
        checkRestorableTheme()
        val ss = arrayListOf<Int>(); val gg = arrayListOf<Int>()
        val gray = call("getLastPalette", arrayOf(List::class.java, List::class.java), ss, gg) as Boolean
        require((ss.isEmpty() && gg.isEmpty()) || (ss.size == 65 && gg.size == 65)) { "Invalid canonical Samsung pair" }
        return JSONObject().put("main", JSONArray(ss)).put("google", JSONArray(gg)).put("gray", gray)
    }
    private fun checkRestorableTheme() {
        // OEM apply deletes Theme Park registrations and its template file. A palette
        // snapshot cannot restore those objects, so never enter that destructive path.
        val all = call("getAllOverlays", arrayOf(Int::class.javaPrimitiveType!!), 0) as Map<*, *>
        val themePark = all.values.flatMap { it as List<*> }.filterNotNull().any {
            it.javaClass.getMethod("getOverlayIdentifier").invoke(it).toString().contains("ThemePark", true)
        }
        val state = android.provider.Settings.System.getInt(context.contentResolver, "themepark_singletheme_state", 0)
        check(!themePark && state == 0 && !java.io.File("/data/overlays/themepark/state_applied.txt").exists()) {
            "Theme Park state cannot be restored by the native palette API"
        }
    }
    private fun google(seed: Int, style: String): JSONObject {
        // Same path as ColorPaletteCreator.generateColorPalette(true): independently
        // create Samsung Monet ColorScheme(seed,false,style), then ColorPalette table.
        val styleType = type("com.samsung.android.wallpaper.colortheme.monet.Style")
        val styleValue = styleType.getMethod("valueOf", String::class.java).invoke(null, style)
        val schemeType = type("com.samsung.android.wallpaper.colortheme.monet.ColorScheme")
        val scheme = schemeType.getConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, styleType)
            .newInstance(seed, false, styleValue)
        val tableType = type("com.samsung.android.wallpaper.colortheme.ColorPalette")
        val palette = tableType.getConstructor(schemeType).newInstance(scheme)
        val table = tableType.getMethod("getTable").invoke(palette) as Array<*>
        require(table.size == 5 && table.all { it is IntArray && it.size == 13 })
        val flat = table.flatMap { (it as IntArray).toList() }
        return JSONObject().put("colors", JSONArray(flat)).put("generator", "Samsung.ColorPalette(ColorScheme(seed,false,$style))")
    }

    companion object {
        internal fun type(name: String): Class<*> = Class.forName(name, true, ClassLoader.getSystemClassLoader())
        internal fun capability(value: String, reason: String) = JSONObject().put("capability", value).put("reason", reason)
        internal fun unwrap(error: Throwable): Throwable = if (error is InvocationTargetException && error.targetException != null) unwrap(error.targetException) else error
        internal fun JSONObject.ints(key: String): List<Int> = getJSONArray(key).let { a -> List(a.length()) { a.getInt(it) } }
        internal fun JSONObject.optInts(key: String): List<Int>? = if (isNull(key) || !has(key)) null else ints(key)
    }
}
