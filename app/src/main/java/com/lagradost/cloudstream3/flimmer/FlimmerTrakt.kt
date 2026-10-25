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

    private class Active(val id: Int, val body: (Double) -> String) {
        var started = false
        var stopped = false
        var lastProgress = 0.0
    }

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

    /** Wird bei jedem Speichern der Wiedergabeposition aufgerufen (DataStoreHelper). */
    fun onProgress(meta: Any?, position: Long, duration: Long) {
        if (!isConfigured || !scrobbleEnabled || !isLoggedIn() || duration <= 0) return
        val ep = meta as? ResultEpisode ?: return
        val progress = (position * 100.0 / duration).coerceIn(0.0, 100.0)

        var a = active
        if (a == null || a.id != ep.id) {
            a?.let { old -> if (old.started && !old.stopped) send("pause", old.body(old.lastProgress)) }
            // Katalog-Titel tragen ihre TMDB-ID direkt in den Abspieldaten
            val body = if (ep.apiName == FlimmerKatalog.NAME) {
                try {
                    val link = parseJson<FlimmerKatalog.LinkData>(ep.data)
                    scrobbleBody(
                        "${if (link.isTv) "show" else "movie"}:tmdb:${link.tmdbId}",
                        link.season, link.episode
                    )
                } catch (t: Throwable) {
                    null
                }
            } else {
                knownIds[ep.parentId]?.let { scrobbleBody(it, ep.season, ep.episode) }
            } ?: return
            a = Active(ep.id, body)
            active = a
        }
        a.lastProgress = progress
        if (!a.started && progress < 80.0) {
            a.started = true
            send("start", a.body(progress))
        }
        if (!a.stopped && progress >= 80.0) {
            a.stopped = true
            send("stop", a.body(progress)) // ab 80 % markiert Trakt als gesehen
        }
    }

    /** App geht in den Hintergrund oder Player wird verlassen. */
    fun onPause() {
        val a = active ?: return
        if (a.started && !a.stopped) send("pause", a.body(a.lastProgress))
        active = null
    }

    private fun send(action: String, body: String) {
        scope.launch {
            try {
                val t = token() ?: return@launch
                val res = com.lagradost.cloudstream3.app.post(
                    "$API/scrobble/$action", headers = headers(t), requestBody = body.toRequestBody(JSON)
                )
                if (!res.isSuccessful && res.code != 409) Log.w(TAG, "scrobble/$action ${res.code}")
                if (action == "stop") AccountManager.traktApi.requireLibraryRefresh = true
            } catch (t: Throwable) {
                Log.w(TAG, "scrobble/$action", t)
            }
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
