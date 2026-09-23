package se.eliash.cartracker

import androidx.compose.runtime.Composable
import java.util.Locale

/**
 * The dialogs for editing or deleting one fill-up or one expense from the
 * history lists. Nothing shows while both are null.
 *
 * [fuelHistory] is the car's history, newest first, as the list shows it:
 * deleting a fill-up changes what the one before it measures.
 */
@Composable
fun HistoryEditDialogs(
    editingFuelUp: FuelUp?,
    editingExpense: Expense?,
    fuelHistory: List<FuelUp>,
    availableFuels: List<String>,
    currencyLocale: Locale,
    viewModel: FuelViewModel,
    onFuelUpDone: () -> Unit,
    onExpenseDone: () -> Unit
) {
    editingFuelUp?.let { editing ->
        EditFuelUpDialog(
            fuelUp = editing,
            availableFuels = availableFuels,
            // The oldest fill-up has no previous one to have missed.
            canMarkMissed = fuelHistory.lastOrNull()?.id != editing.id,
            onSave = { updated ->
                viewModel.updateFuelEntry(updated)
                onFuelUpDone()
            },
            onDelete = {
                // The entry before this one now covers its distance too, so it
                // has to be marked as following a gap - otherwise that distance
                // is credited to a tankful that never covered it.
                val index = fuelHistory.indexOfFirst { it.id == editing.id }
                if (index > 0) {
                    viewModel.updateFuelEntry(fuelHistory[index - 1].copy(missedPrevious = true))
                }
                viewModel.deleteFuelEntry(editing)
                onFuelUpDone()
            },
            onDismiss = onFuelUpDone
        )
    }

    editingExpense?.let { editing ->
        EditExpenseDialog(
            expense = editing,
            currencyLocale = currencyLocale,
            onSave = { updated ->
                viewModel.updateExpense(original = editing, updated = updated)
                onExpenseDone()
            },
            onDelete = {
                viewModel.deleteExpense(editing)
                onExpenseDone()
            },
            onDismiss = onExpenseDone
        )
    }
}
