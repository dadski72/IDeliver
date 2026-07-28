package com.ideliver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MileageDao {

    @Insert
    suspend fun insert(reading: MileageReading): Long

    @Query("SELECT * FROM mileage_reading ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<MileageReading>>

    @Query("DELETE FROM mileage_reading WHERE id = :id")
    suspend fun delete(id: Long)
}
