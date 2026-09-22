package se.eliash.cartracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Locale

/** The chart sub-tabs, in the order they appear. */
private val SUB_TABS = listOf(
    "Price" to "Fuel Price",
    "Consumption" to "Consumption",
    "Monthly" to "Monthly"
)

/**
 * Fuel price and consumption over time, and the month-by-month overview.
 *
 * [subTab] is passed in rather than held here so the chosen chart survives
 * leaving the tab and coming back, as it did when this lived in the screen.
 */
@Composable
fun ChartsTab(
    car: Car,
    fuelHistory: List<FuelUp>,
    expenses: List<Expense>,
    subTab: String,
    onSubTabChange: (String) -> Unit,
    primaryColor: Color,
    currencyLocale: Locale,
    modifier: Modifier = Modifier
) {
    val series = remember(fuelHistory, car) { calculateChartSeries(car, fuelHistory) }

    // "Now" is captured with the data rather than read on every frame. An app
    // left open across midnight on the last of the month keeps yesterday's
    // answer until something is recorded, which is a fair trade for not
    // recomputing this on every recomposition.
    val overview = remember(fuelHistory, expenses) {
        monthlyOverview(fuelHistory, expenses, Calendar.getInstance())
    }

    fun unitFor(fuel: String?) = if (fuel == "Electric") "kWh" else "L"

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = SUB_TABS.indexOfFirst { it.first == subTab }.coerceAtLeast(0)) {
            SUB_TABS.forEach { (key, label) ->
                Tab(
                    selected = subTab == key,
                    onClick = { onSubTabChange(key) },
                    text = { Text(label) }
                )
            }
        }

        if (subTab == "Monthly") {
            MonthlySection(
                overview = overview,
                currencyLocale = currencyLocale,
                primaryColor = primaryColor,
                modifier = Modifier.fillMaxSize()
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (subTab == "Consumption") {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            } else {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            }
        }
    }
}
