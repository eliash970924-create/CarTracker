package se.eliash.cartracker

import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Everything that moves history in or out of the app: the two per-car
 * exports, import, and automatic backup's file and schedule.
 *
 * Each one goes through the system's file picker, which answers through a
 * launcher that has to be registered while composing - so this is built by
 * [rememberBackupActions], and the screens only see what to call.
 */
class BackupActions(
    val exportCsv: (Car) -> Unit,
    val exportZip: (Car) -> Unit,
    val importFile: () -> Unit,
    val autoBackup: AutoBackupState,
    val chooseAutoBackupFile: () -> Unit,
    val setAutoBackupFrequency: (BackupFrequency) -> Unit,
    val backUpNow: () -> Unit,
    val stopAutoBackup: () -> Unit
)

/**
 * [cars] and [selectedCar] are read when a picker answers, not when it
 * opens: a launcher's callback always sees the latest values it was given.
 * The selected car is where a file without a car section is imported to.
 */
@Composable
fun rememberBackupActions(
    viewModel: FuelViewModel,
    prefs: SharedPreferences,
    cars: List<Car>,
    selectedCar: Car?
): BackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Shared with automatic backup: import matches existing rows by this day.
    val formatBackupDate: (Long) -> String = ::formatBackupDay

    // Which car an export is for. Saveable: the system file picker can outlive
    // this screen, and its answer then arrives at a fresh one.
    var exportCarId by rememberSaveable { mutableStateOf<Int?>(null) }

    // Both exports read the chosen car's history from the database rather than
    // what is on screen, so Settings can export any car, not only the open one.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            val car = cars.firstOrNull { c -> c.id == exportCarId } ?: return@let
            scope.launch(Dispatchers.IO) {
                try {
                    val (fuelUps, expenses) = viewModel.historyForExport(car.id)
                    context.contentResolver.openOutputStream(it)
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use { writer ->
                            writeBackupCsv(
                                writer, car, fuelUps, expenses,
                                photoName = null, formatDate = formatBackupDate
                            )
                        }
                    val summary = "Exported ${car.name}: ${fuelUps.size} fill-ups and ${expenses.size} expenses"
                    launch(Dispatchers.Main) { Toast.makeText(context, summary, Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // A second export, because the two serve different ends: the CSV is what
    // opens in a spreadsheet, the zip is what survives losing the phone.
    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            val car = cars.firstOrNull { c -> c.id == exportCarId } ?: return@let
            scope.launch(Dispatchers.IO) {
                try {
                    val (fuelUps, expenses) = viewModel.historyForExport(car.id)
                    context.contentResolver.openOutputStream(it)?.use { output ->
                        writeBackupZip(output, context, car, fuelUps, expenses, formatDate = formatBackupDate)
                    }
                    val withPhoto = car.imageUri?.let { photo -> !isExternalPhotoReference(photo) } ?: false
                    val summary = "Backed up ${car.name}: ${fuelUps.size} fill-ups, " +
                        "${expenses.size} expenses" +
                        if (withPhoto) " and the photo" else ""
                    launch(Dispatchers.Main) { Toast.makeText(context, summary, Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, "Backup failed", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
    fun safeFileName(car: Car) = car.name.replace(" ", "_")

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val fallbackCarId = selectedCar?.id
            val fallbackCarName = selectedCar?.name
            scope.launch(Dispatchers.IO) {
                try {
                    // A zip in either layout - one car or the whole garage - or
                    // a bare CSV: every file the app has written still imports.
                    val backups = readBackup(context) { context.contentResolver.openInputStream(it) }
                    if (backups == null) {
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "Could not read that file", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val parsed = backups.map { backup ->
                        backup to parseBackupCsv(backup.csv) { text ->
                            // parse throws on anything it does not recognise, and
                            // an unreadable row is skipped rather than aborting.
                            runCatching { dateFormat.parse(text)?.time }.getOrNull()
                        }
                    }
                    val unreadable = parsed.sumOf { (_, csv) -> csv.unreadableRows }
                    val imports = parsed.map { (backup, csv) ->
                        FuelViewModel.CarImport(
                            // The stored name comes from the archive read, not the
                            // car row: the photo is saved under a fresh name.
                            car = csv.car?.copy(photo = backup.photoName),
                            fuelUps = csv.fuelUps,
                            expenses = csv.expenses
                        )
                    }

                    viewModel.importBackups(fallbackCarId, imports, dayOf = formatBackupDate) { results ->
                        Toast.makeText(
                            context, importMessage(results, fallbackCarName, unreadable), Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) { launch(Dispatchers.Main) { Toast.makeText(context, "Import failed", Toast.LENGTH_LONG).show() } }
            }
        }
    }

    // Automatic backup: the file is chosen here, once; the scheduled work does
    // the rest. The state follows what the worker records, so Settings updates
    // the moment a backup finishes.
    val autoBackup = rememberAutoBackupState(prefs)
    val autoBackupFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            // Kept across restarts. Without this the grant lasts only until
            // the app is closed, and every scheduled run would fail.
            context.contentResolver.takePersistableUriPermission(uri, BACKUP_URI_FLAGS)
        } catch (e: SecurityException) {
            Toast.makeText(
                context,
                "That place can't be kept for automatic backups. Try Google Drive or the phone's own storage.",
                Toast.LENGTH_LONG
            ).show()
            return@rememberLauncherForActivityResult
        }
        // Let go of the file this replaces, so its grant does not linger.
        autoBackup.uri?.takeIf { it != uri.toString() }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), BACKUP_URI_FLAGS) }
        }
        AutoBackupPrefs.setTarget(prefs, uri.toString(), displayNameOf(context, uri) ?: "Backup file")
        val frequency = autoBackup.frequency.takeUnless { it == BackupFrequency.Off } ?: BackupFrequency.Daily
        AutoBackupPrefs.setFrequency(prefs, frequency)
        AutoBackupScheduler.apply(context, frequency)
        // Straight away: the new file is not left empty, and a place that
        // cannot really be written to shows up now rather than tomorrow.
        AutoBackupScheduler.runNow(context)
    }

    return BackupActions(
        exportCsv = { car ->
            exportCarId = car.id
            exportLauncher.launch("${safeFileName(car)}_History.csv")
        },
        exportZip = { car ->
            exportCarId = car.id
            exportZipLauncher.launch("${safeFileName(car)}_Backup.zip")
        },
        importFile = { importLauncher.launch(arrayOf("*/*")) },
        autoBackup = autoBackup,
        chooseAutoBackupFile = { autoBackupFileLauncher.launch("CarTally_Backup.zip") },
        setAutoBackupFrequency = { frequency ->
            AutoBackupPrefs.setFrequency(prefs, frequency)
            AutoBackupScheduler.apply(context, frequency)
        },
        backUpNow = {
            AutoBackupScheduler.runNow(context)
            Toast.makeText(context, "Backing up...", Toast.LENGTH_SHORT).show()
        },
        stopAutoBackup = {
            autoBackup.uri?.let { current ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(current), BACKUP_URI_FLAGS)
                }
            }
            AutoBackupScheduler.stop(context)
            AutoBackupPrefs.clear(prefs)
        }
    )
}
