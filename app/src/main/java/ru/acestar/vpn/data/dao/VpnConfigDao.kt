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

    @Query("SELECT * FROM vpn_configs WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeAllByUser(userId: Long): Flow<List<VpnConfigEntity>>

    @Query("SELECT * FROM vpn_configs WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun getAllByUser(userId: Long): List<VpnConfigEntity>

    @Query("SELECT COUNT(*) FROM vpn_configs WHERE userId = :userId AND source = :source")
    suspend fun countBySource(userId: Long, source: String): Int

    @Query("SELECT * FROM vpn_configs WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getByIdForUser(id: Long, userId: Long): VpnConfigEntity?

    @Query("DELETE FROM vpn_configs WHERE id = :id AND userId = :userId AND source = 'manual'")
    suspend fun deleteManualById(id: Long, userId: Long)

    @Query("DELETE FROM vpn_configs WHERE userId = :userId AND source = :source")
    suspend fun deleteBySource(userId: Long, source: String)

    @Query("UPDATE vpn_configs SET isFavorite = :favorite WHERE id = :id AND userId = :userId")
    suspend fun setFavorite(id: Long, userId: Long, favorite: Boolean)

    @Query("UPDATE vpn_configs SET name = :name WHERE id = :id AND userId = :userId")
    suspend fun rename(id: Long, userId: Long, name: String)

    @Query("SELECT COUNT(*) FROM vpn_configs WHERE userId = :userId AND isFavorite = 1")
    suspend fun getFavoriteCount(userId: Long): Int
}
