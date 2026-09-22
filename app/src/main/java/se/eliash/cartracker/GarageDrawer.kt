package se.eliash.cartracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The navigation drawer: the cars, the views of the open one, and Settings.
 *
 * Import, export and the theme moved to Settings; the drawer is for getting
 * around. Every callback is the screen's to act on, including closing the
 * drawer, so this holds no state of its own. Choosing a car, a view or
 * Settings closes the drawer, while adding a car does not - the dialog opens
 * over it and the drawer is still there behind.
 */
@Composable
fun GarageDrawer(
    cars: List<Car>,
    selectedCar: Car?,
    currentTab: String,
    onSelectCar: (Car) -> Unit,
    onOpenGarage: () -> Unit,
    onAddCar: () -> Unit,
    onSelectTab: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        // Scrolls, so a garage with many cars still reaches Settings on a
        // short screen instead of cutting it off.
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Your Garage",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.headlineMedium
            )
            // The back gesture also leads here; this is the way that can be seen.
            if (selectedCar != null) {
                NavigationDrawerItem(
                    label = { Text("All cars") },
                    selected = false,
                    onClick = onOpenGarage,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            HorizontalDivider()

            cars.forEach { car ->
                val isSelected = car.id == selectedCar?.id

                Surface(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable { onSelectCar(car) },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        CarAvatar(car = car, size = 56.dp)
                        Spacer(modifier = Modifier.width(16.dp))

                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                car.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(fuelLabel(car), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAddCar,
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            ) { Text("Add New Car") }

            if (selectedCar != null) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Text(
                    "Views",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                listOf(
                    "Entries" to "Log & History",
                    "Expenses" to "Service & Expenses",
                    "Charts" to "Charts & Graphs"
                ).forEach { (tab, label) ->
                    NavigationDrawerItem(
                        label = { Text(label) },
                        selected = currentTab == tab,
                        onClick = { onSelectTab(tab) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            NavigationDrawerItem(
                label = { Text("Settings") },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                selected = false,
                onClick = onOpenSettings,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
