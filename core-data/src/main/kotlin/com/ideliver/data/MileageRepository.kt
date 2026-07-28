package com.ideliver.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Thin façade over the mileage DAO so callers don't touch Room directly. */
class MileageRepository(context: Context) {

    private val dao = IDeliverDatabase.get(context).mileageDao()

    val readings: Flow<List<MileageReading>> = dao.observeAll()

    suspend fun add(
        kind: MileageKind,
        odometerMiles: Double,
        photoPath: String?,
        rawOcrText: String?,
    ): Long = dao.insert(
        MileageReading(
            kind = kind,
            odometerMiles = odometerMiles,
            capturedAt = Instant.now(),
            photoPath = photoPath,
            rawOcrText = rawOcrText,
        ),
    )

    suspend fun delete(id: Long) = dao.delete(id)
}
