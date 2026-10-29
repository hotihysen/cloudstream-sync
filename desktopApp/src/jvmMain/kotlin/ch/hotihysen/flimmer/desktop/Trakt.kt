package ch.hotihysen.flimmer.desktop

import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Trakt unter Windows, gleich wie in der Android-App:
 * Anmeldung pro Profil, Scrobbeln (ab 10 Minuten «Am schauen», ab 80 % gesehen), Watchlist,
 * Bibliothek, Gesehenes übernehmen, Gesehen-Markierungen und Merkliste an Trakt.
 */
object Trakt {
    private const val API = "https://api.trakt.tv"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val clientId: String get() = BuildConfig.FLIMMER_TRAKT_ID.trim()
    val isConfigured get() = clientId.isNotBlank()

    // ---------------------------------------------------------------- Modelle

    @Serializable
    data class DeviceCode(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_url") val verificationUrl: String,
        @SerialName("expires_in") val expiresIn: Int,
        @SerialName("interval") val interval: Int,
    )

    @Serializable
    data class Token(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("expires_in") val expiresIn: Long = 0,
        @SerialName("created_at") val createdAt: Long = 0,
    )

    @Serializable
    data class User(@SerialName("username") val username: String? = null, @SerialName("name") val name: String? = null)

    @Serializable
    data class Settings(@SerialName("user") val user: User? = null)

    @Serializable
    data class Ids(@SerialName("trakt") val trakt: Int? = null, @SerialName("tmdb") val tmdb: Int? = null)

    @Serializable
    data class Media(
        @SerialName("title") val title: String? = null,
        @SerialName("year") val year: Int? = null,
        @SerialName("ids") val ids: Ids? = null,
        @SerialName("rating") val rating: Double? = null,
        @SerialName("aired_episodes") val airedEpisodes: Int? = null,
        @SerialName("runtime") val runtime: Int? = null,
        @SerialName("genres") val genres: List<String> = emptyList(),
    )

    @Serializable
    data class WatchItem(
        @SerialName("type") val type: String? = null,
        @SerialName("movie") val movie: Media? = null,
        @SerialName("show") val show: Media? = null,
    )

    @Serializable
    data class EpisodeRef(@SerialName("season") val season: Int = 0, @SerialName("number") val number: Int = 0)

    @Serializable
    data class Entry(
        @SerialName("id") val id: Long = 0,
        @SerialName("listed_at") val listedAt: String? = null,
        @SerialName("last_watched_at") val lastWatchedAt: String? = null,
        @SerialName("paused_at") val pausedAt: String? = null,
        @SerialName("plays") val plays: Int? = null,
        @SerialName("rating") val rating: Int? = null,
        @SerialName("progress") val progress: Double? = null,
        @SerialName("movie") val movie: Media? = null,
        @SerialName("show") val show: Media? = null,
        @SerialName("episode") val episode: EpisodeRef? = null,
    ) {
        val media get() = movie ?: show
        val isShow get() = show != null
    }

    @Serializable
    data class ProgressEpisode(@SerialName("number") val number: Int = 0, @SerialName("completed") val completed: Boolean = false)

    @Serializable
    data class ProgressSeason(@SerialName("number") val number: Int = 0, @SerialName("episodes") val episodes: List<ProgressEpisode> = emptyList())

    @Serializable
    data class Progress(
        @SerialName("aired") val aired: Int = 0,
        @SerialName("completed") val completed: Int = 0,
        @SerialName("next_episode") val nextEpisode: EpisodeRef? = null,
        @SerialName("seasons") val seasons: List<ProgressSeason> = emptyList(),
    )

    // ---------------------------------------------------------------- Anmeldung (pro Profil)

    /** Profil 0 behält die bisherigen Schlüssel */
    private fun k(name: String) = if (Sync.profile == 0) "trakt/$name" else "trakt/p${Sync.profile}/$name"

