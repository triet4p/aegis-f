package io.github.triet4p.aegisf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The design system's vector vocabulary, drawn from one 24-unit grid.
 *
 * Every glyph is a stroked outline rather than an assertion: the shape says what the destination
 * does, and the adjacent label says it in words, so an icon is never the only cue. The glyphs are
 * shared by the feature launchers, the bottom bar and the status chips, so the same idea is never
 * drawn two different ways.
 */
internal enum class AegisGlyph {
    HOME,
    LEDGER,
    ANALYTICS,
    NOTES,
    CHAT,
    REVIEW,
    MANUAL,
    CATEGORIES,
    SETTINGS,
    ARROW_BACK,
    CHEVRON_RIGHT,
    CHEVRON_LEFT,
    CHECK,
    WARNING,
    CROSS,
    LINK,
    EYE,
    EYE_OFF,
}

/**
 * Draws [glyph] in [tint].
 *
 * The canvas is square by contract; the caller sizes it. A decorative glyph carries no
 * semantics: the label beside it, or the clickable control around it, names the action.
 */
@Composable
internal fun AegisIcon(
    glyph: AegisGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier = modifier.size(size)) { drawAegisGlyph(glyph, tint) }
}

private fun DrawScope.drawAegisGlyph(glyph: AegisGlyph, tint: Color) {
    val unit = minOf(size.width, size.height) / 24f
    val stroke = 2f * unit
    fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(
            color = tint,
            start = point(x1, y1),
            end = point(x2, y2),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
    fun dot(x: Float, y: Float, radius: Float) {
        drawCircle(color = tint, radius = radius * unit, center = point(x, y))
    }

    when (glyph) {
        AegisGlyph.HOME -> {
            line(3f, 11f, 12f, 4f)
            line(12f, 4f, 21f, 11f)
            line(5f, 10f, 5f, 20f)
            line(5f, 20f, 19f, 20f)
            line(19f, 20f, 19f, 10f)
            line(10f, 20f, 10f, 14f)
            line(10f, 14f, 14f, 14f)
            line(14f, 14f, 14f, 20f)
        }

        AegisGlyph.LEDGER -> {
            drawRoundRect(
                color = tint,
                topLeft = point(4f, 4f),
                size = Size(16f * unit, 16f * unit),
                cornerRadius = CornerRadius(2.5f * unit),
                style = Stroke(width = stroke),
            )
            line(8f, 9f, 17f, 9f)
            line(8f, 13f, 17f, 13f)
            line(8f, 17f, 13f, 17f)
        }

        AegisGlyph.ANALYTICS -> {
            line(3f, 20f, 21f, 20f)
            line(7f, 20f, 7f, 13f)
            line(12f, 20f, 12f, 8f)
            line(17f, 20f, 17f, 11f)
        }

        AegisGlyph.NOTES -> {
            line(9f, 18f, 19f, 8f)
            line(19f, 8f, 21f, 10f)
            line(21f, 10f, 11f, 20f)
            line(11f, 20f, 8f, 20.5f)
            line(6f, 6f, 13f, 6f)
            line(4f, 22f, 20f, 22f)
        }

        AegisGlyph.CHAT -> {
            drawRoundRect(
                color = tint,
                topLeft = point(3f, 4f),
                size = Size(18f * unit, 13f * unit),
                cornerRadius = CornerRadius(4f * unit),
                style = Stroke(width = stroke),
            )
            line(8f, 17f, 8f, 21f)
            line(8f, 21f, 12f, 17f)
        }

        AegisGlyph.REVIEW -> {
            val bell = Path().apply {
                moveTo(6f * unit, 17f * unit)
                lineTo(6f * unit, 11f * unit)
                cubicTo(
                    6f * unit,
                    7f * unit,
                    9f * unit,
                    5f * unit,
                    12f * unit,
                    5f * unit,
                )
                cubicTo(
                    15f * unit,
                    5f * unit,
                    18f * unit,
                    7f * unit,
                    18f * unit,
                    11f * unit,
                )
                lineTo(18f * unit, 17f * unit)
            }
            drawPath(bell, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
            line(4f, 17f, 20f, 17f)
            dot(12f, 20f, 1.6f)
        }

        AegisGlyph.MANUAL -> {
            drawCircle(
                color = tint,
                radius = 8f * unit,
                center = point(12f, 12f),
                style = Stroke(width = stroke),
            )
            line(12f, 8f, 12f, 16f)
            line(8f, 12f, 16f, 12f)
        }

        AegisGlyph.CATEGORIES -> {
            val tag = Path().apply {
                moveTo(4f * unit, 12f * unit)
                lineTo(12f * unit, 4f * unit)
                lineTo(20f * unit, 4f * unit)
                lineTo(20f * unit, 12f * unit)
                lineTo(12f * unit, 20f * unit)
                close()
            }
            drawPath(tag, color = tint, style = Stroke(width = stroke))
            dot(8.5f, 8.5f, 1.4f)
        }

        AegisGlyph.SETTINGS -> {
            drawCircle(
                color = tint,
                radius = 3.2f * unit,
                center = point(12f, 12f),
                style = Stroke(width = stroke),
            )
            val spokes = 8
            for (index in 0 until spokes) {
                val angle = Math.PI * 2.0 * index / spokes
                val cos = kotlin.math.cos(angle).toFloat()
                val sin = kotlin.math.sin(angle).toFloat()
                line(
                    12f + cos * 5.6f,
                    12f + sin * 5.6f,
                    12f + cos * 8.4f,
                    12f + sin * 8.4f,
                )
            }
        }

        AegisGlyph.ARROW_BACK -> {
            line(14f, 5f, 7f, 12f)
            line(7f, 12f, 14f, 19f)
            line(7f, 12f, 21f, 12f)
        }

        AegisGlyph.CHEVRON_RIGHT -> {
            line(9f, 5f, 16f, 12f)
            line(16f, 12f, 9f, 19f)
        }

        AegisGlyph.CHEVRON_LEFT -> {
            line(15f, 5f, 8f, 12f)
            line(8f, 12f, 15f, 19f)
        }

        AegisGlyph.CHECK -> {
            line(5f, 13f, 10f, 18f)
            line(10f, 18f, 19f, 6f)
        }

        AegisGlyph.WARNING -> {
            val triangle = Path().apply {
                moveTo(12f * unit, 4f * unit)
                lineTo(21f * unit, 20f * unit)
                lineTo(3f * unit, 20f * unit)
                close()
            }
            drawPath(triangle, color = tint, style = Stroke(width = stroke))
            line(12f, 10f, 12f, 15f)
            dot(12f, 17.4f, 1.1f)
        }

        AegisGlyph.CROSS -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }

        AegisGlyph.LINK -> {
            drawRoundRect(
                color = tint,
                topLeft = point(3f, 9f),
                size = Size(9f * unit, 6f * unit),
                cornerRadius = CornerRadius(3f * unit),
                style = Stroke(width = stroke),
            )
            drawRoundRect(
                color = tint,
                topLeft = point(12f, 9f),
                size = Size(9f * unit, 6f * unit),
                cornerRadius = CornerRadius(3f * unit),
                style = Stroke(width = stroke),
            )
        }

        AegisGlyph.EYE -> {
            line(3f, 12f, 7f, 7f)
            line(7f, 7f, 12f, 6f)
            line(12f, 6f, 17f, 7f)
            line(17f, 7f, 21f, 12f)
            line(21f, 12f, 17f, 17f)
            line(17f, 17f, 12f, 18f)
            line(12f, 18f, 7f, 17f)
            line(7f, 17f, 3f, 12f)
            drawCircle(
                color = tint,
                radius = 2.6f * unit,
                center = point(12f, 12f),
                style = Stroke(width = stroke),
            )
        }

        AegisGlyph.EYE_OFF -> {
            line(3f, 12f, 7f, 7f)
            line(7f, 7f, 12f, 6f)
            line(12f, 6f, 17f, 7f)
            line(17f, 7f, 21f, 12f)
            line(21f, 12f, 17f, 17f)
            line(17f, 17f, 12f, 18f)
            line(12f, 18f, 7f, 17f)
            line(7f, 17f, 3f, 12f)
            drawCircle(
                color = tint,
                radius = 2.6f * unit,
                center = point(12f, 12f),
                style = Stroke(width = stroke),
            )
            line(4f, 20f, 20f, 4f)
        }
    }
}
