// ui/EmailConfirmationActivity.kt
package ru.acestar.vpn.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import ru.acestar.vpn.data.model.EmailConfirmationState
import ru.acestar.vpn.databinding.ActivityEmailConfirmBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.acestar.vpn.data.model.EmailConfirmationViewModel

class EmailConfirmationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmailConfirmBinding
    private val viewModel = EmailConfirmationViewModel()

//    companion object {
//        private const val EXTRA_EMAIL = "email"
//        private const val EXTRA_FOR_RESULT = "result"
//
//        fun newIntent(
//            context: Context,
//            email: String = "",
//            forResult: Boolean = false
//        ): Intent {
//            return Intent(context, EmailConfirmationActivity::class.java).apply {
//                putExtra(EXTRA_EMAIL, email)
//                putExtra(EXTRA_FOR_RESULT, forResult)
//            }
//        }
//
//        const val RESULT_EMAIL_CONFIRMED = 200
//        const val EXTRA_CONFIRMED_EMAIL = "confirmed_email"
//    }

    private var currentEmail: String = ""
    private var forResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentEmail = intent.getStringExtra("email") ?: ""
        forResult = intent.getBooleanExtra("", false)

        setupToolbar()
        setupViews()
        setupObservers()
        setupTextWatchers()

        // Если email передан, сразу показываем поле для кода
        if (currentEmail.isNotEmpty()) {
            binding.etEmail.setText(currentEmail)
            viewModel.sendConfirmationCode(currentEmail)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupViews() {
        binding.btnSendCode.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (email.isNotEmpty()) {
                currentEmail = email
                viewModel.sendConfirmationCode(email)
            } else {
                showMessage("Введите email", isError = true)
            }
        }

        binding.btnVerifyCode.setOnClickListener {
            val code = binding.etCode.text.toString().trim()
            if (code.isNotEmpty() && currentEmail.isNotEmpty()) {
                viewModel.verifyCode(currentEmail, code)
            } else {
                showMessage("Введите код подтверждения", isError = true)
            }
        }

        binding.btnResendCode.setOnClickListener {
            viewModel.resendCode(currentEmail)
        }
    }

    private fun setupTextWatchers() {
        // Автоматический переход к коду после ввода 6 цифр
        binding.etCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (s?.length == 6) {
                    binding.btnVerifyCode.performClick()
                }
            }
        })

        // Маска для email (простой пример)
        binding.etEmail.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val email = binding.etEmail.text.toString().trim()
                if (email.isNotEmpty() && !isValidEmail(email)) {
                    binding.inputLayoutEmail.error = "Некорректный email"
                } else {
                    binding.inputLayoutEmail.error = null
                }
            }
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun setupObservers() {
        viewModel.emailState.observe(this, Observer { state ->
            when (state) {
                is EmailConfirmationState.Loading -> {
                    showLoading(true)
                }

                is EmailConfirmationState.CodeSent -> {
                    showLoading(false)
                    showCodeInput(state.email)
                    showMessage("Код отправлен на $state.email", isError = false)
                }

                is EmailConfirmationState.Verified -> {
                    showLoading(false)
                    showSuccess(state.email)

                }

                is EmailConfirmationState.Error -> {
                    showLoading(false)
                    showMessage(state.message, isError = true)
                }

                is EmailConfirmationState.Idle -> {
                    showLoading(false)
                }
            }
        })

        viewModel.countdown.observe(this, Observer { seconds ->
            if (seconds > 0) {
                binding.tvTimer.text = "Повторная отправка через $seconds сек."
                binding.tvTimer.visibility = View.VISIBLE
                binding.btnResendCode.isEnabled = false
            } else {
                binding.tvTimer.visibility = View.GONE
                binding.btnResendCode.isEnabled = true
            }
        })
    }

    private fun showLoading(isLoading: Boolean) {
        if (isLoading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnSendCode.isEnabled = false
            binding.btnVerifyCode.isEnabled = false
            binding.btnResendCode.isEnabled = false
        } else {
            binding.progressBar.visibility = View.GONE
            binding.btnSendCode.isEnabled = true
            binding.btnVerifyCode.isEnabled = true
        }
    }

    private fun showCodeInput(email: String) {
        binding.inputLayoutEmail.visibility = View.GONE
        binding.btnSendCode.visibility = View.GONE

        binding.inputLayoutCode.visibility = View.VISIBLE
        binding.btnVerifyCode.visibility = View.VISIBLE
        binding.btnResendCode.visibility = View.VISIBLE

        binding.tvDescription.text = "Введите 6-значный код, отправленный на\n$email"

        // Автофокус на поле ввода кода
        binding.etCode.requestFocus()
    }

    private fun showMessage(message: String, isError: Boolean) {
        binding.tvMessage.text = message
        binding.tvMessage.visibility = View.VISIBLE
        binding.tvMessage.setTextColor(
            if (isError) resources.getColor(android.R.color.holo_red_dark, theme)
            else resources.getColor(android.R.color.holo_green_dark, theme)
        )

        // Скрываем сообщение через 5 секунд
        binding.tvMessage.postDelayed({
            binding.tvMessage.visibility = View.GONE
        }, 5000)
    }

    private fun showSuccess(email: String) {
        // Показываем диалог успеха
        MaterialAlertDialogBuilder(this)
            .setTitle("Email подтвержден")
            .setMessage("Email $email успешно подтвержден!")
            .setPositiveButton("OK") { _, _ ->
                // Возвращаем результат в вызывающую Activity
                val resultIntent = Intent().apply {
                    putExtra("is_email_confirmed", "true")
                }
                setResult(RESULT_OK, resultIntent)
                finish()

            }
            .setCancelable(false)
            .show()
    }

    override fun onBackPressed() {
        if (binding.inputLayoutCode.visibility == View.VISIBLE) {
            // Если мы на шаге ввода кода, спрашиваем подтверждение
            MaterialAlertDialogBuilder(this)
                .setTitle("Отмена подтверждения")
                .setMessage("Вы уверены, что хотите прервать процесс подтверждения email?")
                .setPositiveButton("Да") { _, _ ->
                    val resultIntent = Intent().apply {
                        putExtra("is_email_confirmed", "false")
                    }
                    setResult(RESULT_CANCELED, resultIntent)
                    finish()
                }
                .setNegativeButton("Нет", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

}