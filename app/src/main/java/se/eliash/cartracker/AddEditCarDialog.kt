package se.eliash.cartracker

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

const val DEFAULT_THEME_COLOR: Long = 0xFF1976D2

val CAR_FUEL_TYPES = listOf("Petrol", "Diesel", "Electric", "Gas", "E85")

private val THEME_PALETTE = listOf(
    0xFF1976D2, 0xFFD32F2F, 0xFF388E3C, 0xFFFBC02D, 0xFF8E24AA, 0xFF424242
)

/**
 * The add/edit car form's contents.
 *
 * Owned by the screen because three places touch it: the dialog itself, the
 * drawer's "Add New Car" button, which clears it, and the top bar's edit
 * button, which fills it from the selected car. Those two used to set the ten
 * fields inline, in two places that had to be kept in step by hand.
 */
@Stable
class CarFormState {
    var name by mutableStateOf("")
    var odometer by mutableStateOf("")
    var nameError by mutableStateOf<String?>(null)
    var odometerError by mutableStateOf<String?>(null)
    var primaryFuel by mutableStateOf(CAR_FUEL_TYPES[0])
    var isBifuel by mutableStateOf(false)
    var secondaryFuel by mutableStateOf("Electric")
    var photo by mutableStateOf<String?>(null)
    var detectedColor by mutableStateOf<Long?>(null)
    var themeColor by mutableStateOf(DEFAULT_THEME_COLOR)

    /** Blank form, for adding a car. */
    fun reset() {
        name = ""
        odometer = ""
        nameError = null
        odometerError = null
        primaryFuel = CAR_FUEL_TYPES[0]
        isBifuel = false
        secondaryFuel = "Electric"
        photo = null
        detectedColor = null
        themeColor = DEFAULT_THEME_COLOR
    }

    /** Fills the form from an existing car, for editing. */
    fun loadFrom(car: Car) {
        name = car.name
        odometer = car.initialOdometer.toString()
        nameError = null
        odometerError = null
        primaryFuel = car.fuelType
        isBifuel = car.secondaryFuelType != null
        secondaryFuel = car.secondaryFuelType ?: "Electric"
        photo = car.imageUri
        // Cleared rather than derived: the swatch offers a colour taken from a
        // photo the user has just picked, and an existing car has not.
        detectedColor = null
        themeColor = car.themeColor ?: DEFAULT_THEME_COLOR
    }

    /** The secondary fuel only counts when the car is marked bifuel. */
    fun secondaryFuelOrNull(): String? = if (isBifuel) secondaryFuel else null
}

