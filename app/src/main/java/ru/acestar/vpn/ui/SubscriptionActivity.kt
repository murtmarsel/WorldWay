package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.api.PaymentApiService
import ru.acestar.vpn.data.model.PaymentState
import ru.acestar.vpn.data.model.PaymentViewModel
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.databinding.ActivitySubscriptionBinding
import java.util.Locale

class SubscriptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionBinding
    private val viewModel: PaymentViewModel by viewModels()
    private val sessionManager by lazy { SessionManager.getInstance(this) }
    private val paymentApiService by lazy { PaymentApiService(sessionManager) }
    private val authApiService by lazy { AuthApiService(sessionManager) }
    private val userRepository by lazy {
        UserRepository((application as VpnApplication).database.userDao())
    }

    private var currentPaymentId: String? = null
    private var currentUserId: Long = -1L

    companion object {
        private const val REQUEST_CODE_PAYMENT = 1001
        private const val FIRST_PAYMENT_DISCOUNT = 0.2

        // Цены тарифов
        private const val PRICE_BASIC_MONTH   = 199.0
        private const val PRICE_BASIC_YEAR    = 1_490.0
        private const val PRICE_ADVANCED_MONTH = 349.0
        private const val PRICE_ADVANCED_YEAR  = 2_990.0
        private const val PRICE_PREMIUM_YEAR   = 4_990.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            val user = sessionManager.getCurrentUser()
            currentUserId = user?.id ?: -1L
            binding.tvNewUserDiscount.visibility =
                if (user?.isNew == true) View.VISIBLE else View.GONE
        }

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        viewModel.paymentState.observe(this, Observer { state ->
            when (state) {
                is PaymentState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = getString(R.string.payment_creating)
                }
                is PaymentState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    currentPaymentId = state.paymentData.payment_id
                    binding.tvStatus.text = getString(R.string.payment_redirecting)
                    binding.tvStatus.visibility = View.VISIBLE
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
                    binding.tvStatus.text = getString(R.string.payment_success)
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvPaymentDetails.visibility = View.VISIBLE
                    binding.tvPaymentDetails.text = getString(
                        R.string.payment_details_fmt,
                        state.statusData.amount.value,
                        state.statusData.amount.currency
                    )
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
        // Basic
        binding.btnBasicMonthly.setOnClickListener {
            purchase("Basic", "monthly", PRICE_BASIC_MONTH)
        }
        binding.btnBasicYearly.setOnClickListener {
            purchase("Basic", "yearly", PRICE_BASIC_YEAR)
        }
        binding.cardBasic.setOnClickListener { binding.btnBasicMonthly.performClick() }

        // Advanced
        binding.btnAdvancedMonthly.setOnClickListener {
            purchase("Advanced", "monthly", PRICE_ADVANCED_MONTH)
        }
        binding.btnAdvancedYearly.setOnClickListener {
            purchase("Advanced", "yearly", PRICE_ADVANCED_YEAR)
        }
        binding.cardAdvanced.setOnClickListener { binding.btnAdvancedMonthly.performClick() }

        // Premium
        binding.btnPremiumYearly.setOnClickListener {
            purchase("Premium", "yearly", PRICE_PREMIUM_YEAR)
        }
        binding.cardPremium.setOnClickListener { binding.btnPremiumYearly.performClick() }

        // Hour (реклама)
        binding.btnWatchAd.setOnClickListener { showRewardedAd() }
        binding.cardAd.setOnClickListener { binding.btnWatchAd.performClick() }

        // Юридические
        binding.tvRestorePurchases.setOnClickListener { restorePurchases() }
        binding.tvPrivacyPolicy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://yourapp.com/privacy")))
        }
        binding.tvTerms.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://yourapp.com/terms")))
        }
    }

    private fun purchase(tariff: String, period: String, basePrice: Double) {
        lifecycleScope.launch {
            val user = sessionManager.getCurrentUser()
            val finalPrice = if (user?.isNew == true)
                basePrice * (1.0 - FIRST_PAYMENT_DISCOUNT)
            else basePrice

            val description = when (period) {
                "monthly" -> getString(R.string.payment_desc_monthly, tariff)
                "yearly"  -> getString(R.string.payment_desc_yearly, tariff)
                else      -> tariff
            }

            Toast.makeText(
                this@SubscriptionActivity,
                getString(R.string.payment_starting_fmt,
                    String.format(Locale.US, "%.0f", finalPrice)),
                Toast.LENGTH_SHORT
            ).show()

            startPayment(
                amount = String.format(Locale.US, "%.2f", finalPrice),
                description = description,
                tariff = tariff,
                period = period
            )
        }
    }

    private fun startPayment(amount: String, description: String, tariff: String, period: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.payment_creating)
        binding.tvStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = paymentApiService.createPayment(
                amountValue = amount,
                returnUrl = "http://94.249.239.35/payment-return",
                description = description,
                metadata = mapOf(
                    "user_id" to currentUserId.toString(),
                    "tariff"  to tariff,
                    "period"  to period
                )
            )
            result.onSuccess { paymentData ->
                currentPaymentId = paymentData.payment_id
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.payment_redirecting)
                openPaymentPage(paymentData.confirmation_url)
            }.onFailure { ex ->
                binding.progressBar.visibility = View.GONE
                showErrorDialog(ex.message ?: "Ошибка")
            }
        }
    }

    private fun openPaymentPage(url: String) {
        startActivityForResult(
            PaymentWebViewActivity.newIntent(
                context = this,
                paymentUrl = url,
                returnUrl = "http://94.249.239.35/payment-return",
                paymentId = currentPaymentId ?: ""
            ),
            REQUEST_CODE_PAYMENT
        )
    }

    private fun onFirstPaymentCompleted() {
        lifecycleScope.launch {
            val token = sessionManager.getAccessToken()
            authApiService.markUserNotNew(token)
            userRepository.setIsNewUser(currentUserId, false)
            val updated = userRepository.getUserById(currentUserId)
            if (updated != null) {
                sessionManager.updateCurrentUser(updated.copy(isNew = false))
            }
            binding.tvNewUserDiscount.visibility = View.GONE
        }
    }

    private fun showRewardedAd() {
        Toast.makeText(this, getString(R.string.ad_loading), Toast.LENGTH_SHORT).show()
        // TODO: AdMob интеграция — после просмотра активировать тариф Hour
    }

    private fun restorePurchases() {
        Toast.makeText(this, getString(R.string.restore_purchases_stub), Toast.LENGTH_SHORT).show()
    }

    private fun showErrorDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PAYMENT) {
            when (resultCode) {
                RESULT_OK -> {
                    val success = data?.getBooleanExtra("payment_success", false) ?: false
                    val paymentId = data?.getStringExtra("payment_id") ?: ""
                    if (success) {
                        binding.tvStatus.text = getString(R.string.payment_checking)
                        viewModel.checkPaymentStatus(paymentId)
                    } else {
                        showErrorDialog(getString(R.string.payment_not_completed))
                        binding.progressBar.visibility = View.GONE
                        binding.tvStatus.visibility = View.GONE
                    }
                }
                RESULT_CANCELED -> {
                    showErrorDialog(getString(R.string.payment_cancelled))
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.visibility = View.GONE
                }
            }
        }
    }
}
