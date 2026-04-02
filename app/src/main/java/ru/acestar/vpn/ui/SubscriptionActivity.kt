package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import ru.acestar.vpn.data.model.PaymentState
import ru.acestar.vpn.data.model.PaymentViewModel
import ru.acestar.vpn.databinding.ActivitySubscriptionBinding
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.api.PaymentApiService
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.acestar.vpn.data.model.User
import java.util.Locale

class SubscriptionActivity : AppCompatActivity() {

    private val paymentApiService by lazy { PaymentApiService(sessionManager) }
    private lateinit var binding: ActivitySubscriptionBinding
    private val viewModel: PaymentViewModel by viewModels()
    private var currentUser: User? = null
    private var currentUserId: Long = -1L
    private var currentPaymentId: String? = null
    private val sessionManager by lazy { SessionManager.getInstance(this) }
    private val authApiService = AuthApiService(sessionManager)
    private val userRepository by lazy {
        UserRepository((application as VpnApplication).database.userDao())
    }

    companion object {
        private const val REQUEST_CODE_PAYMENT = 1001
        /** Скидка на первую оплату для аккаунта с isNew. */
        private const val FIRST_PAYMENT_DISCOUNT = 0.2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val user = sessionManager.getCurrentUser()
            val userId = user?.id ?: -1L
            if (userId != -1L) {
                binding.tvNewUserDiscount.visibility =
                    if (user?.isNew == true) View.VISIBLE else View.GONE
            }
        }

