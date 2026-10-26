package ch.hotihysen.flimmer.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.schedule

/**
 * Einstellungen und Daten von Flimmer für Windows.
 *
 * Portabel: Liegt neben Flimmer.exe ein beschreibbarer Ordner, kommen die Daten in «Flimmer-Daten»
 * daneben. Sonst (z.B. in «Programme») unter %APPDATA%\Flimmer.
 */
object Store {
    val dataDir: File by lazy { resolveDataDir().also { it.mkdirs() } }
    val pluginDir: File get() = File(dataDir, "erweiterungen").also { it.mkdirs() }
    val cacheDir: File get() = File(dataDir, "cache").also { it.mkdirs() }

    private val file: File get() = File(dataDir, "einstellungen.json")
    private val values = ConcurrentHashMap<String, String>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    @Volatile private var loaded = false
    private val timer = Timer("flimmer-store", true)
    @Volatile private var pending = false

    /** Ordner der Anwendung (enthält Flimmer.exe), wenn bekannt. */
    val appDir: File? by lazy {
        System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile }
            ?: try {
                val jar = File(Store::class.java.protectionDomain.codeSource.location.toURI())
                // …\Flimmer\app\flimmer.jar → …\Flimmer
                if (jar.isFile) jar.parentFile?.parentFile else null
            } catch (_: Throwable) {
                null
            }
    }

    private fun resolveDataDir(): File {
        System.getenv("FLIMMER_DATA")?.let { return File(it) }
        appDir?.let { dir ->
            if (File(dir, "Flimmer.exe").exists()) {
                val portable = File(dir, "Flimmer-Daten")
                if (portable.exists() || canWrite(dir)) return portable
            }
        }
        val appData = System.getenv("APPDATA")?.let { File(it, "Flimmer") }
        return appData ?: File(System.getProperty("user.home"), ".flimmer")
    }

    private fun canWrite(dir: File): Boolean = try {
        val probe = File(dir, ".flimmer-test")
        probe.writeText("x")
        probe.delete()
        true
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (file.exists()) {
                val obj = json.parseToJsonElement(file.readText()) as? JsonObject ?: return
                for ((k, v) in obj) v.jsonPrimitive.contentOrNull?.let { values[k] = it }
            }
        } catch (t: Throwable) {
            System.err.println("Einstellungen nicht lesbar: ${t.message}")
        }
    }

    fun getString(key: String): String? {
        ensureLoaded()
        return values[key]
    }

    fun putString(key: String, value: String?) {
        ensureLoaded()
        if (value == null) values.remove(key) else values[key] = value
        scheduleSave()
    }

    fun getBoolean(key: String, default: Boolean) = getString(key)?.toBooleanStrictOrNull() ?: default
    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    fun getLong(key: String, default: Long) = getString(key)?.toLongOrNull() ?: default
    fun putLong(key: String, value: Long) = putString(key, value.toString())

    fun keys(prefix: String): List<String> {
        ensureLoaded()
        return values.keys.filter { it.startsWith(prefix) }
    }

    private fun scheduleSave() {
        if (pending) return
        pending = true
        timer.schedule(800) { save() }
    }

    @Synchronized
    fun save() {
        pending = false
        try {
            val obj = JsonObject(values.toSortedMap().mapValues { JsonPrimitive(it.value) })
            val tmp = File(dataDir, "einstellungen.json.tmp")
            tmp.writeText(json.encodeToString(JsonObject.serializer(), obj))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (t: Throwable) {
            System.err.println("Einstellungen nicht gespeichert: ${t.message}")
        }
    }
}
