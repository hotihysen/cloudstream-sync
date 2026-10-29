package ch.hotihysen.flimmer.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Zugriff auf den Flimmer Katalog und die Erweiterungen, ohne Oberfläche. */
object Catalog {
    val modes = listOf(
        FlimmerKatalog.Mode.ALL to "Alles",
        FlimmerKatalog.Mode.SERIES to "Serien",
        FlimmerKatalog.Mode.MOVIES to "Filme",
        FlimmerKatalog.Mode.ANIME to "Anime",
    )

    private val katalogs = FlimmerKatalog.Mode.entries.associateWith { FlimmerKatalog(it) }

    fun katalog(mode: FlimmerKatalog.Mode): FlimmerKatalog = katalogs.getValue(mode)

    /** Kataloge wie Erweiterungen anmelden, damit sie über ihren Namen gefunden werden. */
    fun register() {
        for (k in katalogs.values) {
            if (APIHolder.allProviders.none { it.name == k.name }) {
                APIHolder.allProviders.add(k)
                APIHolder.addPluginMapping(k)
            }
        }
    }

    fun api(name: String?): MainAPI? = APIHolder.getApiFromNameNull(name)

    private val rowLimit = Semaphore(5)

    suspend fun row(api: MainAPI, request: MainPageRequest): HomePageList? = withContext(Dispatchers.IO) {
        rowLimit.withPermit {
            try {
                api.getMainPage(1, request)?.items?.firstOrNull()
            } catch (t: Throwable) {
                System.err.println("Reihe ${request.name}: ${t.message}")
                null
            }
        }
    }

    suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        katalog(FlimmerKatalog.Mode.ALL).search(query)
    }

    suspend fun load(apiName: String, url: String): LoadResponse? = withContext(Dispatchers.IO) {
        api(apiName)?.load(url)
    }

    // ---------------------------------------------------------------- IDs wie in der Android-App

    /** Entspricht LoadResponse.getId() der App: gleiche IDs, damit der Sync passt. */
    fun mainId(apiName: String, url: String): Int {
        val mainUrl = api(apiName)?.mainUrl ?: ""
        return url.replace(mainUrl, "").replace("/", "").hashCode()
    }

    fun mainId(load: LoadResponse): Int = mainId(load.apiName, load.uniqueUrl)

    data class Ep(
        val id: Int,
        val season: Int?,
        val episode: Int,
        val name: String?,
        val description: String?,
        val poster: String?,
        val data: String,
        val runTime: Int?,
    )

    /** Folgen mit den gleichen IDs wie in der App (Serien). */
    fun episodes(load: LoadResponse): List<Ep> {
        val mainId = mainId(load)
        return when (load) {
            is TvSeriesLoadResponse -> load.episodes
                .sortedBy { (it.season?.times(10_000) ?: 0) + (it.episode ?: 0) }
                .mapIndexed { index, e -> e.toEp(mainId + (e.season?.times(100_000) ?: 0) + (e.episode ?: (index + 1)) + 1, index) }
                .distinctBy { it.id }
            is AnimeLoadResponse -> {
                val out = mutableListOf<Ep>()
                load.episodes.forEach { (dub, list) ->
                    list.forEachIndexed { index, e ->
                        val ep = e.episode ?: (index + 1)
                        out += e.toEp(mainId + ep + dub.id * 1_000_000 + (e.season?.times(10_000) ?: 0), index)
                    }
                }
                out.distinctBy { it.season to it.episode }
            }
            else -> emptyList()
        }
    }

    private fun Episode.toEp(id: Int, index: Int) =
        Ep(id, season, episode ?: (index + 1), name, description, posterUrl, data, runTime)

    fun movieData(load: LoadResponse): String? = (load as? MovieLoadResponse)?.dataUrl

    /** (istSerie, TMDB-ID) bei Titeln aus dem Katalog, für Trakt */
    fun tmdbOf(load: LoadResponse): Pair<Boolean, Int>? {
        if (!FlimmerKatalog.isKatalog(load.apiName)) return null
        val isTv = load.url.contains("/tv/")
        val id = load.url.substringAfterLast("/").substringBefore("?").toIntOrNull() ?: return null
        return isTv to id
    }

    fun bookmarkOf(load: LoadResponse) = Sync.Bookmark(
        bookmarkedTime = System.currentTimeMillis(),
        id = mainId(load),
        name = load.name,
        url = load.url,
        apiName = load.apiName,
        type = load.type.name,
        posterUrl = load.posterUrl,
        year = load.year,
        plot = load.plot,
        tags = load.tags,
    )
}