        setupObservers()
        setupClickListeners()
    }

    private fun openPaymentPage(url: String) {
        // Используем PaymentWebViewActivity вместо встроенного WebView
        val intent = PaymentWebViewActivity.newIntent(
            context = this,
            paymentUrl = url,
            returnUrl = "http://94.249.239.35/payment-return",
            paymentId = currentPaymentId ?: ""
        )
        startActivityForResult(intent, REQUEST_CODE_PAYMENT)
    }

    private fun setupObservers() {
        viewModel.paymentState.observe(this, Observer { state ->
            when (state) {
                is PaymentState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "Создание платежа..."
                }

                is PaymentState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    currentPaymentId = state.paymentData.payment_id
                    binding.tvStatus.text = "Перенаправление на страницу оплаты..."
                    binding.tvStatus.visibility = View.VISIBLE

                    // Открываем страницу оплаты в PaymentWebViewActivity
                    openPaymentPage(state.paymentData.confirmation_url)
                }

                is PaymentState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Ошибка: ${state.message}"
                    binding.tvStatus.visibility = View.VISIBLE
                    showErrorDialog(state.message)
                }

                is PaymentState.PaymentCompleted -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Платеж успешно завершен!"
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvPaymentDetails.visibility = View.VISIBLE
                    binding.tvPaymentDetails.text = """
                        Статус: ${state.statusData.status}
                        Сумма: ${state.statusData.amount.value} ${state.statusData.amount.currency}
                        Оплачен: ${if (state.statusData.paid) "Да" else "Нет"}
                    """.trimIndent()

                    updateSubscriptionStatus(true)
                    onFirstPaymentCompleted()
                }

                is PaymentState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.visibility = View.GONE
                    binding.tvPaymentDetails.visibility = View.GONE
                }
            }
        })
    }

    private fun setupClickListeners() {
        // Суточная подписка - ИСПРАВЛЕНО: используем binding вместо findViewById
        binding.btnGetDaily.setOnClickListener {
            purchaseSubscription("daily", 49.0)
        }

        binding.cardDaily.setOnClickListener {
            binding.btnGetDaily.performClick()
        }

        // Месячная подписка
        binding.btnGetMonthly.setOnClickListener {
            purchaseSubscription("monthly", 349.0)
        }

        binding.cardMonthly.setOnClickListener {
            binding.btnGetMonthly.performClick()
        }

        // Годовая подписка
        binding.btnGetYearly.setOnClickListener {
            purchaseSubscription("yearly", 2990.0)
        }

        binding.cardYearly.setOnClickListener {
            binding.btnGetYearly.performClick()
        }

        // Просмотр рекламы
        binding.btnWatchAd.setOnClickListener {
            showRewardedAd()
        }

        binding.cardAd.setOnClickListener {
            binding.btnWatchAd.performClick()
        }

        // Восстановление покупок
        binding.tvRestorePurchases.setOnClickListener {
            restorePurchases()
        }

        // Юридические ссылки
        binding.tvPrivacyPolicy.setOnClickListener {
            openPrivacyPolicy()
        }

        binding.tvTerms.setOnClickListener {
            openTermsOfService()
        }
    }

    private fun startPayment(amount: String, description: String) {
        // Показываем прогресс
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Создание платежа..."
        binding.tvStatus.visibility = View.VISIBLE

        val returnUrl = "http://94.249.239.35/payment-return"

        lifecycleScope.launch {
            try {
                val result = paymentApiService.createPayment(
                    amountValue = amount,
                    returnUrl = returnUrl,
                    description = description,
                    metadata = mapOf(
                        "user_id" to currentUserId.toString(),
                        "tariff" to description
                    )
                )

                result.onSuccess { paymentData ->
                    currentPaymentId = paymentData.payment_id
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Перенаправление на страницу оплаты..."
                    binding.tvStatus.visibility = View.VISIBLE
                    openPaymentPage(paymentData.confirmation_url)
                }.onFailure { exception ->
                    binding.progressBar.visibility = View.GONE
                    showErrorDialog("Ошибка создания платежа: ${exception.message}")
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                showErrorDialog("Ошибка: ${e.message}")
            }
        }
    }

    private fun purchaseSubscription(type: String, price: Double) {
        lifecycleScope.launch {
            val user = sessionManager.getCurrentUser()
            val finalPrice =
                if (user?.isNew == true) price * (1.0 - FIRST_PAYMENT_DISCOUNT) else price
            val message = when (type) {
                "daily" -> "Оформление суточной подписки за $finalPrice ₽"
                "monthly" -> "Оформление месячной подписки за $finalPrice ₽"
                "yearly" -> "Оформление годовой подписки за $finalPrice ₽"
                else -> "Обработка платежа..."
            }
            Toast.makeText(this@SubscriptionActivity, message, Toast.LENGTH_SHORT).show()
            startPayment(String.format(Locale.US, "%.2f", finalPrice), message)
        }
    }

    private fun onFirstPaymentCompleted() {
        lifecycleScope.launch {
            currentUser = sessionManager.getCurrentUser()
            currentUserId = currentUser?.id ?: -1

            val token = sessionManager.getAccessToken()
//            if (token.isNotEmpty()) {
                authApiService.markUserNotNew(token)
//            }
            userRepository.setIsNewUser(currentUserId, false)
            val updated = userRepository.getUserById(currentUserId)
            if (updated != null) {
                sessionManager.updateCurrentUser(updated.copy(isNew = false))
            }
            binding.tvNewUserDiscount.visibility = View.GONE
        }
    }

    private fun showRewardedAd() {
        Toast.makeText(this, "Загрузка рекламы...", Toast.LENGTH_SHORT).show()
        // TODO: Интеграция с AdMob
    }

    private fun restorePurchases() {
        Toast.makeText(this, "Восстановление покупок...", Toast.LENGTH_SHORT).show()
        // TODO: Проверка предыдущих покупок
    }

    private fun openPrivacyPolicy() {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://yourapp.com/privacy"))
        startActivity(intent)
    }

    private fun openTermsOfService() {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://yourapp.com/terms"))
        startActivity(intent)
    }

    private fun showErrorDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Ошибка")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun updateSubscriptionStatus(isActive: Boolean) {
        if (isActive) {
            // Обновляем UI для активной подписки
            binding.btnGetDaily.isEnabled = false
            binding.btnGetMonthly.isEnabled = false
            binding.btnGetYearly.isEnabled = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_PAYMENT) {
            when (resultCode) {
                RESULT_OK -> {
                    val success = data?.getBooleanExtra("payment_success", false) ?: false
                    val paymentId = data?.getStringExtra("payment_id") ?: ""

                    if (success) {
                        // Платеж успешен, проверяем статус
                        binding.tvStatus.text = "Проверка статуса платежа..."
                        viewModel.checkPaymentStatus(paymentId)
                    } else {
                        showErrorDialog("Платеж не был завершен")
                        binding.progressBar.visibility = View.GONE
                        binding.tvStatus.visibility = View.GONE
                    }
                }
                RESULT_CANCELED -> {
                    showErrorDialog("Оплата отменена")
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.visibility = View.GONE
                }
            }
        }
    }
}