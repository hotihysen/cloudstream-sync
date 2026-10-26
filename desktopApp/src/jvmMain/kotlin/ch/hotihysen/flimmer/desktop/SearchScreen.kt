package ch.hotihysen.flimmer.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(nav: Nav) {
    var query by remember { mutableStateOf(nav.lastQuery) }
    var results by remember { mutableStateOf<List<SearchResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        nav.lastQuery = query
        if (query.trim().length < 2) {
            results = emptyList(); return@LaunchedEffect
        }
        delay(350)
        loading = true
        results = try { Catalog.search(query.trim()) } catch (_: Throwable) { emptyList() }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Titel, Person oder Genre suchen …") },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White, unfocusedBorderColor = FlimmerColors.SurfaceHigh,
                cursorColor = FlimmerColors.Accent,
            ),
            modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth().widthIn(max = 900.dp).focusRequester(focus),
        )
        Box(Modifier.fillMaxSize()) {
            if (loading && results.isEmpty()) {
                CircularProgressIndicator(color = FlimmerColors.Accent, modifier = Modifier.align(Alignment.Center))
            } else if (query.length >= 2 && results.isEmpty() && !loading) {
                Box(Modifier.align(Alignment.Center)) { Dim("Keine Treffer für «$query».") }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(48.dp, 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(results, key = { it.url }) { r ->
                    PosterCard(r.name, r.posterUrl, r.yearText(), width = 160) { nav.go(Screen.Detail(r.apiName, r.url)) }
                }
            }
        }
    }
}
