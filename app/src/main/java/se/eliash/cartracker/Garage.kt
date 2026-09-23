package se.eliash.cartracker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a garage card shows beyond the car itself: one row per car with fill-ups. */
data class CarSummary(
    val carId: Int,
    val fillUps: Int,
    val latestOdometerKm: Int,
    val lastFillUpMillis: Long
)

/**
 * The car to open when the app starts, or null to open the garage.
 *
 * Only a default that still exists counts. The id is kept in preferences,
 * outside the database, so it can outlive the car it names; this is the one
 * place that has to notice.
 */
fun launchCar(cars: List<Car>, defaultCarId: Int?): Car? =
    defaultCarId?.let { id -> cars.firstOrNull { it.id == id } }

/**
 * The car the "Log fill-up" shortcut opens: the default, or failing that the
 * only car there is. Null, and the garage, when there is a choice to make -
 * logging a fill-up against a guessed car is worse than one extra tap.
 */
fun fillUpShortcutCar(cars: List<Car>, defaultCarId: Int?): Car? =
    launchCar(cars, defaultCarId) ?: cars.singleOrNull()

/**
 * The garage: every car, and the way into each.
 *
 * The landing screen unless a default car is set. The star on a card makes
 * that car the default, so someone with one car - or one main car - is not
 * made to pass through here every time.
 */
@Composable
fun GarageScreen(
    cars: List<Car>,
    summaries: Map<Int, CarSummary>,
    defaultCarId: Int?,
    locale: Locale,
    onOpenCar: (Car) -> Unit,
    onToggleDefault: (Car) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (cars.isEmpty()) {
        Column(
            modifier = modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Your garage is empty", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Add your first car with +, or bring one back from a backup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            // A fresh install restoring from a backup starts here, so the way
            // in should not need the drawer to be found first.
            OutlinedButton(onClick = onImport) { Text("Import a backup") }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        // Room at the bottom so the last card clears the add button.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Only for the case the default exists to serve: one car and nothing
        // chosen yet. With several cars, landing here is the point.
        if (cars.size == 1 && defaultCarId == null) {
            item {
                Text(
                    "Tap the star to open this car straight away when CarTally starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(items = cars, key = { it.id }) { car ->
            GarageCarCard(
                car = car,
                summary = summaries[car.id],
                isDefault = car.id == defaultCarId,
                locale = locale,
                onOpen = { onOpenCar(car) },
                onToggleDefault = { onToggleDefault(car) }
            )
        }
    }
}

@Composable
private fun GarageCarCard(
    car: Car,
    summary: CarSummary?,
    isDefault: Boolean,
    locale: Locale,
    onOpen: () -> Unit,
    onToggleDefault: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
        ) {
            CarAvatar(car = car, size = 64.dp, shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    car.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    fuelLabel(car),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    garageStats(car, summary, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isDefault) {
                    Text(
                        "Opens when CarTally starts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            IconButton(onClick = onToggleDefault) {
                if (isDefault) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Stop opening ${car.name} at start",
                        // The scheme's star colour: deep amber on white cards,
                        // the logo amber on dark ones. Neither reads on both.
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    Icon(
                        Icons.Outlined.StarBorder,
                        contentDescription = "Open ${car.name} when CarTally starts",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun fuelLabel(car: Car): String =
    if (car.secondaryFuelType != null) "${car.fuelType} / ${car.secondaryFuelType}" else car.fuelType

/**
 * "12 480 km · last fill-up 18 Sep 2026".
 *
 * The odometer is the higher of the latest reading and the car's starting
 * figure, because a fill-up logged by trip distance records no reading and
 * would otherwise show the car as having gone backwards.
 */
fun garageStats(car: Car, summary: CarSummary?, locale: Locale): String {
    val odometer = maxOf(summary?.latestOdometerKm ?: 0, car.initialOdometer)
    val km = "%,d km".format(locale, odometer)
    if (summary == null) return "$km · no fill-ups yet"
    val date = SimpleDateFormat("d MMM yyyy", locale).format(Date(summary.lastFillUpMillis))
    return "$km · last fill-up $date"
}

/** Where a car's thumbnail has got to. */
private sealed interface Thumbnail {
    data object None : Thumbnail
    data object Loading : Thumbnail
    class Ready(val image: ImageBitmap) : Thumbnail
}

/**
 * The car's photo, or its colour where there is no photo, so a car always
 * reads as itself. Shared by the drawer and the garage.
 *
 * The photo is decoded off the main thread, at the size it is shown, and
 * kept - so the garage and the drawer share one decode, and neither holds up
 * the screen while a full camera image is read. While it loads, a neutral
 * placeholder stands in rather than the car's colour, which would flash.
 */
@Composable
fun CarAvatar(car: Car, size: Dp, shape: Shape = CircleShape) {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    val stored = car.imageUri?.takeIf { it.isNotBlank() }

    // produceState keeps its value when the keys change, so each run starts
    // by setting the value for the photo it is now about - otherwise a
    // replaced photo would go on showing the old one.
    val thumbnail by produceState<Thumbnail>(Thumbnail.Loading, stored, targetPx) {
        val cached = stored?.let { cachedCarThumbnail(it, targetPx) }
        value = when {
            stored == null -> Thumbnail.None
            cached != null -> Thumbnail.Ready(cached)
            else -> Thumbnail.Loading
        }
        if (value == Thumbnail.Loading && stored != null) {
            val loaded = withContext(Dispatchers.IO) {
                try { loadCarThumbnail(context, stored, targetPx) } catch (e: Exception) { null }
            }
            value = loaded?.let { Thumbnail.Ready(it) } ?: Thumbnail.None
        }
    }

    when (val t = thumbnail) {
        is Thumbnail.Ready -> Image(
            bitmap = t.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape)
        )
        Thumbnail.Loading -> Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Thumbnail.None -> Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(car.themeColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)
        )
    }
}
