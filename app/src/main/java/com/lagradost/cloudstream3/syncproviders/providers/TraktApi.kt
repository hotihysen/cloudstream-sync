package com.lagradost.cloudstream3.syncproviders.providers

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthPinData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar
import java.util.Date

/**
 * Trakt als Sync-Dienst (Bibliothek, Status, Bewertung, Favoriten).
 *
 * Sync-ID-Format: "<movie|show>:<tmdb|imdb>:<wert>", z.B. "show:tmdb:220542".
 * Gesetzt von LoadResponse.addTMDbId / addImdbId (siehe MainAPI.kt) und damit für
 * den Flimmer Katalog und alle Erweiterungen, die TMDB- oder IMDb-IDs liefern.
 *
 * Anmeldung per Gerätecode ohne Client Secret (Trakt-PKCE-App).
 */
class TraktApi : SyncAPI() {
    override val name = "Trakt"
    override val idPrefix = "trakt"
    override val mainUrl = "https://api.trakt.tv"
    override val icon = R.drawable.ic_trakt
    override val requiresLogin = true
    override val hasPin = true
    override val createAccountUrl = "https://trakt.tv/auth/join"
    override val syncIdName = SyncIdName.Trakt
    override val supportedWatchTypes = setOf(
        SyncWatchType.NONE, SyncWatchType.WATCHING, SyncWatchType.COMPLETED,
        SyncWatchType.PLANTOWATCH, SyncWatchType.DROPPED
    )

    companion object {
        private const val TAG = "TraktApi"
        private const val REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
        private const val CACHE_MS = 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val clientId: String get() = BuildConfig.FLIMMER_TRAKT_ID.trim()
        private val clientSecret: String
            get() = BuildConfig.FLIMMER_TRAKT_SECRET.trim().takeIf { it.isNotBlank() && it != "null" } ?: ""

        data class TraktId(val isShow: Boolean, val idType: String, val value: String) {
            /** IDs für Trakt-Anfragen, ohne leere Felder */
            fun ids(): Map<String, Any> = when (idType) {
                "tmdb" -> mapOf("tmdb" to (value.toIntOrNull() ?: value))
                "imdb" -> mapOf("imdb" to value)
                else -> mapOf("trakt" to (value.toIntOrNull() ?: value))
            }

            fun matches(other: Ids?): Boolean = when (idType) {
                "tmdb" -> other?.tmdb?.toString() == value
                "imdb" -> other?.imdb == value
                else -> other?.trakt?.toString() == value
            }

            override fun toString() = "${if (isShow) "show" else "movie"}:$idType:$value"

            companion object {
                fun parse(id: String): TraktId? {
                    val parts = id.split(":")
                    if (parts.size != 3) return null
                    val show = when (parts[0]) {
                        "show" -> true
                        "movie" -> false
                        else -> return null
                    }
                    return TraktId(show, parts[1], parts[2])
                }
            }
        }
    }

    // ------------------------------------------------------------------ Modelle

    @Serializable
    data class Ids(
        @JsonProperty("trakt") @SerialName("trakt") val trakt: Int? = null,
        @JsonProperty("slug") @SerialName("slug") val slug: String? = null,
        @JsonProperty("imdb") @SerialName("imdb") val imdb: String? = null,
        @JsonProperty("tmdb") @SerialName("tmdb") val tmdb: Int? = null,
    )

    @Serializable
    data class Images(
        @JsonProperty("poster") @SerialName("poster") val poster: List<String> = emptyList(),
        @JsonProperty("fanart") @SerialName("fanart") val fanart: List<String> = emptyList(),
    )

    @Serializable
    data class Media(
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("year") @SerialName("year") val year: Int? = null,
        @JsonProperty("ids") @SerialName("ids") val ids: Ids = Ids(),
        @JsonProperty("overview") @SerialName("overview") val overview: String? = null,
        @JsonProperty("rating") @SerialName("rating") val rating: Double? = null,
        @JsonProperty("aired_episodes") @SerialName("aired_episodes") val airedEpisodes: Int? = null,
        @JsonProperty("runtime") @SerialName("runtime") val runtime: Int? = null,
        @JsonProperty("genres") @SerialName("genres") val genres: List<String> = emptyList(),
        @JsonProperty("images") @SerialName("images") val images: Images? = null,
    )

