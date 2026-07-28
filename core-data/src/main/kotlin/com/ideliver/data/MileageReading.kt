package com.ideliver.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/** Whether an odometer reading was taken at the start or end of a shift. */
enum class MileageKind { START, END }

/**
 * One odometer reading, captured from the car's dashboard. [odometerMiles] is
 * the whole odometer value the driver confirmed (OCR is only a pre-fill), so it
 * is the source of truth for the IRS mileage log. [photoPath] points at the
 * on-device photo kept as a record; [rawOcrText] retains what OCR actually read,
 * for debugging misreads.
 */
@Entity(tableName = "mileage_reading")
data class MileageReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: MileageKind,
    val odometerMiles: Double,
    val capturedAt: Instant,
    val photoPath: String?,
    val rawOcrText: String?,
)
