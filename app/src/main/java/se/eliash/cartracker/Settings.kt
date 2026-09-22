package se.eliash.cartracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.eliash.cartracker.ui.theme.ThemeMode

/**
 * Settings: everything about the app rather than about one car.
 *
 * Import, export and the theme used to live in the drawer, which had become
 * wherever things ended up rather than where anyone would look for them.
 * Every choice here acts the moment it is made; there is no save button.
 *
 * Exports are offered for each car, not only the open one - the history is
 * read from the database for the car chosen, so Settings needs no car open.
 */
@Composable
fun SettingsScreen(
    cars: List<Car>,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    defaultCarId: Int?,
    onDefaultCarChange: (Int?) -> Unit,
    onImport: () -> Unit,
    onBackup: (Car) -> Unit,
    onExportCsv: (Car) -> Unit,
    versionName: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        SectionHeader("Appearance")
        ListItem(
            headlineContent = { Text("Theme") },
            supportingContent = { Text("Follow the phone, or keep it light or dark") }
        )
        ThemeModePicker(
            selected = themeMode,
            onSelect = onThemeModeChange,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider()
        SectionHeader("Start-up")
        ListItem(
            headlineContent = { Text("Open at start") },
            supportingContent = { Text("The same as the star in the garage") }
        )
        Column(modifier = Modifier.selectableGroup()) {
            StartOption(
                label = "The garage",
                selected = defaultCarId == null,
                onClick = { onDefaultCarChange(null) }
            )
            cars.forEach { car ->
                StartOption(
                    label = car.name,
                    selected = defaultCarId == car.id,
                    onClick = { onDefaultCarChange(car.id) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider()
        SectionHeader("Backup & restore")
        ListItem(
            headlineContent = { Text("Import from a file") },
            supportingContent = {
                Text("A backup (.zip) or a CSV export. Anything already here is skipped; nothing is deleted.")
            },
            leadingContent = { Icon(Icons.Outlined.Restore, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onImport)
        )
        if (cars.isEmpty()) {
            ListItem(
                headlineContent = { Text("Nothing to back up yet") },
                supportingContent = { Text("Add a car first, or import one above.") }
            )
        }
        cars.forEach { car ->
            ListItem(
                headlineContent = { Text("Back up ${car.name}") },
                supportingContent = { Text("Everything, photo included (.zip) - the one to keep") },
                leadingContent = { Icon(Icons.Outlined.Backup, contentDescription = null) },
                modifier = Modifier.clickable { onBackup(car) }
            )
            ListItem(
                headlineContent = { Text("Export ${car.name} to CSV") },
                supportingContent = { Text("Opens in a spreadsheet. No photo.") },
                leadingContent = { Icon(Icons.Outlined.TableChart, contentDescription = null) },
                modifier = Modifier.clickable { onExportCsv(car) }
            )
        }

        HorizontalDivider()
        SectionHeader("About")
        ListItem(
            headlineContent = { Text("CarTally") },
            supportingContent = { Text(versionName?.let { "Version $it" } ?: "Version unknown") },
            leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/** A whole-row radio choice: the row is the target, not just the dot. */
@Composable
private fun StartOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // The row handles the click, so the button itself must not.
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        ThemeMode.System to "System",
        ThemeMode.Light to "Light",
        ThemeMode.Dark to "Dark"
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label, maxLines = 1) }
            )
        }
    }
}