    val isLoggedIn: Boolean get() = Store.getString(k("access")) != null
    val userName: String? get() = Store.getString(k("user"))

    private val _lastReport = MutableStateFlow<String?>(null)
    /** Letzte Meldung an Trakt (zur Kontrolle in den Einstellungen) */
    val lastReport: StateFlow<String?> = _lastReport

    private fun report(text: String) {
        val time = java.text.SimpleDateFormat("HH:mm").format(java.util.Date())
        _lastReport.value = "$time · $text"
    }

    private fun headers(token: String?) = buildMap {
        put("Content-Type", "application/json")
        put("trakt-api-version", "2")
        put("trakt-api-key", clientId)
        token?.let { put("Authorization", "Bearer $it") }
    }

    suspend fun requestCode(): DeviceCode {
        val body = buildJsonObject { put("client_id", clientId) }.toString()
        val res = app.post("$API/oauth/device/code", headers = headers(null), requestBody = body.toRequestBody(JSON_TYPE))
        if (!res.isSuccessful) throw IllegalStateException("Trakt HTTP ${res.code}")
        return json.decodeFromString(DeviceCode.serializer(), res.text)
    }

    /** Wartet, bis der Code auf trakt.tv/activate bestätigt ist. */
    suspend fun waitForLogin(code: DeviceCode): Boolean {
        val end = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < end) {
            delay(code.interval.coerceAtLeast(5) * 1000L)
            val body = buildJsonObject {
                put("code", code.deviceCode)
                put("client_id", clientId)
            }.toString()
            val res = app.post("$API/oauth/device/token", headers = headers(null), requestBody = body.toRequestBody(JSON_TYPE))
            when (res.code) {
                200 -> {
                    saveToken(json.decodeFromString(Token.serializer(), res.text))
                    try {
                        val s = app.get("$API/users/settings", headers = headers(Store.getString(k("access"))))
                        val u = json.decodeFromString(Settings.serializer(), s.text).user
                        Store.putString(k("user"), u?.name?.takeIf { it.isNotBlank() } ?: u?.username)
                    } catch (_: Throwable) {
                    }
                    forceRefresh()
                    return true
                }
                400 -> continue // noch nicht bestätigt
                else -> return false
            }
        }
        return false
    }

    private fun saveToken(t: Token) {
        Store.putString(k("access"), t.accessToken)
        Store.putString(k("refresh"), t.refreshToken)
        Store.putLong(k("expires"), (t.createdAt + t.expiresIn) * 1000L)
    }

    fun logout() {
        listOf("access", "refresh", "expires", "user").forEach { Store.putString(k(it), null) }
        forceRefresh()
    }

    private suspend fun token(): String? {
        val access = Store.getString(k("access")) ?: return null
        val expires = Store.getLong(k("expires"), 0)
        if (expires > 0 && System.currentTimeMillis() > expires - 86_400_000L) {
            val refresh = Store.getString(k("refresh")) ?: return access
            val body = buildJsonObject {
                put("refresh_token", refresh)
                put("client_id", clientId)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                put("grant_type", "refresh_token")
            }.toString()
            val res = app.post("$API/oauth/token", headers = headers(null), requestBody = body.toRequestBody(JSON_TYPE))
            if (res.isSuccessful) {
                saveToken(json.decodeFromString(Token.serializer(), res.text))
                return Store.getString(k("access"))
            }
        }
        return access
    }

    private suspend fun post(path: String, body: String, t: String) =
        app.post("$API$path", headers = headers(t), requestBody = body.toRequestBody(JSON_TYPE))

    private fun idsBody(isTv: Boolean, tmdb: Int, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null): String =
        buildJsonObject {
            putJsonArray(if (isTv) "shows" else "movies") {
                add(buildJsonObject {
                    putJsonObject("ids") { put("tmdb", tmdb) }
                    extra?.invoke(this)
                })
            }
        }.toString()

    // ---------------------------------------------------------------- Watchlist (Startseite)

    /** (istSerie, TMDB-ID) der Watchlist */
    suspend fun watchlist(limit: Int = 40): List<Pair<Boolean, Int>> {
        val t = token() ?: return emptyList()
        val res = app.get("$API/sync/watchlist?sort=added,desc", headers = headers(t))
        if (!res.isSuccessful) return emptyList()
        return json.decodeFromString(ListSerializer(WatchItem.serializer()), res.text)
            .mapNotNull { w ->
                when (w.type) {
                    "movie" -> w.movie?.ids?.tmdb?.let { false to it }
                    "show" -> w.show?.ids?.tmdb?.let { true to it }
                    else -> null
                }
            }.take(limit)
    }

    // ---------------------------------------------------------------- Scrobbeln

    /** Scrobbeln: start / pause / stop. Ab 80 % markiert Trakt den Titel als gesehen. */
    fun scrobble(action: String, isTv: Boolean, tmdbId: Int, season: Int?, episode: Int?, progress: Double, then: (suspend (String) -> Unit)? = null) {
        if (!isLoggedIn || !Store.getBoolean("trakt/scrobble", true)) return
        scope.launch {
            val label = when (action) { "start" -> "Start"; "pause" -> "Pause (Fortschritt gespeichert)"; else -> "Stopp (gesehen)" }
            try {
                val t = token() ?: run { report("$label: nicht angemeldet"); return@launch }
                val body = buildJsonObject {
                    if (isTv) {
                        putJsonObject("show") { putJsonObject("ids") { put("tmdb", tmdbId) } }
                        putJsonObject("episode") {
                            put("season", season ?: 1)
                            put("number", episode ?: 1)
                        }
                    } else {
                        putJsonObject("movie") { putJsonObject("ids") { put("tmdb", tmdbId) } }
                    }
                    put("progress", progress)
                }.toString()
                if (isTv && action == "start") unhide(tmdbId, t)
                val res = post("/scrobble/$action", body, t)
                report("$label: " + when {
                    res.isSuccessful -> "OK"
                    res.code == 409 -> "schon gemeldet"
                    res.code == 404 -> "Titel bei Trakt nicht gefunden"
                    res.code == 401 -> "Anmeldung abgelaufen, bitte neu anmelden"
                    else -> "Fehler ${res.code}"
                })
                // Gesehen trotzdem eintragen
                if (action == "stop" && !res.isSuccessful && res.code != 409) {
                    markHistory(isTv, tmdbId, listOfNotNull(if (isTv) (season ?: 1) to (episode ?: 1) else null), true)
                }
                if (action != "start") invalidate()
                then?.invoke(t)
            } catch (e: Throwable) {
                report("$label: keine Verbindung")
            }
        }
    }

    /** Ab 10 Minuten: Position bei Trakt speichern («Am schauen») und aus «Geplant» nehmen */
    fun markWatching(isTv: Boolean, tmdbId: Int, season: Int?, episode: Int?, progress: Double) {
        scrobble("pause", isTv, tmdbId, season, episode, progress) { t ->
            scrobble("start", isTv, tmdbId, season, episode, progress)
            post("/sync/watchlist/remove", idsBody(isTv, tmdbId), t)
        }
    }

    private suspend fun unhide(tmdbId: Int, t: String) {
        try {
            post("/users/hidden/progress_watched/remove", idsBody(true, tmdbId), t)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- Weiterschauen entfernen

    fun removeFromContinue(isTv: Boolean, tmdbId: Int) {
        if (!isLoggedIn) return
        scope.launch {
            try {
                val t = token() ?: return@launch
                val res = app.get("$API/sync/playback?limit=100", headers = headers(t))
                if (res.isSuccessful) {
                    json.decodeFromString(ListSerializer(Entry.serializer()), res.text)
                        .filter { pb -> if (isTv) pb.show?.ids?.tmdb == tmdbId else (pb.show == null && pb.movie?.ids?.tmdb == tmdbId) }
                        .forEach { pb -> app.delete("$API/sync/playback/${pb.id}", headers = headers(t)) }
                }
                if (isTv) post("/users/hidden/progress_watched", idsBody(true, tmdbId), t)
                report("Aus «Weiterschauen» entfernt (auch bei Trakt)")
                invalidate()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- Gesehenes

    /** Folgen (Staffel, Folge) bzw. Film bei Trakt als gesehen ein- oder austragen */
    fun markHistory(isTv: Boolean, tmdbId: Int, episodes: List<Pair<Int, Int>>, watched: Boolean) {
        if (!isLoggedIn) return
        scope.launch {
            try {
                val t = token() ?: return@launch
                val body = if (isTv) idsBody(true, tmdbId) {
                    put("seasons", buildJsonArray {
                        episodes.groupBy { it.first }.forEach { (s, eps) ->
                            add(buildJsonObject {
                                put("number", s)
                                put("episodes", buildJsonArray { eps.forEach { e -> add(buildJsonObject { put("number", e.second) }) } })
                            })
                        }
                    })
                } else idsBody(false, tmdbId)
                val res = post(if (watched) "/sync/history" else "/sync/history/remove", body, t)
                report("${if (isTv) episodes.size else 1} als ${if (watched) "gesehen" else "nicht gesehen"} markiert: " +
                        if (res.isSuccessful) "OK" else "Fehler ${res.code}")
                invalidate()
            } catch (_: Throwable) {
            }
        }
    }

    private val traktIdCache = HashMap<String, Int>()

    private suspend fun traktIdOf(isTv: Boolean, tmdbId: Int, t: String): Int? {
        val key = "$isTv/$tmdbId"
        traktIdCache[key]?.let { return it }
        val type = if (isTv) "show" else "movie"
        val res = app.get("$API/search/tmdb/$tmdbId?type=$type", headers = headers(t))
        if (!res.isSuccessful) return null
        val hit = json.decodeFromString(ListSerializer(WatchItem.serializer()), res.text).firstOrNull()
        val id = (if (isTv) hit?.show else hit?.movie)?.ids?.trakt ?: return null
        traktIdCache[key] = id
        return id
    }

    /** Bei Trakt Gesehenes: Menge (Staffel, Folge); bei Filmen (0, 0), wenn gesehen */
    suspend fun watchedOf(isTv: Boolean, tmdbId: Int): Set<Pair<Int, Int>> {
        if (!isLoggedIn) return emptySet()
        return try {
            val t = token() ?: return emptySet()
            val id = traktIdOf(isTv, tmdbId, t) ?: return emptySet()
            if (isTv) {
                val res = app.get("$API/shows/$id/progress/watched?hidden=false&specials=false&count_specials=false", headers = headers(t))
                if (!res.isSuccessful) return emptySet()
                json.decodeFromString(Progress.serializer(), res.text).seasons
                    .flatMap { s -> s.episodes.filter { it.completed }.map { s.number to it.number } }.toSet()
            } else {
                val res = app.get("$API/sync/history/movies/$id?limit=1", headers = headers(t))
                if (res.isSuccessful && res.text.trim().length > 2) setOf(0 to 0) else emptySet()
            }
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun reportImported(n: Int, title: String) {
        if (n > 0) report("$n als gesehen von Trakt übernommen («$title»)")
    }

    // ---------------------------------------------------------------- Merkliste → Trakt

    /** Lesezeichen in Flimmer geändert: bei Trakt nachziehen (wie in der App) */
    fun onBookmark(isTv: Boolean, tmdbId: Int, status: Sync.WatchType?) {
        if (!isLoggedIn || !Store.getBoolean("trakt/bookmarks", true)) return
        scope.launch {
            try {
                val t = token() ?: return@launch
                when (status) {
                    Sync.WatchType.PLANTOWATCH -> post("/sync/watchlist", idsBody(isTv, tmdbId), t)
                    Sync.WatchType.COMPLETED -> {
                        post("/sync/history", idsBody(isTv, tmdbId), t)
                        post("/sync/watchlist/remove", idsBody(isTv, tmdbId), t)
                    }
                    null -> post("/sync/watchlist/remove", idsBody(isTv, tmdbId), t)
                    else -> post("/sync/watchlist/remove", idsBody(isTv, tmdbId), t)
                }
                invalidate()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- Bibliothek

    data class LibItem(
        val isTv: Boolean,
        val tmdb: Int,
        val title: String,
        val year: Int?,
        val poster: String?,
        val info: String,
        val progress: Float?,
        val rating: Double?,
        val ownRating: Int?,
        val anime: Boolean,
        val time: String?,
    )

    data class Library(val lists: List<Pair<String, List<LibItem>>>)

    private val libLock = Mutex()
    @Volatile private var libCache: Pair<Long, Library>? = null
    @Volatile private var skipProgressCache = false

    private fun invalidate() {
        libCache = null
    }

    fun forceRefresh() {
        skipProgressCache = true
        libCache = null
    }

    private suspend fun list(path: String, t: String): List<Entry> = try {
        val res = app.get("$API$path", headers = headers(t))
        if (res.isSuccessful) json.decodeFromString(ListSerializer(Entry.serializer()), res.text) else emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private val progressLimit = Semaphore(6)

    private suspend fun progressOf(e: Entry, t: String): Progress? {
        val id = e.show?.ids?.trakt ?: return null
        val cacheKey = "trakt/progress/$id"
        val stamp = e.lastWatchedAt ?: ""
        if (!skipProgressCache) {
            Store.getString(cacheKey)?.split("|", limit = 3)?.takeIf { it.size == 3 && it[0] == stamp }?.let { c ->
                if (System.currentTimeMillis() - (c[1].toLongOrNull() ?: 0) < 86_400_000L) {
                    try {
                        return json.decodeFromString(Progress.serializer(), c[2])
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return progressLimit.withPermit {
            try {
                val res = app.get("$API/shows/$id/progress/watched?hidden=false&specials=false&count_specials=false", headers = headers(t))
                if (!res.isSuccessful) return@withPermit null
                val p = json.decodeFromString(Progress.serializer(), res.text)
                Store.putString(cacheKey, "$stamp|${System.currentTimeMillis()}|${json.encodeToString(Progress.serializer(), p.copy(seasons = emptyList()))}")
                p
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Geplant, Am schauen, Abgeschlossen, Favoriten, wie in der App */
    suspend fun library(): Library? = libLock.withLock {
        libCache?.let { (time, lib) -> if (System.currentTimeMillis() - time < 60_000L) return@withLock lib }
        val t = token() ?: return@withLock null
        val lib = coroutineScope {
            val wlM = async { list("/sync/watchlist/movies?extended=full", t) }
            val wlS = async { list("/sync/watchlist/shows?extended=full", t) }
            val wM = async { list("/sync/watched/movies?extended=full", t) }
            val wS = async { list("/sync/watched/shows?extended=full&extended=noseasons", t) }
            val rat = async { list("/sync/ratings", t) }
            val fav = async { list("/sync/favorites?extended=full", t) }
            val pb = async { list("/sync/playback?extended=full&limit=100", t) }
            val hid = async { list("/users/hidden/progress_watched?type=show&limit=1000", t) }
            val shows = wS.await()
            val progress = shows.map { e -> async { e.show?.ids?.trakt to progressOf(e, t) } }.awaitAll().toMap()
            skipProgressCache = false
            val ratings = rat.await().mapNotNull { r -> r.media?.ids?.tmdb?.let { "${r.isShow}/$it" to (r.rating ?: return@mapNotNull null) } }.toMap()
            val hidden = hid.await().mapNotNull { it.show?.ids?.trakt }.toSet()
            val playback = pb.await()

            suspend fun item(e: Entry, info: String, progress: Float?): LibItem? {
                val m = e.media ?: return null
                val tmdb = m.ids?.tmdb ?: return null
                return LibItem(
                    isTv = e.isShow, tmdb = tmdb, title = m.title ?: return null, year = m.year,
                    poster = null, info = info, progress = progress,
                    rating = m.rating, ownRating = ratings["${e.isShow}/$tmdb"],
                    anime = m.genres.any { it.equals("anime", true) },
                    time = e.pausedAt ?: e.lastWatchedAt ?: e.listedAt,
                )
            }

            val watching = mutableListOf<LibItem>()
            val completed = mutableListOf<LibItem>()
            val pausedEp = playback.filter { it.show != null && it.episode != null }.sortedByDescending { it.pausedAt ?: "" }
                .distinctBy { it.show?.ids?.trakt }
            for (s in shows) {
                val p = progress[s.show?.ids?.trakt]
                val aired = p?.aired ?: s.show?.airedEpisodes ?: 0
                val count = p?.completed ?: 0
                val done = aired > 0 && count >= aired
                val paused = pausedEp.firstOrNull { it.show?.ids?.trakt == s.show?.ids?.trakt }
                val info = when {
                    paused?.episode != null -> "S${paused.episode.season} · F${paused.episode.number} angefangen"
                    p?.nextEpisode != null && !done -> "Als Nächstes: S${p.nextEpisode.season} · F${p.nextEpisode.number}"
                    aired > 0 -> "$count von $aired Folgen"
                    else -> "Gesehen"
                }
                val it = item(s, info, if (aired > 0 && count in 1 until aired) count.toFloat() / aired else null) ?: continue
                when {
                    done && paused == null -> completed += it.copy(info = "Komplett gesehen")
                    paused == null && s.show?.ids?.trakt in hidden -> Unit
                    else -> watching += it
                }
            }
            for (pb2 in pausedEp) {
                if (shows.any { it.show?.ids?.trakt == pb2.show?.ids?.trakt }) continue
                item(pb2, "S${pb2.episode?.season} · F${pb2.episode?.number} angefangen", 0.02f)?.let { watching += it }
            }
            for (pb2 in playback.filter { it.movie != null }.distinctBy { it.movie?.ids?.trakt }) {
                val pct = (pb2.progress ?: 0.0).toInt().coerceIn(1, 99)
                val runtime = pb2.movie?.runtime
                val info = if (runtime != null && runtime > 0) "Noch ${((100 - pct) * runtime / 100).coerceAtLeast(1)} Min." else "$pct % geschaut"
                item(pb2, info, pct / 100f)?.let { watching += it }
            }
            val watchingIds = watching.map { "${it.isTv}/${it.tmdb}" }.toSet()
            for (m in wM.await()) {
                val it = item(m, "Gesehen" + ((m.plays ?: 0).takeIf { p -> p > 1 }?.let { p -> " · $p×" } ?: ""), null) ?: continue
                if ("${it.isTv}/${it.tmdb}" !in watchingIds) completed += it
            }
            val planned = (wlM.await() + wlS.await()).mapNotNull { e ->
                item(e, listOfNotNull(e.media?.year?.toString(), e.media?.genres?.firstOrNull()?.replaceFirstChar { c -> c.uppercase() }).joinToString(" · "), null)
            }.filter { "${it.isTv}/${it.tmdb}" !in watchingIds }
            val favorites = fav.await().mapNotNull { e -> item(e, e.media?.year?.toString() ?: "", null) }
            Library(listOf(
                "Geplant" to planned,
                "Am schauen" to watching.sortedByDescending { it.time ?: "" },
                "Abgeschlossen" to completed.sortedByDescending { it.time ?: "" },
                "Favoriten" to favorites,
            ))
        }
        libCache = System.currentTimeMillis() to lib
        lib
    }
}
