package dev.brahmkshatriya.echo.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Android `app_color` — the Echo cyan used by the phone app. */
val EchoCyan = Color(0xFF22BBFF)
val EchoCyanHighlight = Color(0xFFBFD5FF)

/**
 * Scalloped play-mark matching the Android launcher / docs logo.
 */
@Composable
fun EchoLogo(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outer = minOf(cx, cy) * 0.98f
        val inner = outer * 0.78f
        val teeth = 12
        val scallop = Path().apply {
            fillType = PathFillType.EvenOdd
            val step = (2.0 * PI / teeth).toFloat()
            for (i in 0 until teeth) {
                val a0 = -PI.toFloat() / 2f + i * step
                val a1 = a0 + step / 2f
                val a2 = a0 + step
                val x0 = cx + cos(a0) * inner
                val y0 = cy + sin(a0) * inner
                if (i == 0) moveTo(x0, y0) else lineTo(x0, y0)
                quadraticBezierTo(
                    cx + cos(a1) * outer,
                    cy + sin(a1) * outer,
                    cx + cos(a2) * inner,
                    cy + sin(a2) * inner
                )
            }
            close()
        }
        drawPath(
            path = scallop,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFB8ECFF), EchoCyan, Color(0xFF1494D6)),
                start = Offset(cx * 0.2f, cy * 0.2f),
                end = Offset(this.size.width, this.size.height)
            )
        )
        val play = Path().apply {
            val left = cx - outer * 0.16f
            val right = cx + outer * 0.34f
            val top = cy - outer * 0.30f
            val bottom = cy + outer * 0.30f
            moveTo(left, top)
            lineTo(right, cy)
            lineTo(left, bottom)
            close()
        }
        drawPath(play, color = Color(0xFF1C1B1F))
    }
}

/** Home/Search/Library header matching Android's main header (logo + extension + settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoHeader(
    title: String,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EchoLogo(size = 28.dp)
                Text(
                    text = title.ifBlank { "Echo" },
                    modifier = Modifier.padding(start = 10.dp),
                    maxLines = 1
                )
            }
        },
        actions = {
            actions()
            IconButton(onClick = onOpenExtensions) {
                Icon(Icons.Filled.Extension, contentDescription = "Extensions")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    )
}
