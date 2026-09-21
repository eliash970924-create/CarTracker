package com.example.cartracker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The navigation drawer: the garage, the view switcher and backup.
 *
 * Every callback is the screen's to act on, including closing the drawer, so
 * this holds no state of its own. Note that choosing a car, a view, or import
 * or export closes the drawer, while adding a car does not - the dialog opens
 * over it and the drawer is still there behind.
 */
@Composable
fun GarageDrawer(
    cars: List<Car>,
    selectedCar: Car?,
    currentTab: String,
    hasDataToExport: Boolean,
    onSelectCar: (Car) -> Unit,
    onAddCar: () -> Unit,
    onSelectTab: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportBackup: () -> Unit
) {
    val context = LocalContext.current

    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        Text(
            "Your Garage",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.headlineMedium
        )
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
                    val photo = remember(car.imageUri) {
                        try {
                            loadCarPhoto(context, car.imageUri)?.asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (photo != null) {
                        Image(
                            bitmap = photo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                        )
                    } else {
                        // Falls back to the car's colour, so a car without a
                        // photo still reads as itself in the list.
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    car.themeColor?.let { Color(it) }
                                        ?: MaterialTheme.colorScheme.primary
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))

                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            car.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (car.secondaryFuelType != null) {
                                "${car.fuelType} / ${car.secondaryFuelType}"
                            } else {
                                car.fuelType
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
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

        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider()
        Text(
            "Data Management",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        // Offered with no car selected too: a backup carries its own car
        // details, so restoring onto a fresh install no longer means
        // recreating the car by hand first.
        NavigationDrawerItem(
            label = { Text(if (selectedCar != null) "Import from file" else "Import a car from file") },
            selected = false,
            onClick = onImport,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        if (selectedCar != null && hasDataToExport) {
            // Two exports on purpose: the CSV opens in a spreadsheet, the zip
            // carries the photo and is the one to keep if the phone is lost.
            NavigationDrawerItem(
                label = { Text("Export ${selectedCar.name} to CSV") },
                selected = false,
                onClick = onExport,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Full backup of ${selectedCar.name} (.zip)") },
                selected = false,
                onClick = onExportBackup,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
