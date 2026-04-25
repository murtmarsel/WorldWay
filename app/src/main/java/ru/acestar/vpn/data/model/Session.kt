// data/model/Session.kt
package ru.acestar.vpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import ru.acestar.vpn.data.database.Converters
import java.sql.Date

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val token: String?,
    val tokenExpiry: Long,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val email: String? = null,
    val ipAddress: String? = null
)

//val id: Long = 0,
//val email: String,
//val password: String? = null,
///** SHA-256 хеш, как при отправке на сервер (не храните пароль в открытом виде). */
//val createdAt: Long = System.currentTimeMillis(),
//val subsEndDate: Long? = 0,
//val isActive: Boolean = true,
//val emailConfirmed: Boolean = false,
///** Первый период / скидка на первую оплату; после первой оплаты сбрасывается на сервере и локально. */
//val isNew: Boolean = true,
//val isAdmin: Boolean = false,
//val isLoggedIn: Boolean = false,
//val username: String? = null

@Entity(tableName = "current_session")
//@TypeConverters(Converters::class)
data class CurrentSession(
    @PrimaryKey
    val id: Int = 1,
    val userId: Long,
    val email: String,
    val tariff: String,
    val subsEndDate: Long,
    val token: String?,
    val tokenExpiry: Long,
    val isConfirmed: Boolean,
    val isActive: Boolean,
    val isAdmin: Boolean = false,
    val isNew: Boolean = true,
    val createdAt: Long
)
