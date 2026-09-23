package se.eliash.cartracker

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Something the car needs by a date, by an odometer reading, or both -
 * whichever comes first.
 *
 * The next due point is stored rather than when it was last done, so "the
 * inspection is due on 15 March" can be entered as it is, without knowing
 * the history behind it. [repeatMonths] and [repeatKm] move it on when it is
 * marked done; a reminder with neither is finished once done.
 *
 * Deleted with its car, like fill-ups and expenses.
 */
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = Car::class,
            parentColumns = ["id"],
            childColumns = ["carId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["carId"])]
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val carId: Int,
    /** A [ReminderType] name. Stored as text so a new type needs no migration. */
    val type: String,
    val title: String,
    val dueDateMillis: Long?,
    val dueOdometerKm: Int?,
    val repeatMonths: Int?,
    val repeatKm: Int?,
    /** How early "due soon" starts, by date and by distance. */
    val warnDays: Int = DEFAULT_WARN_DAYS,
    val warnKm: Int = DEFAULT_WARN_KM,
    /**
     * The furthest [Urgency] already notified, as its ordinal, so each stage
     * is announced once. Back to 0 whenever the due point moves.
     */
    val notifiedStage: Int = 0
)

const val DEFAULT_WARN_DAYS = 7
const val DEFAULT_WARN_KM = 500

enum class ReminderType(val label: String) {
    Inspection("Inspection"),
    Service("Service"),
    Tyres("Tyre change"),
    Insurance("Insurance renewal"),
    Other("Other");

    companion object {
        fun fromStored(value: String?): ReminderType = entries.firstOrNull { it.name == value } ?: Other
    }
}
