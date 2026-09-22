package se.eliash.cartracker.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * The garage's colours: CarTally's own, from the logo.
 *
 * Petrol for the bar, ivory pages, white cards - the mockups' layout - over
 * Material's defaults, whose neutrals are tinted towards the default purple
 * and read as lavender-grey.
 */
fun carTallyColorScheme(base: ColorScheme): ColorScheme = base.copy(
    primary = CarTallyPetrol,
    onPrimary = CarTallyIvory,
    // The top bar. Solid petrol: the 20% tint cars use turns petrol grey.
    primaryContainer = CarTallyPetrol,
    onPrimaryContainer = CarTallyIvory,
    secondaryContainer = lerp(CarTallyIvory, CarTallyAmber, 0.25f),
    onSecondaryContainer = CarTallyInk,
    background = CarTallyIvory,
    onBackground = CarTallyInk,
    surface = CarTallyIvory,
    onSurface = CarTallyInk,
    onSurfaceVariant = CarTallyMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outlineVariant = CarTallyHairline
)

/**
 * A car's colours: its own accent, over Material's neutrals as before.
 *
 * The containers are solid tints of the accent, not the see-through copies
 * they used to be. A translucent surface shows whatever is behind it - on a
 * floating button, its own shadow, which is what drew a lighter square
 * inside the add button. Blending over the surface instead looks the same
 * on the page and hides what is underneath.
 */
fun carColorScheme(base: ColorScheme, accent: Color): ColorScheme {
    val lightAccent = accent.luminance() > 0.5f
    return base.copy(
        primary = accent,
        onPrimary = if (lightAccent) Color.Black else Color.White,
        primaryContainer = lerp(base.surface, accent, 0.2f),
        onPrimaryContainer = if (lightAccent) Color(0xFF1A1A1A) else accent,
        secondaryContainer = lerp(base.surface, accent, 0.1f)
    )
}
