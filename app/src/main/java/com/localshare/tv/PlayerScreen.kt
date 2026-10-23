package com.localshare.tv

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/** Plays every video or song in the folder as a playlist, resumes where you stopped, and streams over the phone's Wi-Fi. */
@Composable
fun PlayerScreen(api: Api?, media: Media, store: Store, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(media.items[media.index].name) }
    var controls by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember(media) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(api?.client ?: OkHttpClient())))
            .build().apply {
                val items = media.items.map {
                    MediaItem.Builder().setUri(if (it.url.isNotEmpty()) it.url else api!!.fileUrl(it.path)).setMediaId(if (it.url.isNotEmpty()) "shared:${it.name}:${it.size}" else it.path)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).build()).build()
                }
                setMediaItems(items, media.index, store.position(items[media.index].mediaId))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                title = item?.mediaMetadata?.title?.toString() ?: title
                error = null
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "This file can't be played on this TV"
                if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare() }
            }
        }
        player.addListener(l)
        onDispose {
            save(player, store)
            player.removeListener(l)
            player.release()
        }
    }
    LaunchedEffect(player) { while (true) { delay(5000); save(player, store) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == View.VISIBLE })
                    post { requestFocus() }
                }
            },
        )
        if (controls || error != null) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(horizontal = 40.dp, vertical = 28.dp)) {
                Column {
                    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    error?.let { Text(it, fontSize = 18.sp, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Text("Back: close · Left/Right: skip 10s · OK: pause", Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), fontSize = 12.sp, color = Color(0x66FFFFFF))
    }
}

private fun save(p: Player, store: Store) {
    val id = p.currentMediaItem?.mediaId ?: return
    val d = p.duration
    store.setPosition(id, if (d > 0 && p.currentPosition > d * 0.95) 0 else p.currentPosition)
}
