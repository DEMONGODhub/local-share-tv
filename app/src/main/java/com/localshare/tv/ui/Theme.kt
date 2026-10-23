package com.localshare.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private val Scheme = darkColorScheme(
    primary = Color(0xFFF7A600), onPrimary = Color(0xFF2A1C00),
    primaryContainer = Color(0xFF4A3300), onPrimaryContainer = Color(0xFFFFDDA0),
    secondary = Color(0xFFFF7A45), onSecondary = Color(0xFF2A0E00),
    background = Color(0xFF17140F), onBackground = Color(0xFFF6EFE2),
    surface = Color(0xFF211C14), onSurface = Color(0xFFF6EFE2),
    surfaceVariant = Color(0xFF2B2419), onSurfaceVariant = Color(0xFFA39982),
    outline = Color(0xFF3A3122), error = Color(0xFFF0745F), onError = Color(0xFF2A0A05),
)

/** A TV is always viewed in the dark, from a distance, so there is only the dark amber palette. */
@Composable
fun TvTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

/** Something you can reach with the remote: it grows and gets an amber ring while focused. */
@Composable
fun FocusCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "focusScale")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) { content() }
}

/** QR drawn on white so it scans from across the room. */
@Composable
fun QrCode(content: String, size: Dp, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp).semantics { contentDescription = description }) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
