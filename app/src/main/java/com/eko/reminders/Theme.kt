package com.eko.reminders

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Deep space: near-black blue, violet and teal nebulae, starlight accents. */
object Cosmos {
    val Deep = Color(0xFF05060F)
    val Night = Color(0xFF0A0C24)
    val Dusk = Color(0xFF170F33)
    val Glass = Color(0x0FFFFFFF)
    val GlassEdge = Color(0x1AFFFFFF)
    val Panel = Color(0xFF14163A)
    val Outline = Color(0xFF2B2F55)
    val Text = Color(0xFFEEF0FF)
    val Muted = Color(0xFF9AA0C3)
    val Cyan = Color(0xFF7DF9FF)
    val Violet = Color(0xFFB388FF)
    val Pink = Color(0xFFFF7AC6)
    val Mint = Color(0xFF7CF2B0)
}

fun priorityColor(p: Priority): Color = when (p) {
    Priority.HIGH -> Cosmos.Pink
    Priority.MEDIUM -> Cosmos.Cyan
    Priority.LOW -> Cosmos.Muted
}

private val CosmicScheme = darkColorScheme(
    primary = Cosmos.Cyan,
    onPrimary = Cosmos.Deep,
    primaryContainer = Color(0xFF123A44),
    onPrimaryContainer = Cosmos.Cyan,
    secondary = Cosmos.Violet,
    onSecondary = Cosmos.Deep,
    secondaryContainer = Color(0xFF2A1F4D),
    onSecondaryContainer = Cosmos.Text,
    background = Cosmos.Deep,
    onBackground = Cosmos.Text,
    surface = Cosmos.Deep,
    onSurface = Cosmos.Text,
    surfaceVariant = Cosmos.Panel,
    onSurfaceVariant = Cosmos.Muted,
    surfaceContainerLowest = Cosmos.Deep,
    surfaceContainerLow = Cosmos.Night,
    surfaceContainer = Cosmos.Panel,
    surfaceContainerHigh = Cosmos.Panel,
    surfaceContainerHighest = Cosmos.Panel,
    outline = Cosmos.Outline,
    outlineVariant = Cosmos.Outline,
    error = Cosmos.Pink,
    errorContainer = Color(0xFF3D1530),
    onErrorContainer = Color(0xFFFFC2E2),
)

private val CosmicType = Typography().let { t ->
    t.copy(
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = 2.sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Light),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Light, letterSpacing = 1.sp),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CosmicScheme, typography = CosmicType, content = content)
}

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float)

/** Full-screen space backdrop: gradient sky, two nebula glows, slowly twinkling stars. */
@Composable
fun Starfield(modifier: Modifier = Modifier) {
    val stars = remember {
        val rnd = Random(7)
        List(150) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                r = 0.5f + rnd.nextFloat() * rnd.nextFloat() * 2.2f,
                phase = rnd.nextFloat() * 6.2832f,
                speed = 0.5f + rnd.nextFloat() * 1.5f,
            )
        }
    }
    val t = rememberInfiniteTransition(label = "stars").animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "twinkle",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Cosmos.Deep, Cosmos.Night, Cosmos.Dusk)))

        val violetCenter = Offset(size.width * 0.9f, size.height * 0.08f)
        val violetR = size.width * 0.8f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Cosmos.Violet.copy(alpha = 0.20f), Color.Transparent),
                center = violetCenter,
                radius = violetR,
            ),
            radius = violetR,
            center = violetCenter,
        )
        val tealCenter = Offset(size.width * 0.05f, size.height * 0.78f)
        val tealR = size.width * 0.75f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Cosmos.Cyan.copy(alpha = 0.10f), Color.Transparent),
                center = tealCenter,
                radius = tealR,
            ),
            radius = tealR,
            center = tealCenter,
        )

        val time = t.value
        for (s in stars) {
            val twinkle = 0.5f + 0.5f * sin(time * s.speed + s.phase)
            val alpha = 0.25f + 0.7f * twinkle
            val c = Offset(s.x * size.width, s.y * size.height)
            val r = s.r.dp.toPx()
            if (s.r > 1.6f) {
                drawCircle(Cosmos.Cyan.copy(alpha = 0.12f * alpha), radius = r * 4f, center = c)
            }
            drawCircle(Color.White.copy(alpha = alpha), radius = r, center = c)
        }
    }
}

/** Translucent glass panel. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Cosmos.Glass),
        border = BorderStroke(1.dp, Cosmos.GlassEdge),
        content = content,
    )
}

/** Progress as an orbit: a faint ring, a glowing arc, and a small planet at its tip. */
@Composable
fun OrbitRing(fraction: Float, ringSize: Dp, center: @Composable () -> Unit) {
    Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2 + 8.dp.toPx()
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            val f = fraction.coerceIn(0f, 1f)
            if (f > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Cosmos.Violet, Cosmos.Cyan, Cosmos.Violet)),
                    startAngle = -90f,
                    sweepAngle = 360f * f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            val angle = Math.toRadians((-90.0 + 360.0 * f))
            val radius = arcSize.width / 2
            val c = Offset(size.width / 2, size.height / 2)
            val p = Offset(c.x + radius * cos(angle).toFloat(), c.y + radius * sin(angle).toFloat())
            drawCircle(Cosmos.Cyan.copy(alpha = 0.25f), radius = 10.dp.toPx(), center = p)
            drawCircle(Color.White, radius = 4.dp.toPx(), center = p)
        }
        center()
    }
}

/** Priority as a star: dim speck, bright star, or glowing star. */
@Composable
fun PriorityStar(p: Priority, dimmed: Boolean = false, starSize: Dp = 16.dp) {
    val col = if (dimmed) Cosmos.Outline else priorityColor(p)
    Canvas(Modifier.size(starSize)) {
        val r = size.minDimension / 2
        if (p == Priority.HIGH && !dimmed) drawCircle(col.copy(alpha = 0.25f), radius = r)
        if (p != Priority.LOW && !dimmed) drawCircle(col.copy(alpha = 0.35f), radius = r * 0.62f)
        drawCircle(col, radius = if (p == Priority.LOW) r * 0.28f else r * 0.36f)
    }
}