@Composable
fun rememberCarFormState(): CarFormState = remember { CarFormState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditCarDialog(
    form: CarFormState,
    isEditMode: Boolean,
    /** How much goes with the car, so the confirmation can say what is at stake. */
    fuelUpCount: Int = 0,
    expenseCount: Int = 0,
    onSave: (name: String, primaryFuel: String, secondaryFuel: String?, odometer: Int, photo: String?, themeColor: Long) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var confirmingDelete by remember { mutableStateOf(false) }
    var primaryExpanded by remember { mutableStateOf(false) }
    var secondaryExpanded by remember { mutableStateOf(false) }
    var showCustomColorSlider by remember { mutableStateOf(false) }
    var customHue by remember { mutableFloatStateOf(0f) }

    val rainbowBrush = Brush.sweepGradient(
        listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
    )

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // Copied into app storage straight away rather than kept as the
            // picker's content:// URI, which is a grant that dies on reinstall.
            form.photo = copyPhotoIntoAppStorage(context, uri)
            try {
                // Only a colour is wanted, so a tiny decode is plenty. It used
                // to read the whole camera image for this, on the main thread.
                // Scaling to one pixel samples near the middle of the photo,
                // which is usually the car.
                val bitmap = loadCarPhoto(context, form.photo, targetPx = 64)
                if (bitmap != null) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                    val averaged = scaled.getPixel(0, 0)
                    scaled.recycle()
                    val hex = Color(averaged).copy(alpha = 1f).toArgb().toLong() and 0xFFFFFFFFL
                    form.detectedColor = hex
                    form.themeColor = hex
                }
            } catch (e: Exception) {
                // A colour could not be read from the photo; the palette still works.
            }
        }
    }

    @Composable
    fun ColourSwatch(colour: Long) {
        val selected = form.themeColor == colour
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(colour))
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) Color.Black else Color.Transparent,
                    shape = CircleShape
                )
                .clickable { form.themeColor = colour; showCustomColorSlider = false }
        )
    }

    if (confirmingDelete) {
        val carName = form.name.ifBlank { "this car" }
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete $carName?") },
            text = {
                val parts = buildList {
                    if (fuelUpCount > 0) {
                        add("$fuelUpCount ${if (fuelUpCount == 1) "fill-up" else "fill-ups"}")
                    }
                    if (expenseCount > 0) {
                        add("$expenseCount ${if (expenseCount == 1) "expense" else "expenses"}")
                    }
                }
                Text(
                    if (parts.isEmpty()) {
                        "Nothing is logged against it yet, so only the car itself goes."
                    } else {
                        // The one way back, and only if it was taken before
                        // the tap rather than after.
                        "Everything logged against it goes too: " +
                            "${parts.joinToString(" and ")}. This cannot be undone.\n\n" +
                            "A backup exported earlier can be imported into a new car."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmingDelete = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditMode) "Edit Car Details" else "Add New Car") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        pickMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (form.photo == null) "Upload Car Photo" else "Change Photo")
                }

                Text("Select App Theme Color", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    form.detectedColor?.let { ColourSwatch(it) }
                    THEME_PALETTE.forEach { ColourSwatch(it) }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(rainbowBrush)
                            .clickable { showCustomColorSlider = !showCustomColorSlider }
                    )
                }

                if (showCustomColorSlider) {
                    Column {
                        Slider(
                            value = customHue,
                            onValueChange = {
                                customHue = it
                                form.themeColor = Color.hsv(it, 1f, 1f).toArgb().toLong() and 0xFFFFFFFFL
                            },
                            valueRange = 0f..360f
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                                    )
                                )
                        )
                    }
                }

                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form.name = it; form.nameError = null },
                    label = { Text("Car Name") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = form.nameError != null,
                    supportingText = { form.nameError?.let { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                ExposedDropdownMenuBox(
                    expanded = primaryExpanded,
                    onExpandedChange = { primaryExpanded = !primaryExpanded }
                ) {
                    OutlinedTextField(
                        value = form.primaryFuel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Primary Fuel") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(primaryExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = primaryExpanded,
                        onDismissRequest = { primaryExpanded = false }
                    ) {
                        CAR_FUEL_TYPES.forEach { fuel ->
                            DropdownMenuItem(
                                text = { Text(fuel) },
                                onClick = { form.primaryFuel = fuel; primaryExpanded = false }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = form.isBifuel, onCheckedChange = { form.isBifuel = it })
                    Text("Bifuel / Hybrid vehicle")
                }

                if (form.isBifuel) {
                    ExposedDropdownMenuBox(
                        expanded = secondaryExpanded,
                        onExpandedChange = { secondaryExpanded = !secondaryExpanded }
                    ) {
                        OutlinedTextField(
                            value = form.secondaryFuel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Secondary Fuel") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(secondaryExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = secondaryExpanded,
                            onDismissRequest = { secondaryExpanded = false }
                        ) {
                            CAR_FUEL_TYPES.forEach { fuel ->
                                DropdownMenuItem(
                                    text = { Text(fuel) },
                                    onClick = { form.secondaryFuel = fuel; secondaryExpanded = false }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = form.odometer,
                    onValueChange = { form.odometer = it; form.odometerError = null },
                    label = { Text("Current Odometer (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = form.odometerError != null,
                    supportingText = { form.odometerError?.let { Text(it) } }
                )

                if (isEditMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        // Asks first. This button sits directly under a field
                        // worth editing, and the car takes every fill-up and
                        // expense logged against it on the way out.
                        onClick = { confirmingDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                    ) {
                        Text("Delete Car")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val odometer = form.odometer.toIntOrNull()
                if (form.name.isBlank()) form.nameError = "Car name is required"
                if (odometer == null) form.odometerError = "Valid odometer required"

                if (form.name.isNotBlank() && odometer != null) {
                    onSave(
                        form.name,
                        form.primaryFuel,
                        form.secondaryFuelOrNull(),
                        odometer,
                        form.photo,
                        form.themeColor
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
