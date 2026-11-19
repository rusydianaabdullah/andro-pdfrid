package id.larus.pdf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The PDF red: the app's own accent, and the colour of every document glyph in it. */
val PdfRed = Color(0xFFC8322B)
private val PdfRedLight = Color(0xFFE8574F)

private val DarkColors = darkColorScheme(
    primary = PdfRedLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1512),
    onPrimaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121215),
    surface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFF2A2A2F),
    onSurfaceVariant = Color(0xFFCFCFD6),
)

private val LightColors = lightColorScheme(
    primary = PdfRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410004),
    background = Color(0xFFF6F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0EEF1),
    onSurfaceVariant = Color(0xFF55535A),
)

/**
 * The ink a mark can be made in. Kept as opaque ARGB ints: the same value has to survive JSON,
 * a Compose canvas and the flattened PDF, and only the highlighter dilutes it.
 */
val InkPalette = listOf(
    0xFFFFC400.toInt(), // amber
    0xFF32D74B.toInt(), // green
    0xFF2F6FED.toInt(), // blue
    0xFFFF453A.toInt(), // red
    0xFFFF6D00.toInt(), // orange
    0xFF1C1C1E.toInt(), // near-black
)

@Composable
fun PdfridTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
