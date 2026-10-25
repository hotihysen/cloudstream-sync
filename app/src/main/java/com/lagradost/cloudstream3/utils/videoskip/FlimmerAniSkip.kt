package com.lagradost.cloudstream3.utils.videoskip

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sprungmarken (Opening, Ending, Recap) für Anime aus dem Flimmer Katalog.
 *
 * Der Katalog kennt nur TMDB-IDs, AniSkip braucht aber die MyAnimeList-ID der jeweiligen Staffel.
 * Ablauf: Titel bei AniList suchen (englisch, original, Alternativtitel), anhand des Jahres die
 * erste Staffel wählen, dann über die «Fortsetzung»-Verknüpfungen zur gewünschten Staffel bzw.
 * zum richtigen Abschnitt springen. Ergebnis wird pro Serie und Staffel dauerhaft gespeichert.
 */
class FlimmerAniSkip : SkipAPI() {
    override val name = "AniSkip"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val TAG = "FlimmerAniSkip"
        private const val ANILIST = "https://graphql.anilist.co"
        private const val CACHE = "flimmer_mal_map"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val SERIES_FORMATS = setOf("TV", "TV_SHORT", "ONA")

        private const val FIELDS = """
            id idMal format episodes startDate { year }
            relations { edges { relationType node { id idMal format type episodes startDate { year } } } }
        """
    }

    @Serializable
    data class Year(@JsonProperty("year") @SerialName("year") val year: Int? = null)

    @Serializable
    data class Node(
        @JsonProperty("id") @SerialName("id") val id: Int = 0,
        @JsonProperty("idMal") @SerialName("idMal") val idMal: Int? = null,
        @JsonProperty("format") @SerialName("format") val format: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null,
        @JsonProperty("episodes") @SerialName("episodes") val episodes: Int? = null,
        @JsonProperty("startDate") @SerialName("startDate") val startDate: Year? = null,
        @JsonProperty("relations") @SerialName("relations") val relations: Relations? = null,
    )

    @Serializable
    data class Relations(@JsonProperty("edges") @SerialName("edges") val edges: List<Edge> = emptyList())

    @Serializable
    data class Edge(
        @JsonProperty("relationType") @SerialName("relationType") val relationType: String? = null,
        @JsonProperty("node") @SerialName("node") val node: Node? = null,
    )

    @Serializable
    data class PageData(@JsonProperty("Page") @SerialName("Page") val page: PageMedia? = null)

    @Serializable
    data class PageMedia(@JsonProperty("media") @SerialName("media") val media: List<Node> = emptyList())

    @Serializable
    data class SearchResult(@JsonProperty("data") @SerialName("data") val data: PageData? = null)

    @Serializable
    data class MediaData(@JsonProperty("Media") @SerialName("Media") val media: Node? = null)

    @Serializable
    data class MediaResult(@JsonProperty("data") @SerialName("data") val data: MediaData? = null)

    private suspend fun graphql(query: String, variables: Map<String, Any>): String? {
        val body = mapOf("query" to query, "variables" to variables).toJson()
        val res = app.post(
            ANILIST,
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            requestBody = body.toRequestBody(JSON)
        )
        return if (res.isSuccessful) res.text else null
    }

    private suspend fun search(title: String, movie: Boolean): List<Node> {
        val q = "query(\$s:String){Page(perPage:8){media(search:\$s,type:ANIME,sort:SEARCH_MATCH){$FIELDS}}}"
        val text = graphql(q, mapOf("s" to title)) ?: return emptyList()
        return parseJson<SearchResult>(text).data?.page?.media.orEmpty()
            .filter { if (movie) it.format == "MOVIE" else it.format in SERIES_FORMATS }
    }

    private suspend fun media(id: Int): Node? {
        val q = "query(\$id:Int){Media(id:\$id,type:ANIME){$FIELDS}}"
        val text = graphql(q, mapOf("id" to id)) ?: return null
        return parseJson<MediaResult>(text).data?.media
    }

    private fun Node.sequel(): Node? = relations?.edges
        ?.firstOrNull { it.relationType == "SEQUEL" && it.node?.type == "ANIME" && it.node.format in SERIES_FORMATS }
        ?.node

    /** (MAL-ID, Folge innerhalb dieses Eintrags) für TMDB-Staffel/-Folge. */
    private suspend fun resolve(link: FlimmerKatalog.LinkData): Pair<Int, Int>? {
        val season = if (link.isTv) link.season ?: 1 else 1
        val episode = if (link.isTv) link.episode ?: 1 else 1
        val cacheKey = "${link.tmdbId}_${if (link.isTv) "tv" else "movie"}_s$season"

        // Gespeichert: Startpunkt der Staffel als «aniListId»
        var start: Node? = CloudStreamApp.getKey<Int>(CACHE, cacheKey)?.let { media(it) }

        if (start == null) {
            val titles = (link.altTitles + listOfNotNull(link.originalTitle, link.title)).distinct().take(4)
            var first: Node? = null
            for (t in titles) {
                val hits = search(t, !link.isTv)
                first = hits.firstOrNull { h ->
                    val y = h.startDate?.year
                    y == null || link.year == null || kotlin.math.abs(y - link.year) <= 1
                } ?: continue
                break
            }
            var node = first ?: return null
            // Zur gewünschten Staffel weitergehen (Staffel 1 = erster Eintrag)
            repeat(season - 1) {
                val next = node.sequel()?.let { media(it.id) } ?: return null
                node = next
            }
            start = node
            CloudStreamApp.setKey(CACHE, cacheKey, node.id)
        }

        // Lange Staffeln sind bei AniList oft in mehrere Abschnitte (Cours) geteilt
        var node: Node = start
        var ep = episode
        var guard = 0
        while (true) {
            val count = node.episodes
            if (count == null || ep <= count || guard++ > 6) break
            val next = node.sequel()?.let { media(it.id) } ?: break
            ep -= count
            node = next
        }
        val mal = node.idMal ?: return null
        return mal to ep
    }

    override suspend fun stamps(
        data: LoadResponse,
        episode: ResultEpisode,
        episodeDurationMs: Long
    ): List<SkipStamp>? {
        if (!FlimmerKatalog.isKatalog(data.apiName)) return null
        val link = try {
            parseJson<FlimmerKatalog.LinkData>(episode.data)
        } catch (t: Throwable) {
            return null
        }
        val (malId, ep) = try {
            resolve(link)
        } catch (t: Throwable) {
            Log.w(TAG, "resolve", t)
            null
        } ?: return null

        val url = "https://api.aniskip.com/v2/skip-times/$malId/$ep?types[]=ed&types[]=mixed-ed&types[]=mixed-op" +
                "&types[]=op&types[]=recap&episodeLength=${episodeDurationMs / 1000L}"
        val response = app.get(url).parsed<AniSkip.AniSkipResponse>()
        return response.results?.mapNotNull { stamp ->
            val type = when (stamp.skipType) {
                "op" -> SkipType.Opening
                "ed" -> SkipType.Ending
                "recap" -> SkipType.Recap
                "mixed-ed" -> SkipType.MixedEnding
                "mixed-op" -> SkipType.MixedOpening
                else -> null
            } ?: return@mapNotNull null
            SkipStamp(
                type = type,
                startMs = (stamp.interval.startTime * 1000.0).toLong(),
                endMs = (stamp.interval.endTime * 1000.0).toLong(),
            )
        }
    }
}
