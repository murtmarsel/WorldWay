package ru.acestar.vpn.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.data.model.Tariff
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.databinding.ActivityAccountBinding
import ru.acestar.vpn.ui.adapters.PaymentRowAdapter
import ru.acestar.vpn.api.PaymentApiService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding
    private val sessionManager by lazy { SessionManager.getInstance(this) }
    private val userRepository by lazy {
        UserRepository((application as VpnApplication).database.userDao())
    }
    private val authApiService by lazy { AuthApiService(sessionManager) }
    private val paymentApiService by lazy { PaymentApiService(sessionManager) }

    private var autoRefreshJob: Job? = null
    private var refreshAnimator: ObjectAnimator? = null

    companion object {
        private const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L // 10 минут
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Скрываем блок статистики (реализован, но скрыт по требованию)
        binding.cardStats.visibility = View.GONE

        binding.btnExtendSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }

        // Иконка обновления с анимацией
        setupRefreshIcon()
        binding.ivRefreshAccount.setOnClickListener { loadAndRefresh() }
    }

    override fun onResume() {
        super.onResume()
        loadAndRefresh()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        autoRefreshJob?.cancel()
        refreshAnimator?.cancel()
    }

    private fun setupRefreshIcon() {
        refreshAnimator = ObjectAnimator.ofFloat(binding.ivRefreshAccount, "rotation", 0f, 360f).apply {
            duration = 700
            interpolator = LinearInterpolator()
            repeatCount = 0
        }
    }

    private fun spinRefreshIcon() {
        refreshAnimator?.cancel()
        binding.ivRefreshAccount.rotation = 0f
        refreshAnimator = ObjectAnimator.ofFloat(binding.ivRefreshAccount, "rotation", 0f, 360f).apply {
            duration = 700
            interpolator = LinearInterpolator()
            repeatCount = 0
            start()
        }
    }

    /** Загружает локально сразу, потом получает с сервера */
    private fun loadAndRefresh() {
        lifecycleScope.launch {
            // 1. Сразу показываем локальные данные
            val session = sessionManager.getCurrentUser() ?: return@launch
            val localUser = userRepository.getUserById(session.id) ?: session
            bindUser(localUser)

            // 2. Получаем актуальные данные с сервера в фоне
            try {
                authApiService.refreshOnResume(userRepository)
                val updated = userRepository.getUserById(session.id)
                if (updated != null) {
                    bindUser(updated)
                    spinRefreshIcon()
                }
            } catch (e: Exception) {
                // Нет сети — показываем локальные данные
            }

            // 3. Загружаем историю платежей
            loadPayments()
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                if (isActive) loadAndRefresh()
            }
        }
    }

    private fun bindUser(user: User) {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val tariff = Tariff.fromId(user.tariff)

        // Аватар
        binding.tvAvatarLetter.text = user.email.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        // Email
        binding.tvEmail.text = user.email

        // Подтверждение email
        binding.ivEmailConfirmed.visibility = if (user.emailConfirmed) View.VISIBLE else View.GONE

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
                ContextCompat.getColor(this, if (isActive) R.color.connected else R.color.error)
            )

        // Срок действия
        binding.tvValidUntil.text = when {
            tariff == Tariff.UNLIMITED -> getString(R.string.subscription_active_unlimited)
            tariff == Tariff.HOUR ->
                if (user.subsEndDate != null && user.subsEndDate > 0)
                    dateFormat.format(Date(user.subsEndDate)) + " (1 ч)"
                else getString(R.string.account_expired)
            user.subsEndDate != null && user.subsEndDate > 0 ->
                dateFormat.format(Date(user.subsEndDate))
            else -> getString(R.string.account_expired)
        }

        // Трафик — используем effectiveTrafficLimit() который учитывает серверный лимит
        val limit = user.effectiveTrafficLimit()
        if (limit == null) {
            // Безлимит — просто название тарифа без "безлимитно"
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
                    ContextCompat.getColor(this, when {
                        progress >= 90 -> R.color.error
                        progress >= 70 -> R.color.connecting
                        else           -> R.color.primary
                    })
                )

            val remaining = user.remainingTrafficBytes() ?: 0L
            binding.tvTrafficRemaining.text =
                getString(R.string.account_traffic_remaining_fmt, Tariff.formatUsed(remaining))
        }

        binding.btnExtendSubscription.visibility =
            if (tariff == Tariff.UNLIMITED) View.GONE else View.VISIBLE
    }

    private fun loadPayments() {
        lifecycleScope.launch {
            try {
                val result = paymentApiService.getPaymentHistory()
                result.onSuccess { payments ->
                    val adapter = PaymentRowAdapter()
                    binding.rvPaymentsHistory.layoutManager = LinearLayoutManager(this@AccountActivity)
                    binding.rvPaymentsHistory.adapter = adapter

                    if (payments.isEmpty()) {
                        binding.layoutPaymentsHeader.visibility = View.GONE
                        binding.rvPaymentsHistory.visibility = View.GONE
                        binding.tvPaymentsEmpty.visibility = View.VISIBLE
                    } else {
                        binding.layoutPaymentsHeader.visibility = View.VISIBLE
                        binding.rvPaymentsHistory.visibility = View.VISIBLE
                        binding.tvPaymentsEmpty.visibility = View.GONE
                        // Последние 10 платежей
                        adapter.submitList(payments.take(10))
                    }
                }.onFailure {
                    // Нет данных с сервера — показываем заглушку, карточку не прячем
                    binding.layoutPaymentsHeader.visibility = View.GONE
                    binding.rvPaymentsHistory.visibility = View.GONE
                    binding.tvPaymentsEmpty.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                binding.layoutPaymentsHeader.visibility = View.GONE
                binding.rvPaymentsHistory.visibility = View.GONE
                binding.tvPaymentsEmpty.visibility = View.VISIBLE
            }
        }
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
