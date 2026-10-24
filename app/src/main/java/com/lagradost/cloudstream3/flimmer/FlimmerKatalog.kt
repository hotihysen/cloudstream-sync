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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Flimmer Katalog: eine Gesamtübersicht unabhängig von den einzelnen Hosts.
 *
 * - Startseite, Suche und Detailseiten kommen von TMDB, auf Deutsch.
 * - Beim Abspielen sucht der Katalog den Titel bei allen installierten Erweiterungen,
 *   öffnet die passenden Treffer und reicht deren Streams an den Player weiter.
 */
class FlimmerKatalog : MainAPI() {
    companion object {
        const val NAME = "Flimmer Katalog"
        private const val TAG = "FlimmerKatalog"
        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"
        private const val LANG = "de-DE"
        private const val REGION = "CH"
        private const val PROVIDER_TIMEOUT_MS = 30_000L

        val apiKey: String get() = BuildConfig.TMDB_API_KEY.trim()
        val isConfigured: Boolean get() = apiKey.isNotBlank() && apiKey != "null"
    }

    override var name = NAME
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "de"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val loadLinksTimeoutMs: Long = 120_000L
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Cartoon, TvType.Documentary)

    override val mainPage = mainPageOf(
        "trending/all/week" to "Im Trend",
        "trakt:watchlist" to "Meine Trakt-Watchlist",
        "movie/now_playing?region=$REGION" to "Neu im Kino",
        "discover/movie?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=8" to "Filme auf Netflix",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=8" to "Serien auf Netflix",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=337" to "Serien auf Disney+",
        "discover/tv?sort_by=popularity.desc&watch_region=$REGION&with_watch_providers=119" to "Serien auf Prime Video",
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
    )

    @Serializable
    data class TmdbPage(
        @JsonProperty("page") @SerialName("page") val page: Int = 1,
        @JsonProperty("total_pages") @SerialName("total_pages") val totalPages: Int = 1,
        @JsonProperty("results") @SerialName("results") val results: List<TmdbItem> = emptyList(),
    )

    @Serializable
    data class TmdbGenre(@JsonProperty("name") @SerialName("name") val name: String = "")

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
    data class TmdbImages(@JsonProperty("logos") @SerialName("logos") val logos: List<TmdbImage> = emptyList())

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
            "tv" -> newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries, fix = false) {
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
        val horizontal = request.data.startsWith("trending")
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = horizontal),
            hasNext = data.page < data.totalPages
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val data = parseJson<TmdbPage>(tmdb("search/multi", "query=$q&include_adult=false"))
        return data.results.mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val id = url.substringAfterLast("/").substringBefore("?").toIntOrNull() ?: return null
        val kind = if (isTv) "tv" else "movie"
        val d = parseJson<TmdbDetail>(
            tmdb(
                "$kind/$id",
                "append_to_response=credits,videos,images,external_ids,recommendations" +
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

        if (!isTv) {
            val link = LinkData(id, false, title, original, year, imdbId = imdb)
            return newMovieLoadResponse(title, url, TvType.Movie, link.toJson()) {
                posterUrl = img(d.posterPath)
                backgroundPosterUrl = img(d.backdropPath, "original")
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
                val link = LinkData(id, true, title, original, year, e.seasonNumber, e.episodeNumber, imdb)
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
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = img(d.posterPath)
            backgroundPosterUrl = img(d.backdropPath, "original")
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

    /** Bester Treffer einer Erweiterung für den Titel, oder null. */
    private suspend fun findMatch(api: MainAPI, d: LinkData): SearchResponse? {
        val wanted = listOfNotNull(d.title, d.originalTitle).map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        for (query in listOfNotNull(d.title, d.originalTitle).distinct()) {
            val results = try {
                api.search(query, 1)?.items ?: emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
            val candidates = results.filter { typeFits(it, d.isTv) && normalize(it.name) in wanted }
            if (candidates.isEmpty()) continue
            val withYear = candidates.firstOrNull { r ->
                val y = searchYear(r)
                y != null && d.year != null && kotlin.math.abs(y - d.year) <= 1
            }
            val noYear = candidates.firstOrNull { searchYear(it) == null || d.year == null }
            return withYear ?: noYear ?: continue
        }
        return null
    }

    private fun episodeData(load: LoadResponse, d: LinkData): String? {
        if (!d.isTv) {
            return (load as? MovieLoadResponse)?.dataUrl?.takeIf { it.isNotBlank() }
        }
        val eps: List<Episode> = when (load) {
            is TvSeriesLoadResponse -> load.episodes
            // Deutsche Synchronfassung bevorzugen, wenn die Erweiterung trennt
            is AnimeLoadResponse -> load.episodes[DubStatus.Dubbed] ?: load.episodes.values.flatten()
            else -> return null
        }
        val s = d.season ?: return null
        val e = d.episode ?: return null
        eps.firstOrNull { it.season == s && it.episode == e }?.let { return it.data }
        // Erweiterungen ohne Staffelangabe: nur bei Staffel 1 sicher zuordenbar
        if (s == 1) eps.firstOrNull { it.season == null && it.episode == e }?.let { return it.data }
        return null
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
        coroutineScope {
            providers.map { api ->
                async {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        try {
                            val match = findMatch(api, d) ?: return@withTimeoutOrNull
                            val load = api.load(match.url) ?: return@withTimeoutOrNull
                            val epData = episodeData(load, d) ?: return@withTimeoutOrNull
                            api.loadLinks(epData, isCasting, subtitleCallback) { link ->
                                linkOwner[link.url] = api
                                found.incrementAndGet()
                                callback(link)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "${api.name}: ${t.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        return found.get() > 0
    }
}