    @Serializable
    data class ListEntry(
        @JsonProperty("listed_at") @SerialName("listed_at") val listedAt: String? = null,
        @JsonProperty("last_watched_at") @SerialName("last_watched_at") val lastWatchedAt: String? = null,
        @JsonProperty("rated_at") @SerialName("rated_at") val ratedAt: String? = null,
        @JsonProperty("plays") @SerialName("plays") val plays: Int? = null,
        @JsonProperty("rating") @SerialName("rating") val rating: Int? = null,
        @JsonProperty("movie") @SerialName("movie") val movie: Media? = null,
        @JsonProperty("show") @SerialName("show") val show: Media? = null,
        @JsonProperty("seasons") @SerialName("seasons") val seasons: List<WatchedSeason> = emptyList(),
    ) {
        val media get() = movie ?: show
        val isShow get() = show != null
        fun watchedEpisodes(): Int = seasons.filter { it.number > 0 }.sumOf { s -> s.episodes.count { (it.plays ?: 0) > 0 } }
    }

    @Serializable
    data class WatchedSeason(
        @JsonProperty("number") @SerialName("number") val number: Int = 0,
        @JsonProperty("episodes") @SerialName("episodes") val episodes: List<WatchedEpisode> = emptyList(),
    )

    @Serializable
    data class WatchedEpisode(
        @JsonProperty("number") @SerialName("number") val number: Int = 0,
        @JsonProperty("plays") @SerialName("plays") val plays: Int? = null,
    )

    @Serializable
    data class DeviceCode(
        @JsonProperty("device_code") @SerialName("device_code") val deviceCode: String = "",
        @JsonProperty("user_code") @SerialName("user_code") val userCode: String = "",
        @JsonProperty("verification_url") @SerialName("verification_url") val verificationUrl: String = "https://trakt.tv/activate",
        @JsonProperty("expires_in") @SerialName("expires_in") val expiresIn: Int = 600,
        @JsonProperty("interval") @SerialName("interval") val interval: Int = 5,
    )

    @Serializable
    data class Token(
        @JsonProperty("access_token") @SerialName("access_token") val accessToken: String = "",
        @JsonProperty("refresh_token") @SerialName("refresh_token") val refreshToken: String = "",
        @JsonProperty("expires_in") @SerialName("expires_in") val expiresIn: Long = 0,
        @JsonProperty("created_at") @SerialName("created_at") val createdAt: Long = 0,
    ) {
        fun toAuthToken(): AuthToken {
            val created = if (createdAt > 0) createdAt else System.currentTimeMillis() / 1000
            return AuthToken(
                accessToken = accessToken,
                refreshToken = refreshToken,
                accessTokenLifetime = created + expiresIn,
            )
        }
    }

    @Serializable
    data class UserSettings(
        @JsonProperty("user") @SerialName("user") val user: User? = null,
    )

    @Serializable
    data class User(
        @JsonProperty("username") @SerialName("username") val username: String? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("ids") @SerialName("ids") val ids: Ids? = null,
        @JsonProperty("images") @SerialName("images") val images: UserImages? = null,
    )

    @Serializable
    data class UserImages(@JsonProperty("avatar") @SerialName("avatar") val avatar: Avatar? = null)

    @Serializable
    data class Avatar(@JsonProperty("full") @SerialName("full") val full: String? = null)

    @Serializable
    data class SeasonInfo(
        @JsonProperty("number") @SerialName("number") val number: Int = 0,
        @JsonProperty("episodes") @SerialName("episodes") val episodes: List<WatchedEpisode> = emptyList(),
    )

