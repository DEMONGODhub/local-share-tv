package com.localshare.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.localshare.tv.ui.FocusCard

private fun fmt(n: Long) = when {
    n < 1048576 -> "${n / 1024} KB"
    n < 1073741824 -> "%.1f MB".format(n / 1048576.0)
    else -> "%.2f GB".format(n / 1073741824.0)
}

@Composable
fun BrowseScreen(st: UiState, api: Api?, actions: TvActions) {
    val ctx = LocalContext.current
    val loader = remember(api) { ImageLoader.Builder(ctx).apply { api?.let { okHttpClient(it.client) } }.crossfade(true).build() }
    val first = remember { FocusRequester() }
    LaunchedEffect(st.path, st.entries) { if (st.entries.isNotEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (st.path.isEmpty()) "Choose a folder" else if (st.relay) st.entries.firstOrNull()?.let { "Files" } ?: "Files" else st.path.substringAfterLast('/'), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Text((st.phone?.name ?: "Shared from your phone") + (if (st.path.isEmpty() || st.relay) "" else "  ›  " + st.path.replace("/", "  ›  ")), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (st.loading) Text("Loading…", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        st.message?.let {
            FocusCard(actions::dismissMessage, Modifier.fillMaxWidth()) { Text("$it   (press OK to dismiss)", Modifier.padding(16.dp), fontSize = 18.sp, color = MaterialTheme.colorScheme.error) }
        }
        if (st.entries.isEmpty() && !st.loading) Text("This folder is empty. Press Back to go up.", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(GridCells.Adaptive(230.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(8.dp)) {
            items(st.entries, key = { it.path }) { e ->
                val mod = if (e == st.entries.first()) Modifier.focusRequester(first) else Modifier
                FocusCard({ actions.open(e) }, mod.fillMaxWidth()) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                            when {
                                e.dir -> Image(painterResource(R.drawable.ic_folder), null, Modifier.size(64.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
                                e.kind == "audio" -> Text("♪", fontSize = 48.sp, color = MaterialTheme.colorScheme.secondary)
                                else -> Text(e.name.substringAfterLast('.', "").take(4).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (api != null && e.url.isEmpty() && !e.dir && (e.kind == "video" || e.kind == "image")) {
                                AsyncImage(api!!.thumbUrl(e.path), null, loader, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                            }
                            if (e.kind == "video") Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .7f), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.padding(4.dp).size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(e.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (e.dir) "Folder" else fmt(e.size), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
