package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager  // ✅ lateinit вместо lazy
    private lateinit var authApiService: AuthApiService  // ✅ lateinit
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)

        // ✅ Инициализация всех зависимостей после super.onCreate()
        sessionManager = SessionManager.getInstance(this)
        authApiService = AuthApiService(sessionManager)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthKeyboardMotion.attach(binding.authScroll, binding.ivLogo, binding.tvAppName, binding.cardLogin, binding.btnLogin)


        setupUI()
        observeViewModel()

        // Проверка авторизации
        lifecycleScope.launch {
            authApiService.refreshTokenIfNeeded()
            if (sessionManager.isLoggedIn()) {
                navigateToMain(true)
            }
        }
    }

    private fun setupUI() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()
            viewModel.login(email, password)

        }

        binding.tvForgotPassword.setOnClickListener {
            PasswordRecoverySheet.newInstance(binding.etEmail.text.toString().trim())
                .show(supportFragmentManager, "password_recovery")
        }

        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.authState.collectLatest { state ->
                when (state) {
                    is AuthState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.btnLogin.isEnabled = false
                    }

                    is AuthState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        // ✅ Запускаем корутину для получения файлов

                        navigateToMain(false)
                    }

                    is AuthState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, state.message, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }

                    is AuthState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                    }
                }
            }
        }
    }

    private fun navigateToMain(gettingData: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).apply{
            putExtra(MainActivity.EXTRA_GETTING_USER_DATA, gettingData)
        })
        finish()
    }
}


