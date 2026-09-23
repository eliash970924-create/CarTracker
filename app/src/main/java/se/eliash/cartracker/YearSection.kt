package se.eliash.cartracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun unitFor(fuel: String) = if (fuel == "Electric") "kWh" else "L"

/** "mars", "juli": a month's name in [locale], as the Monthly view writes it. */
private fun monthName(month: Int, locale: Locale): String =
    SimpleDateFormat("MMMM", locale).format(
        Calendar.getInstance().apply { clear(); set(2000, month, 1) }.time
    )

/**
 * Each year the car has been logged, newest first: what it cost, how far it
 * went, and its highlights.
 */
@Composable
fun YearSection(years: List<YearSummary>, locale: Locale, modifier: Modifier = Modifier) {
    if (years.isEmpty()) {
        Text(
            "Nothing logged yet. Each year gets its summary here as soon as it has a fill-up or an expense.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(32.dp)
        )
        return
    }
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(years, key = { _, it -> it.year }) { index, year ->
            // The list is newest first, so the year before is the next one.
            YearCard(year, previous = years.getOrNull(index + 1), locale = locale)
        }
    }
}

@Composable
private fun YearCard(year: YearSummary, previous: YearSummary?, locale: Locale) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (year.partial) "${year.year} so far" else "${year.year}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Figure("Cost", "%,.0f SEK".format(locale, year.totalCost))
                Figure("Driven", "%,.0f km".format(locale, year.distanceKm))
                Figure("Per mil", year.costPerMil?.let { "%.2f kr".format(locale, it) } ?: "-")
            }

            Text(
                "Fuel %,.0f · Other %,.0f SEK · ${year.fillUps} fill-ups".format(locale, year.fuelCost, year.otherCost),
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )

            costPerMilChange(year, previous)?.let { change ->
                val rounded = change.roundToInt()
                Text(
                    when {
                        rounded == 0 -> "Running cost about the same as ${previous!!.year}"
                        rounded > 0 -> "Running cost $rounded% higher than ${previous!!.year}"
                        else -> "Running cost ${abs(rounded)}% lower than ${previous!!.year}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (year.consumption.isNotEmpty() || year.bestTanks.isNotEmpty() ||
                year.priciestMonth != null || year.mostDrivenMonth != null
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
            }

            year.consumption.forEach { c ->
                val best = year.bestTanks.firstOrNull { it.fuel == c.fuel }
                Line(
                    "${c.fuel} average",
                    "%.2f ${unitFor(c.fuel)}/100km".format(locale, c.per100Km)
                )
                best?.let {
                    Line(
                        "  Best tank, " + SimpleDateFormat("d MMM", locale).format(Date(it.dateMillis)),
                        "%.2f ${unitFor(c.fuel)}/100km".format(locale, it.per100Km)
                    )
                }
            }
            year.priciestMonth?.let {
                Line("Priciest month, ${monthName(it.month, locale)}", "%,.0f SEK".format(locale, it.value))
            }
            year.mostDrivenMonth?.let {
                Line("Most driven, ${monthName(it.month, locale)}", "%,.0f km".format(locale, it.value))
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
