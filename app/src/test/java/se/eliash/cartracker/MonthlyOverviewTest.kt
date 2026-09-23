package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.Calendar
import java.util.Locale
import org.junit.Test

class MonthlyOverviewTest {

    private val delta = 0.5

    private fun at(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance().apply { clear(); set(year, month, day) }

    private fun fill(
        year: Int, month: Int, day: Int,
        odometer: Int,
        cost: Double = 0.0,
        fuel: String = "Petrol",
        missed: Boolean = false
    ) = FuelUp(
        carId = 1,
        fuelTypeUsed = fuel,
        dateMillis = at(year, month, day).timeInMillis,
        odometerKm = odometer,
        litersFilled = 40.0,
        pricePerLiterSek = 18.0,
        totalCostSek = cost,
        missedPrevious = missed
    )

    private fun expense(year: Int, month: Int, day: Int, cost: Double, monthly: Boolean = false) =
        Expense(
            carId = 1,
            dateMillis = at(year, month, day).timeInMillis,
            category = "Insurance",
            description = "",
            costSek = cost,
            isMonthly = monthly
        )

    private fun monthOf(overview: MonthlyOverview, year: Int, month: Int) =
        overview.months.single { it.year == year && it.month == month }

    @Test
    fun `a tank spanning a month boundary is shared between the two months`() {
        val overview = monthlyOverview(
            fuelUps = listOf(
                fill(2026, Calendar.JANUARY, 28, odometer = 10000),
                fill(2026, Calendar.FEBRUARY, 20, odometer = 11000)
            ),
            expenses = emptyList(),
            now = at(2026, Calendar.MARCH, 10)
        )
        // 1000 km over 23 days of driving. A day belongs to the month it
        // starts in, so the 28th, 29th, 30th and 31st are January's four,
        // and the 1st to the 19th are February's nineteen.
        assertEquals(1000.0 * 4 / 23, monthOf(overview, 2026, Calendar.JANUARY).distanceKm, delta)
        assertEquals(1000.0 * 19 / 23, monthOf(overview, 2026, Calendar.FEBRUARY).distanceKm, delta)
    }

    @Test
    fun `no distance is lost when it is split`() {
        val overview = monthlyOverview(
            fuelUps = listOf(
                fill(2026, Calendar.JANUARY, 10, odometer = 10000),
                fill(2026, Calendar.MARCH, 5, odometer = 12400)
            ),
            expenses = emptyList(),
            now = at(2026, Calendar.APRIL, 1)
        )
        assertEquals(2400.0, overview.months.sumOf { it.distanceKm }, delta)
    }

    @Test
    fun `fuel and expenses land in the month they were recorded`() {
        val overview = monthlyOverview(
            fuelUps = listOf(fill(2026, Calendar.JANUARY, 15, odometer = 10000, cost = 740.0)),
            expenses = listOf(expense(2026, Calendar.JANUARY, 20, 8000.0)),
            now = at(2026, Calendar.FEBRUARY, 1)
        )
        val january = monthOf(overview, 2026, Calendar.JANUARY)
        assertEquals(740.0, january.fuelCost, 0.001)
        assertEquals(8000.0, january.expenseCost, 0.001)
        assertEquals(8740.0, january.totalCost, 0.001)
    }

    @Test
    fun `a quiet month is present with zeroes rather than missing`() {
        val overview = monthlyOverview(
            fuelUps = listOf(
                fill(2026, Calendar.JANUARY, 10, odometer = 10000, cost = 700.0),
                fill(2026, Calendar.MARCH, 10, odometer = 10600, cost = 700.0)
            ),
            expenses = emptyList(),
            now = at(2026, Calendar.APRIL, 1)
        )
        assertEquals(3, overview.months.size)
        // February recorded no fill-up but still carries its share of the drive.
        val february = monthOf(overview, 2026, Calendar.FEBRUARY)
        assertEquals(0.0, february.fuelCost, 0.001)
        assertTrue(february.distanceKm > 0)
    }

    @Test
    fun `the average counts quiet months, so it is not flattered by them`() {
        val overview = monthlyOverview(
            fuelUps = listOf(fill(2026, Calendar.JANUARY, 10, odometer = 10000, cost = 900.0)),
            expenses = emptyList(),
            now = at(2026, Calendar.APRIL, 1)
        )
        // January, February and March: 900 kr over three months, not over one.
        assertEquals(3, overview.months.size)
        assertEquals(300.0, overview.averageMonthlyCost, 0.001)
    }

    @Test
    fun `the current month is left out of the overview`() {
        val overview = monthlyOverview(
            fuelUps = listOf(
                fill(2026, Calendar.JANUARY, 10, odometer = 10000, cost = 600.0),
                fill(2026, Calendar.FEBRUARY, 10, odometer = 10500, cost = 600.0)
            ),
            expenses = emptyList(),
            now = at(2026, Calendar.FEBRUARY, 20)
        )
        // Only January is complete; February is still running.
        assertEquals(1, overview.months.size)
        assertEquals(2026, overview.months.single().year)
        assertEquals(Calendar.JANUARY, overview.months.single().month)
    }

    @Test
    fun `records only in the current month give an empty overview, not a wrong one`() {
        val overview = monthlyOverview(
            fuelUps = listOf(fill(2026, Calendar.FEBRUARY, 10, odometer = 10000, cost = 600.0)),
            expenses = emptyList(),
            now = at(2026, Calendar.FEBRUARY, 20)
        )
        assertEquals(emptyList<MonthSummary>(), overview.months)
        assertEquals(0.0, overview.averageMonthlyCost, 0.001)
    }

    @Test
    fun `a missed fill-up still contributes its distance`() {
        val overview = monthlyOverview(
            fuelUps = listOf(
                fill(2026, Calendar.JANUARY, 5, odometer = 10000),
                fill(2026, Calendar.JANUARY, 25, odometer = 10800, missed = true)
            ),
            expenses = emptyList(),
            now = at(2026, Calendar.FEBRUARY, 1)
        )
        // The fuel is unknown, but the 800 km were still driven, and this is
        // a question about distance rather than consumption.
        assertEquals(800.0, monthOf(overview, 2026, Calendar.JANUARY).distanceKm, delta)
    }

    @Test
    fun `a hybrid's distance is counted once, not once per fuel`() {
        val overview = monthlyOverview(
            fuelUps = listOf(
                fill(2026, Calendar.JANUARY, 5, odometer = 10000, fuel = "Petrol"),
                fill(2026, Calendar.JANUARY, 12, odometer = 10300, fuel = "Electric"),
                fill(2026, Calendar.JANUARY, 20, odometer = 10500, fuel = "Petrol")
            ),
            expenses = emptyList(),
            now = at(2026, Calendar.FEBRUARY, 1)
        )
        // The odometer went 10000 to 10500 however it was fuelled.
        assertEquals(500.0, monthOf(overview, 2026, Calendar.JANUARY).distanceKm, delta)
    }

    @Test
    fun `entries without an odometer reading contribute no distance`() {
        val overview = monthlyOverview(
            fuelUps = listOf(
                fill(2026, Calendar.JANUARY, 5, odometer = 0, cost = 500.0),
                fill(2026, Calendar.JANUARY, 20, odometer = 0, cost = 500.0)
            ),
            expenses = emptyList(),
            now = at(2026, Calendar.FEBRUARY, 1)
        )
        val january = monthOf(overview, 2026, Calendar.JANUARY)
        assertEquals(0.0, january.distanceKm, 0.001)
        // The cost is still known, so it is still counted.
        assertEquals(1000.0, january.fuelCost, 0.001)
        assertNull(january.costPerMil)
    }

    @Test
    fun `cost per mil is reported for a month that recorded distance`() {
        val overview = monthlyOverview(
            fuelUps = listOf(
                fill(2026, Calendar.JANUARY, 5, odometer = 10000, cost = 0.0),
                fill(2026, Calendar.JANUARY, 25, odometer = 11000, cost = 1000.0)
            ),
            expenses = emptyList(),
            now = at(2026, Calendar.FEBRUARY, 1)
        )
        // 1000 kr over 1000 km is 10 kr/mil.
        assertEquals(10.0, monthOf(overview, 2026, Calendar.JANUARY).costPerMil!!, 0.01)
    }

    @Test
    fun `a year boundary is walked correctly`() {
        val overview = monthlyOverview(
            fuelUps = listOf(fill(2025, Calendar.NOVEMBER, 10, odometer = 10000, cost = 600.0)),
            expenses = emptyList(),
            now = at(2026, Calendar.JANUARY, 15)
        )
        assertEquals(2, overview.months.size)
        assertEquals(2025 to Calendar.NOVEMBER, overview.months.first().let { it.year to it.month })
        assertEquals(2025 to Calendar.DECEMBER, overview.months.last().let { it.year to it.month })
    }

    @Test
    fun `no records at all gives an empty overview`() {
        val overview = monthlyOverview(emptyList(), emptyList(), at(2026, Calendar.JUNE, 1))
        assertEquals(emptyList<MonthSummary>(), overview.months)
        assertEquals(0.0, overview.averageMonthlyCost, 0.001)
        assertEquals(0.0, overview.averageMonthlyDistance, 0.001)
    }

    // --- what the overview screen draws with ---

    private fun summary(
        year: Int, month: Int,
        fuelCost: Double = 0.0,
        expenseCost: Double = 0.0,
        distanceKm: Double = 0.0
    ) = MonthSummary(year, month, fuelCost, expenseCost, distanceKm)

    @Test
    fun `bar fractions scale from zero, not from the smallest value`() {
        val fractions = barFractions(listOf(100.0, 50.0, 25.0))
        assertEquals(1f, fractions[0], 0.001f)
        assertEquals(0.5f, fractions[1], 0.001f)
        assertEquals(0.25f, fractions[2], 0.001f)
    }

    @Test
    fun `a run of zeroes gives flat bars rather than dividing by zero`() {
        assertEquals(listOf(0f, 0f, 0f), barFractions(listOf(0.0, 0.0, 0.0)))
    }

    @Test
    fun `no months means no bars`() {
        assertEquals(emptyList<Float>(), barFractions(emptyList()))
    }

    @Test
    fun `a single month fills the chart`() {
        assertEquals(listOf(1f), barFractions(listOf(742.0)))
    }

    @Test
    fun `a quiet month among busy ones is drawn at zero`() {
        val fractions = barFractions(listOf(800.0, 0.0, 400.0))
        assertEquals(0f, fractions[1], 0.001f)
        assertEquals(0.5f, fractions[2], 0.001f)
    }

    @Test
    fun `month labels carry the year, short ones do not`() {
        val march = summary(2026, Calendar.MARCH)
        assertEquals("Mar 2026", monthLabel(march, Locale.ENGLISH))
        assertEquals("Mar", shortMonthLabel(march, Locale.ENGLISH))
    }

    @Test
    fun `the first month of the year labels as January, not December`() {
        assertEquals("Jan 2026", monthLabel(summary(2026, Calendar.JANUARY), Locale.ENGLISH))
        assertEquals("Dec 2025", monthLabel(summary(2025, Calendar.DECEMBER), Locale.ENGLISH))
    }

    @Test
    fun `the months a real overview produces label as themselves`() {
        val overview = monthlyOverview(
            fuelUps = listOf(fill(2025, Calendar.NOVEMBER, 10, odometer = 10000, cost = 600.0)),
            expenses = emptyList(),
            now = at(2026, Calendar.JANUARY, 15)
        )
        assertEquals(
            listOf("Nov 2025", "Dec 2025"),
            overview.months.map { monthLabel(it, Locale.ENGLISH) }
        )
    }

    // --- a month's cost, split by fuel ---

    private val now = at(2026, Calendar.APRIL, 15)

    @Test
    fun `a hybrid's fuel cost is kept per fuel`() {
        val overview = monthlyOverview(
            listOf(
                fill(2026, Calendar.MARCH, 2, 1000, cost = 700.0, fuel = "Petrol"),
                fill(2026, Calendar.MARCH, 9, 1200, cost = 120.0, fuel = "Electric"),
                fill(2026, Calendar.MARCH, 20, 1500, cost = 80.0, fuel = "Electric")
            ),
            emptyList(),
            now
        )
        val march = monthOf(overview, 2026, Calendar.MARCH)
        assertEquals(700.0, march.fuelCostByType["Petrol"]!!, delta)
        assertEquals(200.0, march.fuelCostByType["Electric"]!!, delta)
        assertEquals(march.fuelCost, march.fuelCostByType.values.sum(), delta)
    }

    @Test
    fun `the breakdown follows the car's fuel order and ends with everything else`() {
        val month = MonthSummary(2026, 2, fuelCost = 900.0, expenseCost = 500.0, distanceKm = 0.0,
            fuelCostByType = mapOf("Electric" to 200.0, "Petrol" to 700.0))
        assertEquals(
            listOf(CostPart("Petrol", 700.0), CostPart("Electric", 200.0), CostPart(OTHER_COSTS, 500.0)),
            costBreakdown(month, listOf("Petrol", "Electric"))
        )
    }

    @Test
    fun `every month has every part, so a colour always means the same thing`() {
        val petrolOnly = MonthSummary(2026, 2, 700.0, 0.0, 0.0, mapOf("Petrol" to 700.0))
        assertEquals(
            listOf("Petrol", "Electric", OTHER_COSTS),
            costBreakdown(petrolOnly, listOf("Petrol", "Electric")).map { it.label }
        )
        assertEquals(0.0, costBreakdown(petrolOnly, listOf("Petrol", "Electric"))[1].amount, 0.0)
    }

    @Test
    fun `fuel the car no longer lists is counted, under other`() {
        // Logged as diesel before the car was changed to petrol: not lost,
        // and not passed off as petrol either.
        val month = MonthSummary(2026, 2, 1000.0, 300.0, 0.0, mapOf("Petrol" to 600.0, "Diesel" to 400.0))
        val parts = costBreakdown(month, listOf("Petrol"))
        assertEquals(listOf(CostPart("Petrol", 600.0), CostPart(OTHER_COSTS, 700.0)), parts)
        assertEquals(month.totalCost, parts.sumOf { it.amount }, delta)
    }

    @Test
    fun `the average's parts add up to the average`() {
        val overview = monthlyOverview(
            listOf(
                fill(2026, Calendar.JANUARY, 5, 1000, cost = 600.0, fuel = "Petrol"),
                fill(2026, Calendar.MARCH, 5, 1300, cost = 90.0, fuel = "Electric")
            ),
            listOf(expense(2026, Calendar.FEBRUARY, 1, 300.0)),
            now
        )
        val parts = averageCostBreakdown(overview.months, listOf("Petrol", "Electric"))
        assertEquals(listOf(CostPart("Petrol", 200.0), CostPart("Electric", 30.0), CostPart(OTHER_COSTS, 100.0)), parts)
        assertEquals(overview.averageMonthlyCost, parts.sumOf { it.amount }, delta)
    }

    @Test
    fun `no months gives no average parts`() {
        assertTrue(averageCostBreakdown(emptyList(), listOf("Petrol")).isEmpty())
    }

    // --- stacked bars ---

    @Test
    fun `a stacked bar is as tall as a plain one, gaps included`() {
        val heights = stackHeights(listOf(300.0, 100.0, 600.0), total = 100f, gap = 2f)
        assertEquals(96f, heights.sum(), 0.01f)
        assertEquals(96f * 0.3f, heights[0], 0.01f)
        assertEquals(96f * 0.6f, heights[2], 0.01f)
    }

    @Test
    fun `an empty part takes neither height nor a gap`() {
        val heights = stackHeights(listOf(500.0, 0.0, 500.0), total = 100f, gap = 2f)
        assertEquals(listOf(49f, 0f, 49f), heights)
    }

    @Test
    fun `a tiny part is still drawn`() {
        val heights = stackHeights(listOf(10000.0, 1.0), total = 100f, gap = 2f)
        assertEquals(1f, heights[1], 0.0f)
    }

    @Test
    fun `nothing to stack gives nothing`() {
        assertEquals(listOf(0f, 0f), stackHeights(listOf(0.0, 0.0), total = 100f, gap = 2f))
    }
}
