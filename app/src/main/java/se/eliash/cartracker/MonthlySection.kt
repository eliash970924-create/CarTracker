package se.eliash.cartracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Wider than the bar needs to be, because the column is also the tap target.
private val BAR_WIDTH = 36.dp
private val BAR_MAX_HEIGHT = 140.dp

/**
 * What the car has cost and covered, month by month.
 *
 * The charts run oldest to newest, the way a timeline reads; the list below
 * runs newest first, because that is the month being asked about.
 */
@Composable
fun MonthlySection(
    overview: MonthlyOverview,
    currencyLocale: Locale,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    if (overview.months.isEmpty()) {
        Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                "Nothing to summarise yet. A month shows up here once it has " +
                    "ended - the one in progress is left out so it cannot drag " +
                    "the average down.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // Newest first, and a stable key so a new month does not recycle a row.
    val newestFirst = remember(overview.months) { overview.months.asReversed() }

    // Shared by both charts, so tapping March on one reads March on the other.
    // Starts on the latest month, which is the figure being asked for when the
    // tab is opened - the numbers are there before anything is tapped.
    var selected by remember(overview.months) {
        mutableIntStateOf(overview.months.lastIndex)
    }

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { AverageCard(overview, currencyLocale) }

        item {
            MonthlyBarChart(
                title = "Cost per month",
                months = overview.months,
                valueOf = { it.totalCost },
                formatValue = { "%.0f SEK".format(currencyLocale, it) },
                barColor = primaryColor,
                locale = currencyLocale,
                selectedIndex = selected,
                onSelect = { selected = it }
            )
        }

        item {
            MonthlyBarChart(
                title = "Distance per month",
                months = overview.months,
                valueOf = { it.distanceKm },
                formatValue = { "%.0f km".format(currencyLocale, it) },
                barColor = MaterialTheme.colorScheme.tertiary,
                locale = currencyLocale,
                selectedIndex = selected,
                onSelect = { selected = it }
            )
        }

        items(items = newestFirst, key = { it.year * 12 + it.month }) { month ->
            MonthRow(month, currencyLocale)
        }
    }
}

@Composable
private fun AverageCard(overview: MonthlyOverview, currencyLocale: Locale) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AverageFigure(
                    label = "Average per month",
                    value = "%.0f SEK".format(currencyLocale, overview.averageMonthlyCost)
                )
                AverageFigure(
                    label = "Driven per month",
                    value = "%.0f km".format(currencyLocale, overview.averageMonthlyDistance)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            val monthWord = if (overview.months.size == 1) "month" else "months"
            Text(
                "Over ${overview.months.size} complete $monthWord",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AverageFigure(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

/**
 * Bars laid out as composables rather than drawn on a Canvas, so each one can
 * carry a real month label underneath.
 *
 * The bars are a fixed width and scroll sideways, which keeps them readable
 * whether the history is three months or three years.
 *
 * A bar on its own only shows a shape. The selected month's figure is spelled
 * out above the chart, starting on the latest month, so a number is there
 * without tapping anything and any other month is one tap away.
 */
@Composable
private fun MonthlyBarChart(
    title: String,
    months: List<MonthSummary>,
    valueOf: (MonthSummary) -> Double,
    formatValue: (Double) -> String,
    barColor: Color,
    locale: Locale,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val values = remember(months) { months.map(valueOf) }
    val fractions = remember(values) { barFractions(values) }
    val scrollState = rememberScrollState()

    // Opens on the most recent months, which are the ones being asked about.
    // maxValue is a key because it is zero until the row has been measured.
    LaunchedEffect(scrollState.maxValue) { scrollState.scrollTo(scrollState.maxValue) }

    val peakIndex = values.indices.maxByOrNull { values[it] }
    val safeSelected = selectedIndex.coerceIn(0, months.lastIndex)

    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    monthLabel(months[safeSelected], locale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatValue(values[safeSelected]),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                months.forEachIndexed { index, month ->
                    val isSelected = index == safeSelected
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        // The whole column is the target, not the bar: a month
                        // with nothing in it draws a two-pixel sliver, which
                        // is not something anyone can tap.
                        modifier = Modifier
                            .width(BAR_WIDTH)
                            .clickable { onSelect(index) }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(BAR_MAX_HEIGHT),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // A month with nothing in it still gets a
                                    // sliver, so the gap reads as a month
                                    // rather than as missing data.
                                    .height(barHeight(fractions[index]))
                                    .background(
                                        if (isSelected) barColor else barColor.copy(alpha = 0.35f),
                                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            shortMonthLabel(month, locale),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }

            if (peakIndex != null && values[peakIndex] > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Highest: ${monthLabel(months[peakIndex], locale)} - " +
                        formatValue(values[peakIndex]),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun barHeight(fraction: Float): Dp =
    (BAR_MAX_HEIGHT * fraction).coerceAtLeast(2.dp)

@Composable
private fun MonthRow(month: MonthSummary, currencyLocale: Locale) {
    val quiet = month.totalCost == 0.0 && month.distanceKm == 0.0

    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    monthLabel(month, currencyLocale),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "%.2f SEK".format(currencyLocale, month.totalCost),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (quiet) {
                Text(
                    "Nothing recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        buildString {
                            append("%.0f km".format(currencyLocale, month.distanceKm))
                            month.costPerMil?.let {
                                append(" - %.2f kr/mil".format(currencyLocale, it))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Fuel %.0f - Other %.0f".format(
                            currencyLocale, month.fuelCost, month.expenseCost
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
