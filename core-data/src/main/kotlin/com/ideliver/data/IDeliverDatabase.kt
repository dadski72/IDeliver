package com.ideliver.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * On-device Room database. Currently holds the mileage log; the offer log and
 * analytics tables will join it here as those land.
 */
@Database(entities = [MileageReading::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class IDeliverDatabase : RoomDatabase() {

    abstract fun mileageDao(): MileageDao

    companion object {
        @Volatile
        private var instance: IDeliverDatabase? = null

        fun get(context: Context): IDeliverDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    IDeliverDatabase::class.java,
                    "ideliver.db",
                ).build().also { instance = it }
            }
    }
}