    @Serializable
    data class Progress(
        @JsonProperty("aired") @SerialName("aired") val aired: Int = 0,
        @JsonProperty("completed") @SerialName("completed") val completed: Int = 0,
        @JsonProperty("next_episode") @SerialName("next_episode") val nextEpisode: EpisodeRef? = null,
    )

    @Serializable
    data class EpisodeRef(
        @JsonProperty("season") @SerialName("season") val season: Int = 0,
        @JsonProperty("number") @SerialName("number") val number: Int = 0,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
    )

    /** Angefangene Filme und Folgen (Trakt merkt sich die Position beim Pausieren/Stoppen). */
    @Serializable
    data class PlaybackEntry(
        @JsonProperty("progress") @SerialName("progress") val progress: Double = 0.0,
        @JsonProperty("paused_at") @SerialName("paused_at") val pausedAt: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null,
        @JsonProperty("movie") @SerialName("movie") val movie: Media? = null,
        @JsonProperty("show") @SerialName("show") val show: Media? = null,
        @JsonProperty("episode") @SerialName("episode") val episode: EpisodeRef? = null,
    ) {
        fun asListEntry() = ListEntry(lastWatchedAt = pausedAt, movie = movie, show = show)
    }

    @Serializable
    data class SearchHit(
        @JsonProperty("type") @SerialName("type") val type: String? = null,
        @JsonProperty("movie") @SerialName("movie") val movie: Media? = null,
        @JsonProperty("show") @SerialName("show") val show: Media? = null,
    )

    // ------------------------------------------------------------------ HTTP

