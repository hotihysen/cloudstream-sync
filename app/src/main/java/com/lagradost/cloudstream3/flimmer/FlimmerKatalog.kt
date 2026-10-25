package com.lagradost.cloudstream3.flimmer

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Flimmer Katalog: eine Gesamtübersicht unabhängig von den einzelnen Hosts.
 *
 * - Startseite, Suche und Detailseiten kommen von TMDB, auf Deutsch.
 * - Beim Abspielen sucht der Katalog den Titel bei allen installierten Erweiterungen,
 *   öffnet die passenden Treffer und reicht deren Streams an den Player weiter.
 */
/**
 * @param mode Bereich: alles, nur Serien, nur Filme oder nur Anime (eigene Reiter auf der Startseite)
 */
class FlimmerKatalog(private val mode: Mode = Mode.ALL) : MainAPI() {
    enum class Mode { ALL, SERIES, MOVIES, ANIME }

    companion object {
        const val NAME = "Flimmer Katalog"
        const val ANIME_NAME = "Flimmer Anime"
        const val SERIES_NAME = "Flimmer Serien"
        const val MOVIES_NAME = "Flimmer Filme"

        /** Gehört der Titel zu einem der Flimmer-Kataloge? */
        fun isKatalog(apiName: String?) =
            apiName == NAME || apiName == ANIME_NAME || apiName == SERIES_NAME || apiName == MOVIES_NAME

        /** Detailseite → textfreies Poster im Hochformat für die Hero-Karte auf dem Handy */
        private val heroPosters = ConcurrentHashMap<String, String>()
        fun heroPoster(url: String?): String? = url?.let { heroPosters[it] }

        /** Japanische Animation */
        private const val A = "with_genres=16&with_original_language=ja"
        private const val CRUNCHYROLL = 283

        private fun recentDate(days: Int): String {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return f.format(java.util.Date(System.currentTimeMillis() - days * 86_400_000L))
        }
        private const val TAG = "FlimmerKatalog"
        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"
        private const val LANG = "de-DE"
        private const val REGION = "CH"
        private const val PROVIDER_TIMEOUT_MS = 45_000L
        /** Nach dem ersten Treffer so lange auf bessere Quellen warten */
        private const val SETTLE_MS = 10_000L
        /** Nach einem sehr guten Treffer (Deutsch, mind. 1080p) nur noch kurz warten */
        private const val EXCELLENT_GRACE_MS = 2_500L

        val apiKey: String get() = BuildConfig.TMDB_API_KEY.trim()
        val isConfigured: Boolean get() = apiKey.isNotBlank() && apiKey != "null"

        private const val POSTER_FOLDER = "flimmer_tmdb_poster"
        private val posterSemaphore = kotlinx.coroutines.sync.Semaphore(8)

        /** Deutsches Poster (sonst Originalposter) eines Titels, dauerhaft zwischengespeichert. */
        suspend fun posterUrl(isTv: Boolean, tmdbId: Int): String? {
            if (!isConfigured) return null
            val key = "${if (isTv) "tv" else "movie"}/$tmdbId"
            com.lagradost.cloudstream3.CloudStreamApp.getKey<String>(POSTER_FOLDER, key.replace("/", "_"))
                ?.let { return it.ifEmpty { null } }
            return posterSemaphore.withPermit {
                try {
                    val bearer = apiKey.length > 40
                    val url = "$API/$key/images?include_image_language=de,en,null" + if (bearer) "" else "&api_key=$apiKey"
                    val headers = if (bearer) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
                    val res = app.get(url, headers = headers)
                    if (!res.isSuccessful) return@withPermit null
                    val posters = parseJson<TmdbPosters>(res.text).posters
                    val pick = posters.firstOrNull { it.lang == "de" } ?: posters.firstOrNull { it.lang == "en" }
                        ?: posters.firstOrNull()
                    val result = pick?.let { "$IMG/w342${it.filePath}" }
                    com.lagradost.cloudstream3.CloudStreamApp.setKey(POSTER_FOLDER, key.replace("/", "_"), result ?: "")
                    result
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }

    override var name = when (mode) {
        Mode.ALL -> NAME
        Mode.SERIES -> SERIES_NAME
        Mode.MOVIES -> MOVIES_NAME
        Mode.ANIME -> ANIME_NAME
    }
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "de"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val loadLinksTimeoutMs: Long = 120_000L
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Cartoon, TvType.Documentary)

    override val mainPage = if (mode == Mode.SERIES) mainPageOf(
        "trending/tv/week" to "Serien im Trend",
        "tv/on_the_air" to "Neue Folgen",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=8" to "Serien auf Netflix",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=337" to "Serien auf Disney+",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=119" to "Serien auf Prime Video",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=350" to "Serien auf Apple TV+",
        "tv/popular" to "Beliebte Serien",
        "tv/top_rated" to "Bestbewertete Serien",
        "discover/tv?with_genres=80&sort_by=popularity.desc" to "Krimi",
        "discover/tv?with_genres=18&sort_by=popularity.desc" to "Drama",
        "discover/tv?with_genres=35&sort_by=popularity.desc" to "Komödie",
        "discover/tv?with_genres=10759&sort_by=popularity.desc" to "Action & Abenteuer",
        "discover/tv?with_genres=10765&sort_by=popularity.desc" to "Sci-Fi & Fantasy",
        "discover/tv?with_genres=9648&sort_by=popularity.desc" to "Mystery",
        "discover/tv?with_origin_country=KR&sort_by=popularity.desc" to "K-Dramen",
        "discover/tv?with_genres=99&sort_by=popularity.desc" to "Dokuserien",
    ) else if (mode == Mode.MOVIES) mainPageOf(
        "trending/movie/week" to "Filme im Trend",
        "movie/now_playing?region=$REGION" to "Neu im Kino",
        "movie/upcoming?region=$REGION" to "Demnächst",
        "discover/movie?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=8" to "Filme auf Netflix",
        "discover/movie?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=337" to "Filme auf Disney+",
        "discover/movie?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=119" to "Filme auf Prime Video",
        "movie/popular" to "Beliebte Filme",
        "movie/top_rated" to "Bestbewertete Filme",
        "discover/movie?with_genres=28&sort_by=popularity.desc" to "Action",
        "discover/movie?with_genres=35&sort_by=popularity.desc" to "Komödie",
        "discover/movie?with_genres=53&sort_by=popularity.desc" to "Thriller",
        "discover/movie?with_genres=80&sort_by=popularity.desc" to "Krimi",
        "discover/movie?with_genres=27&sort_by=popularity.desc" to "Horror",
        "discover/movie?with_genres=878&sort_by=popularity.desc" to "Science-Fiction",
        "discover/movie?with_genres=10749&sort_by=popularity.desc" to "Romantik",
        "discover/movie?with_genres=10751&sort_by=popularity.desc" to "Familie",
        "discover/movie?with_genres=16&sort_by=popularity.desc" to "Animation",
    ) else if (mode == Mode.ANIME) mainPageOf(
        "discover/tv?$A&sort_by=popularity.desc&air_date.gte=${recentDate(45)}" to "Diese Saison",
        "discover/tv?$A&sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=$CRUNCHYROLL" to "Anime auf Crunchyroll",
        "discover/tv?$A&sort_by=popularity.desc" to "Beliebte Anime",
        "discover/tv?$A&sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=8" to "Anime auf Netflix",
        "discover/tv?$A&sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=337" to "Anime auf Disney+",
        "discover/tv?$A&sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=119" to "Anime auf Prime Video",
        "discover/tv?$A&sort_by=vote_average.desc&vote_count.gte=400" to "Bestbewertete Anime",
        "discover/movie?$A&sort_by=popularity.desc" to "Anime-Filme",
        "discover/tv?with_genres=16,10759&with_original_language=ja&sort_by=popularity.desc" to "Action & Abenteuer",
        "discover/tv?with_genres=16,10765&with_original_language=ja&sort_by=popularity.desc" to "Fantasy & Sci-Fi",
        "discover/tv?with_genres=16,35&with_original_language=ja&sort_by=popularity.desc" to "Komödie",
        "discover/tv?with_genres=16,18&with_original_language=ja&sort_by=popularity.desc" to "Drama",
        "discover/tv?with_genres=16,9648&with_original_language=ja&sort_by=popularity.desc" to "Mystery",
        "discover/tv?$A&sort_by=first_air_date.desc&vote_count.gte=20&first_air_date.lte=${recentDate(0)}" to "Neu erschienen",
    ) else mainPageOf(
        "trending/all/week" to "Im Trend",
        "trakt:watchlist" to "Meine Trakt-Watchlist",
        "movie/now_playing?region=$REGION" to "Neu im Kino",
        "discover/movie?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=8" to "Filme auf Netflix",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=8" to "Serien auf Netflix",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=337" to "Serien auf Disney+",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=119" to "Serien auf Prime Video",
        "discover/tv?$A&sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=$CRUNCHYROLL" to "Anime auf Crunchyroll",
        "movie/popular" to "Beliebte Filme",
        "tv/popular" to "Beliebte Serien",
        "movie/top_rated" to "Bestbewertete Filme",
        "tv/top_rated" to "Bestbewertete Serien",
        "discover/movie?with_genres=28&sort_by=popularity.desc" to "Action",
        "discover/movie?with_genres=35&sort_by=popularity.desc" to "Komödie",
        "discover/movie?with_genres=53&sort_by=popularity.desc" to "Thriller",
        "discover/movie?with_genres=27&sort_by=popularity.desc" to "Horror",
        "discover/movie?with_genres=878&sort_by=popularity.desc" to "Science-Fiction",
        "discover/movie?with_genres=10751&sort_by=popularity.desc" to "Familie",
        "discover/movie?with_genres=16&sort_by=popularity.desc" to "Animation",
        "discover/tv?with_genres=99&sort_by=popularity.desc" to "Dokumentationen",
    )

    // ------------------------------------------------------------------ TMDB-Modelle

    @Serializable
    data class TmdbItem(
        @JsonProperty("id") @SerialName("id") val id: Int = 0,
        @JsonProperty("media_type") @SerialName("media_type") val mediaType: String? = null,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("poster_path") @SerialName("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") @SerialName("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") @SerialName("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") @SerialName("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") @SerialName("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genre_ids") @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
        @JsonProperty("original_language") @SerialName("original_language") val originalLanguage: String? = null,
    )

    @Serializable
    data class TmdbPage(
        @JsonProperty("page") @SerialName("page") val page: Int = 1,
        @JsonProperty("total_pages") @SerialName("total_pages") val totalPages: Int = 1,
        @JsonProperty("results") @SerialName("results") val results: List<TmdbItem> = emptyList(),
    )

    @Serializable
    data class TmdbGenre(
        @JsonProperty("id") @SerialName("id") val id: Int = 0,
        @JsonProperty("name") @SerialName("name") val name: String = "",
    )

    @Serializable
    data class TmdbCast(
        @JsonProperty("name") @SerialName("name") val name: String = "",
        @JsonProperty("character") @SerialName("character") val character: String? = null,
        @JsonProperty("profile_path") @SerialName("profile_path") val profilePath: String? = null,
    )

    @Serializable
    data class TmdbCredits(@JsonProperty("cast") @SerialName("cast") val cast: List<TmdbCast> = emptyList())

    @Serializable
    data class TmdbVideo(
        @JsonProperty("key") @SerialName("key") val key: String = "",
        @JsonProperty("site") @SerialName("site") val site: String = "",
        @JsonProperty("type") @SerialName("type") val type: String = "",
    )

    @Serializable
    data class TmdbVideos(@JsonProperty("results") @SerialName("results") val results: List<TmdbVideo> = emptyList())

    @Serializable
    data class TmdbImage(
        @JsonProperty("file_path") @SerialName("file_path") val filePath: String = "",
        @JsonProperty("iso_639_1") @SerialName("iso_639_1") val lang: String? = null,
    )

    @Serializable
    data class TmdbPosters(@JsonProperty("posters") @SerialName("posters") val posters: List<TmdbImage> = emptyList())

    @Serializable
    data class TmdbImages(
        @JsonProperty("logos") @SerialName("logos") val logos: List<TmdbImage> = emptyList(),
        @JsonProperty("posters") @SerialName("posters") val posters: List<TmdbImage> = emptyList(),
    )

    @Serializable
    data class TmdbAltTitle(
        @JsonProperty("iso_3166_1") @SerialName("iso_3166_1") val country: String? = null,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
    )

    @Serializable
    data class TmdbAltTitles(
        @JsonProperty("titles") @SerialName("titles") val titles: List<TmdbAltTitle> = emptyList(),
        @JsonProperty("results") @SerialName("results") val results: List<TmdbAltTitle> = emptyList(),
    )

    @Serializable
    data class TmdbTranslationData(
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
    )

    @Serializable
    data class TmdbTranslation(
        @JsonProperty("iso_639_1") @SerialName("iso_639_1") val lang: String? = null,
        @JsonProperty("data") @SerialName("data") val data: TmdbTranslationData? = null,
    )

    @Serializable
    data class TmdbTranslations(
        @JsonProperty("translations") @SerialName("translations") val translations: List<TmdbTranslation> = emptyList(),
    )

    @Serializable
    data class TmdbExternalIds(@JsonProperty("imdb_id") @SerialName("imdb_id") val imdbId: String? = null)

    @Serializable
    data class TmdbSeasonInfo(
        @JsonProperty("season_number") @SerialName("season_number") val seasonNumber: Int = 0,
        @JsonProperty("episode_count") @SerialName("episode_count") val episodeCount: Int = 0,
    )

    @Serializable
    data class TmdbEpisode(
        @JsonProperty("episode_number") @SerialName("episode_number") val episodeNumber: Int = 0,
        @JsonProperty("season_number") @SerialName("season_number") val seasonNumber: Int = 0,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("overview") @SerialName("overview") val overview: String? = null,
        @JsonProperty("still_path") @SerialName("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") @SerialName("air_date") val airDate: String? = null,
        @JsonProperty("runtime") @SerialName("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") @SerialName("vote_average") val voteAverage: Double? = null,
    )

    @Serializable
    data class TmdbSeason(@JsonProperty("episodes") @SerialName("episodes") val episodes: List<TmdbEpisode> = emptyList())

    @Serializable
    data class TmdbDetail(
        @JsonProperty("id") @SerialName("id") val id: Int = 0,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("original_title") @SerialName("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") @SerialName("original_name") val originalName: String? = null,
        @JsonProperty("original_language") @SerialName("original_language") val originalLanguage: String? = null,
        @JsonProperty("overview") @SerialName("overview") val overview: String? = null,
        @JsonProperty("poster_path") @SerialName("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") @SerialName("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") @SerialName("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") @SerialName("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("runtime") @SerialName("runtime") val runtime: Int? = null,
        @JsonProperty("episode_run_time") @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
        @JsonProperty("vote_average") @SerialName("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genres") @SerialName("genres") val genres: List<TmdbGenre> = emptyList(),
        @JsonProperty("seasons") @SerialName("seasons") val seasons: List<TmdbSeasonInfo> = emptyList(),
        @JsonProperty("credits") @SerialName("credits") val credits: TmdbCredits? = null,
        @JsonProperty("videos") @SerialName("videos") val videos: TmdbVideos? = null,
        @JsonProperty("images") @SerialName("images") val images: TmdbImages? = null,
        @JsonProperty("external_ids") @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
        @JsonProperty("imdb_id") @SerialName("imdb_id") val imdbId: String? = null,
        @JsonProperty("recommendations") @SerialName("recommendations") val recommendations: TmdbPage? = null,
        @JsonProperty("alternative_titles") @SerialName("alternative_titles") val alternativeTitles: TmdbAltTitles? = null,
        @JsonProperty("translations") @SerialName("translations") val translations: TmdbTranslations? = null,
    )

    /** Daten für das Abspielen; landen als JSON in Episode.data bzw. dataUrl. */
    @Serializable
    data class LinkData(
        @JsonProperty("tmdb") @SerialName("tmdb") val tmdbId: Int,
        @JsonProperty("tv") @SerialName("tv") val isTv: Boolean,
        @JsonProperty("title") @SerialName("title") val title: String,
        @JsonProperty("orig") @SerialName("orig") val originalTitle: String? = null,
        @JsonProperty("year") @SerialName("year") val year: Int? = null,
        @JsonProperty("s") @SerialName("s") val season: Int? = null,
        @JsonProperty("e") @SerialName("e") val episode: Int? = null,
        @JsonProperty("imdb") @SerialName("imdb") val imdbId: String? = null,
        /** Weitere Titel für die Suche: englisch, Original in lateinischer Schrift, Alternativtitel */
        @JsonProperty("alt") @SerialName("alt") val altTitles: List<String> = emptyList(),
    )

    // ------------------------------------------------------------------ TMDB-Zugriff

    private suspend fun tmdb(path: String, extra: String = ""): String {
        if (!isConfigured) throw IllegalStateException("TMDB-Schlüssel fehlt (Secret TMDB_API_KEY)")
        val sep = if (path.contains("?")) "&" else "?"
        val bearer = apiKey.length > 40 // v4-Lesetoken statt v3-Schlüssel
        val url = buildString {
            append("$API/$path${sep}language=$LANG")
            if (extra.isNotEmpty()) append("&$extra")
            if (!bearer) append("&api_key=$apiKey")
        }
        val headers = if (bearer) mapOf("Authorization" to "Bearer $apiKey", "accept" to "application/json")
        else mapOf("accept" to "application/json")
        val res = app.get(url, headers = headers)
        if (!res.isSuccessful) throw IllegalStateException("TMDB HTTP ${res.code}")
        return res.text
    }

    private fun img(path: String?, size: String = "w500") = path?.let { "$IMG/$size$it" }
    private fun yearOf(date: String?) = date?.take(4)?.toIntOrNull()

    private fun TmdbItem.toSearch(forcedType: String? = null): SearchResponse? {
        val kind = forcedType ?: mediaType ?: return null
        val title = (title ?: name)?.takeIf { it.isNotBlank() } ?: return null
        return when (kind) {
            "movie" -> newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie, fix = false) {
                posterUrl = img(posterPath)
                year = yearOf(releaseDate)
                score = Score.from10(voteAverage)
            }
            "tv" -> newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", if (mode == Mode.ANIME) TvType.Anime else TvType.TvSeries, fix = false) {
                posterUrl = img(posterPath)
                year = yearOf(firstAirDate)
                score = Score.from10(voteAverage)
            }
            else -> null
        }
    }

    private fun typeFromPath(path: String): String? = when {
        path.startsWith("movie/") || path.startsWith("discover/movie") -> "movie"
        path.startsWith("tv/") || path.startsWith("discover/tv") -> "tv"
        else -> null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "trakt:watchlist") {
            val items = if (page > 1 || !FlimmerTrakt.isLoggedIn()) emptyList() else coroutineScope {
                FlimmerTrakt.watchlist().map { (isTv, id) ->
                    async {
                        try {
                            val kind = if (isTv) "tv" else "movie"
                            parseJson<TmdbItem>(tmdb("$kind/$id")).toSearch(kind)
                        } catch (t: Throwable) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            return newHomePageResponse(HomePageList(request.name, items), hasNext = false)
        }
        val data = parseJson<TmdbPage>(tmdb(request.data, "page=$page"))
        val type = typeFromPath(request.data)
        val items = data.results.mapNotNull { it.toSearch(type) }
        // Erste Reihe gross im Querformat, wie bei Streamingdiensten
        val horizontal = request.data == mainPage.first().data
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = horizontal),
            hasNext = data.page < data.totalPages
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val data = parseJson<TmdbPage>(tmdb("search/multi", "query=$q&include_adult=false"))
        val results = when (mode) {
            Mode.ANIME -> data.results.filter { it.originalLanguage == "ja" && 16 in it.genreIds }
            Mode.SERIES -> data.results.filter { it.mediaType == "tv" }
            Mode.MOVIES -> data.results.filter { it.mediaType == "movie" }
            Mode.ALL -> data.results
        }
        return results.mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val id = url.substringAfterLast("/").substringBefore("?").toIntOrNull() ?: return null
        val kind = if (isTv) "tv" else "movie"
        val d = parseJson<TmdbDetail>(
            tmdb(
                "$kind/$id",
                "append_to_response=credits,videos,images,external_ids,recommendations,alternative_titles,translations" +
                        "&include_image_language=de,en,null&include_video_language=de,en"
            )
        )
        val title = (d.title ?: d.name) ?: return null
        val original = (d.originalTitle ?: d.originalName)?.takeIf { it != title }
        val year = yearOf(d.releaseDate ?: d.firstAirDate)
        val imdb = d.imdbId ?: d.externalIds?.imdbId
        val logo = (d.images?.logos?.firstOrNull { it.lang == "de" } ?: d.images?.logos?.firstOrNull())
            ?.let { img(it.filePath, "w500") }
        val trailer = d.videos?.results
            ?.sortedByDescending { it.type == "Trailer" }
            ?.firstOrNull { it.site == "YouTube" && it.key.isNotBlank() }
            ?.let { "https://www.youtube.com/watch?v=${it.key}" }
        val recs = d.recommendations?.results?.mapNotNull { it.toSearch(kind) }
        val cast = d.credits?.cast?.take(20)?.map { Actor(it.name, img(it.profilePath, "w185")) to it.character }
        val alt = altTitles(d, title)
        // Textfreies Poster (ohne Sprache) für die Hero-Karte, sonst das deutsche
        (d.images?.posters?.firstOrNull { it.lang == null || it.lang == "xx" }
            ?: d.images?.posters?.firstOrNull { it.lang == "de" })
            ?.let { heroPosters[url] = "$IMG/w780${it.filePath}" }
        // Japanische Animation als Anime kennzeichnen (Sprungmarken über AniSkip, Skip-Knopf im Player)
        val isAnime = d.originalLanguage == "ja" && d.genres.any { it.id == 16 }

        if (!isTv) {
            val link = LinkData(id, false, title, original, year, imdbId = imdb, altTitles = alt)
            return newMovieLoadResponse(title, url, if (isAnime) TvType.AnimeMovie else TvType.Movie, link.toJson()) {
                posterUrl = img(d.posterPath)
                backgroundPosterUrl = img(d.backdropPath, "w1280")
                logoUrl = logo
                plot = d.overview
                this.year = year
                tags = d.genres.map { it.name }
                score = Score.from10(d.voteAverage)
                duration = d.runtime
                recommendations = recs
                addActors(cast)
                addImdbId(imdb)
                addTMDbId(id.toString())
                addTrailer(trailer)
            }
        }

        val episodes = mutableListOf<Episode>()
        for (s in d.seasons.filter { it.seasonNumber > 0 && it.episodeCount > 0 }) {
            val season = try {
                parseJson<TmdbSeason>(tmdb("tv/$id/season/${s.seasonNumber}"))
            } catch (t: Throwable) {
                Log.w(TAG, "Staffel ${s.seasonNumber} nicht geladen", t)
                continue
            }
            for (e in season.episodes) {
                val link = LinkData(id, true, title, original, year, e.seasonNumber, e.episodeNumber, imdb, alt)
                episodes += newEpisode(link.toJson()) {
                    name = e.name
                    this.season = e.seasonNumber
                    episode = e.episodeNumber
                    description = e.overview
                    posterUrl = img(e.stillPath, "w300")
                    runTime = e.runtime
                    score = Score.from10(e.voteAverage)
                    e.airDate?.let { addDate(it) }
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
            posterUrl = img(d.posterPath)
            backgroundPosterUrl = img(d.backdropPath, "w1280")
            logoUrl = logo
            plot = d.overview
            this.year = year
            tags = d.genres.map { it.name }
            score = Score.from10(d.voteAverage)
            duration = d.episodeRunTime.firstOrNull()
            recommendations = recs
            addActors(cast)
            addImdbId(imdb)
            addTMDbId(id.toString())
            addTrailer(trailer)
        }
    }

    // ------------------------------------------------------------------ Quellen bei allen Erweiterungen

    /** Welche Erweiterung hat welchen Stream geliefert (für Header/Interceptor beim Abspielen). */
    private val linkOwner = ConcurrentHashMap<String, MainAPI>()

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        linkOwner[extractorLink.url]?.getVideoInterceptor(extractorLink)

    private fun normalize(s: String?): String {
        if (s == null) return ""
        val t = s.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace("&", "und")
        return Normalizer.normalize(t, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    private val noiseSuffixes = listOf(
        "germandub", "gerdub", "germansub", "gersub", "deutsch", "german",
        "dubbed", "subbed", "dub", "sub", "omu", "uncensored", "tvseries", "tv"
    )

    /** Wie [normalize], ohne Artikel vorne und ohne Zusätze wie «German Dub», «TV», Jahreszahl. */
    private fun loose(s: String?): String {
        var n = normalize(s)
        for (article in listOf("the", "der", "die", "das")) {
            if (n.startsWith(article) && n.length > article.length + 3) n = n.removePrefix(article)
        }
        var changed = true
        while (changed) {
            changed = false
            n.replace(Regex("(19|20)\\d{2}$"), "").let { if (it != n && it.length > 2) { n = it; changed = true } }
            for (suf in noiseSuffixes) {
                if (n.endsWith(suf) && n.length > suf.length + 2) {
                    n = n.removeSuffix(suf); changed = true
                }
            }
        }
        return n
    }

    private fun seasonSuffixes(season: Int): List<String> {
        val ord = when (season) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return listOf("season$season", "staffel$season", "$season$ord" + "season", "s$season", "part$season", "$season")
    }

    /** Lateinische Schrift (für Suchanfragen sinnvoll). */
    private fun isLatin(s: String) = s.count { it.isLetter() && it.code < 0x250 } >= s.count { it.isLetter() } * 0.8

    private fun altTitles(d: TmdbDetail, germanTitle: String): List<String> {
        val out = linkedSetOf<String>()
        d.translations?.translations
            ?.firstOrNull { it.lang == "en" }?.data?.let { (it.title ?: it.name) }
            ?.takeIf { it.isNotBlank() }?.let { out += it }
        (d.originalTitle ?: d.originalName)?.takeIf { isLatin(it) }?.let { out += it }
        val alts = (d.alternativeTitles?.titles.orEmpty() + d.alternativeTitles?.results.orEmpty())
        val preferred = listOf("US", "GB", "JP", "DE", "AT", "CH")
        alts.sortedBy { a -> preferred.indexOf(a.country).let { if (it < 0) 99 else it } }
            .mapNotNull { it.title?.trim() }
            .filter { it.isNotBlank() && isLatin(it) }
            .forEach { out += it }
        out.remove(germanTitle)
        return out.take(8)
    }

    private fun searchYear(r: SearchResponse): Int? = when (r) {
        is MovieSearchResponse -> r.year
        is TvSeriesSearchResponse -> r.year
        else -> null
    }

    private fun typeFits(r: SearchResponse, isTv: Boolean): Boolean {
        val t = r.type ?: return true
        return if (isTv) t in setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AsianDrama, TvType.Documentary, TvType.OVA)
        else t in setOf(TvType.Movie, TvType.AnimeMovie, TvType.Cartoon, TvType.Documentary)
    }

    private data class Match(val result: SearchResponse, val seasonEntry: Boolean)

    /** Bester Treffer einer Erweiterung für den Titel, oder null. */
    private suspend fun findMatch(api: MainAPI, d: LinkData): Match? {
        val allTitles = (listOf(d.title) + listOfNotNull(d.originalTitle) + d.altTitles).distinct()
        val wanted = allTitles.map { loose(it) }.filter { it.length >= 2 }.toSet()
        val season = d.season
        // Anime-Seiten führen spätere Staffeln oft als eigenen Eintrag («… Season 2»)
        val wantedSeason = if (d.isTv && season != null && season > 1) {
            wanted.flatMap { w -> seasonSuffixes(season).map { w + it } }.toSet()
        } else emptySet()

        // Höchstens vier Suchanfragen pro Erweiterung: deutsch, englisch, original, ein Alternativtitel
        val queries = allTitles.take(4)
        for (query in queries) {
            val results = try {
                api.search(query, 1)?.items ?: emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
            val fitting = results.filter { typeFits(it, d.isTv) }
            fun yearOk(r: SearchResponse): Boolean {
                val y = searchYear(r) ?: return true
                val want = d.year ?: return true
                return kotlin.math.abs(y - want) <= 1
            }
            if (wantedSeason.isNotEmpty()) {
                fitting.firstOrNull { loose(it.name) in wantedSeason }?.let { return Match(it, true) }
            }
            val exact = fitting.filter { loose(it.name) in wanted }
            // Bei Serien kann das Jahr eines Eintrags von der ersten Staffel abweichen: Jahr nur als Vorzug
            (exact.firstOrNull { yearOk(it) } ?: if (d.isTv) exact.firstOrNull() else null)
                ?.let { return Match(it, false) }
        }
        return null
    }

    private fun episodeData(load: LoadResponse, d: LinkData, seasonEntry: Boolean): String? {
        if (!d.isTv) {
            return (load as? MovieLoadResponse)?.dataUrl?.takeIf { it.isNotBlank() }
        }
        val lists: List<List<Episode>> = when (load) {
            is TvSeriesLoadResponse -> listOf(load.episodes)
            // Erst Synchronfassung, dann Untertitel, dann alles andere
            is AnimeLoadResponse -> listOfNotNull(
                load.episodes[DubStatus.Dubbed],
                load.episodes[DubStatus.Subbed],
                load.episodes.values.flatten()
            )
            else -> return null
        }
        val s = d.season ?: return null
        val e = d.episode ?: return null
        for (eps in lists) {
            if (seasonEntry) {
                // Eigener Eintrag pro Staffel: Folgen sind dort meist ab 1 nummeriert
                eps.firstOrNull { (it.season == null || it.season == 1 || it.season == s) && it.episode == e }
                    ?.let { return it.data }
                continue
            }
            eps.firstOrNull { it.season == s && it.episode == e }?.let { return it.data }
            // Erweiterungen ohne Staffelangabe: nur bei Staffel 1 sicher zuordenbar
            if (s == 1) eps.firstOrNull { it.season == null && it.episode == e }?.let { return it.data }
        }
        return null
    }

    /** Name der Erweiterung voranstellen, damit in der Auswahl sichtbar ist, woher ein Stream kommt. */
    private suspend fun withSourceName(api: MainAPI, link: ExtractorLink): ExtractorLink {
        if (link.javaClass != ExtractorLink::class.java || link.name.startsWith(api.name)) return link
        return try {
            newExtractorLink(link.source, "${api.name} · ${link.name}", link.url, link.type) {
                referer = link.referer
                quality = link.quality
                headers = link.headers
                extractorData = link.extractorData
                audioTracks = link.audioTracks
            }
        } catch (t: Throwable) {
            link
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val d = parseJson<LinkData>(data)
        val wantedTypes = if (d.isTv) setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AsianDrama, TvType.Documentary)
        else setOf(TvType.Movie, TvType.AnimeMovie, TvType.Cartoon, TvType.Documentary)

        val providers = APIHolder.apis.withLock { APIHolder.apis.toList() }
            .filter { it !is FlimmerKatalog && it.supportedTypes.any { t -> t in wantedTypes } }
            .distinctBy { it.name }

        val found = AtomicInteger(0)
        val firstLinkAt = AtomicLong(0)
        val excellentAt = AtomicLong(0)
        val incoming = Channel<Pair<MainAPI, ExtractorLink>>(Channel.UNLIMITED)

        coroutineScope {
            // Alle Erweiterungen gleichzeitig abfragen
            val jobs = providers.map { api ->
                launch {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        try {
                            val match = findMatch(api, d) ?: return@withTimeoutOrNull
                            val load = api.load(match.result.url) ?: return@withTimeoutOrNull
                            val epData = episodeData(load, d, match.seasonEntry) ?: return@withTimeoutOrNull
                            api.loadLinks(epData, isCasting, subtitleCallback) { link ->
                                incoming.trySend(api to link)
                            }
                        } catch (t: kotlinx.coroutines.CancellationException) {
                            throw t
                        } catch (t: Throwable) {
                            Log.w(TAG, "${api.name}: ${t.message}")
                        }
                    }
                }
            }

            // Links aufbereiten: Qualität ermitteln, Herkunft im Namen, Bewertung registrieren
            val processor = launch {
                for ((api, raw) in incoming) {
                    launch {
                        FlimmerStreams.probeQuality(raw)
                        val link = withSourceName(api, raw)
                        linkOwner[link.url] = api
                        FlimmerStreams.register(link, api.name, api.lang)
                        val now = System.currentTimeMillis()
                        firstLinkAt.compareAndSet(0, now)
                        if (FlimmerStreams.isExcellent(link)) excellentAt.compareAndSet(0, now)
                        found.incrementAndGet()
                        callback(link)
                    }
                }
            }

            // Nicht ewig auf langsame Erweiterungen warten: nach einem sehr guten Treffer kurz,
            // sonst nach dem ersten Treffer noch einige Sekunden auf bessere Quellen warten
            while (true) {
                delay(250)
                if (jobs.all { it.isCompleted }) break
                val now = System.currentTimeMillis()
                val excellent = excellentAt.get()
                val first = firstLinkAt.get()
                if (excellent > 0 && now - excellent >= EXCELLENT_GRACE_MS) break
                if (first > 0 && now - first >= SETTLE_MS) break
            }
            jobs.forEach { it.cancel() }
            incoming.close()
            withTimeoutOrNull(5_000L) { processor.join() }
            processor.cancel()
        }
        return found.get() > 0
    }
}
