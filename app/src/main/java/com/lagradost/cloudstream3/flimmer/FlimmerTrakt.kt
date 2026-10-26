package com.lagradost.cloudstream3.flimmer

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.syncproviders.providers.TraktApi
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Ergänzungen zu Trakt, die über den Sync-Dienst (TraktApi) hinausgehen:
 * - Scrobbeln während der Wiedergabe (für alle Titel mit TMDB- oder IMDb-ID)
 * - Lesezeichen in Flimmer → Trakt (Watchlist, Gesehen, …)
 * - Watchlist-Reihe auf der Startseite
 *
 * Die Anmeldung läuft über Einstellungen, Konten, «Trakt» (AccountManager).
 */
object FlimmerTrakt {
    private const val TAG = "FlimmerTrakt"
    private const val API = "https://api.trakt.tv"
    private const val PREFS = "flimmer_trakt"
    private const val K_SCROBBLE = "scrobble"
    private const val K_BOOKMARKS = "bookmarks"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isConfigured: Boolean get() = TraktApi.clientId.isNotBlank() && TraktApi.clientId != "null"

    private val repo: SyncRepo? get() = AccountManager.syncApis.firstOrNull { it.idPrefix == AccountManager.traktApi.idPrefix }

    fun isLoggedIn(): Boolean = repo?.authUser() != null
    fun userName(): String? = repo?.authUser()?.name

    private val ctx: Context? get() = CloudStreamApp.context
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var scrobbleEnabled: Boolean
        get() = ctx?.let { prefs(it).getBoolean(K_SCROBBLE, true) } ?: false
        set(value) {
            ctx?.let { c -> prefs(c).edit { putBoolean(K_SCROBBLE, value) } }
        }

    /** Lesezeichen in Flimmer automatisch zu Trakt übertragen */
    var bookmarksEnabled: Boolean
        get() = ctx?.let { prefs(it).getBoolean(K_BOOKMARKS, true) } ?: false
        set(value) {
            ctx?.let { c -> prefs(c).edit { putBoolean(K_BOOKMARKS, value) } }
        }

    private suspend fun token(): String? = repo?.currentAuth()?.token?.accessToken

