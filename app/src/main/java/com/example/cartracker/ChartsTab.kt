package com.example.cartracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fuel price and consumption over time.
 *
 * [subTab] is passed in rather than held here so the chosen chart survives
 * leaving the tab and coming back, as it did when this lived in the screen.
 */
@Composable
fun ChartsTab(
    car: Car,
    fuelHistory: List<FuelUp>,
    subTab: String,
    onSubTabChange: (String) -> Unit,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    val series = remember(fuelHistory, car) { calculateChartSeries(car, fuelHistory) }

    fun unitFor(fuel: String?) = if (fuel == "Electric") "kWh" else "L"

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = if (subTab == "Price") 0 else 1) {
            Tab(
                selected = subTab == "Price",
                onClick = { onSubTabChange("Price") },
                text = { Text("Fuel Price") }
            )
            Tab(
                selected = subTab == "Consumption",
                onClick = { onSubTabChange("Consumption") },
                text = { Text("Consumption") }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (subTab == "Price") {
                if (series.primaryPrices.size >= 2) {
                    item {
                        NativeLineChart(
                            data = series.primaryPrices,
                            title = "${car.fuelType} Price (SEK/${unitFor(car.fuelType)})",
                            lineColor = primaryColor
                        )
                    }
                } else {
                    item {
                        Text(
                            "Add at least 2 ${car.fuelType} entries to generate a chart.",
                            color = Color.Gray
                        )
                    }
                }
                if (car.secondaryFuelType != null && series.secondaryPrices.size >= 2) {
                    item {
                        NativeLineChart(
                            data = series.secondaryPrices,
                            title = "${car.secondaryFuelType} Price (SEK/${unitFor(car.secondaryFuelType)})",
                            lineColor = Color(0xFF1976D2)
                        )
                    }
                }
            } else {
                if (series.primaryConsumption.size >= 2) {
                    item {
                        NativeLineChart(
                            data = series.primaryConsumption,
                            title = "${car.fuelType} Consumption (${unitFor(car.fuelType)}/100km)",
                            lineColor = primaryColor
                        )
                    }
                } else {
                    item {
                        Text(
                            "Add at least 2 consecutive ${car.fuelType} entries to generate a chart.",
                            color = Color.Gray
                        )
                    }
                }
                if (car.secondaryFuelType != null && series.secondaryConsumption.size >= 2) {
                    item {
                        NativeLineChart(
                            data = series.secondaryConsumption,
                            title = "${car.secondaryFuelType} Consumption (${unitFor(car.secondaryFuelType)}/100km)",
                            lineColor = Color(0xFFFBC02D)
                        )
                    }
                }
            }
        }
    }
}
