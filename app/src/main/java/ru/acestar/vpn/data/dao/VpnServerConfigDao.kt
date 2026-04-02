package ru.acestar.vpn.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.data.model.VpnServerConfigEntity

@Dao
interface VpnServerConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: VpnServerConfigEntity): Long

    @Query("SELECT * FROM vpn_server_configs WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeAllByUser(userId: Long): Flow<List<VpnServerConfigEntity>>

//    val id: Long = 0,
//    val userId: Long,
//    val name: String,
//    val type: String,
//    val category: String,
//    val protocol: String,
//    val content: String,
//    val createdAt: Long = System.currentTimeMillis()


    @Query("SELECT * FROM vpn_server_configs " +
            "WHERE userId = :userId " +
            "ORDER BY " +
            "   CASE type" +
            "       WHEN 'openvpn' THEN 1" +
            "       WHEN 'wireguard' THEN 2" +
            "       WHEN 'amneziawg' THEN 3" +
            "       ELSE 4" +
            "   END," +
            "   CASE category" +
            "       WHEN 'antizapret' THEN 1" +
            "       WHEN 'antizapret-tcp' THEN 2" +
            "       WHEN 'antizapret-udp' THEN 3" +
            "       WHEN 'vpn' THEN 4" +
            "       WHEN 'vpn-tcp' THEN 5" +
            "       WHEN 'vpn-udp' THEN 6" +
            "       ELSE 7" +
            "   END;")
    fun observeAllByOrder(userId: Long): Flow<List<VpnServerConfigEntity>>

//    @Query("SELECT COUNT(*) FROM vpn_server_configs WHERE userId = :userId AND source = :source")
//    suspend fun countBySource(userId: Long, source: String): Int

//    @Query("SELECT * FROM vpn_server_configs WHERE id = :id AND userId = :userId LIMIT 1")
//    suspend fun getByIdForUser(id: Long, userId: Long): VpnServerConfigDao?

    @Query("DELETE FROM vpn_server_configs WHERE userId = :userId")
    suspend fun deleteByUserId(userId: Long)

}
