package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import java.util.Date
import java.sql.Date as SqlDate
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.data.dao.VpnServerConfigDao
import ru.acestar.vpn.utils.PasswordHasher
import ru.acestar.vpn.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val sessionManager by lazy { SessionManager.getInstance(this) }
    private val authApiService = AuthApiService(sessionManager)
    private val userRepository by lazy {
        UserRepository((application as VpnApplication).database.userDao())
    }
//    private val vpnServerConfigDao = (application as VpnApplication).database.vpnServerConfigDao()
    private val viewModel: AuthViewModel by viewModels()
    private var pendingEmail: String = ""
    private var pendingPassword: String = ""
    private var pendingConfirmPassword: String = ""
    private var pendingCreateDate: Date = Date()
    private var pendingTariff: String = "Free"
    private var pendingIsNew: Boolean = true
    private var registrationJob: Job? = null
    private var registrationSucceeded: Boolean = false
    private var registrationErrorMessage: String? = null


    private val agreementLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            lifecycleScope.launch {
                registrationJob?.join()
                if (registrationSucceeded) {
                    viewModel.login(pendingEmail, pendingPassword)
                    navigateToMain()
                } else {
                    showErrorDialog(registrationErrorMessage ?: "Ошибка регистрации")
                }
            }
        } else {
            Toast.makeText(this, R.string.agreement_required, Toast.LENGTH_SHORT).show()
        }
    }

    private val emailConfirmLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            registrationJob = lifecycleScope.launch {
                registrationSucceeded = false
                registrationErrorMessage = null
                create_client(
                    email = pendingEmail,
                    password = pendingPassword,
                    createDate = pendingCreateDate,
                )
            }
            agreementLauncher.launch(LegalDocumentActivity.agreementIntent(this))
        } else {
            Toast.makeText(this, R.string.emailConfirm_required, Toast.LENGTH_SHORT).show()
        }
    }


    
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        observeViewModel()
    }

    private fun email_manager(){
        emailConfirmLauncher.launch(Intent(this, EmailConfirmationActivity::class.java).apply{
            putExtra("email", pendingEmail)
        })
    }
    private suspend fun getClientFilesList(
        user_id: Long
    ){
        val result = authApiService.getClientFilesList(user_id)

        result.onSuccess { VpnServerConfigEntity ->
//            vpnServerConfigDao.insert()
        }.onFailure{ exception ->
            showErrorDialog("Ошибка получения данных: ${exception.hashCode()}")
        }

//        name = fileJson.optString("name", ""),
//        path = fileJson.optString("path", ""),
//        size = fileJson.optLong("size", 0),
//        modified = fileJson.optDouble("modified", 0.0),
//        type = fileJson.optString("type", "unknown"),
//        category = fileJson.optString("category", null),
//        protocol = fileJson.optString("protocol", null),
//        config = fileJson.optString("config", null),
//        content = fileJson.optString("content", null)

    }
    private suspend fun create_client(
        email: String,
        password: String,
        createDate: Date,
    ) {
        try {
            val result = authApiService.createUser(
                email = email,
                password = password,
                createDate = createDate as SqlDate
            )

            result.onSuccess { authData ->
                val emailTrim = authData.email.ifBlank { email.trim() }
                val passwordHash = PasswordHasher.hashForServer(password, emailTrim)
                val user = userRepository.upsertAfterServerAuth(emailTrim, passwordHash, authData)

                sessionManager.saveSession(
                    id = authData.id,
                    user = user,
                    token = authData.token,
                    email = emailTrim,
                    tariff = authData.tariff,
                    subsEndDate = authData.subsEndDate,
                    isConfirmed = authData.isConfirmed,
                    isActive = authData.isActive,
                    createdAt = authData.createdAt
                )

                val tokenForConfirm = sessionManager.getAccessToken()
                tokenForConfirm?.let { authApiService.setAuthToken(it) }

                val confirmResult = authApiService.confirmEmail(
                    email = emailTrim,
                    isConfirmed = true
                )
                confirmResult.fold(
                    onSuccess = {
                        registrationSucceeded = true
                    },
                    onFailure = { exception ->
                        registrationSucceeded = false
                        registrationErrorMessage = "Ошибка подтверждения email: ${exception.message}"
                    }
                )
            }.onFailure { exception ->
                registrationSucceeded = false
                registrationErrorMessage = "Ошибка регистрации: ${exception.message}"
            }
        } catch (e: Exception) {
            registrationSucceeded = false
            registrationErrorMessage = "Ошибка: ${e.message}"
        }
    }

    private fun confirmEmail(
        email: String,
        isConfirmed: Boolean) {
        lifecycleScope.launch {
            try {
                val result = authApiService.confirmEmail(
                    email = email,
                    isConfirmed = isConfirmed
                )

                result.onSuccess { authData ->

                }.onFailure { exception ->
                    showErrorDialog("Ошибка подтверждения email: ${exception.message}")
                }


            } catch (e: Exception) {
                showErrorDialog("Ошибка: ${e.message}")
            }
        }
    }
    private fun setupUI() {
        binding.btnRegister.setOnClickListener {
            val utilDate = Date()
            val sqlDate = SqlDate(utilDate.time)
            pendingEmail = binding.etEmail.text.toString()
            pendingPassword = binding.etPassword.text.toString()
            pendingConfirmPassword = binding.etConfirmPassword.text.toString()
            pendingCreateDate = sqlDate


            var check = viewModel.fields_check(pendingEmail, pendingPassword, pendingConfirmPassword)
            if(check) {
                email_manager()

            }




        }
        
        binding.tvLogin.setOnClickListener {
            finish()
        }

        binding.tvPrivacyPolicy.setOnClickListener {
            startActivity(LegalDocumentActivity.privacyIntent(this))
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.authState.collectLatest { state ->
                when (state) {
                    is AuthState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.btnRegister.isEnabled = false
                    }
                    is AuthState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        navigateToMain()
                    }
                    is AuthState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnRegister.isEnabled = true
                        Toast.makeText(this@RegisterActivity, state.message, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is AuthState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnRegister.isEnabled = true
                    }
                }
            }
        }
    }
    
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    private fun showErrorDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Ошибка")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
