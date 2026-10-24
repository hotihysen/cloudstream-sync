package com.lagradost.cloudstream3.flimmer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Trakt-Anbindung für Flimmer.
 *
 * - Anmeldung per Gerätecode (trakt.tv/activate), ohne Client Secret (Trakt-PKCE-App).
 * - Scrobbeln: Titel aus dem Flimmer Katalog werden beim Schauen an Trakt gemeldet und
 *   ab 80 % als gesehen markiert.
 * - Watchlist: liefert die TMDB-IDs der Trakt-Watchlist für die Startseite.
 */
object FlimmerTrakt {
    private const val TAG = "FlimmerTrakt"
    private const val API = "https://api.trakt.tv"
    private const val PREFS = "flimmer_trakt"
    private const val K_ACCESS = "access"
    private const val K_REFRESH = "refresh"
    private const val K_EXPIRES = "expires"
    private const val K_USER = "user"
    private const val K_SCROBBLE = "scrobble"
    private const val REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val clientId: String get() = BuildConfig.FLIMMER_TRAKT_ID.trim()
    private val clientSecret: String get() = BuildConfig.FLIMMER_TRAKT_SECRET.trim()
    val isConfigured: Boolean get() = clientId.isNotBlank() && clientId != "null"

    private val ctx: Context? get() = CloudStreamApp.context
    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = ctx?.let { prefs(it).getString(K_ACCESS, null) } != null
    fun userName(): String? = ctx?.let { prefs(it).getString(K_USER, null) }

    var scrobbleEnabled: Boolean
        get() = ctx?.let { prefs(it).getBoolean(K_SCROBBLE, true) } ?: false
        set(value) {
            ctx?.let { c -> prefs(c).edit { putBoolean(K_SCROBBLE, value) } }
        }

