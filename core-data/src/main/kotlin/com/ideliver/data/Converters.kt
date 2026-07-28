package com.ideliver.data

import androidx.room.TypeConverter
import java.time.Instant

/** Room type converters for the non-primitive columns. */
class Converters {

    @TypeConverter
    fun instantToEpochMilli(instant: Instant): Long = instant.toEpochMilli()

    @TypeConverter
    fun epochMilliToInstant(millis: Long): Instant = Instant.ofEpochMilli(millis)

    @TypeConverter
    fun mileageKindToString(kind: MileageKind): String = kind.name

    @TypeConverter
    fun stringToMileageKind(value: String): MileageKind = MileageKind.valueOf(value)
}
