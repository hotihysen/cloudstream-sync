package ch.hotihysen.flimmer.desktop

import android.content.Context
import com.googlecode.d2j.dex.Dex2jar
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

/**
 * Erweiterungen (.cs3) unter Windows.
 *
 * Eine .cs3-Datei ist ein Zip mit Android-Code (classes.dex) und manifest.json. Flimmer wandelt den
 * Android-Code einmalig in Java-Code um (dex2jar), legt ihn im Cache ab und lädt die Erweiterung
 * dann wie die Android-App.
 */
object Extensions {
    const val DEFAULT_REPO = "https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    data class RepoManifest(
        @SerialName("name") val name: String? = null,
        @SerialName("description") val description: String? = null,
        @SerialName("manifestVersion") val manifestVersion: Int? = null,
        @SerialName("pluginLists") val pluginLists: List<String> = emptyList(),
    )

    @Serializable
    data class SitePlugin(
        @SerialName("url") val url: String = "",
        @SerialName("status") val status: Int = 1,
        @SerialName("version") val version: Int = 0,
        @SerialName("name") val name: String = "",
        @SerialName("internalName") val internalName: String = "",
        @SerialName("description") val description: String? = null,
        @SerialName("tvTypes") val tvTypes: List<String>? = null,
        @SerialName("language") val language: String? = null,
        @SerialName("iconUrl") val iconUrl: String? = null,
        @SerialName("fileSize") val fileSize: Long? = null,
    )

    @Serializable
    data class Installed(
        @SerialName("internalName") val internalName: String,
        @SerialName("name") val name: String,
        @SerialName("url") val url: String,
        @SerialName("version") val version: Int,
        @SerialName("repo") val repo: String,
        @SerialName("language") val language: String? = null,
        @SerialName("tvTypes") val tvTypes: List<String>? = null,
    )

    @Serializable
    data class Manifest(
        @SerialName("name") val name: String? = null,
        @SerialName("pluginClassName") val pluginClassName: String? = null,
        @SerialName("requiresResources") val requiresResources: Boolean = false,
        @SerialName("version") val version: Int? = null,
    )

    data class Repo(val url: String, val name: String, val plugins: List<SitePlugin>)

    /** Zustand einer geladenen Erweiterung für die Anzeige in den Einstellungen. */
    data class LoadState(val internalName: String, val ok: Boolean, val message: String, val providers: List<String>)

    private val _states = MutableStateFlow<Map<String, LoadState>>(emptyMap())
    val states: StateFlow<Map<String, LoadState>> = _states

    private val _installed = MutableStateFlow<List<Installed>>(emptyList())
    val installed: StateFlow<List<Installed>> = _installed

    private val loadedProviders = HashMap<String, List<MainAPI>>()
    private val desktopContext = Context()

    // ---------------------------------------------------------------- Repos

    fun repos(): List<String> {
        val raw = Store.getString("repos") ?: return listOf(DEFAULT_REPO).also { saveRepos(it) }
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun saveRepos(list: List<String>) = Store.putString("repos", list.distinct().joinToString("\n"))

    /** «cloudstreamrepo://…» und Adressen ohne https:// in eine normale Adresse umwandeln. */
    fun normalizeRepoUrl(input: String): String {
        var s = input.trim()
        s = s.replace(Regex("^cloudstreamrepo://", RegexOption.IGNORE_CASE), "https://")
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    fun addRepo(url: String) = saveRepos(repos() + normalizeRepoUrl(url))
    fun removeRepo(url: String) = saveRepos(repos().filter { it != url })

    suspend fun fetchRepo(url: String): Repo = withContext(Dispatchers.IO) {
        val text = app.get(url).text
        val manifest = json.decodeFromString(RepoManifest.serializer(), text)
        val plugins = coroutineScope {
            manifest.pluginLists.map { list ->
                async {
                    try {
                        json.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(SitePlugin.serializer()),
                            app.get(list).text
                        )
                    } catch (t: Throwable) {
                        System.err.println("Pluginliste $list: ${t.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        Repo(url, manifest.name ?: url.substringAfter("://").substringBefore("/"), plugins.filter { it.status != 0 })
    }

    // ---------------------------------------------------------------- Installieren

    private fun readInstalled(): List<Installed> = Store.keys("plugins/installed/").mapNotNull { key ->
        try {
            json.decodeFromString(Installed.serializer(), Store.getString(key) ?: return@mapNotNull null)
        } catch (_: Throwable) {
            null
        }
    }.sortedBy { it.name.lowercase() }

    private fun cs3File(internalName: String) = File(Store.pluginDir, "${safe(internalName)}.cs3")
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    suspend fun install(repoUrl: String, p: SitePlugin): LoadState = withContext(Dispatchers.IO) {
        val bytes = app.get(p.url).body.bytes()
        if (bytes.size < 100) throw IllegalStateException("Datei der Erweiterung ist leer")
        unload(p.internalName)
        cs3File(p.internalName).writeBytes(bytes)
        val info = Installed(p.internalName, p.name, p.url, p.version, repoUrl, p.language, p.tvTypes)
        Store.putString("plugins/installed/${p.internalName}", json.encodeToString(Installed.serializer(), info))
        _installed.value = readInstalled()
        load(info)
    }

    fun uninstall(internalName: String) {
        unload(internalName)
        cs3File(internalName).delete()
        Store.putString("plugins/installed/$internalName", null)
        _installed.value = readInstalled()
        _states.value = _states.value - internalName
    }

    /** Beim Start: alle installierten Erweiterungen laden. */
    suspend fun loadAll() = withContext(Dispatchers.IO) {
        val list = readInstalled()
        _installed.value = list
        list.forEach { load(it) }
    }

    /** Neuere Versionen aus den Repos holen. Gibt die Namen der aktualisierten Erweiterungen zurück. */
    suspend fun updateAll(): List<String> = withContext(Dispatchers.IO) {
        val installed = readInstalled()
        if (installed.isEmpty()) return@withContext emptyList()
        val updated = mutableListOf<String>()
        for (repoUrl in installed.map { it.repo }.distinct()) {
            val repo = try { fetchRepo(repoUrl) } catch (_: Throwable) { continue }
            for (inst in installed.filter { it.repo == repoUrl }) {
                val site = repo.plugins.firstOrNull { it.internalName == inst.internalName } ?: continue
                if (site.version > inst.version) {
                    try {
                        install(repoUrl, site)
                        updated += site.name
                    } catch (t: Throwable) {
                        System.err.println("Update ${site.name}: ${t.message}")
                    }
                }
            }
        }
        updated
    }

    // ---------------------------------------------------------------- Laden

    private fun unload(internalName: String) {
        val apis = loadedProviders.remove(internalName) ?: return
        for (api in apis) {
            APIHolder.allProviders.removeIf { it === api }
            APIHolder.removePluginMapping(api)
        }
    }

    private fun setState(state: LoadState) {
        _states.value = _states.value + (state.internalName to state)
    }

    @Synchronized
    fun load(info: Installed): LoadState {
        val cs3 = cs3File(info.internalName)
        val state = try {
            if (!cs3.exists()) throw IllegalStateException("Datei fehlt, bitte neu installieren")
            val (manifest, jar) = ZipFile(cs3).use { zip ->
                val m = zip.getEntry("manifest.json")?.let { e ->
                    json.decodeFromString(Manifest.serializer(), zip.getInputStream(e).readBytes().decodeToString())
                } ?: throw IllegalStateException("manifest.json fehlt")
                val dexEntry = zip.getEntry("classes.dex") ?: throw IllegalStateException("classes.dex fehlt")
                val out = File(Store.cacheDir, "jar3/${safe(info.internalName)}-v${info.version}-${cs3.length()}")
                if (!File(out, ".fertig").exists()) {
                    out.deleteRecursively()
                    out.mkdirs()
                    val dex = zip.getInputStream(dexEntry).readBytes()
                    Dex2jar.from(dex)
                        .skipDebug(false)
                        .topoLogicalSort()
                        .dontSanitizeNames(true) // Kotlin-Namen wie «constructor-impl» unverändert lassen
                        .to(out.toPath())
                    File(out, ".fertig").writeText("ok")
                }
                m to out
            }
            val className = manifest.pluginClassName ?: throw IllegalStateException("pluginClassName fehlt")
            val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), Extensions::class.java.classLoader)
            val before = APIHolder.allProviders.toList().toSet()
            val extractorsBefore = extractorApis.size
            val plugin = loader.loadClass(className).getDeclaredConstructor().newInstance() as Plugin
            plugin.filename = cs3.absolutePath
            plugin.load(desktopContext)
            val added = APIHolder.allProviders.toList().filter { it !in before }
            loadedProviders[info.internalName] = added
            val extractors = extractorApis.size - extractorsBefore
            if (added.isEmpty() && extractors == 0) LoadState(info.internalName, false, "geladen, aber keine Quelle registriert", emptyList())
            else LoadState(info.internalName, true, "bereit", added.map { it.name })
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            System.err.println("Erweiterung ${info.name}: ${root.javaClass.name}: ${root.message}")
            LoadState(info.internalName, false, "läuft unter Windows nicht (${root.javaClass.simpleName}: ${root.message?.take(80) ?: ""})", emptyList())
        }
        setState(state)
        return state
    }
}
