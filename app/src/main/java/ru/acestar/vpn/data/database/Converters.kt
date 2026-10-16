// data/database/Converters.kt
package ru.acestar.vpn.data.database

import androidx.room.TypeConverter
import java.text.SimpleDateFormat
import java.util.Date

class Converters {
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    @TypeConverter
    fun fromDate(date: Date?): String? {
        return date?.let { format.format(it) }
    }

    @TypeConverter
    fun toDate(value: String?): Date? {
        return value?.let {format.parse(it)}
    }
}