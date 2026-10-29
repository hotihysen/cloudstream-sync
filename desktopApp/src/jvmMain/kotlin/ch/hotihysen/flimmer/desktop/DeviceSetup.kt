package ch.hotihysen.flimmer.desktop

import com.lagradost.cloudstream3.flimmer.FlimmerStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Geräte-Einrichtung über den Gist, im gleichen Format wie die Android-App:
 * Profile, Repos, installierte Erweiterungen und die Einstellungen, die es auch unter Windows gibt.
 * Repos und Erweiterungen werden nur ergänzt, nie entfernt.
 */
object DeviceSetup {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installLock = Mutex()

    // Einstellungen, die Windows kennt (Schlüssel wie in der App)
    private const val S_LANGUAGE = "f:flimmer_streams:language"
    private const val S_SCROBBLE = "f:flimmer_trakt:scrobble"
    private const val S_BOOKMARKS = "f:flimmer_trakt:bookmarks"

    /** Beim Start: Meldungen des Syncs entgegennehmen */
    fun init() {
        Sync.onGlobal = { key, value ->
            when (key) {
                Sync.KEY_PROFILES -> Profiles.mergeRemote(value)
                Sync.KEY_REPOS -> applyRepos(value)
                Sync.KEY_PLUGINS -> scope.launch { installMissing(value) }
                Sync.KEY_SETTINGS -> applySettings(value)
            }
        }
    }

    // ---------------------------------------------------------------- Übernehmen

    private fun repoNames(): MutableMap<String, String> =
        Store.getString("repos/names")?.let { s ->
            try {
                json.parseToJsonElement(s).jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
            } catch (_: Throwable) { null }
        } ?: mutableMapOf()

    fun rememberRepoName(url: String, name: String?) {
        if (name.isNullOrBlank()) return
        val m = repoNames()
        if (m[url] == name) return
        m[url] = name
        Store.putString("repos/names", JsonObject(m.mapValues { JsonPrimitive(it.value) }).toString())
    }

    /** Gibt die Anzahl neuer Repos zurück */
    fun applyRepos(value: String): Int = try {
        val local = Extensions.repos()
        var added = 0
        json.parseToJsonElement(value).jsonArray.forEach { e ->
            val o = e.jsonObject
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            rememberRepoName(url, o["name"]?.jsonPrimitive?.contentOrNull)
            if (local.none { it == url }) {
                Extensions.addRepo(url)
                added++
            }
        }
        added
    } catch (t: Throwable) {
        System.err.println("Repos: ${t.message}")
        0
    }

