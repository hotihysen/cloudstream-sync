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
 * Wiedergabestand (Weiterschauen, Positionen) und Gist-Sync, im gleichen Format wie die Android-App.
 * Dadurch erscheint, was am PC geschaut wurde, auch auf Handy und Fernseher, und umgekehrt.
 */
object Sync {
    private const val GITHUB_API = "https://api.github.com"
    private const val GIST_MARKER = "cloudstream-sync.json"
    private const val PROFILE = "0"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

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

    data class ResumeItem(val resume: ResumeWatching, val header: Header, val pos: PosDur?)

    private val _version = MutableStateFlow(0)
    /** Ändert sich bei jeder lokalen oder synchronisierten Änderung (für die Startseite). */
    val version: StateFlow<Int> = _version

    private val _status = MutableStateFlow(Store.getString("sync/status") ?: "")
    val status: StateFlow<String> = _status

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

    // ---------------------------------------------------------------- Lokale Daten

    private fun rec(k: String): Record? = Store.getString("sync/rec/$k")?.let {
        try { json.decodeFromString(Record.serializer(), it) } catch (_: Throwable) { null }
    }

    private fun value(k: String): String? = rec(k)?.takeIf { !it.d }?.v

    private fun put(k: String, v: String?) {
        val r = Record(k, v, System.currentTimeMillis(), v == null)
        Store.putString("sync/rec/$k", json.encodeToString(Record.serializer(), r))
        val pending = pending().toMutableSet().apply { add(k) }
        Store.putString("sync/pending", pending.joinToString("\n"))
    }

    private fun pending(): Set<String> = Store.getString("sync/pending")?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

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
        if (isActive) scope.launch { push() }
    }

    fun removeResume(parentId: Int) {
        put("resume/$parentId", null)
        _version.value++
        if (isActive) scope.launch { push() }
    }

    fun resumeList(): List<ResumeItem> = Store.keys("sync/rec/resume/").mapNotNull { key ->
        val k = key.removePrefix("sync/rec/")
        val r = value(k)?.let { try { json.decodeFromString(ResumeWatching.serializer(), it) } catch (_: Throwable) { null } }
            ?: return@mapNotNull null
        val h = value("header/${r.parentId}")?.let { try { json.decodeFromString(Header.serializer(), it) } catch (_: Throwable) { null } }
            ?: return@mapNotNull null
        ResumeItem(r, h, r.episodeId?.let { position(it) } ?: position(r.parentId))
    }.sortedByDescending { it.resume.updateTime }

    // ---------------------------------------------------------------- GitHub Gist

    private fun githubHeaders() = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "Content-Type" to "application/json",
    )

    private fun ownFileName() = "device-$deviceId-p$PROFILE.json"
    private fun isProfileFile(name: String) = name.startsWith("device-") && name.endsWith("-p$PROFILE.json")

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

    private fun setStatus(error: String?) {
        val text = if (error == null) "Zuletzt synchronisiert: " +
                java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(java.util.Date()) else "Fehler: $error"
        Store.putString("sync/status", text)
        _status.value = text
    }

    suspend fun push(): Boolean = lock.withLock {
        if (!isActive) return@withLock false
        val keys = pending()
        if (keys.isEmpty()) return@withLock true
        try {
            val id = gistId()
            val own = Store.getString("sync/ownFile")?.let {
                try { json.decodeFromString(DeviceFile.serializer(), it).records.toMutableMap() } catch (_: Throwable) { null }
            } ?: mutableMapOf()
            for (k in keys) {
                val r = rec(k) ?: continue
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
            Store.putString("sync/ownFile", content)
            val now = pending().toMutableSet().apply { removeAll(keys) }
            Store.putString("sync/pending", now.joinToString("\n").ifEmpty { null })
            setStatus(null)
            true
        } catch (t: Throwable) {
            setStatus(t.message ?: t.javaClass.simpleName)
            false
        }
    }

    /** Stand der anderen Geräte holen. Gibt die Anzahl übernommener Einträge zurück. */
    suspend fun pull(): Int {
        if (!isActive) return 0
        push()
        return lock.withLock {
            try {
                val id = gistId()
                val res = app.get("$GITHUB_API/gists/$id?_=${System.currentTimeMillis()}", headers = githubHeaders())
                if (res.code == 404) Store.putString("sync/gistId", null)
                if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
                val gist = json.decodeFromString(Gist.serializer(), res.text)
                val newest = HashMap<String, Record>()
                for ((name, f) in gist.files) {
                    if (name == ownFileName() || !isProfileFile(name)) continue
                    val text = if (!f.truncated && f.content != null) f.content else {
                        val url = f.rawUrl ?: continue
                        val raw = app.get(url, headers = mapOf("Authorization" to "Bearer $token"))
                        if (!raw.isSuccessful) continue
                        raw.text
                    }
                    val device = try { json.decodeFromString(DeviceFile.serializer(), text) } catch (_: Throwable) { continue }
                    for (r in device.records.values) {
                        val cur = newest[r.k]
                        if (cur == null || r.t > cur.t) newest[r.k] = r
                    }
                }
                var applied = 0
                for (r in newest.values) {
                    val local = rec(r.k)
                    if (local != null && r.t <= local.t) continue
                    Store.putString("sync/rec/${r.k}", json.encodeToString(Record.serializer(), r))
                    applied++
                }
                setStatus(null)
                if (applied > 0) _version.value++
                applied
            } catch (t: Throwable) {
                setStatus(t.message ?: t.javaClass.simpleName)
                0
            }
        }
    }
}
