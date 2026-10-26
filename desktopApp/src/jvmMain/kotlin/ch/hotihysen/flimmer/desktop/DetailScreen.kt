package ch.hotihysen.flimmer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.lagradost.cloudstream3.LoadResponse

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(apiName: String, url: String, nav: Nav) {
    var load by remember(url) { mutableStateOf<LoadResponse?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    val syncVersion by Sync.version.collectAsState()

    LaunchedEffect(url) {
        try {
            load = Catalog.load(apiName, url) ?: run { error = "Titel konnte nicht geladen werden."; null }
        } catch (t: Throwable) {
            error = "Titel konnte nicht geladen werden: ${t.message}"
        }
    }

    val l = load
    if (l == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) Dim(error!!) else CircularProgressIndicator(color = FlimmerColors.Accent)
        }
        return
    }

    val episodes = remember(l) { Catalog.episodes(l) }
    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted() }
    // Startstaffel: die der zuletzt geschauten Folge, sonst die erste
    val resume = remember(l, syncVersion) { Sync.resumeList().firstOrNull { it.header.id == Catalog.mainId(l) } }
    var season by remember(l) { mutableStateOf(resume?.resume?.season ?: seasons.firstOrNull()) }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "head") {
            Box(Modifier.fillMaxWidth().height(600.dp)) {
                AsyncImage(
                    l.backgroundPosterUrl ?: l.posterUrl, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.fillMaxSize().leftFade())
                Box(Modifier.fillMaxSize().bottomFade())
                Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 32.dp).widthIn(max = 680.dp)) {
                    if (l.logoUrl != null) {
                        AsyncImage(
                            l.logoUrl, contentDescription = l.name, contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier.heightIn(max = 150.dp).widthIn(max = 480.dp)
                        )
                    } else {
                        Text(l.name, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Gap(h = 14)
                    Text(metaLine(l), color = FlimmerColors.TextDim, fontSize = 15.sp)
                    Gap(h = 10)
                    Text(l.plot ?: "", color = Color.White, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Gap(h = 18)
                    Bar {
                        val label = when {
                            resume != null && episodes.isNotEmpty() -> "▶  Weiter mit S${resume.resume.season ?: 1}:F${resume.resume.episode ?: 1}"
                            resume != null -> "▶  Weiterschauen"
                            else -> "▶  Abspielen"
                        }
                        FlimmerButton(label, primary = true) { nav.playDefault(l) }
                        Gap(w = 12)
                        FlimmerButton("‹  Zurück") { nav.back() }
                        if (resume != null) {
                            Gap(w = 12)
                            FlimmerButton("Aus «Weiterschauen» entfernen") { Sync.removeResume(resume.header.id) }
                        }
                    }
                    l.actors?.takeIf { it.isNotEmpty() }?.let { actors ->
                        Gap(h = 16)
                        Text(
                            "Mit: " + actors.take(6).joinToString(", ") { it.actor.name },
                            color = FlimmerColors.TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (episodes.isNotEmpty()) {
            item(key = "seasons") {
                Column(Modifier.padding(horizontal = 48.dp)) {
                    SectionTitle("Folgen")
                    if (seasons.size > 1) {
                        FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            seasons.forEach { s ->
                                Pill(
                                    "Staffel $s",
                                    if (s == season) Color.White else FlimmerColors.SurfaceHigh,
                                    if (s == season) Color.Black else Color.White,
                                ) { season = s }
                            }
                        }
                        Gap(h = 12)
                    }
                }
            }
            val shown = episodes.filter { season == null || it.season == season }
            items(shown, key = { "ep${it.id}" }) { ep ->
                EpisodeRow(ep, l, syncVersion) { nav.play(l, ep) }
            }
        }

        l.recommendations?.takeIf { it.isNotEmpty() }?.let { recs ->
            item(key = "recs") {
                ShelfRow("Ähnliche Titel", recs.distinctBy { it.url }, key = { it.url }) { r ->
                    PosterCard(r.name, r.posterUrl, r.yearText()) { nav.go(Screen.Detail(r.apiName, r.url)) }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun EpisodeRow(ep: Catalog.Ep, load: LoadResponse, syncVersion: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pos = remember(ep.id, syncVersion) { Sync.position(ep.id) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) FlimmerColors.SurfaceHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .handCursor()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ep.episode}", color = FlimmerColors.TextDim, fontSize = 22.sp, modifier = Modifier.width(44.dp))
        Box(Modifier.width(200.dp).height(112.dp).clip(RoundedCornerShape(6.dp)).background(FlimmerColors.SurfaceHigh)) {
            AsyncImage(ep.poster ?: load.backgroundPosterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    Text("▶", color = Color.White, fontSize = 34.sp)
                }
            }
            if (pos != null && pos.duration > 0 && pos.position > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((pos.position.toFloat() / pos.duration).coerceIn(0.03f, 1f)).background(FlimmerColors.Accent))
                }
            }
        }
        Gap(w = 18)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ep.name ?: "Folge ${ep.episode}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                ep.runTime?.let { Gap(w = 12); Dim("$it Min.") }
            }
            ep.description?.takeIf { it.isNotBlank() }?.let {
                Gap(h = 6)
                Text(it, color = FlimmerColors.TextDim, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            }
        }
    }
}
