package com.example.cartracker

import java.util.Calendar

/**
 * Works out which months of a recurring expense are missing, so they can be
 * filled in up to the current month.
 *
 * Kept separate from the database so the rule can be tested: getting it wrong
 * quietly corrupts the expense totals rather than failing visibly.
 */
fun missingRecurringExpenses(expenses: List<Expense>, now: Calendar): List<Expense> {
    val monthly = expenses.filter { it.isMonthly }
    if (monthly.isEmpty()) return emptyList()

    // Grouped by category and description only. Including the cost - as this
    // once did - makes a price change look like a separate expense: the old
    // amount keeps generating beside the new one, every month, for ever. An
    // insurance premium going up silently doubled the bill.
    val grouped = monthly.groupBy { "${it.category}_${it.description}" }

    val generated = mutableListOf<Expense>()

    grouped.values.forEach { group ->
        // Latest month wins; where two rows share it, the one entered later
        // does, which is the one carrying the new price. Comparing on date
        // alone leaves the winner arbitrary in exactly the month a price
        // changes, which is the month that decides every month after it.
        val latest = group.maxWithOrNull(
            compareBy<Expense>({ it.dateMillis }, { it.id })
        ) ?: return@forEach

        val cursor = Calendar.getInstance().apply { timeInMillis = latest.dateMillis }

        while (true) {
            val year = cursor.get(Calendar.YEAR)
            val month = cursor.get(Calendar.MONTH)
            val targetYear = now.get(Calendar.YEAR)
            val targetMonth = now.get(Calendar.MONTH)

            if (year < targetYear || (year == targetYear && month < targetMonth)) {
                cursor.add(Calendar.MONTH, 1)
                generated.add(latest.copy(id = 0, dateMillis = cursor.timeInMillis))
            } else {
                break
            }
        }
    }

    return generated
}
