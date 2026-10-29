package ch.hotihysen.flimmer.desktop

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Gist-Sync im gleichen Format wie die Android-App:
 * - pro Profil: Wiedergabeposition, Weiterschauen, Gesehen-Markierungen, Merkliste (Lesezeichen)
 * - gemeinsam: Profile (PIN verschlüsselt), Repos, Erweiterungen, wichtige Einstellungen
 *
 * Jedes Gerät schreibt pro Profil die Datei «device-<id>-p<profil>.json». Gemeinsame Einträge stehen
 * in den Dateien aller Profile und werden aus allen gelesen.
 */
object Sync {
    private const val GITHUB_API = "https://api.github.com"
    private const val GIST_MARKER = "cloudstream-sync.json"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    // Gemeinsame Einträge (nicht pro Profil)
    const val KEY_PROFILES = "prof/0"
    const val KEY_REPOS = "repo/0"
    const val KEY_PLUGINS = "plug/0"
    const val KEY_SETTINGS = "set/0"
    private val GLOBAL_KEYS = setOf(KEY_PROFILES, KEY_REPOS, KEY_PLUGINS, KEY_SETTINGS)

    /** Wird aufgerufen, wenn ein gemeinsamer Eintrag von einem anderen Gerät kommt (Profile, Repos, …). */
    @Volatile var onGlobal: ((key: String, value: String) -> Unit)? = null

    @Serializable
    data class Record(
        @SerialName("k") val k: String,
        @SerialName("v") val v: String? = null,
        @SerialName("t") val t: Long = 0,
        @SerialName("d") val d: Boolean = false,
    )

    @Serializable
    data class DeviceFile(
        @SerialName("device") val device: String = "",
        @SerialName("updated") val updated: Long = 0,
        @SerialName("records") val records: Map<String, Record> = emptyMap(),
    )

    @Serializable
    data class GistFile(
        @SerialName("filename") val filename: String? = null,
        @SerialName("content") val content: String? = null,
        @SerialName("truncated") val truncated: Boolean = false,
        @SerialName("raw_url") val rawUrl: String? = null,
    )

    @Serializable
    data class Gist(
        @SerialName("id") val id: String,
        @SerialName("files") val files: Map<String, GistFile> = emptyMap(),
    )

    // Werte wie in der Android-App gespeichert
    @Serializable
    data class PosDur(@SerialName("position") val position: Long, @SerialName("duration") val duration: Long)

    @Serializable
    data class ResumeWatching(
        @SerialName("parentId") val parentId: Int,
        @SerialName("episodeId") val episodeId: Int? = null,
        @SerialName("episode") val episode: Int? = null,
        @SerialName("season") val season: Int? = null,
        @SerialName("updateTime") val updateTime: Long = 0,
        @SerialName("isFromDownload") val isFromDownload: Boolean = false,
    )

    @Serializable
    data class Header(
        @SerialName("apiName") val apiName: String,
        @SerialName("url") val url: String,
        @SerialName("type") val type: String = "TvSeries",
        @SerialName("name") val name: String,
        @SerialName("poster") val poster: String? = null,
        @SerialName("cacheTime") val cacheTime: Long = 0,
        @SerialName("id") val id: Int,
    )

    /** Lesezeichen-Daten wie DataStoreHelper.BookmarkedData in der App */
    @Serializable
    data class Bookmark(
        @SerialName("bookmarkedTime") val bookmarkedTime: Long = 0,
        @SerialName("id") val id: Int? = null,
        @SerialName("latestUpdatedTime") val latestUpdatedTime: Long = 0,
        @SerialName("name") val name: String = "",
        @SerialName("url") val url: String = "",
        @SerialName("apiName") val apiName: String = "",
        @SerialName("type") val type: String? = null,
        @SerialName("posterUrl") val posterUrl: String? = null,
        @SerialName("year") val year: Int? = null,
        @SerialName("syncData") val syncData: Map<String, String>? = null,
        @SerialName("plot") val plot: String? = null,
        @SerialName("tags") val tags: List<String>? = null,
    )

    /** Lesezeichen-Status, gleiche Nummern wie WatchType in der App */
    enum class WatchType(val id: Int, val label: String) {
        WATCHING(0, "Am Schauen"), COMPLETED(1, "Abgeschlossen"), ONHOLD(2, "Pausiert"),
        DROPPED(3, "Abgebrochen"), PLANTOWATCH(4, "Geplant");

        companion object {
            fun of(id: Int?) = entries.firstOrNull { it.id == id }
        }
    }

    data class ResumeItem(val resume: ResumeWatching, val header: Header, val pos: PosDur?)

    private val _version = MutableStateFlow(0)
    /** Ändert sich bei jeder lokalen oder synchronisierten Änderung (für die Anzeige). */
    val version: StateFlow<Int> = _version