    private fun headers(token: String? = null): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("trakt-api-version", "2")
        put("trakt-api-key", clientId)
        put("User-Agent", "Flimmer/${BuildConfig.FLIMMER_BUILD}")
        if (token != null) put("Authorization", "Bearer $token")
    }

    private suspend fun get(path: String, token: String?) = app.get("$mainUrl$path", headers = headers(token))

    private suspend fun post(path: String, body: String, token: String? = null) =
        app.post("$mainUrl$path", headers = headers(token), requestBody = body.toRequestBody(JSON))

    private fun image(path: String?): String? =
        path?.let { if (it.startsWith("http")) it else "https://$it" }

    // ------------------------------------------------------------------ Anmeldung

    override suspend fun pinRequest(): AuthPinData? {
        val res = post("/oauth/device/code", mapOf("client_id" to clientId).toJson())
        if (!res.isSuccessful) throw IllegalStateException("Trakt HTTP ${res.code}")
        val code = parseJson<DeviceCode>(res.text)
        return AuthPinData(
            deviceCode = code.deviceCode,
            userCode = code.userCode,
            verificationUrl = code.verificationUrl,
            expiresIn = code.expiresIn,
            interval = code.interval,
        )
    }

    /** Wird periodisch aufgerufen; null solange der Code noch nicht bestätigt ist. */
    override suspend fun login(payload: AuthPinData): AuthToken? {
        val body = buildMap {
            put("code", payload.deviceCode)
            put("client_id", clientId)
            if (clientSecret.isNotEmpty()) put("client_secret", clientSecret)
        }.toJson()
        val res = post("/oauth/device/token", body)
        return when (res.code) {
            200 -> parseJson<Token>(res.text).toAuthToken()
            else -> null
        }
    }

    override suspend fun refreshToken(token: AuthToken): AuthToken? {
        val refresh = token.refreshToken ?: return null
        val body = buildMap {
            put("refresh_token", refresh)
            put("client_id", clientId)
            if (clientSecret.isNotEmpty()) put("client_secret", clientSecret)
            put("redirect_uri", REDIRECT)
            put("grant_type", "refresh_token")
        }.toJson()
        val res = post("/oauth/token", body)
        if (!res.isSuccessful) {
            Log.w(TAG, "refresh ${res.code}")
            return null
        }
        return parseJson<Token>(res.text).toAuthToken()
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        val access = token?.accessToken ?: return null
        val res = get("/users/settings", access)
        if (!res.isSuccessful) return null
        val user = parseJson<UserSettings>(res.text).user ?: return null
        val id = user.ids?.trakt ?: (user.ids?.slug ?: user.username ?: "trakt").hashCode()
        return AuthUser(
            name = user.name?.takeIf { it.isNotBlank() } ?: user.username,
            id = id,
            profilePicture = user.images?.avatar?.full,
        )
    }

    // ------------------------------------------------------------------ Listen (mit kurzem Zwischenspeicher)

    private data class Lists(
        val watchlist: List<ListEntry>,
        val watchedMovies: List<ListEntry>,
        val watchedShows: List<ListEntry>,
        val ratings: List<ListEntry>,
        val favorites: List<ListEntry>,
        /** Trakt-ID der Serie → (ausgestrahlt, gesehen), wie Trakt selbst es zählt */
        val progress: Map<Int, Progress>,
        val playback: List<PlaybackEntry>,
        /** Im Trakt-Fortschritt ausgeblendete Serien (Trakt-IDs), z.B. aus «Weiterschauen» entfernt */
        val hidden: Set<Int>,
        val time: Long,
    )

    private val listLock = Mutex()
    @Volatile private var cache: Pair<String, Lists>? = null

    private fun invalidate() {
        cache = null
        requireLibraryRefresh = true
    }

    private suspend fun fetchList(path: String, token: String): List<ListEntry> {
        val res = get(path, token)
        if (!res.isSuccessful) {
            Log.w(TAG, "$path ${res.code}")
            return emptyList()
        }
        return parseJson<List<ListEntry>>(res.text)
    }

    private suspend fun lists(auth: AuthData): Lists = listLock.withLock {
        val token = auth.token.accessToken ?: throw IllegalStateException("Nicht angemeldet")
        cache?.let { (owner, l) ->
            if (owner == token && System.currentTimeMillis() - l.time < CACHE_MS) return@withLock l
        }
        val l = coroutineScope {
            val wlM = async { fetchList("/sync/watchlist/movies?extended=full,images", token) }
            val wlS = async { fetchList("/sync/watchlist/shows?extended=full,images", token) }
            val wM = async { fetchList("/sync/watched/movies?extended=full,images", token) }
            val wS = async { fetchList("/sync/watched/shows?extended=full,images", token) }
            val rM = async { fetchList("/sync/ratings/movies", token) }
            val rS = async { fetchList("/sync/ratings/shows", token) }
            val fM = async { fetchList("/sync/favorites/movies?extended=full,images", token) }
            val fS = async { fetchList("/sync/favorites/shows?extended=full,images", token) }
            val pb = async {
                try {
                    val res = get("/sync/playback?extended=full,images&limit=100", token)
                    if (res.isSuccessful) parseJson<List<PlaybackEntry>>(res.text) else emptyList()
                } catch (t: Throwable) {
                    Log.w(TAG, "playback", t)
                    emptyList()
                }
            }
            val hid = async {
                try {
                    val res = get("/users/hidden/progress_watched?type=show&limit=1000", token)
                    if (res.isSuccessful) parseJson<List<ListEntry>>(res.text).mapNotNull { it.show?.ids?.trakt }.toSet() else emptySet()
                } catch (t: Throwable) {
                    emptySet()
                }
            }
            val shows = wS.await()
            Lists(
                watchlist = wlM.await() + wlS.await(),
                watchedMovies = wM.await(),
                watchedShows = shows,
                ratings = rM.await() + rS.await(),
                favorites = fM.await() + fS.await(),
                progress = progressFor(shows, token),
                playback = pb.await(),
                hidden = hid.await(),
                time = System.currentTimeMillis(),
            )
        }
        cache = token to l
        l
    }

    // ------------------------------------------------------------------ Fortschritt pro Serie

    private val progressSemaphore = kotlinx.coroutines.sync.Semaphore(6)
    private val progressFolder = "flimmer_trakt_progress2"

    /**
     * Fragt für jede gesehene Serie den Fortschritt bei Trakt ab (ausgestrahlte vs. gesehene Folgen).
     * Zwischengespeichert pro Serie und «zuletzt gesehen»-Zeitpunkt: neu geladen wird nur,
     * wenn seither etwas geschaut wurde oder der Eintrag älter als ein Tag ist.
     */
    private suspend fun progressFor(shows: List<ListEntry>, token: String): Map<Int, Progress> = coroutineScope {
        val now = System.currentTimeMillis()
        shows.mapNotNull { entry ->
            val id = entry.show?.ids?.trakt ?: return@mapNotNull null
            val stamp = entry.lastWatchedAt ?: ""
            async {
                val cached = com.lagradost.cloudstream3.CloudStreamApp.getKey<String>(progressFolder, id.toString())
                    ?.split("|", limit = 3)
                if (cached != null && cached.size == 3 && cached[0] == stamp &&
                    now - (cached[1].toLongOrNull() ?: 0L) < 24 * 3600_000L
                ) {
                    try {
                        return@async id to parseJson<Progress>(cached[2])
                    } catch (_: Throwable) {
                    }
                }
                progressSemaphore.withPermit {
                    try {
                        val res = get("/shows/$id/progress/watched?hidden=false&specials=false&count_specials=false", token)
                        if (!res.isSuccessful) return@withPermit null
                        val p = parseJson<Progress>(res.text)
                        com.lagradost.cloudstream3.CloudStreamApp.setKey(
                            progressFolder, id.toString(), "$stamp|$now|${p.toJson()}"
                        )
                        id to p
                    } catch (t: Throwable) {
                        Log.w(TAG, "progress $id", t)
                        null
                    }
                }
            }
        }.mapNotNull { it.await() }.toMap()
    }

    private fun isComplete(p: Progress?) = p != null && p.aired > 0 && p.completed >= p.aired

    // ------------------------------------------------------------------ Status

    override suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? {
        auth ?: return null
        val tid = TraktId.parse(id) ?: return null
        val l = lists(auth)
        fun List<ListEntry>.find() = firstOrNull { it.isShow == tid.isShow && tid.matches(it.media?.ids) }

        val inWatchlist = l.watchlist.find()
        val rating = l.ratings.find()?.rating
        val favorite = l.favorites.find() != null

        val (status, watched, max) = if (tid.isShow) {
            val w = l.watchedShows.find()
            val p = w?.show?.ids?.trakt?.let { l.progress[it] }
            val count = p?.completed ?: w?.watchedEpisodes() ?: 0
            val aired = p?.aired ?: w?.show?.airedEpisodes ?: inWatchlist?.show?.airedEpisodes
            val started = l.playback.any { it.show != null && tid.matches(it.show.ids) }
            val st = when {
                w != null && (isComplete(p) || (p == null && aired != null && aired > 0 && count >= aired)) -> SyncWatchType.COMPLETED
                count > 0 || started -> SyncWatchType.WATCHING
                inWatchlist != null -> SyncWatchType.PLANTOWATCH
                else -> SyncWatchType.NONE
            }
            Triple(st, count, aired)
        } else {
            val w = l.watchedMovies.find()
            val started = l.playback.any { it.movie != null && tid.matches(it.movie.ids) }
            val st = when {
                started -> SyncWatchType.WATCHING
                (w?.plays ?: 0) > 0 -> SyncWatchType.COMPLETED
                inWatchlist != null -> SyncWatchType.PLANTOWATCH
                else -> SyncWatchType.NONE
            }
            Triple(st, if (st == SyncWatchType.COMPLETED) 1 else 0, 1)
        }

        return SyncStatus(
            status = status,
            score = Score.from10(rating),
            watchedEpisodes = watched,
            isFavorite = favorite,
            maxEpisodes = max,
        )
    }

    private fun itemBody(tid: TraktId, extra: Map<String, Any>? = null): String {
        val item = buildMap<String, Any> {
            put("ids", tid.ids())
            extra?.let { putAll(it) }
        }
        return mapOf((if (tid.isShow) "shows" else "movies") to listOf(item)).toJson()
    }

    private suspend fun send(path: String, body: String, token: String) {
        val res = post(path, body, token)
        if (!res.isSuccessful) throw IllegalStateException("Trakt $path HTTP ${res.code}")
    }

    override suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean {
        auth ?: return false
        val token = auth.token.accessToken ?: return false
        val tid = TraktId.parse(id) ?: return false
        val old = status(auth, id) as? SyncStatus

        try {
            // Status
            if (old?.status != newStatus.status) {
                when (newStatus.status) {
                    SyncWatchType.PLANTOWATCH -> send("/sync/watchlist", itemBody(tid), token)
                    SyncWatchType.NONE -> send("/sync/watchlist/remove", itemBody(tid), token)
                    SyncWatchType.COMPLETED -> {
                        send("/sync/history", itemBody(tid), token) // Serie: alle ausgestrahlten Folgen
                        send("/sync/watchlist/remove", itemBody(tid), token)
                    }
                    SyncWatchType.WATCHING, SyncWatchType.REWATCHING ->
                        send("/sync/watchlist/remove", itemBody(tid), token)
                    SyncWatchType.DROPPED ->
                        if (tid.isShow) send("/users/hidden/dropped", itemBody(tid), token)
                    SyncWatchType.ONHOLD -> Unit
                }
            }

            // Folgenfortschritt bei Serien
            val newCount = newStatus.watchedEpisodes
            val oldCount = old?.watchedEpisodes ?: 0
            if (tid.isShow && newCount != null && newCount > oldCount && newStatus.status != SyncWatchType.COMPLETED) {
                markEpisodes(tid, oldCount, newCount, token)
            }

            // Bewertung
            val newRating = newStatus.score?.toInt(10)
            if (newRating != old?.score?.toInt(10)) {
                if (newRating != null && newRating in 1..10) {
                    send("/sync/ratings", itemBody(tid, mapOf("rating" to newRating)), token)
                } else if (old?.score != null) {
                    send("/sync/ratings/remove", itemBody(tid), token)
                }
            }

            // Favorit
            val fav = newStatus.isFavorite
            if (fav != null && fav != old?.isFavorite) {
                send(if (fav) "/sync/favorites" else "/sync/favorites/remove", itemBody(tid), token)
            }
        } finally {
            invalidate()
        }
        return true
    }

    /** Markiert die Folgen (from, to] in Ausstrahlungsreihenfolge als gesehen. */
    private suspend fun markEpisodes(tid: TraktId, from: Int, to: Int, token: String) {
        val traktId = resolveTraktId(tid, token) ?: return
        val res = get("/shows/$traktId/seasons?extended=episodes", token)
        if (!res.isSuccessful) return
        val ordered = parseJson<List<SeasonInfo>>(res.text)
            .filter { it.number > 0 }
            .sortedBy { it.number }
            .flatMap { s -> s.episodes.sortedBy { it.number }.map { s.number to it.number } }
        val pick = ordered.drop(from).take(to - from)
        if (pick.isEmpty()) return
        val seasons = pick.groupBy({ it.first }, { it.second }).map { (s, eps) ->
            mapOf("number" to s, "episodes" to eps.map { mapOf("number" to it) })
        }
        val body = mapOf("shows" to listOf(mapOf("ids" to tid.ids(), "seasons" to seasons))).toJson()
        send("/sync/history", body, token)
    }

    private suspend fun resolveTraktId(tid: TraktId, token: String): Int? {
        if (tid.idType == "trakt") return tid.value.toIntOrNull()
        val type = if (tid.isShow) "show" else "movie"
        val res = get("/search/${tid.idType}/${tid.value}?type=$type", token)
        if (!res.isSuccessful) return null
        return parseJson<List<SearchHit>>(res.text).firstNotNullOfOrNull { (it.show ?: it.movie)?.ids?.trakt }
    }

    // ------------------------------------------------------------------ Suche und Metadaten

    override suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult>? {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val res = get("/search/movie,show?query=$q&extended=images&limit=20", auth?.token?.accessToken)
        if (!res.isSuccessful) return null
        return parseJson<List<SearchHit>>(res.text).mapNotNull { hit ->
            val m = hit.movie ?: hit.show ?: return@mapNotNull null
            val isShow = hit.show != null
            val tmdb = m.ids.tmdb ?: return@mapNotNull null
            SyncSearchResult(
                name = m.title ?: return@mapNotNull null,
                apiName = this.name,
                syncId = TraktId(isShow, "tmdb", tmdb.toString()).toString(),
                url = "https://www.themoviedb.org/${if (isShow) "tv" else "movie"}/$tmdb",
                posterUrl = image(m.images?.poster?.firstOrNull()),
                type = if (isShow) TvType.TvSeries else TvType.Movie,
            )
        }
    }

    override suspend fun load(auth: AuthData?, id: String): SyncResult? {
        // Metadaten liefert der Flimmer Katalog (TMDB, Deutsch); hier nur die ID bestätigen
        return SyncResult(id = id)
    }

    override fun urlToId(url: String): String? {
        val m = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url) ?: return null
        return TraktId(m.groupValues[1] == "tv", "tmdb", m.groupValues[2]).toString()
    }

    // ------------------------------------------------------------------ Bibliothek

    private fun parseDate(s: String?): Long? = try {
        s?.let { java.time.Instant.parse(it).epochSecond }
    } catch (t: Throwable) {
        null
    }

    private fun yearDate(year: Int?): Date? = year?.let {
        Calendar.getInstance().apply { clear(); set(Calendar.YEAR, it) }.time
    }

    private fun ListEntry.toLibraryItem(
        ratings: Map<String, Int>,
        posters: Map<String, String>,
        completed: Int? = null,
        total: Int? = null,
    ): LibraryItem? {
        val m = media ?: return null
        val tmdb = m.ids.tmdb ?: return null
        val tid = TraktId(isShow, "tmdb", tmdb.toString())
        return LibraryItem(
            name = m.title ?: return null,
            url = "https://www.themoviedb.org/${if (isShow) "tv" else "movie"}/$tmdb",
            syncId = tid.toString(),
            episodesCompleted = completed,
            episodesTotal = total ?: m.airedEpisodes,
            personalRating = Score.from10(ratings[tid.toString()]),
            lastUpdatedUnixTime = parseDate(lastWatchedAt ?: listedAt),
            apiName = FlimmerKatalog.NAME, // öffnet die deutsche Detailseite des Katalogs
            type = if (isShow) TvType.TvSeries else TvType.Movie,
            // Deutsches Poster von TMDB, sonst das von Trakt
            posterUrl = posters[tid.toString()] ?: image(m.images?.poster?.firstOrNull()),
            posterHeaders = null,
            quality = null,
            releaseDate = yearDate(m.year),
            plot = m.overview,
            score = Score.from10(m.rating),
            tags = m.genres,
        )
    }

    override suspend fun library(auth: AuthData?): LibraryMetadata? {
        auth ?: return null
        val l = lists(auth)
        val ratings = l.ratings.mapNotNull { r ->
            val tmdb = r.media?.ids?.tmdb ?: return@mapNotNull null
            val rating = r.rating ?: return@mapNotNull null
            TraktId(r.isShow, "tmdb", tmdb.toString()).toString() to rating
        }.toMap()

        // Poster für alle Einträge (parallel, zwischengespeichert)
        val all = l.watchlist + l.watchedShows + l.watchedMovies + l.favorites
        val posters = coroutineScope {
            all.mapNotNull { e -> e.media?.ids?.tmdb?.let { e.isShow to it } }
                .distinct()
                .map { (isShow, tmdb) ->
                    async {
                        FlimmerKatalog.posterUrl(isShow, tmdb)?.let {
                            TraktId(isShow, "tmdb", tmdb.toString()).toString() to it
                        }
                    }
                }.mapNotNull { it.await() }.toMap()
        }

        val watching = mutableListOf<LibraryItem>()
        val completed = mutableListOf<LibraryItem>()
        val info = HashMap<String, String>()
        // Angefangene Folgen pro Serie (Trakt-ID → zuletzt pausierte Folge)
        val pausedEpisode = l.playback.filter { it.show != null && it.episode != null }
            .sortedByDescending { it.pausedAt ?: "" }
            .distinctBy { it.show?.ids?.trakt }
        for (s in l.watchedShows) {
            val p = s.show?.ids?.trakt?.let { l.progress[it] }
            val count = p?.completed ?: s.watchedEpisodes()
            val aired = p?.aired ?: s.show?.airedEpisodes
            val item = s.toLibraryItem(ratings, posters, count, aired) ?: continue
            val done = isComplete(p) || (p == null && aired != null && aired > 0 && count >= aired)
            val paused = pausedEpisode.firstOrNull { it.show?.ids?.trakt == s.show?.ids?.trakt }
            info[item.syncId] = when {
                paused?.episode != null -> "S${paused.episode.season} · F${paused.episode.number} angefangen"
                p?.nextEpisode != null && !done -> "Als Nächstes: S${p.nextEpisode.season} · F${p.nextEpisode.number}"
                aired != null && aired > 0 -> "$count von $aired Folgen"
                else -> "$count Folgen gesehen"
            }
            // Neue Folgen erschienen: wieder unter «Am schauen»; ausgeblendete Serien nur, wenn angefangen
            when {
                done && paused == null -> completed += item
                paused == null && s.show?.ids?.trakt in l.hidden -> Unit
                else -> watching += item
            }
        }
        // Serien, deren erste Folge angefangen ist (noch keine ganz gesehen)
        for (pb in pausedEpisode) {
            if (l.watchedShows.any { it.show?.ids?.trakt == pb.show?.ids?.trakt }) continue
            val item = pb.asListEntry().toLibraryItem(ratings, posters, 0, pb.show?.airedEpisodes) ?: continue
            info[item.syncId] = "S${pb.episode?.season} · F${pb.episode?.number} angefangen"
            watching += item
        }
        // Angefangene Filme: Fortschritt in Prozent
        for (pb in l.playback.filter { it.movie != null }.distinctBy { it.movie?.ids?.trakt }) {
            val pct = pb.progress.toInt().coerceIn(1, 99)
            val item = pb.asListEntry().toLibraryItem(ratings, posters, pct, 100) ?: continue
            val runtime = pb.movie?.runtime
            info[item.syncId] = if (runtime != null && runtime > 0)
                "Noch ${((100 - pct) * runtime / 100).coerceAtLeast(1)} Min." else "$pct % geschaut"
            watching += item
        }
        // Was angefangen ist, steht nicht mehr unter «Geplant» bzw. «Abgeschlossen»
        val watchingIds = watching.map { it.syncId }.toSet()
        for (m in l.watchedMovies) {
            val item = m.toLibraryItem(ratings, posters, 1, 1) ?: continue
            if (item.syncId in watchingIds) continue
            info[item.syncId] = "Gesehen" + ((m.plays ?: 0).takeIf { it > 1 }?.let { " · $it×" } ?: "")
            completed += item
        }
        val planned = l.watchlist.mapNotNull { it.toLibraryItem(ratings, posters) }.filter { it.syncId !in watchingIds }
        com.lagradost.cloudstream3.flimmer.FlimmerLibraryInfo.update(info)

        val lists = listOf(
            LibraryList(txt(R.string.type_plan_to_watch), planned),
            LibraryList(txt(R.string.type_watching), watching),
            LibraryList(txt(R.string.type_completed), completed),
            LibraryList(txt(R.string.favorites_list_name), l.favorites.mapNotNull { it.toLibraryItem(ratings, posters) }),
        )
        return LibraryMetadata(
            lists,
            setOf(
                ListSorting.UpdatedNew, ListSorting.UpdatedOld,
                ListSorting.AlphabeticalA, ListSorting.AlphabeticalZ,
                ListSorting.RatingHigh, ListSorting.RatingLow,
                ListSorting.ReleaseDateNew, ListSorting.ReleaseDateOld,
            )
        )
    }
}
