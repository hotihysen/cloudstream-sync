package ch.hotihysen.flimmer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Eine Kachel der Bibliothek (aus Flimmer-Lesezeichen oder aus Trakt) */
private data class LibCard(
    val key: String,
    val title: String,
    val poster: String?,
    val posterTmdb: Pair<Boolean, Int>?,
    val info: String,
    val progress: Float?,
    val kind: Kind,
    val badge: String?,
    val open: Screen.Detail,
)

private enum class Kind { SERIES, MOVIE, ANIME }

private val filters = listOf(null to "Alles", Kind.SERIES to "Serien", Kind.MOVIE to "Filme", Kind.ANIME to "Anime")

private fun katalogUrl(isTv: Boolean, tmdb: Int) = "https://www.themoviedb.org/${if (isTv) "tv" else "movie"}/$tmdb"

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    val syncVersion by Sync.version.collectAsState()
    var source by remember { mutableStateOf(if (Trakt.isLoggedIn) Store.getString("library/source") ?: "trakt" else "flimmer") }
    var tab by remember(source) { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<Kind?>(null) }
    var traktLib by remember { mutableStateOf<Trakt.Library?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val lastReport by Trakt.lastReport.collectAsState()

    LaunchedEffect(source, reload, syncVersion) {
        if (source != "trakt" || !Trakt.isLoggedIn) return@LaunchedEffect
        loading = true
        traktLib = withContext(Dispatchers.IO) { try { Trakt.library() } catch (_: Throwable) { null } }
        loading = false
    }

    // Listen je nach Quelle
    val lists: List<Pair<String, List<LibCard>>> = if (source == "trakt") {
        traktLib?.lists?.map { (name, items) ->
            name to items.map { i ->
                LibCard(
                    key = "${i.isTv}/${i.tmdb}", title = i.title, poster = i.poster, posterTmdb = i.isTv to i.tmdb,
                    info = i.info, progress = i.progress,
                    kind = when { i.anime -> Kind.ANIME; i.isTv -> Kind.SERIES; else -> Kind.MOVIE },
                    badge = i.ownRating?.let { "★ $it" } ?: i.rating?.let { "★ " + String.format("%.1f", it) },
                    open = Screen.Detail(FlimmerKatalog.NAME, katalogUrl(i.isTv, i.tmdb)),
                )
            }
        } ?: emptyList()
    } else {
        val all = remember(syncVersion) { Sync.bookmarks() }
        val resume = remember(syncVersion) { Sync.resumeList().associateBy { it.header.id } }
        Sync.WatchType.entries.map { wt ->
            wt.label to all.filter { it.first == wt }.map { (_, b) ->
                val r = b.id?.let { resume[it] }
                LibCard(
                    key = "${b.id}/${b.url}", title = b.name, poster = b.posterUrl, posterTmdb = null,
                    info = when {
                        r?.resume?.episode != null -> "S${r.resume.season ?: 1} · F${r.resume.episode} angefangen"
                        r != null -> "Angefangen"
                        else -> listOfNotNull(b.year?.toString(), b.tags?.firstOrNull()).joinToString(" · ")
                    },
                    progress = r?.pos?.let { if (it.duration > 0) it.position.toFloat() / it.duration else null },
                    kind = when {
                        b.type?.contains("Anime", true) == true || b.type == "OVA" -> Kind.ANIME
                        b.type?.contains("Movie", true) == true -> Kind.MOVIE
                        else -> Kind.SERIES
                    },
                    badge = null,
                    open = Screen.Detail(b.apiName, b.url),
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bibliothek", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Gap(w = 24)
            TabText("Flimmer", source == "flimmer") { source = "flimmer"; Store.putString("library/source", "flimmer") }
            if (Trakt.isLoggedIn) TabText("Trakt", source == "trakt") { source = "trakt"; Store.putString("library/source", "trakt") }
            Box(Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(color = FlimmerColors.Accent, strokeWidth = 2.dp, modifier = Modifier.width(18.dp).height(18.dp))
                Gap(w = 12)
            }
            FlimmerButton("Synchronisieren") {
                scope.launch {
                    loading = true
                    withContext(Dispatchers.IO) { Sync.pull() }
                    if (source == "trakt") Trakt.forceRefresh()
                    reload++
                    loading = false
                }
            }
        }
        Gap(h = 8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lists.forEachIndexed { i, (name, items) ->
                val count = items.count { filter == null || it.kind == filter }
                Pill(
                    "$name  $count",
                    if (i == tab) Color.White else FlimmerColors.SurfaceHigh,
                    if (i == tab) Color.Black else Color.White,
                ) { tab = i }
            }
            Box(Modifier.width(24.dp))
            filters.forEach { (k, label) ->
                Pill(label, if (k == filter) FlimmerColors.Accent else FlimmerColors.Surface, if (k == filter) Color.Black else Color.White) { filter = k }
            }
        }
        if (source == "trakt") lastReport?.let { Gap(h = 6); Dim("Letzte Meldung an Trakt: $it", 12) }
        Gap(h = 12)
        val shown = lists.getOrNull(tab)?.second?.filter { filter == null || it.kind == filter } ?: emptyList()
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                Dim(
                    when {
                        loading -> "Wird geladen …"
                        source == "trakt" && traktLib == null -> "Trakt nicht erreichbar oder nicht angemeldet."
                        source == "flimmer" -> "Noch nichts in dieser Liste. Auf der Detailseite fügst du Titel mit «Meine Liste» hinzu."
                        else -> "Nichts in dieser Liste."
                    }
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.key }) { c -> LibraryCard(c) { nav.go(c.open) } }
            }
        }
    }
}

@Composable
private fun LibraryCard(c: LibCard, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val poster by produceState(c.poster, c.key) {
        if (value == null && c.posterTmdb != null) {
            value = withContext(Dispatchers.IO) {
                try { FlimmerKatalog.posterUrl(c.posterTmdb.first, c.posterTmdb.second) } catch (_: Throwable) { null }
            }
        }
    }
    Column(Modifier.hoverable(interaction).clickable(onClick = onClick).handCursor()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(FlimmerColors.SurfaceHigh)
        ) {
            if (poster != null) AsyncImage(poster, contentDescription = c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(c.title, color = FlimmerColors.TextDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            if (hovered) Box(Modifier.fillMaxSize().background(Color(0x33FFFFFF)))
            c.badge?.let {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(it, color = Color.White, fontSize = 11.sp)
                }
            }
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(when (c.kind) { Kind.SERIES -> "Serie"; Kind.MOVIE -> "Film"; Kind.ANIME -> "Anime" }, color = FlimmerColors.TextDim, fontSize = 11.sp)
            }
            c.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.02f, 1f)).background(FlimmerColors.Accent))
                }
            }
        }
        Gap(h = 6)
        Text(c.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.info.isNotBlank()) Text(c.info, color = FlimmerColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