    /** Fehlende Erweiterungen der anderen Geräte installieren. Gibt die Anzahl neu installierter zurück. */
    suspend fun installMissing(value: String? = Sync.global(Sync.KEY_PLUGINS)): Int = installLock.withLock {
        value ?: return@withLock 0
        val wanted = try {
            json.parseToJsonElement(value).jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                val n = o["n"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Triple(n, o["u"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null, o["r"]?.jsonPrimitive?.contentOrNull ?: "")
            }
        } catch (_: Throwable) {
            return@withLock 0
        }
        val installed = Extensions.installed.value.map { it.internalName }.toSet()
        val missing = wanted.filter { it.first !in installed }
        if (missing.isEmpty()) return@withLock 0
        val repoCache = HashMap<String, Extensions.Repo?>()
        var n = 0
        for ((name, url, repoUrl) in missing) {
            try {
                if (repoUrl.isNotBlank() && Extensions.repos().none { it == repoUrl }) Extensions.addRepo(repoUrl)
                val repo = if (repoUrl.isBlank()) null else repoCache.getOrPut(repoUrl) {
                    try { Extensions.fetchRepo(repoUrl) } catch (_: Throwable) { null }
                }
                val site = repo?.plugins?.firstOrNull { it.internalName == name }
                    ?: Extensions.SitePlugin(url = url, name = name, internalName = name)
                if (Extensions.install(repoUrl, site).ok) n++
            } catch (t: Throwable) {
                System.err.println("Erweiterung $name: ${t.message}")
            }
        }
        n
    }

    private fun applySettings(value: String) {
        try {
            val o = json.parseToJsonElement(value).jsonObject
            o[S_LANGUAGE]?.jsonObject?.get("v")?.jsonPrimitive?.contentOrNull?.let {
                if (it in listOf(FlimmerStreams.LANG_DE, FlimmerStreams.LANG_ORIGINAL, FlimmerStreams.LANG_ANY)) FlimmerStreams.language = it
            }
            o[S_SCROBBLE]?.jsonObject?.get("v")?.jsonPrimitive?.booleanOrNull?.let { Store.putBoolean("trakt/scrobble", it) }
            o[S_BOOKMARKS]?.jsonObject?.get("v")?.jsonPrimitive?.booleanOrNull?.let { Store.putBoolean("trakt/bookmarks", it) }
        } catch (t: Throwable) {
            System.err.println("Einstellungen: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- Eigenen Stand melden

    private fun reposJson(): String {
        val names = repoNames()
        return buildJsonArray {
            Extensions.repos().forEach { url ->
                add(buildJsonObject {
                    put("iconUrl", null as String?)
                    put("name", names[url] ?: url.substringAfter("://").substringBefore("/"))
                    put("url", url)
                    put("description", null as String?)
                })
            }
        }.toString()
    }

    private fun pluginsJson(): String = buildJsonArray {
        Extensions.installed.value.sortedBy { it.internalName }.forEach { p ->
            add(buildJsonObject {
                put("n", p.internalName)
                put("u", p.url)
                put("r", p.repo)
            })
        }
    }.toString()

    /** Einstellungen der anderen Geräte behalten, nur die Windows-bekannten ersetzen */
    private fun settingsJson(): String? {
        val current = Sync.global(Sync.KEY_SETTINGS)?.let {
            try { json.parseToJsonElement(it).jsonObject } catch (_: Throwable) { null }
        } ?: JsonObject(emptyMap())
        val mine = mapOf(
            S_LANGUAGE to buildJsonObject { put("t", "s"); put("v", FlimmerStreams.language) },
            S_SCROBBLE to buildJsonObject { put("t", "b"); put("v", Store.getBoolean("trakt/scrobble", true)) },
            S_BOOKMARKS to buildJsonObject { put("t", "b"); put("v", Store.getBoolean("trakt/bookmarks", true)) },
        )
        if (mine.all { (k, v) -> current[k] == v }) return null
        return JsonObject(current + mine).toString()
    }

    private fun sameRepos(a: String?, b: String): Boolean = try {
        fun urls(s: String) = json.parseToJsonElement(s).jsonArray.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }.toSet()
        a != null && urls(a) == urls(b)
    } catch (_: Throwable) { false }

    private fun samePlugins(a: String?, b: String): Boolean = try {
        fun names(s: String) = json.parseToJsonElement(s).jsonArray.mapNotNull { it.jsonObject["n"]?.jsonPrimitive?.contentOrNull }.toSet()
        a != null && names(a) == names(b)
    } catch (_: Throwable) { false }

    /**
     * Eigenen Stand in den Sync geben (nach Änderungen und regelmässig). Damit ein Gerät mit weniger
     * die anderen nicht leert, wird vorher mit dem bekannten Stand der anderen zusammengeführt.
     */
    fun snapshot() {
        if (!Sync.isActive) return
        val repos = reposJson()
        val plugins = pluginsJson()
        val first = Store.getString("setup/offered") == null
        if (first) {
            // Erstmals: nur anbieten, die Stände der anderen Geräte gewinnen
            if (Extensions.repos().isNotEmpty()) Sync.offerGlobal(Sync.KEY_REPOS, repos)
            if (Extensions.installed.value.isNotEmpty()) Sync.offerGlobal(Sync.KEY_PLUGINS, plugins)
            Profiles.offer()
            Store.putString("setup/offered", "1")
            return
        }
        if (!sameRepos(Sync.global(Sync.KEY_REPOS), repos) && Extensions.repos().isNotEmpty()) Sync.putGlobal(Sync.KEY_REPOS, repos)
        if (!samePlugins(Sync.global(Sync.KEY_PLUGINS), plugins) && Extensions.installed.value.isNotEmpty()) Sync.putGlobal(Sync.KEY_PLUGINS, plugins)
        settingsJson()?.let { Sync.putGlobal(Sync.KEY_SETTINGS, it) }
    }

    // ---------------------------------------------------------------- Alles übernehmen

    data class RestoreResult(val entries: Int, val plugins: Int, val error: String?)

    /** «Von einem anderen Gerät übernehmen»: Profile, Repos, Erweiterungen, Einstellungen, Weiterschauen, Merklisten */
    suspend fun restore(): RestoreResult {
        if (!Sync.isActive) return RestoreResult(0, 0, "Sync ist nicht eingeschaltet oder der Token fehlt")
        val n = Sync.pull()
        if (n < 0) return RestoreResult(0, 0, Sync.status.value.removePrefix("Fehler: "))
        val plugins = installMissing()
        Store.putString("setup/offered", "1")
        snapshot()
        return RestoreResult(n, plugins, null)
    }
}
