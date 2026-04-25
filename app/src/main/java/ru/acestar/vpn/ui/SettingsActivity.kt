package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.data.database.AppDatabase
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.databinding.ActivitySettingsBinding
import ru.acestar.vpn.vpn.OpenVpnManager

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROTOCOL_CHANGED = "protocol_changed"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var initialProtocol: String
    private lateinit var sessionManager: SessionManager
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)

        // ✅ Инициализация после super.onCreate
        sessionManager = SessionManager.getInstance(this)
        database = AppDatabase.getDatabase(this)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
    }

    private fun setupUi() {
        initialProtocol = VpnSettingsPreferences.getProtocolMode(this)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finishWithResult() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithResult()
            }
        })

        // Настройка темы
        when (ThemePreferences.getThemeMode(this)) {
            ThemePreferences.THEME_LIGHT -> binding.rbThemeLight.isChecked = true
            ThemePreferences.THEME_DARK -> binding.rbThemeDark.isChecked = true
            else -> binding.rbThemeSystem.isChecked = true
        }

        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> ThemePreferences.THEME_LIGHT
                R.id.rbThemeDark -> ThemePreferences.THEME_DARK
                else -> ThemePreferences.THEME_SYSTEM
            }
            ThemePreferences.saveThemeMode(this, mode)
        }

        // Настройка VPN системных настроек
        binding.btnOpenSystemVpnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }

        // Настройка протокола
        when (VpnSettingsPreferences.getProtocolMode(this)) {
            VpnSettingsPreferences.PROTOCOL_UDP -> binding.rbProtocolUdp.isChecked = true
            VpnSettingsPreferences.PROTOCOL_TCP -> binding.rbProtocolTcp.isChecked = true
            else -> binding.rbProtocolAuto.isChecked = true
        }

        binding.rgProtocol.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbProtocolUdp -> VpnSettingsPreferences.PROTOCOL_UDP
                R.id.rbProtocolTcp -> VpnSettingsPreferences.PROTOCOL_TCP
                else -> VpnSettingsPreferences.PROTOCOL_AUTO
            }
            VpnSettingsPreferences.saveProtocolMode(this, mode)
        }

        // Настройка уведомлений
        val toastNotificationsEnabled = VpnSettingsPreferences.isToastNotificationsEnabled(this)
        binding.switchToastNotifications.isChecked = toastNotificationsEnabled

        binding.switchToastNotifications.setOnCheckedChangeListener { _, isChecked ->
            VpnSettingsPreferences.setToastNotificationsEnabled(this, isChecked)
        }

        binding.btnSystemNotifications.setOnClickListener {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }

        // Юридические ссылки
        binding.tvPrivacyPolicyFooter.setOnClickListener {
            startActivity(LegalDocumentActivity.privacyIntent(this))
        }

        // Управление подпиской
        binding.tvSubscriptionManagement.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        // ✅ Выход из системы
        binding.tvLogout.setOnClickListener {
            logout()
        }

        binding.btnSaveSettings.setOnClickListener {
            finishWithResult()
        }
    }

    private fun logout() {
        // Показываем диалог подтверждения
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.logout_yes) { _, _ ->
                performLogout()
            }
            .setNegativeButton(R.string.logout_no, null)
            .show()
    }

    private fun performLogout() {
        // Отключаем VPN
        OpenVpnManager.getInstance(this).disconnect()
        OpenVpnManager.getInstance(this).resetUiStateToDisconnected()

        // ✅ Очищаем сессию
        lifecycleScope.launch {
            try {

                sessionManager.logout()
                sessionManager.clearSession()

                // Переходим на экран входа
                val intent = Intent(this@SettingsActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                // В случае ошибки все равно переходим на экран входа
                val intent = Intent(this@SettingsActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private fun finishWithResult() {
        val changed = initialProtocol != VpnSettingsPreferences.getProtocolMode(this)
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_PROTOCOL_CHANGED, changed)
        )
        finish()
    }
}