    private val _status = MutableStateFlow(Store.getString("sync/status") ?: "")
    val status: StateFlow<String> = _status

    fun changed() {
        _version.value++
    }

    /** Nach mehreren Änderungen ohne Hochladen: anzeigen und hochladen */
    fun commit() {
        _version.value++
        schedulePush()
    }

    // ---------------------------------------------------------------- Einstellungen

    var token: String
        get() = Store.getString("sync/token") ?: ""
        set(value) {
            Store.putString("sync/token", value.trim().ifEmpty { null })
            Store.putString("sync/gistId", null)
        }

    var enabled: Boolean
        get() = Store.getBoolean("sync/enabled", false)
        set(value) = Store.putBoolean("sync/enabled", value)

    val isActive get() = enabled && token.isNotBlank()

    private val deviceId: String
        get() = Store.getString("sync/device") ?: ("pc-" + UUID.randomUUID().toString()).also { Store.putString("sync/device", it) }

    /** Aktuelles Profil (keyIndex wie in der App) */
    var profile: Int
        get() = Store.getString("profile/current")?.toIntOrNull() ?: 0
        set(value) {
            Store.putString("profile/current", value.toString())
            _version.value++
        }

    // ---------------------------------------------------------------- Lokale Daten

    private fun isGlobal(k: String) = k in GLOBAL_KEYS

    /** Profil 0 behält die bisherigen Speicherorte (Daten aus früheren Versionen) */
    private fun storeKey(k: String, p: Int = profile) = when {
        isGlobal(k) -> "sync/global/$k"
        p == 0 -> "sync/rec/$k"
        else -> "sync/rec$p/$k"
    }

    private fun pendingKey(p: Int = profile) = if (p == 0) "sync/pending" else "sync/pending$p"
    private const val PENDING_GLOBAL = "sync/pendingGlobal"
    private fun ownFileKey(p: Int = profile) = if (p == 0) "sync/ownFile" else "sync/ownFile$p"

    private fun rec(k: String): Record? = Store.getString(storeKey(k))?.let {
        try { json.decodeFromString(Record.serializer(), it) } catch (_: Throwable) { null }
    }

    private fun value(k: String): String? = rec(k)?.takeIf { !it.d }?.v

    private fun readSet(key: String): Set<String> =
        Store.getString(key)?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun put(k: String, v: String?, time: Long = System.currentTimeMillis()) {
        val r = Record(k, v, time, v == null)
        Store.putString(storeKey(k), json.encodeToString(Record.serializer(), r))
        val pk = if (isGlobal(k)) PENDING_GLOBAL else pendingKey()
        Store.putString(pk, (readSet(pk) + k).joinToString("\n"))
    }

    private fun schedulePush() {
        if (isActive) scope.launch { push() }
    }

    // ---------------------------------------------------------------- Gemeinsame Einträge

    fun global(k: String): String? = value(k)

    /** Gemeinsamen Eintrag lokal ändern (wird hochgeladen) */
    fun putGlobal(k: String, v: String) {
        if (value(k) == v) return
        put(k, v)
        schedulePush()
    }

    /** Wert eines gemeinsamen Eintrags ersetzen, ohne ihn als eigene Änderung zu senden (z.B. nach dem Zusammenführen) */
    fun replaceGlobalSilently(k: String, v: String) {
        val r = rec(k) ?: Record(k, v, 1L)
        Store.putString(storeKey(k), json.encodeToString(Record.serializer(), r.copy(v = v, d = false)))
    }

    /** Erstmalig vorhandenen Stand anbieten: verliert gegen jede echte Änderung anderer Geräte */
    fun offerGlobal(k: String, v: String) {
        if (rec(k) != null) return
        put(k, v, time = 1L)
    }

    // ---------------------------------------------------------------- Wiedergabe

    fun position(episodeId: Int): PosDur? = value("pos/$episodeId")?.let {
        try { json.decodeFromString(PosDur.serializer(), it) } catch (_: Throwable) { null }
    }

    /** Nach dem Abspielen: Position, «Weiterschauen» und Kachel speichern, danach hochladen. */
    fun savePlayback(
        episodeId: Int, parentId: Int, positionMs: Long, durationMs: Long,
        header: Header, season: Int?, episode: Int?,
    ) {
        if (durationMs <= 0) return
        put("pos/$episodeId", json.encodeToString(PosDur.serializer(), PosDur(positionMs, durationMs)))
        val finished = positionMs >= durationMs * 0.92
        val isMovie = header.type.contains("Movie")
        if (finished) setWatched(episodeId, true, push = false)
        if (finished && isMovie) {
            put("resume/$parentId", null)
        } else {
            put(
                "resume/$parentId",
                json.encodeToString(
                    ResumeWatching.serializer(),
                    ResumeWatching(parentId, episodeId, episode, season, System.currentTimeMillis(), false)
                )
            )
            put("header/$parentId", json.encodeToString(Header.serializer(), header.copy(cacheTime = System.currentTimeMillis())))
        }
        _version.value++
        schedulePush()
    }

