package se.eliash.cartracker

import android.app.Activity
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The top bar reaches up under the status bar - edge-to-edge is enforced
 * from Android 15 for this target SDK - so the clock and battery have to
 * suit it: light over a dark bar, dark over a pale one. The gesture bar
 * sits over the page, so it follows the page. Earlier versions keep their
 * own system bar colours, so are left alone.
 */
@Composable
fun SystemBarAppearance(colors: ColorScheme, darkTheme: Boolean) {
    val view = LocalView.current
    val lightStatusIcons = colors.primaryContainer.luminance() < 0.5f
    SideEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !lightStatusIcons
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
