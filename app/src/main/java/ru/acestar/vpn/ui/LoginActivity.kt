package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.data.model.VpnServerConfigEntity
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.databinding.ActivityLoginBinding
import ru.acestar.vpn.data.dao.VpnServerConfigDao

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager  // ✅ lateinit вместо lazy
    private lateinit var authApiService: AuthApiService  // ✅ lateinit
    private val viewModel: AuthViewModel by viewModels()
    private var pendEmail: String = ""
    private lateinit var vpnServerConfigDao: VpnServerConfigDao  // ✅ lateinit

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)

        // ✅ Инициализация всех зависимостей после super.onCreate()
        sessionManager = SessionManager.getInstance(this)
        authApiService = AuthApiService(sessionManager)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Инициализация DAO
        vpnServerConfigDao = (application as VpnApplication).database.vpnServerConfigDao()

        setupUI()
        observeViewModel()

        // Проверка авторизации
        lifecycleScope.launch {
            if (sessionManager.isLoggedIn()) {
                navigateToMain(true)
            }
        }
    }

    private fun setupUI() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()

            pendEmail = email
            viewModel.login(email, password)
            lifecycleScope.launch {

                getClientFilesList(pendEmail)

            }
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

    private suspend fun getClientFilesList(email: String) {
        val result = authApiService.getClientFilesList(email, "notoken")
        val userId = sessionManager.getUserId()
        println("Получение файлов ${result} для ${userId}")

        result.onSuccess { filesList ->
            if (filesList.isEmpty()) {
                return@onSuccess
            }

            // Записываем каждый файл в базу данных
            filesList.forEach { file ->
                try {
                    // ✅ Проверка на null для category и protocol
                    val configEntity = VpnServerConfigEntity(
                        userId = userId,
                        name = file.name,
                        type = file.type,
                        category = file.category ?: "unknown",  // ✅ значение по умолчанию
                        protocol = file.protocol ?: "auto",      // ✅ значение по умолчанию
                        content = file.content ?: ""             // ✅ значение по умолчанию
                    )

                    vpnServerConfigDao.insert(configEntity)
                } catch (e: Exception) {
                    // Логируем ошибку, но продолжаем
                    e.printStackTrace()
                }
            }

        }.onFailure { exception ->
            showErrorDialog("Ошибка получения данных: ${exception.hashCode()}")
        }
    }

    private fun showErrorDialog(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ошибка")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showSuccessDialog(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Успех")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun navigateToMain(gettingData: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).apply{
            putExtra(MainActivity.EXTRA_GETTING_USER_DATA, gettingData)
        })
        finish()
    }
}