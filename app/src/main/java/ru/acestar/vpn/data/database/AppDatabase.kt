package ru.acestar.vpn.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.acestar.vpn.data.dao.ConnectionStatsDao
import ru.acestar.vpn.data.dao.UserDao
import ru.acestar.vpn.data.model.ConnectionStats
import ru.acestar.vpn.data.model.User

@Database(
    entities = [User::class, ConnectionStats::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun connectionStatsDao(): ConnectionStatsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vpn_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
