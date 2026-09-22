package se.eliash.cartracker

import java.util.Calendar

/**
 * What makes two rows the same recurring expense: category and description,
 * never the cost.
 *
 * Including the cost - as this once did - makes a price change look like a
 * separate expense: the old amount keeps generating beside the new one, every
 * month, for ever. An insurance premium going up silently doubled the bill.
 *
 * [missingRecurringExpenses] and [seriesOf] must agree on this, or switching a
 * series off would leave behind a row the generator still treats as a seed.
 */
fun recurringKey(expense: Expense): String = "${expense.category}_${expense.description}"

/**
 * Every row belonging to the same recurring expense as [member], the row
 * itself included.
 *
 * Switching a repeat off has to clear the flag across all of them. Clearing it
 * on one row only moves the problem: the generator seeds from the latest row
 * still marked monthly, so the next one down takes over and fills the months
 * back in.
 *
 * Rows are matched on [member]'s own key, so this still finds the series when
 * the caller is part-way through renaming it.
 */
fun seriesOf(expenses: List<Expense>, member: Expense): List<Expense> {
    val key = recurringKey(member)
    return expenses.filter { recurringKey(it) == key }
}

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

    val grouped = monthly.groupBy(::recurringKey)

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
