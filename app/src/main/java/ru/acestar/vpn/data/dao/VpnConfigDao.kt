package ru.acestar.vpn.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.model.VpnConfigEntity

@Dao
interface VpnConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: VpnConfigEntity): Long

    @Query("SELECT * FROM vpn_configs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VpnConfigEntity>>

    @Query("SELECT COUNT(*) FROM vpn_configs WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("SELECT * FROM vpn_configs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VpnConfigEntity?
}
