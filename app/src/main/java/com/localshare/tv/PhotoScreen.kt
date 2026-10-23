package com.localshare.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** Full-screen photos. Left/Right to move, OK to start or stop a slideshow. */
@Composable
fun PhotoScreen(api: Api?, media: Media) {
    val ctx = LocalContext.current
    val loader = remember(api) { ImageLoader.Builder(ctx).apply { api?.let { okHttpClient(it.client) } }.crossfade(true).build() }
    val focus = remember { FocusRequester() }
    val n = media.items.size
    var i by remember { mutableIntStateOf(media.index) }
    var slideshow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(slideshow) { while (slideshow) { delay(5000); i = (i + 1) % n } }
    LaunchedEffect(i) { loader.enqueue(ImageRequest.Builder(ctx).data(model(api, media.items[(i + 1) % n])).build()) }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { i = (i + 1) % n; true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { i = (i - 1 + n) % n; true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { slideshow = !slideshow; true }
                    else -> false
                }
            }.focusable(),
    ) {
        AsyncImage(model(api, media.items[i]), media.items[i].name, loader, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Text("${i + 1} / $n   ${media.items[i].name}${if (slideshow) "   ▶ slideshow" else ""}", Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 18.sp, color = Color.White)
    }
}

private fun model(api: Api?, e: Entry): Any = if (e.url.isNotEmpty()) e.url else api!!.fileUrl(e.path)
