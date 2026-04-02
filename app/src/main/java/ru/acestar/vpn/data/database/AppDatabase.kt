package ru.acestar.vpn.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ru.acestar.vpn.data.dao.ConnectionStatsDao
import ru.acestar.vpn.data.dao.SessionDao
import ru.acestar.vpn.data.dao.UserDao
import ru.acestar.vpn.data.dao.VpnConfigDao
import ru.acestar.vpn.data.dao.VpnServerConfigDao
import ru.acestar.vpn.data.model.ConnectionStats
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.data.model.VpnServerConfigEntity
import ru.acestar.vpn.data.model.Session
import ru.acestar.vpn.data.model.CurrentSession

@Database(
    entities = [
        User::class,
        ConnectionStats::class,
        VpnConfigEntity::class,
        VpnServerConfigEntity::class,
        Session::class,
        CurrentSession::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sessionDao(): SessionDao
    abstract fun connectionStatsDao(): ConnectionStatsDao
    abstract fun vpnConfigDao(): VpnConfigDao

    abstract fun vpnServerConfigDao(): VpnServerConfigDao
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