    private fun headers(token: String): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "trakt-api-version" to "2",
        "trakt-api-key" to TraktApi.clientId,
        "Authorization" to "Bearer $token",
    )

    // ------------------------------------------------------------------ IDs der geöffneten Titel

    /** Titel-ID (LoadResponse.getId) → Trakt-Sync-ID, damit beim Abspielen gescrobbelt werden kann */
    private val knownIds = ConcurrentHashMap<Int, String>()

    fun rememberIds(response: LoadResponse) {
        try {
            response.syncData[AccountManager.traktApi.idPrefix]?.let { knownIds[response.getId()] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "rememberIds", t)
        }
    }

    // ------------------------------------------------------------------ Lesezeichen → Trakt

    fun onBookmark(response: LoadResponse, status: WatchType) {
        if (!isConfigured || !bookmarksEnabled || !isLoggedIn()) return
        val id = response.syncData[AccountManager.traktApi.idPrefix] ?: return
        val r = repo ?: return
        val target = when (status) {
            WatchType.PLANTOWATCH -> SyncWatchType.PLANTOWATCH
            WatchType.WATCHING -> SyncWatchType.WATCHING
            WatchType.COMPLETED -> SyncWatchType.COMPLETED
            WatchType.DROPPED -> SyncWatchType.DROPPED
            WatchType.ONHOLD -> SyncWatchType.ONHOLD
            WatchType.NONE -> SyncWatchType.NONE
        }
        scope.launch {
            try {
                val current = r.status(id).getOrNull()
                val newStatus = SyncAPI.SyncStatus(
                    status = target,
                    score = current?.score,
                    watchedEpisodes = current?.watchedEpisodes,
                    isFavorite = current?.isFavorite,
                    maxEpisodes = current?.maxEpisodes,
                )
                r.updateStatus(id, newStatus)
            } catch (t: Throwable) {
                Log.w(TAG, "onBookmark", t)
            }
        }
    }

    // ------------------------------------------------------------------ Scrobbeln

    private class Active(val id: Int, val body: (Double) -> String, val syncId: String?) {
        var started = false
        var stopped = false
        var markedWatching = false
        var lastProgress = 0.0
    }

    /** Ab dieser Spielzeit gilt ein Titel als angefangen und erscheint bei Trakt unter «Am schauen». */
    private const val WATCHING_AFTER_MS = 10 * 60_000L

    @Volatile private var active: Active? = null

    private fun scrobbleBody(syncId: String, season: Int?, episode: Int?): ((Double) -> String)? {
        val tid = TraktApi.Companion.TraktId.parse(syncId) ?: return null
        return if (tid.isShow) {
            val s = season ?: return null
            val e = episode ?: return null
            { p -> mapOf("show" to mapOf("ids" to tid.ids()), "episode" to mapOf("season" to s, "number" to e), "progress" to p).toJson() }
        } else {
            { p -> mapOf("movie" to mapOf("ids" to tid.ids()), "progress" to p).toJson() }
        }
    }

    /** (Sync-ID, (Staffel, Folge)) einer Folge bzw. eines Films. Katalog-Titel tragen die TMDB-ID in den Abspieldaten. */
    private fun episodeSyncId(ep: ResultEpisode): Pair<String, Pair<Int?, Int?>>? =
        if (FlimmerKatalog.isKatalog(ep.apiName)) {
            try {
                val link = parseJson<FlimmerKatalog.LinkData>(ep.data)
                "${if (link.isTv) "show" else "movie"}:tmdb:${link.tmdbId}" to (link.season to link.episode)
            } catch (t: Throwable) {
                null
            }
        } else {
            knownIds[ep.parentId]?.let { it to (ep.season to ep.episode) }
        }

    /** Sync-ID eines Titels aus «Weiterschauen» (Katalog-URL enthält die TMDB-ID). */
    private fun titleSyncId(apiName: String?, url: String?, parentId: Int?): String? {
        if (FlimmerKatalog.isKatalog(apiName) && url != null) {
            val m = Regex("""/(tv|movie)/(\d+)""").find(url) ?: return null
            return "${if (m.groupValues[1] == "tv") "show" else "movie"}:tmdb:${m.groupValues[2]}"
        }
        return parentId?.let { knownIds[it] }
    }

    /** Letzte Meldung an Trakt, zur Kontrolle in den Einstellungen */
    @Volatile var lastReport: String? = null
        private set

    /** Wird bei jedem Speichern der Wiedergabeposition aufgerufen (DataStoreHelper) und jede Minute vom Player. */
    fun onProgress(meta: Any?, position: Long, duration: Long) {
        if (!isConfigured || !scrobbleEnabled || !isLoggedIn() || duration <= 0) return
        val ep = meta as? ResultEpisode ?: return
        val progress = (position * 100.0 / duration).coerceIn(0.0, 100.0)

        var a = active
        if (a == null || a.id != ep.id) {
            a?.let { old -> if (old.started && !old.stopped) send("pause", old.body(old.lastProgress)) }
            val syncId = episodeSyncId(ep) ?: return
            val body = scrobbleBody(syncId.first, syncId.second.first, syncId.second.second) ?: return
            a = Active(ep.id, body, syncId.first)
            active = a
            // Serie war aus «Weiterschauen» entfernt (bei Trakt ausgeblendet): wieder einblenden
            if (syncId.first.startsWith("show:")) unhideProgress(syncId.first)
        }
        a.lastProgress = progress
        if (!a.started && progress < 80.0) {
            a.started = true
            send("start", a.body(progress))
        }
        // Nach 10 Minuten (bei kurzen Folgen nach 40 %): Position bei Trakt speichern, damit der Titel
        // unter «Am schauen» erscheint, und aus «Geplant» nehmen
        if (!a.markedWatching && !a.stopped && (position >= WATCHING_AFTER_MS || progress >= 40.0)) {
            a.markedWatching = true
            val body = a.body
            val syncId = a.syncId
            send("pause", body(progress)) {
                send("start", body(progress))
                syncId?.let { removeFromWatchlist(it) }
            }
        }
        if (!a.stopped && progress >= 80.0) {
            a.stopped = true
            send("stop", a.body(progress)) // ab 80 % markiert Trakt als gesehen
        }
    }

    /** App geht in den Hintergrund oder Player wird verlassen. */
    fun onPause() {
        val a = active ?: return
        active = null
        if (a.stopped) return
        when {
            a.lastProgress >= 80.0 -> send("stop", a.body(a.lastProgress))
            a.lastProgress >= 1.0 -> send("pause", a.body(a.lastProgress))
        }
    }

    private fun send(action: String, body: String, then: (suspend () -> Unit)? = null) {
        scope.launch {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN).format(java.util.Date())
            val label = when (action) { "start" -> "Start"; "pause" -> "Pause (Fortschritt gespeichert)"; else -> "Stopp (gesehen)" }
            try {
                val t = token() ?: run {
                    lastReport = "$time · $label: nicht angemeldet oder Anmeldung abgelaufen"
                    return@launch
                }
                val res = com.lagradost.cloudstream3.app.post(
                    "$API/scrobble/$action", headers = headers(t), requestBody = body.toRequestBody(JSON)
                )
                lastReport = "$time · $label: " + when {
                    res.isSuccessful -> "OK"
                    res.code == 409 -> "schon gemeldet"
                    res.code == 404 -> "Titel bei Trakt nicht gefunden"
                    res.code == 401 -> "Anmeldung abgelaufen, bitte neu anmelden"
                    else -> "Fehler ${res.code}"
                }
                if (!res.isSuccessful && res.code != 409) {
                    Log.w(TAG, "scrobble/$action ${res.code}")
                    // Gesehen trotzdem eintragen
                    if (action == "stop") addHistoryFromScrobble(body, t)
                }
                if (action == "stop" || action == "pause") AccountManager.traktApi.requireLibraryRefresh = true
                then?.invoke()
            } catch (t: Throwable) {
                lastReport = "$time · $label: keine Verbindung"
                Log.w(TAG, "scrobble/$action", t)
            }
        }
    }

    private suspend fun post(path: String, body: String, token: String) =
        com.lagradost.cloudstream3.app.post("$API$path", headers = headers(token), requestBody = body.toRequestBody(JSON))

    /** Scrobble-Daten in einen Verlaufseintrag umwandeln (falls «stop» scheitert) */
    private suspend fun addHistoryFromScrobble(body: String, token: String) {
        try {
            val m = parseJson<Map<String, Any?>>(body)
            val history = if (m["show"] != null) {
                val ep = m["episode"] as? Map<*, *> ?: return
                mapOf("shows" to listOf(mapOf(
                    "ids" to (m["show"] as Map<*, *>)["ids"],
                    "seasons" to listOf(mapOf("number" to ep["season"], "episodes" to listOf(mapOf("number" to ep["number"]))))
                )))
            } else {
                mapOf("movies" to listOf(mapOf("ids" to (m["movie"] as? Map<*, *>)?.get("ids"))))
            }
            post("/sync/history", history.toJson(), token)
        } catch (t: Throwable) {
            Log.w(TAG, "history fallback", t)
        }
    }

    // ------------------------------------------------------------------ Gesehen-Markierungen → Trakt

    /** Folgen in Flimmer als gesehen / nicht gesehen markiert: bei Trakt im Verlauf ein- bzw. austragen. */
    fun markWatched(episodes: List<ResultEpisode>, watched: Boolean) {
        if (!isConfigured || !isLoggedIn() || episodes.isEmpty()) return
        scope.launch {
            try {
                val t = token() ?: return@launch
                val shows = HashMap<String, MutableMap<Int, MutableList<Int>>>()
                val movies = mutableListOf<String>()
                for (ep in episodes) {
                    val (id, se) = episodeSyncId(ep) ?: continue
                    if (id.startsWith("movie:")) {
                        movies += id
                    } else {
                        val season = se.first ?: continue
                        val number = se.second ?: continue
                        shows.getOrPut(id) { HashMap() }.getOrPut(season) { mutableListOf() } += number
                    }
                }
                val body = mapOf(
                    "movies" to movies.distinct().mapNotNull { TraktApi.Companion.TraktId.parse(it)?.let { tid -> mapOf("ids" to tid.ids()) } },
                    "shows" to shows.mapNotNull { (id, seasons) ->
                        val tid = TraktApi.Companion.TraktId.parse(id) ?: return@mapNotNull null
                        mapOf("ids" to tid.ids(), "seasons" to seasons.map { (n, eps) ->
                            mapOf("number" to n, "episodes" to eps.distinct().map { mapOf("number" to it) })
                        })
                    },
                ).toJson()
                val res = post(if (watched) "/sync/history" else "/sync/history/remove", body, t)
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN).format(java.util.Date())
                lastReport = "$time · ${episodes.size} als ${if (watched) "gesehen" else "nicht gesehen"} markiert: " +
                        if (res.isSuccessful) "OK" else "Fehler ${res.code}"
                AccountManager.traktApi.requireLibraryRefresh = true
            } catch (e: Throwable) {
                Log.w(TAG, "markWatched", e)
            }
        }
    }

    // ------------------------------------------------------------------ Aus «Weiterschauen» entfernen

    @kotlinx.serialization.Serializable
    data class PlaybackItem(
        @com.fasterxml.jackson.annotation.JsonProperty("id") @kotlinx.serialization.SerialName("id") val id: Long = 0,
        @com.fasterxml.jackson.annotation.JsonProperty("movie") @kotlinx.serialization.SerialName("movie") val movie: TraktApi.Media? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("show") @kotlinx.serialization.SerialName("show") val show: TraktApi.Media? = null,
    )

    /**
     * Titel aus «Weiterschauen» entfernt: bei Trakt die gespeicherte Position löschen und die Serie
     * im Trakt-Fortschritt ausblenden. Wird die Serie wieder geschaut, blendet Flimmer sie wieder ein.
     */
    fun removeFromContinue(apiName: String?, url: String?, parentId: Int?) {
        if (!isConfigured || !isLoggedIn()) return
        val syncId = titleSyncId(apiName, url, parentId) ?: return
        val tid = TraktApi.Companion.TraktId.parse(syncId) ?: return
        scope.launch {
            try {
                val t = token() ?: return@launch
                val res = com.lagradost.cloudstream3.app.get("$API/sync/playback?limit=100", headers = headers(t))
                if (res.isSuccessful) {
                    parseJson<List<PlaybackItem>>(res.text)
                        .filter { pb -> if (tid.isShow) tid.matches(pb.show?.ids) else (pb.show == null && tid.matches(pb.movie?.ids)) }
                        .forEach { pb ->
                            com.lagradost.cloudstream3.app.delete("$API/sync/playback/${pb.id}", headers = headers(t))
                        }
                }
                if (tid.isShow) {
                    post("/users/hidden/progress_watched", mapOf("shows" to listOf(mapOf("ids" to tid.ids()))).toJson(), t)
                }
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN).format(java.util.Date())
                lastReport = "$time · Aus «Weiterschauen» entfernt (auch bei Trakt)"
                AccountManager.traktApi.requireLibraryRefresh = true
            } catch (e: Throwable) {
                Log.w(TAG, "removeFromContinue", e)
            }
        }
    }

    private val unhidden = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun unhideProgress(syncId: String) {
        if (!unhidden.add(syncId)) return
        val tid = TraktApi.Companion.TraktId.parse(syncId) ?: return
        scope.launch {
            try {
                val t = token() ?: return@launch
                post("/users/hidden/progress_watched/remove", mapOf("shows" to listOf(mapOf("ids" to tid.ids()))).toJson(), t)
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun removeFromWatchlist(syncId: String) {
        val tid = TraktApi.Companion.TraktId.parse(syncId) ?: return
        val t = token() ?: return
        val body = mapOf((if (tid.isShow) "shows" else "movies") to listOf(mapOf("ids" to tid.ids()))).toJson()
        try {
            com.lagradost.cloudstream3.app.post(
                "$API/sync/watchlist/remove", headers = headers(t), requestBody = body.toRequestBody(JSON)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "watchlist/remove", e)
        }
    }

    // ------------------------------------------------------------------ Watchlist für die Startseite

    /** Paare (istSerie, TMDB-ID) aus der Trakt-Watchlist. */
    suspend fun watchlist(limit: Int = 40): List<Pair<Boolean, Int>> {
        val r = repo ?: return emptyList()
        val lib = r.library().getOrNull() ?: return emptyList()
        // Erste Liste der Bibliothek ist die Watchlist
        return lib.allLibraryLists.firstOrNull()?.items.orEmpty().mapNotNull { item ->
            val tid = TraktApi.Companion.TraktId.parse(item.syncId) ?: return@mapNotNull null
            if (tid.idType != "tmdb") return@mapNotNull null
            tid.value.toIntOrNull()?.let { tid.isShow to it }
        }.take(limit)
    }
}