    fun removeResume(parentId: Int) {
        put("resume/$parentId", null)
        _version.value++
        schedulePush()
    }

    fun resumeList(): List<ResumeItem> = Store.keys(storeKey("resume/")).mapNotNull { key ->
        val k = "resume/" + key.removePrefix(storeKey("resume/"))
        val r = value(k)?.let { try { json.decodeFromString(ResumeWatching.serializer(), it) } catch (_: Throwable) { null } }
            ?: return@mapNotNull null
        val h = value("header/${r.parentId}")?.let { try { json.decodeFromString(Header.serializer(), it) } catch (_: Throwable) { null } }
            ?: return@mapNotNull null
        ResumeItem(r, h, r.episodeId?.let { position(it) } ?: position(r.parentId))
    }.sortedByDescending { it.resume.updateTime }

    // ---------------------------------------------------------------- Gesehen-Markierungen

    fun isWatched(episodeId: Int): Boolean = value("ws/$episodeId")?.contains("Watched") == true

    fun setWatched(episodeId: Int, watched: Boolean, push: Boolean = true) {
        if (isWatched(episodeId) == watched) return
        put("ws/$episodeId", if (watched) "\"Watched\"" else null)
        if (push) {
            _version.value++
            schedulePush()
        }
    }

    /** Mehrere auf einmal (z.B. von Trakt übernommen) */
    fun setWatchedMany(ids: Collection<Int>): Int {
        var n = 0
        ids.forEach { if (!isWatched(it)) { put("ws/$it", "\"Watched\""); n++ } }
        if (n > 0) {
            _version.value++
            schedulePush()
        }
        return n
    }

    // ---------------------------------------------------------------- Merkliste (Lesezeichen)

    fun bookmarkStatus(id: Int): WatchType? = WatchType.of(value("bm/$id")?.trim()?.toIntOrNull())

    fun setBookmark(id: Int, status: WatchType?, data: Bookmark?) {
        if (status == null) {
            put("bm/$id", null)
            put("bmd/$id", null)
        } else {
            put("bm/$id", status.id.toString())
            data?.let { put("bmd/$id", json.encodeToString(Bookmark.serializer(), it.copy(id = id, latestUpdatedTime = System.currentTimeMillis()))) }
        }
        _version.value++
        schedulePush()
    }

    /** Alle Lesezeichen des aktuellen Profils */
    fun bookmarks(): List<Pair<WatchType, Bookmark>> {
        val prefix = storeKey("bm/")
        return Store.keys(prefix).mapNotNull { key ->
            val id = key.removePrefix(prefix).toIntOrNull() ?: return@mapNotNull null
            val st = bookmarkStatus(id) ?: return@mapNotNull null
            val data = value("bmd/$id")?.let { try { json.decodeFromString(Bookmark.serializer(), it) } catch (_: Throwable) { null } }
                ?: return@mapNotNull null
            st to data
        }.sortedByDescending { it.second.latestUpdatedTime }
    }

    // ---------------------------------------------------------------- GitHub Gist

