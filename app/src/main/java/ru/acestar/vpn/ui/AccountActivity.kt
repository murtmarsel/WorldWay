package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.model.Tariff
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.StatsRepository
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.databinding.ActivityAccountBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding
    private val sessionManager by lazy { SessionManager.getInstance(this) }
    private val userRepository by lazy {
        UserRepository((application as VpnApplication).database.userDao())
    }
    private val statsRepository by lazy {
        StatsRepository((application as VpnApplication).database.connectionStatsDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnExtendSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }
    }

    override fun onResume() {
        super.onResume()
        // Каждый раз при открытии/возврате — читаем свежие данные из БД
        loadUserData()
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            // Сессия даёт нам userId и базовые поля
            val session = sessionManager.getCurrentUser() ?: return@launch
            // Читаем полную запись из БД — там актуальный usedTrafficBytes
            val user = userRepository.getUserById(session.id) ?: session
            bindUser(user)
            loadStats(user.id)
        }
    }

    private fun bindUser(user: User) {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val tariff = Tariff.fromId(user.tariff)

        // Аватар — первая буква email
        binding.tvAvatarLetter.text =
            user.email.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        // Email (без username, без переноса — авто-масштаб через autoSizeTextType)
        binding.tvEmail.text = user.email

        // Подтверждение email
        binding.ivEmailConfirmed.visibility =
            if (user.emailConfirmed) View.VISIBLE else View.GONE

        // Дата регистрации
        binding.tvRegisteredDate.text = dateFormat.format(Date(user.createdAt))

        // Название тарифа
        binding.tvTariffName.text = tariff.displayName

        // Бейдж статуса
        val isActive = user.hasActiveSubscription()
        binding.tvTariffBadge.text =
            if (isActive) getString(R.string.account_active) else getString(R.string.account_expired)
        binding.tvTariffBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(
                    this, if (isActive) R.color.connected else R.color.error
                )
            )

        // Срок действия
        binding.tvValidUntil.text = when {
            tariff == Tariff.UNLIMITED -> getString(R.string.subscription_active_unlimited)
            tariff == Tariff.HOUR      -> getString(R.string.account_by_traffic)
            user.subsEndDate != null && user.subsEndDate > 0 ->
                dateFormat.format(Date(user.subsEndDate))
            else -> getString(R.string.account_expired)
        }

        // Трафик
        val limit = tariff.trafficLimitBytes
        if (limit == null) {
            binding.layoutTrafficLimit.visibility = View.GONE
            binding.layoutUnlimitedTraffic.visibility = View.VISIBLE
        } else {
            binding.layoutUnlimitedTraffic.visibility = View.GONE
            binding.layoutTrafficLimit.visibility = View.VISIBLE

            val usedBytes = user.usedTrafficBytes
            binding.tvTrafficUsed.text =
                "${Tariff.formatUsed(usedBytes)} / ${Tariff.formatTrafficLimit(limit)}"

            val progress = ((usedBytes.toFloat() / limit) * 100).toInt().coerceIn(0, 100)
            binding.progressTraffic.progress = progress
            binding.progressTraffic.progressTintList =
                android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(
                        this, when {
                            progress >= 90 -> R.color.error
                            progress >= 70 -> R.color.connecting
                            else           -> R.color.primary
                        }
                    )
                )

            val remaining = user.remainingTrafficBytes() ?: 0L
            binding.tvTrafficRemaining.text =
                getString(R.string.account_traffic_remaining_fmt, Tariff.formatUsed(remaining))
        }

        // Кнопка продления — скрываем для Unlimited
        binding.btnExtendSubscription.visibility =
            if (tariff == Tariff.UNLIMITED) View.GONE else View.VISIBLE
    }

    private suspend fun loadStats(userId: Long) {
        val (received, sent) = statsRepository.getTotalUsage(userId)
        val sessions = statsRepository.getRecentStats(userId).size
        binding.tvTotalReceived.text = Tariff.formatUsed(received)
        binding.tvTotalSent.text     = Tariff.formatUsed(sent)
        binding.tvTotalSessions.text = sessions.toString()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.logout_yes) { _, _ ->
                lifecycleScope.launch {
                    sessionManager.logout()
                    startActivity(
                        Intent(this@AccountActivity, LoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                }
            }
            .setNegativeButton(R.string.logout_no, null)
            .show()
    }
}
