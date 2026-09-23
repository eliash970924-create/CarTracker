package se.eliash.cartracker

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import se.eliash.cartracker.ui.theme.ThemeMode

/** Preference holding the id of the car to open at start. Absent means the garage. */
private const val DEFAULT_CAR_KEY = "default_car_id"

/** Preference holding the light / dark / follow-the-phone choice. */
private const val THEME_KEY = "theme_mode"

/**
 * The choices kept about this phone rather than about a car: which car opens
 * at start, the theme, and the fuel last logged for each car.
 *
 * Read once and then held as state, so a change shows at once; each change is
 * written straight back. Kept in preferences, not the database, because they
 * are not facts about a car and are not part of a backup.
 */
@Stable
class AppPreferences(private val prefs: SharedPreferences) {

    var defaultCarId: Int? by mutableStateOf(prefs.getInt(DEFAULT_CAR_KEY, -1).takeIf { it >= 0 })
        private set

    var themeMode: ThemeMode by mutableStateOf(ThemeMode.fromStored(prefs.getString(THEME_KEY, null)))
        private set

    fun changeDefaultCar(id: Int?) {
        defaultCarId = id
        val editor = prefs.edit()
        if (id == null) editor.remove(DEFAULT_CAR_KEY) else editor.putInt(DEFAULT_CAR_KEY, id)
        editor.apply()
    }

    fun changeThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(THEME_KEY, mode.name).apply()
    }

    /** The fuel last logged for [carId], so the form starts on it next time. */
    fun lastFuel(carId: Int): String? = prefs.getString("last_fuel_$carId", null)

    fun rememberFuel(carId: Int, fuel: String) {
        prefs.edit().putString("last_fuel_$carId", fuel).apply()
    }
}

@Composable
fun rememberAppPreferences(prefs: SharedPreferences): AppPreferences = remember(prefs) { AppPreferences(prefs) }
