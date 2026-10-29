package ch.hotihysen.flimmer.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

object FlimmerColors {
    val Background = Color(0xFF141414)
    val Surface = Color(0xFF1C1C1C)
    val SurfaceHigh = Color(0xFF2A2A2A)
    val Accent = Color(0xFFFF7A4D)
    val Text = Color(0xFFF2F2F2)
    val TextDim = Color(0xFFA8A8A8)
    val Good = Color(0xFF46D369)
}

@Composable
fun FlimmerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FlimmerColors.Accent,
            onPrimary = Color.Black,
            background = FlimmerColors.Background,
            onBackground = FlimmerColors.Text,
            surface = FlimmerColors.Surface,
            onSurface = FlimmerColors.Text,
            surfaceVariant = FlimmerColors.SurfaceHigh,
            onSurfaceVariant = FlimmerColors.TextDim,
            secondary = FlimmerColors.Accent,
        ),
        content = content,
    )
}

fun Modifier.handCursor() = this.pointerHoverIcon(PointerIcon.Hand)

/** Knopf im Streaming-Stil: weiss (Hauptaktion) oder grau durchscheinend. */
@Composable
fun FlimmerButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        primary && hovered -> Color(0xFFD9D9D9)
        primary -> Color.White
        hovered -> Color(0x80808080)
        else -> Color(0x596D6D6E)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .handCursor()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
}

/** Kleiner Textknopf (Kopfzeile, Reiter). */
@Composable
fun TabText(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .handCursor()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = if (selected || hovered) Color.White else FlimmerColors.TextDim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.width(20.dp).height(2.dp)
                .background(if (selected) FlimmerColors.Accent else Color.Transparent, RoundedCornerShape(1.dp))
        )
    }
}

/** Kachel mit Poster (2:3) und sanfter Vergrösserung beim Darüberfahren. */
@Composable
fun PosterCard(
    title: String,
    poster: String?,
    subtitle: String? = null,
    progress: Float? = null,
    width: Int = 150,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.06f else 1f)
    Column(
        Modifier.width(width.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .handCursor()
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(FlimmerColors.SurfaceHigh)
                .then(if (hovered) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
        ) {
            if (poster != null) {
                AsyncImage(poster, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    title, color = FlimmerColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp)
                )
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.02f, 1f)).background(FlimmerColors.Accent))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, color = FlimmerColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Reihe mit Titel und Pfeilen zum Blättern (Mausrad blättert senkrecht). */
@Composable
fun <T> ShelfRow(title: String, items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).hoverable(interaction)) {
        Text(
            title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp, bottom = 10.dp)
        )
        Box(Modifier.fillMaxWidth()) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = key) { content(it) }
            }
            if (hovered) {
                ArrowButton("‹", Modifier.align(Alignment.CenterStart)) {
                    scope.launch { state.animateScrollBy(-900f) }
                }
                ArrowButton("›", Modifier.align(Alignment.CenterEnd)) {
                    scope.launch { state.animateScrollBy(900f) }
                }
            }
        }
    }
}

@Composable
private fun ArrowButton(symbol: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.width(44.dp).height(120.dp)
            .background(Color(0x99000000))
            .clickable(onClick = onClick)
            .handCursor(),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
    }
}

/** Halbtransparenter Verlauf über Bildern, damit Text lesbar bleibt. */
fun Modifier.bottomFade() = this.background(
    Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color(0x66141414), 1f to FlimmerColors.Background)
)

fun Modifier.leftFade() = this.background(
    Brush.horizontalGradient(0f to Color(0xEE141414), 0.45f to Color(0x99141414), 0.75f to Color.Transparent)
)

@Composable
fun Pill(text: String, color: Color = FlimmerColors.SurfaceHigh, textColor: Color = Color.White, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(CircleShape).background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).handCursor() else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
fun Dim(text: String, size: Int = 14) {
    Text(text, color = FlimmerColors.TextDim, fontSize = size.sp)
}

@Composable
fun Bar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
fun Gap(w: Int = 0, h: Int = 0) = Spacer(Modifier.size(w.dp, h.dp))
