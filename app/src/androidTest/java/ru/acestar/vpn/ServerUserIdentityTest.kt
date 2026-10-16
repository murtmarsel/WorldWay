package ru.acestar.vpn

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.acestar.vpn.data.database.AppDatabase
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.model.VpnConfigEntity

@RunWith(AndroidJUnit4::class)
class ServerUserIdentityTest {
    @Test
    fun changingLocalIdPreservesManualConfigsAndRejectsOtherAccountCollision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.userDao()
            val original = User(id = 1, email = "first@example.com")
            dao.insert(original)
            db.vpnConfigDao().insert(VpnConfigEntity(
                userId = 1, name = "Manual", serverHost = "example.com",
                configContent = "client", type = "openvpn", source = "manual"
            ))
            dao.saveServerUser(original.copy(id = 42), 1)
            assertNull(dao.getUserById(1))
            assertEquals(original.email, dao.getUserById(42)?.email)
            assertEquals(1, db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM vpn_configs WHERE userId = 42"
            ).use { it.moveToFirst(); it.getInt(0) })

            val conflict = runCatching {
                dao.saveServerUser(User(id = 42, email = "second@example.com"), null)
            }
            assertTrue(conflict.isFailure)
            assertEquals(original.email, dao.getUserById(42)?.email)
        } finally {
            db.close()
        }
    }
    @Test
    fun profileUpdatePreservesRefreshedTokenAndLogoutRejectsLateRefresh() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.sessionDao()
            dao.saveCurrentSession(ru.acestar.vpn.data.model.CurrentSession(
                userId = 42, email = "first@example.com", tariff = "Trial",
                subsEndDate = 1000, token = "old", tokenExpiry = 100,
                isConfirmed = true, isActive = true, createdAt = 1
            ))
            assertEquals(1, dao.replaceCurrentToken("old", "fresh", 200))
            dao.updateCurrentSession(42, "first@example.com", "Trial", 1000,
                true, true, false, false, 0, 0)
            assertEquals("fresh", dao.getCurrentSessionSync()?.token)
            assertEquals(200L, dao.getCurrentSessionSync()?.tokenExpiry)
            assertEquals(0, dao.replaceCurrentToken("old", "stale", 300))
            dao.clearCurrentSession()
            assertEquals(0, dao.replaceCurrentToken("fresh", "late", 400))
            assertNull(dao.getCurrentSessionSync())
        } finally {
            db.close()
        }
    }}

