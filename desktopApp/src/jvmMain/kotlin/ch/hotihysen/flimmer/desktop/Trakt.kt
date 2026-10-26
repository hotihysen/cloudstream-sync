package ch.hotihysen.flimmer.desktop

import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Trakt unter Windows: Anmeldung mit Code, Watchlist, Scrobbeln. */
object Trakt {
    private const val API = "https://api.trakt.tv"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val clientId: String get() = BuildConfig.FLIMMER_TRAKT_ID.trim()
    val isConfigured get() = clientId.isNotBlank()

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
    data class Ids(@SerialName("tmdb") val tmdb: Int? = null)

    @Serializable
    data class Media(@SerialName("ids") val ids: Ids? = null)

    @Serializable
    data class WatchItem(
        @SerialName("type") val type: String? = null,
        @SerialName("movie") val movie: Media? = null,
        @SerialName("show") val show: Media? = null,
    )

    val isLoggedIn: Boolean get() = Store.getString("trakt/access") != null
    val userName: String? get() = Store.getString("trakt/user")

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
                        val s = app.get("$API/users/settings", headers = headers(Store.getString("trakt/access")))
                        val u = json.decodeFromString(Settings.serializer(), s.text).user
                        Store.putString("trakt/user", u?.name?.takeIf { it.isNotBlank() } ?: u?.username)
                    } catch (_: Throwable) {
                    }
                    return true
                }
                400 -> continue // noch nicht bestätigt
                else -> return false
            }
        }
        return false
    }

    private fun saveToken(t: Token) {
        Store.putString("trakt/access", t.accessToken)
        Store.putString("trakt/refresh", t.refreshToken)
        Store.putLong("trakt/expires", (t.createdAt + t.expiresIn) * 1000L)
    }

    fun logout() {
        listOf("trakt/access", "trakt/refresh", "trakt/expires", "trakt/user").forEach { Store.putString(it, null) }
    }

    private suspend fun token(): String? {
        val access = Store.getString("trakt/access") ?: return null
        val expires = Store.getLong("trakt/expires", 0)
        if (expires > 0 && System.currentTimeMillis() > expires - 86_400_000L) {
            val refresh = Store.getString("trakt/refresh") ?: return access
            val body = buildJsonObject {
                put("refresh_token", refresh)
                put("client_id", clientId)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                put("grant_type", "refresh_token")
            }.toString()
            val res = app.post("$API/oauth/token", headers = headers(null), requestBody = body.toRequestBody(JSON_TYPE))
            if (res.isSuccessful) {
                saveToken(json.decodeFromString(Token.serializer(), res.text))
                return Store.getString("trakt/access")
            }
        }
        return access
    }

    /** (istSerie, TMDB-ID) der Watchlist */
    suspend fun watchlist(limit: Int = 40): List<Pair<Boolean, Int>> {
        val t = token() ?: return emptyList()
        val res = app.get("$API/sync/watchlist?sort=added,desc", headers = headers(t))
        if (!res.isSuccessful) return emptyList()
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WatchItem.serializer()), res.text)
            .mapNotNull { w ->
                when (w.type) {
                    "movie" -> w.movie?.ids?.tmdb?.let { false to it }
                    "show" -> w.show?.ids?.tmdb?.let { true to it }
                    else -> null
                }
            }.take(limit)
    }

    /** Scrobbeln: start / pause / stop. Ab 80 % markiert Trakt den Titel als gesehen. */
    fun scrobble(action: String, isTv: Boolean, tmdbId: Int, season: Int?, episode: Int?, progress: Double) {
        if (!isLoggedIn || !Store.getBoolean("trakt/scrobble", true)) return
        scope.launch {
            try {
                val t = token() ?: return@launch
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
                app.post("$API/scrobble/$action", headers = headers(t), requestBody = body.toRequestBody(JSON_TYPE))
            } catch (t: Throwable) {
                System.err.println("Trakt scrobble: ${t.message}")
            }
        }
    }
}
