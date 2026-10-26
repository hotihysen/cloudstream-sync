package ch.hotihysen.flimmer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun SearchResponse.yearText(): String? = when (this) {
    is MovieSearchResponse -> year?.toString()
    is TvSeriesSearchResponse -> year?.toString()
    else -> null
}

@Composable
fun HomeScreen(mode: FlimmerKatalog.Mode, nav: Nav) {
    val api = Catalog.katalog(mode)
    val rows = remember(mode) { mutableStateMapOf<Int, HomePageList>() }
    var hero by remember(mode) { mutableStateOf<LoadResponse?>(null) }
    val syncVersion by Sync.version.collectAsState()
    val installed by Extensions.installed.collectAsState()
    val resume = remember(syncVersion) { Sync.resumeList() }

    LaunchedEffect(mode) {
        if (!FlimmerKatalog.isConfigured) return@LaunchedEffect
        coroutineScope {
            api.mainPage.forEachIndexed { index, req ->
                launch {
                    Catalog.row(api, MainPageRequest(req.name, req.data, req.horizontalImages))
                        ?.takeIf { it.list.isNotEmpty() }
                        ?.let { rows[index] = it }
                }
            }
            launch {
                // Hero: erster Titel der ersten Reihe, mit Hintergrundbild und Logo
                while (rows.isEmpty()) kotlinx.coroutines.delay(100)
                kotlinx.coroutines.delay(300)
                val first = rows[rows.keys.min()]?.list?.firstOrNull() ?: return@launch
                hero = try { Catalog.load(first.apiName, first.url) } catch (_: Throwable) { null }
            }
        }
    }

    if (!FlimmerKatalog.isConfigured) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Dim("TMDB-Schlüssel fehlt in diesem Build.")
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "hero") { Hero(hero, nav) }
        if (installed.isEmpty()) {
            item(key = "noext") {
                Row(Modifier.padding(horizontal = 48.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dim("Noch keine Erweiterungen installiert, deshalb gibt es noch keine Streams.  ")
                    Pill("Erweiterungen öffnen", FlimmerColors.Accent, Color.Black) { nav.go(Screen.Settings) }
                }
            }
        }
        if (resume.isNotEmpty()) {
            item(key = "resume") {
                ShelfRow("Weiterschauen", resume, key = { it.header.id }) { r ->
                    val pos = r.pos
                    val sub = if (r.resume.season != null || r.resume.episode != null)
                        "S${r.resume.season ?: 1}:F${r.resume.episode ?: 1}" else null
                    PosterCard(
                        r.header.name, r.header.poster, sub,
                        progress = pos?.let { if (it.duration > 0) it.position.toFloat() / it.duration else null },
                    ) { nav.go(Screen.Detail(r.header.apiName, r.header.url)) }
                }
            }
        }
        val ordered = rows.keys.sorted()
        items(ordered, key = { "row$it" }) { index ->
            val row = rows[index] ?: return@items
            ShelfRow(row.name, row.list.distinctBy { it.url }, key = { it.url }) { item ->
                PosterCard(item.name, item.posterUrl, item.yearText()) { nav.go(Screen.Detail(item.apiName, item.url)) }
            }
        }
        if (rows.isEmpty()) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FlimmerColors.Accent)
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun Hero(load: LoadResponse?, nav: Nav) {
    Box(Modifier.fillMaxWidth().height(560.dp).background(FlimmerColors.Background)) {
        if (load != null) {
            AsyncImage(
                load.backgroundPosterUrl ?: load.posterUrl, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().leftFade())
            Box(Modifier.fillMaxSize().bottomFade())
            Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 56.dp).widthIn(max = 620.dp)) {
                if (load.logoUrl != null) {
                    AsyncImage(
                        load.logoUrl, contentDescription = load.name, contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.heightIn(max = 140.dp).widthIn(max = 460.dp)
                    )
                } else {
                    Text(load.name, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                }
                Gap(h = 14)
                Text(metaLine(load), color = FlimmerColors.TextDim, fontSize = 15.sp)
                Gap(h = 10)
                Text(
                    load.plot ?: "", color = Color.White, fontSize = 16.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, lineHeight = 22.sp
                )
                Gap(h = 18)
                Bar {
                    FlimmerButton("▶  Abspielen", primary = true) { nav.playDefault(load) }
                    Gap(w = 12)
                    FlimmerButton("Weitere Infos") { nav.go(Screen.Detail(load.apiName, load.url)) }
                }
            }
        }
    }
}

fun metaLine(load: LoadResponse): String = listOfNotNull(
    load.year?.toString(),
    load.score?.let { "★ " + String.format("%.1f", it.toInt(100) / 10.0) },
    load.duration?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} Std. ${it % 60} Min." else "$it Min." },
    load.tags?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() },
).joinToString("   ·   ")
