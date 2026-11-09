package io.github.triet4p.aegisf.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The shared corner radii: friendly soft surfaces, with tighter chips and wider sheets.
 *
 * `small` is the chip/badge radius, `medium` the button and input radius, `large` the card and
 * list radius, and `extraLarge` the sheet radius.
 */
internal val AegisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
