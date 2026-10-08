package com.eko.reminders

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** "Night board": near-black squares, ivory pieces, a gold king. */
object Palette {
    val Bg = Color(0xFF0E0F12)
    val Surface = Color(0xFF17191E)
    val Surface2 = Color(0xFF22252C)
    val Outline = Color(0xFF353943)
    val Gold = Color(0xFFD4A537)
    val GoldDim = Color(0xFF3A2F12)
    val Ivory = Color(0xFFECE6D8)
    val Muted = Color(0xFF8B909A)
    val Green = Color(0xFF7FA650)
    val Red = Color(0xFFD9544D)
}

fun pieceColor(p: Piece): Color = when (p) {
    Piece.KING -> Palette.Gold
    Piece.ROOK -> Palette.Ivory
    Piece.PAWN -> Palette.Muted
}

private val BoardScheme = darkColorScheme(
    primary = Palette.Gold,
    onPrimary = Palette.Bg,
    primaryContainer = Palette.GoldDim,
    onPrimaryContainer = Palette.Gold,
    secondary = Palette.Green,
    onSecondary = Palette.Bg,
    secondaryContainer = Palette.Surface2,
    onSecondaryContainer = Palette.Ivory,
    background = Palette.Bg,
    onBackground = Palette.Ivory,
    surface = Palette.Bg,
    onSurface = Palette.Ivory,
    surfaceVariant = Palette.Surface2,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Palette.Bg,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface2,
    surfaceContainerHighest = Palette.Surface2,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Red,
    errorContainer = Color(0xFF3A1A18),
    onErrorContainer = Color(0xFFF2B8B5),
)

private val BoardType = Typography().let { t ->
    t.copy(
        displayMedium = t.displayMedium.copy(fontFamily = FontFamily.Monospace),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BoardScheme, typography = BoardType, content = content)
}

/** Two rows of faint chessboard squares, used as a header strip. */
@Composable
fun CheckerStrip(height: Dp = 12.dp, squares: Int = 24) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val w = size.width / squares
        val h = size.height / 2
        for (row in 0..1) {
            for (i in 0 until squares) {
                if ((i + row) % 2 == 0) {
                    drawRect(
                        color = Palette.Gold.copy(alpha = 0.22f),
                        topLeft = Offset(i * w, row * h),
                        size = Size(w, h),
                    )
                }
            }
        }
    }
}