    private fun githubHeaders() = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "Content-Type" to "application/json",
    )

    private fun ownFileName(p: Int = profile) = "device-$deviceId-p$p.json"
    private fun profileOf(name: String): Int? =
        if (name.startsWith("device-") && name.endsWith(".json")) name.substringAfterLast("-p").removeSuffix(".json").toIntOrNull() else null

    private fun httpError(code: Int) = when (code) {
        401 -> "Token ungültig oder abgelaufen"
        403 -> "Kein Zugriff (Berechtigung «gist» fehlt?) oder Limit erreicht"
        404 -> "Nicht gefunden"
        else -> "HTTP $code"
    }

    private suspend fun gistId(): String {
        Store.getString("sync/gistId")?.let { return it }
        var found: String? = null
        for (page in 1..5) {
            val res = app.get("$GITHUB_API/gists?per_page=100&page=$page", headers = githubHeaders())
            if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
            val list = json.decodeFromString(ListSerializer(Gist.serializer()), res.text)
            found = list.firstOrNull { it.files.containsKey(GIST_MARKER) }?.id
            if (found != null || list.size < 100) break
        }
        val id = found ?: run {
            val body = """{"description":"CloudStream Sync (nicht löschen)","public":false,"files":{"$GIST_MARKER":{"content":"{\"app\":\"CloudStream Sync\"}"}}}"""
            val res = app.post("$GITHUB_API/gists", headers = githubHeaders(), requestBody = body.toRequestBody(JSON_TYPE))
            if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
            json.decodeFromString(Gist.serializer(), res.text).id
        }
        Store.putString("sync/gistId", id)
        return id
    }

    /** Schlüssel für die PIN, wie in der App: aus der Gist-ID */
    private fun cryptKey() = "gist:" + (Store.getString("sync/gistId") ?: "")

    private fun setStatus(error: String?) {
        val text = if (error == null) "Zuletzt synchronisiert: " +
                java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(java.util.Date()) else "Fehler: $error"
        Store.putString("sync/status", text)
        _status.value = text
    }

    suspend fun push(): Boolean = lock.withLock {
        if (!isActive) return@withLock false
        val keys = readSet(pendingKey())
        val globals = readSet(PENDING_GLOBAL)
        if (keys.isEmpty() && globals.isEmpty()) return@withLock true
        try {
            val id = gistId()
            val own = Store.getString(ownFileKey())?.let {
                try { json.decodeFromString(DeviceFile.serializer(), it).records.toMutableMap() } catch (_: Throwable) { null }
            } ?: mutableMapOf()
            for (k in keys + globals) {
                var r = rec(k) ?: continue
                if (k == KEY_PROFILES && r.v != null) r = r.copy(v = ProfileCrypt.protect(r.v!!, cryptKey()))
                val cur = own[k]
                if (cur == null || r.t >= cur.t) own[k] = r
            }
            val content = json.encodeToString(DeviceFile.serializer(), DeviceFile(deviceId, System.currentTimeMillis(), own))
            val body = json.encodeToString(
                MapSerializer(String.serializer(), MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))),
                mapOf("files" to mapOf(ownFileName() to mapOf("content" to content)))
            )
            val res = app.patch("$GITHUB_API/gists/$id", headers = githubHeaders(), requestBody = body.toRequestBody(JSON_TYPE))
            if (res.code == 404) Store.putString("sync/gistId", null)
            if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
            Store.putString(ownFileKey(), content)
            Store.putString(pendingKey(), (readSet(pendingKey()) - keys).joinToString("\n").ifEmpty { null })
            Store.putString(PENDING_GLOBAL, (readSet(PENDING_GLOBAL) - globals).joinToString("\n").ifEmpty { null })
            setStatus(null)
            true
        } catch (t: Throwable) {
            setStatus(t.message ?: t.javaClass.simpleName)
            false
        }
    }

    /** Stand der anderen Geräte holen. Gibt die Anzahl übernommener Einträge zurück, oder -1 bei Fehler. */
    suspend fun pull(): Int {
        if (!isActive) return 0
        push()
        val p = profile
        return lock.withLock {
            try {
                val id = gistId()
                val res = app.get("$GITHUB_API/gists/$id?_=${System.currentTimeMillis()}", headers = githubHeaders())
                if (res.code == 404) Store.putString("sync/gistId", null)
                if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
                val gist = json.decodeFromString(Gist.serializer(), res.text)
                val newest = HashMap<String, Record>()
                for ((name, f) in gist.files) {
                    val fileProfile = profileOf(name) ?: continue
                    if (name == ownFileName(p)) continue
                    val sameProfile = fileProfile == p
                    val text = if (!f.truncated && f.content != null) f.content else {
                        val url = f.rawUrl ?: continue
                        val raw = app.get(url, headers = mapOf("Authorization" to "Bearer $token"))
                        if (!raw.isSuccessful) continue
                        raw.text
                    }
                    val device = try { json.decodeFromString(DeviceFile.serializer(), text) } catch (_: Throwable) { continue }
                    for (r in device.records.values) {
                        // Aus fremden Profilen nur die gemeinsamen Einträge
                        if (!sameProfile && !isGlobal(r.k)) continue
                        val cur = newest[r.k]
                        if (cur == null || r.t > cur.t) newest[r.k] = r
                    }
                }
                var applied = 0
                for (r in newest.values) {
                    val local = rec(r.k)
                    if (local != null && r.t <= local.t) continue
                    if (isGlobal(r.k)) {
                        var v = r.v ?: continue
                        if (r.k == KEY_PROFILES) v = ProfileCrypt.reveal(v, cryptKey()) ?: continue
                        Store.putString(storeKey(r.k), json.encodeToString(Record.serializer(), r.copy(v = v)))
                        try {
                            onGlobal?.invoke(r.k, v)
                        } catch (t: Throwable) {
                            System.err.println("Sync ${r.k}: ${t.message}")
                        }
                    } else {
                        Store.putString(storeKey(r.k), json.encodeToString(Record.serializer(), r))
                    }
                    applied++
                }
                setStatus(null)
                if (applied > 0) _version.value++
                applied
            } catch (t: Throwable) {
                setStatus(t.message ?: t.javaClass.simpleName)
                -1
            }
        }
    }
}
