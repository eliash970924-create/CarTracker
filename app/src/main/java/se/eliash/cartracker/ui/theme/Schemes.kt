package se.eliash.cartracker.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Light, dark, or whatever the phone is set to. */
enum class ThemeMode {
    System, Light, Dark;

    companion object {
        /** Anything unrecognised - or nothing saved yet - follows the phone. */
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: System
    }
}

fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** WCAG contrast between two colours: 1 for identical, 21 for black on white. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * [accent], lightened in steps until it reads against [background].
 *
 * A car's colour is chosen on a light screen. Petrol on a dark page is
 * 1.3:1 - a chart line or tab indicator in it would simply not be there - so
 * in dark mode it is lifted towards white until it clears [minimum], which
 * for petrol takes 30%. A colour already bright enough is returned as it is.
 */
fun liftUntilReadable(accent: Color, background: Color, minimum: Double = 3.0): Color {
    // Returned untouched rather than as a 0% blend: blending goes through
    // Oklab and back, which is not guaranteed to land on the same colour.
    if (contrastRatio(accent, background) >= minimum) return accent
    for (step in 1..10) {
        val candidate = lerp(accent, Color.White, step / 10f)
        if (contrastRatio(candidate, background) >= minimum) return candidate
    }
    return Color.White
}

/**
 * The garage's colours: CarTally's own, from the logo.
 *
 * Light: petrol bar, ivory page, white cards - the mockups' layout - over
 * Material's defaults, whose neutrals lean towards the default purple and
 * read as lavender-grey.
 *
 * Dark: the "lifted petrol" mockup. The night page, the lifted petrol for
 * the bar, and amber stepping in as the accent, since petrol has nothing to
 * say against a dark page. Tertiary is the star's colour in both: the logo
 * amber is too faint on a white card, and its deeper amber too dim on a
 * dark one.
 */
fun carTallyColorScheme(base: ColorScheme, dark: Boolean): ColorScheme =
    if (dark) {
        base.copy(
            primary = CarTallyAmber,
            onPrimary = CarTallyPetrol,
            primaryContainer = CarTallyPetrolLifted,
            onPrimaryContainer = CarTallyIvory,
            secondaryContainer = lerp(CarTallyNight, CarTallyAmber, 0.2f),
            onSecondaryContainer = CarTallyIvory,
            tertiary = CarTallyAmber,
            background = CarTallyNight,
            onBackground = CarTallyIvory,
            surface = CarTallyNight,
            onSurface = CarTallyIvory,
            onSurfaceVariant = CarTallyMutedOnDark,
            surfaceContainerLowest = CarTallyNightCard,
            surfaceContainerLow = CarTallyNightCard,
            surfaceContainer = CarTallyNightCard,
            surfaceContainerHigh = CarTallyNightCard,
            surfaceContainerHighest = CarTallyNightCard,
            outlineVariant = lerp(CarTallyNight, CarTallyIvory, 0.15f)
        )
    } else {
        base.copy(
            primary = CarTallyPetrol,
            onPrimary = CarTallyIvory,
            // The top bar. Solid petrol: the 20% tint cars use turns petrol grey.
            primaryContainer = CarTallyPetrol,
            onPrimaryContainer = CarTallyIvory,
            secondaryContainer = lerp(CarTallyIvory, CarTallyAmber, 0.25f),
            onSecondaryContainer = CarTallyInk,
            tertiary = CarTallyAmberDeep,
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
    }

/**
 * A car's colours: its own accent, over Material's neutrals.
 *
 * The containers are solid tints of the accent, not see-through copies. A
 * translucent surface shows whatever is behind it - on a floating button,
 * its own shadow, which is what drew a lighter square inside the add button.
 *
 * Light mode is exactly as it has been. In dark mode the accent is lifted
 * until it reads against the dark page, the tints are a little stronger so
 * the bar still carries the car's colour, and titles on them are light -
 * the accent itself, the light-mode choice, would be dark on dark.
 */
fun carColorScheme(base: ColorScheme, accent: Color, dark: Boolean): ColorScheme {
    val primary = if (dark) liftUntilReadable(accent, base.surface) else accent
    val lightPrimary = primary.luminance() > 0.5f
    return base.copy(
        primary = primary,
        onPrimary = if (lightPrimary) Color.Black else Color.White,
        primaryContainer = lerp(base.surface, accent, if (dark) 0.3f else 0.2f),
        onPrimaryContainer = when {
            dark -> base.onSurface
            lightPrimary -> Color(0xFF1A1A1A)
            else -> accent
        },
        secondaryContainer = lerp(base.surface, accent, if (dark) 0.18f else 0.1f)
    )
}