    // ------------------------------------------------------------------ Modelle

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
    )

    @Serializable
    data class Ids(
        @JsonProperty("tmdb") @SerialName("tmdb") val tmdb: Int? = null,
        @JsonProperty("imdb") @SerialName("imdb") val imdb: String? = null,
    )

    @Serializable
    data class Media(@JsonProperty("ids") @SerialName("ids") val ids: Ids = Ids())

    @Serializable
    data class EpisodeRef(
        @JsonProperty("season") @SerialName("season") val season: Int,
        @JsonProperty("number") @SerialName("number") val number: Int,
    )

    @Serializable
    data class ScrobbleBody(
        @JsonProperty("movie") @SerialName("movie") val movie: Media? = null,
        @JsonProperty("show") @SerialName("show") val show: Media? = null,
        @JsonProperty("episode") @SerialName("episode") val episode: EpisodeRef? = null,
        @JsonProperty("progress") @SerialName("progress") val progress: Double,
    )

    @Serializable
    data class WatchlistItem(
        @JsonProperty("type") @SerialName("type") val type: String = "",
        @JsonProperty("movie") @SerialName("movie") val movie: Media? = null,
        @JsonProperty("show") @SerialName("show") val show: Media? = null,
    )

    @Serializable
    data class UserSettings(@JsonProperty("user") @SerialName("user") val user: User? = null)

    @Serializable
    data class User(@JsonProperty("username") @SerialName("username") val username: String? = null)

    // ------------------------------------------------------------------ HTTP

    private fun headers(token: String? = null): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("trakt-api-version", "2")
        put("trakt-api-key", clientId)
        put("User-Agent", "Flimmer/${BuildConfig.FLIMMER_BUILD}")
        if (token != null) put("Authorization", "Bearer $token")
    }

    private suspend fun post(path: String, body: String, token: String? = null) =
        app.post("$API$path", headers = headers(token), requestBody = body.toRequestBody(JSON))

    /** Gültiges Zugriffstoken, bei Bedarf erneuert; null wenn nicht angemeldet. */
    private suspend fun accessToken(): String? {
        val c = ctx ?: return null
        val p = prefs(c)
        val access = p.getString(K_ACCESS, null) ?: return null
        val expires = p.getLong(K_EXPIRES, 0)
        if (System.currentTimeMillis() / 1000 < expires - 3600) return access
        val refresh = p.getString(K_REFRESH, null) ?: return access
        return try {
            val body = buildMap {
                put("refresh_token", refresh)
                put("client_id", clientId)
                if (clientSecret.isNotBlank() && clientSecret != "null") put("client_secret", clientSecret)
                put("redirect_uri", REDIRECT)
                put("grant_type", "refresh_token")
            }.toJson()
            val res = post("/oauth/token", body)
            if (!res.isSuccessful) {
                Log.w(TAG, "Token-Erneuerung fehlgeschlagen: ${res.code} ${res.text}")
                if (res.code == 400 || res.code == 401) logout()
                return null
            }
            saveToken(c, parseJson<Token>(res.text))
            prefs(c).getString(K_ACCESS, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Token-Erneuerung", t)
            access
        }
    }

    private fun saveToken(c: Context, t: Token) {
        val created = if (t.createdAt > 0) t.createdAt else System.currentTimeMillis() / 1000
        prefs(c).edit {
            putString(K_ACCESS, t.accessToken)
            putString(K_REFRESH, t.refreshToken)
            putLong(K_EXPIRES, created + t.expiresIn)
        }
    }

    fun logout() {
        ctx?.let { c -> prefs(c).edit { remove(K_ACCESS); remove(K_REFRESH); remove(K_EXPIRES); remove(K_USER) } }
    }

    // ------------------------------------------------------------------ Anmeldung per Gerätecode

    suspend fun requestDeviceCode(): DeviceCode {
        if (!isConfigured) throw IllegalStateException("Trakt-Client-ID fehlt (Secret FLIMMER)")
        val res = post("/oauth/device/code", mapOf("client_id" to clientId).toJson())
        if (!res.isSuccessful) throw IllegalStateException("Trakt HTTP ${res.code}")
        return parseJson<DeviceCode>(res.text)
    }

    /**
     * Wartet, bis der Code auf trakt.tv bestätigt wurde.
     * @return null bei Erfolg, sonst eine Fehlermeldung
     */
    suspend fun pollDeviceToken(code: DeviceCode, isCancelled: () -> Boolean): String? {
        val c = ctx ?: return "Kein Kontext"
        val until = System.currentTimeMillis() + code.expiresIn * 1000L
        var interval = code.interval.coerceAtLeast(1) * 1000L
        while (System.currentTimeMillis() < until) {
            delay(interval)
            if (isCancelled()) return "Abgebrochen"
            val body = buildMap {
                put("code", code.deviceCode)
                put("client_id", clientId)
                if (clientSecret.isNotBlank() && clientSecret != "null") put("client_secret", clientSecret)
            }.toJson()
            val res = try {
                post("/oauth/device/token", body)
            } catch (t: Throwable) {
                continue
            }
            when (res.code) {
                200 -> {
                    saveToken(c, parseJson<Token>(res.text))
                    loadUserName()
                    return null
                }
                400 -> Unit // noch nicht bestätigt
                429 -> interval += 1000L // zu schnell
                404 -> return "Code ungültig"
                409 -> return "Code wurde bereits verwendet"
                410 -> return "Code abgelaufen, bitte neu verbinden"
                418 -> return "Anmeldung auf trakt.tv abgelehnt"
                else -> Log.w(TAG, "device/token ${res.code}")
            }
        }
        return "Code abgelaufen, bitte neu verbinden"
    }

    private suspend fun loadUserName() {
        val c = ctx ?: return
        val token = accessToken() ?: return
        try {
            val res = app.get("$API/users/settings", headers = headers(token))
            if (res.isSuccessful) {
                parseJson<UserSettings>(res.text).user?.username?.let { name ->
                    prefs(c).edit { putString(K_USER, name) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "users/settings", t)
        }
    }

    // ------------------------------------------------------------------ Scrobbeln

    private data class Active(val id: Int, val body: (Double) -> ScrobbleBody, var started: Boolean, var stopped: Boolean, var lastProgress: Double)

    @Volatile private var active: Active? = null

    /** Wird bei jedem Speichern der Wiedergabeposition aufgerufen (DataStoreHelper). */
    fun onProgress(meta: Any?, position: Long, duration: Long) {
        if (!isConfigured || !scrobbleEnabled || !isLoggedIn() || duration <= 0) return
        val ep = meta as? ResultEpisode ?: return
        if (ep.apiName != FlimmerKatalog.NAME) return
        val link = try {
            parseJson<FlimmerKatalog.LinkData>(ep.data)
        } catch (t: Throwable) {
            return
        }
        val progress = (position * 100.0 / duration).coerceIn(0.0, 100.0)

        var a = active
        if (a == null || a.id != ep.id) {
            // Vorherigen Titel pausieren, wenn er nicht fertig war
            a?.let { old -> if (old.started && !old.stopped) send("pause", old.body(old.lastProgress)) }
            val make: (Double) -> ScrobbleBody = if (link.isTv && link.season != null && link.episode != null) {
                { p -> ScrobbleBody(show = Media(Ids(tmdb = link.tmdbId)), episode = EpisodeRef(link.season, link.episode), progress = p) }
            } else {
                { p -> ScrobbleBody(movie = Media(Ids(tmdb = link.tmdbId, imdb = link.imdbId)), progress = p) }
            }
            a = Active(ep.id, make, started = false, stopped = false, lastProgress = progress)
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

    private fun send(action: String, body: ScrobbleBody) {
        scope.launch {
            try {
                val token = accessToken() ?: return@launch
                val res = post("/scrobble/$action", body.toJson(), token)
                if (!res.isSuccessful && res.code != 409) Log.w(TAG, "scrobble/$action ${res.code}")
            } catch (t: Throwable) {
                Log.w(TAG, "scrobble/$action", t)
            }
        }
    }

    // ------------------------------------------------------------------ Watchlist

    /** Paare (istSerie, TMDB-ID) aus der Trakt-Watchlist, neueste zuerst. */
    suspend fun watchlist(limit: Int = 40): List<Pair<Boolean, Int>> {
        val token = accessToken() ?: return emptyList()
        val items = mutableListOf<WatchlistItem>()
        for (type in listOf("movies", "shows")) {
            val res = app.get("$API/sync/watchlist/$type/added?limit=$limit", headers = headers(token))
            if (res.isSuccessful) items += parseJson<List<WatchlistItem>>(res.text)
        }
        return items.mapNotNull { w ->
            when (w.type) {
                "movie" -> w.movie?.ids?.tmdb?.let { false to it }
                "show" -> w.show?.ids?.tmdb?.let { true to it }
                else -> null
            }
        }.take(limit)
    }
}
