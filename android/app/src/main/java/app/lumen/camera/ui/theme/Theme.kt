package app.lumen.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Original Lumen palette: camera-black UI, white type, warm amber accent. */
object LumenColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF1A1A1C)
    val SurfaceHigh = Color(0xFF2A2A2E)
    val Chip = Color(0x66000000)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFB8B8BE)
    val Accent = Color(0xFFFFC83D)
    val Record = Color(0xFFFF3B30)
    val Good = Color(0xFF34C759)
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LumenColors.Accent,
            onPrimary = Color.Black,
            background = LumenColors.Background,
            surface = LumenColors.Surface,
            onSurface = LumenColors.Text,
            surfaceVariant = LumenColors.SurfaceHigh,
        ),
        content = content,
    )
}
