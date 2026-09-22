package se.eliash.cartracker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val EXPENSE_CATEGORIES = listOf(
    "Maintenance", "Tires", "Insurance", "Parking", "Wash", "Tolls", "Other"
)

/**
 * The expense entry form's contents.
 *
 * Held by the screen rather than by [ExpensesTab] so a half-filled form
 * survives switching tabs and coming back, which is how it behaved when
 * these were loose variables in the screen.
 */
@Stable
class ExpenseFormState {
    var dateMillis by mutableLongStateOf(System.currentTimeMillis())
    var category by mutableStateOf(EXPENSE_CATEGORIES[0])
    var description by mutableStateOf("")
    var cost by mutableStateOf("")
    var isMonthly by mutableStateOf(false)
    var categoryExpanded by mutableStateOf(false)
    var showDatePicker by mutableStateOf(false)

    /** Resets after a save, leaving the category where the user left it. */
    fun clear() {
        description = ""
        cost = ""
        isMonthly = false
        dateMillis = System.currentTimeMillis()
    }
}

@Composable
fun rememberExpenseFormState(): ExpenseFormState = remember { ExpenseFormState() }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExpensesTab(
    expenses: List<Expense>,
    form: ExpenseFormState,
    currencyLocale: Locale,
    onSave: (dateMillis: Long, category: String, description: String, cost: Double, isMonthly: Boolean) -> Unit,
    onDelete: (Expense) -> Unit,
    modifier: Modifier = Modifier
) {
    val total = remember(expenses) { expenses.sumOf { it.costSek } }
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    if (form.showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = form.dateMillis)
        DatePickerDialog(
            onDismissRequest = { form.showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { form.dateMillis = it }
                    form.showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { form.showDatePicker = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = dpState) }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = dateFormat.format(Date(form.dateMillis)),
                onValueChange = {},
                readOnly = true,
                label = { Text("Date") },
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    IconButton(onClick = { form.showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, null)
                    }
                }
            )
            ExposedDropdownMenuBox(
                expanded = form.categoryExpanded,
                onExpandedChange = { form.categoryExpanded = !form.categoryExpanded },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = form.category,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(form.categoryExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = form.categoryExpanded,
                    onDismissRequest = { form.categoryExpanded = false }
                ) {
                    EXPENSE_CATEGORIES.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category) },
                            onClick = { form.category = category; form.categoryExpanded = false }
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.description,
                onValueChange = { form.description = it },
                label = { Text("Description (Optional)") },
                modifier = Modifier.weight(1.5f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences
                )
            )
            OutlinedTextField(
                value = form.cost,
                onValueChange = { form.cost = it },
                label = { Text("Cost (SEK)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = form.isMonthly, onCheckedChange = { form.isMonthly = it })
            Text("Repeats automatically every month", style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = {
                val cost = form.cost.replace(',', '.').toDoubleOrNull() ?: 0.0
                if (cost > 0) {
                    onSave(form.dateMillis, form.category, form.description, cost, form.isMonthly)
                    form.clear()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Expense") }

        HorizontalDivider()

        if (expenses.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Total Non-Fuel Expenses", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "%.2f SEK".format(currencyLocale, total),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items = expenses, key = { _, item -> item.id }) { _, expense ->
                Card(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = {},
                        onLongClick = { onDelete(expense) }
                    ),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                dateFormat.format(Date(expense.dateMillis)),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                if (expense.isMonthly) "🔄 ${expense.category}" else expense.category,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                expense.description.ifEmpty { "No description" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (expense.description.isEmpty()) Color.Gray else Color.Unspecified
                            )
                            Text(
                                "${"%.2f".format(currencyLocale, expense.costSek)} SEK",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
