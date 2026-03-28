package ru.acestar.vpn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ru.acestar.vpn.databinding.ActivityPaymentWebviewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.webkit.CookieManager
import android.webkit.CookieSyncManager
import ru.acestar.vpn.R

class PaymentWebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaymentWebviewBinding

    companion object {
        private const val EXTRA_PAYMENT_URL = "extra_payment_url"
        private const val EXTRA_RETURN_URL = "extra_return_url"
        private const val EXTRA_PAYMENT_ID = "extra_payment_id"

        fun newIntent(
            context: Context,
            paymentUrl: String,
            returnUrl: String,
            paymentId: String
        ): Intent {
            return Intent(context, PaymentWebViewActivity::class.java).apply {
                putExtra(EXTRA_PAYMENT_URL, paymentUrl)
                putExtra(EXTRA_RETURN_URL, returnUrl)
                putExtra(EXTRA_PAYMENT_ID, paymentId)
            }
        }
    }

    private val paymentUrl by lazy { intent.getStringExtra(EXTRA_PAYMENT_URL) ?: "" }
    private val returnUrl by lazy { intent.getStringExtra(EXTRA_RETURN_URL) ?: "" }
    private val paymentId by lazy { intent.getStringExtra(EXTRA_PAYMENT_ID) ?: "" }

    private var isPaymentCompleted = false

//    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPaymentWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)


        setupToolbar()
        setupWebView()

        if (paymentUrl.isNotEmpty()) {
            loadPaymentPage()
        } else {
            showErrorAndClose("Ошибка: ссылка на оплату отсутствует")
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Оплата"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        binding.toolbar.setNavigationOnClickListener {
            confirmClose()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = settings.userAgentString + " VPN-Client-Android"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.tvError.visibility = android.view.View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // Проверяем, не вернулись ли мы на return_url
                    url?.let { currentUrl ->
                        if (currentUrl.startsWith(returnUrl) && !isPaymentCompleted) {
                            isPaymentCompleted = true
                            // Платеж завершен, возвращаем результат
                            returnPaymentResult(true)
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url.toString()

                    // Проверяем, не содержит ли URL наш return_url
                    if (url.startsWith(returnUrl)) {
                        isPaymentCompleted = true
                        returnPaymentResult(true)
                        return true
                    }

                    // Обработка ссылок на другие приложения (например, банковские приложения)
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return false // Загружаем в WebView
                    } else {
                        // Пытаемся открыть другие схемы (intent, pay, и т.д.) в других приложениях
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            startActivity(intent)
                            return true
                        } catch (e: Exception) {
                            return true
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)

                    if (request?.isForMainFrame == true) {
                        binding.webView.visibility = android.view.View.GONE
                        binding.tvError.visibility = android.view.View.VISIBLE

                        val errorMessage = error?.description?.toString() ?: "Неизвестная ошибка"
                        binding.tvError.text = "Ошибка загрузки: $errorMessage"
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    supportActionBar?.subtitle = title
                }
            }
        }

        // Настройка CookieManager для сохранения сессий
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }
    }

    private fun loadPaymentPage() {
        binding.webView.visibility = android.view.View.VISIBLE
        binding.tvError.visibility = android.view.View.GONE

        // Очищаем куки для новой сессии оплаты (опционально)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().removeAllCookies(null)
        }

        binding.webView.loadUrl(paymentUrl)
    }

    private fun returnPaymentResult(success: Boolean) {
        val resultIntent = Intent().apply {
            putExtra("payment_success", success)
            putExtra("payment_id", paymentId)
        }
        setResult(RESULT_OK, resultIntent)

        // Показываем сообщение об успехе и закрываем
        if (success) {
            binding.webView.visibility = android.view.View.GONE
            binding.tvError.visibility = android.view.View.VISIBLE
            binding.tvError.text = "Платеж успешно завершен!"
            binding.tvError.setTextColor(android.graphics.Color.GREEN)

            // Закрываем через 2 секунды
            lifecycleScope.launch {
                delay(2000)
                finish()
            }
        } else {
            finish()
        }
    }

    private fun confirmClose() {
        if (isPaymentCompleted) {
            finish()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Прервать оплату?")
            .setMessage("Вы уверены, что хотите прервать процесс оплаты?")
            .setPositiveButton("Да") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun showErrorAndClose(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Ошибка")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack() && !isPaymentCompleted) {
            // Спрашиваем, хочет ли пользователь вернуться назад
            MaterialAlertDialogBuilder(this)
                .setTitle("Вернуться назад?")
                .setMessage("Это может прервать процесс оплаты. Продолжить?")
                .setPositiveButton("Да") { _, _ ->
                    binding.webView.goBack()
                }
                .setNegativeButton("Нет", null)
                .show()
        } else {
            confirmClose()
        }
    }
}