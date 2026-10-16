package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import ru.acestar.vpn.R
import ru.acestar.vpn.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROTOCOL_CHANGED = "protocol_changed"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var initialProtocol: String

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
    }

    private fun setupUi() {
        initialProtocol = VpnSettingsPreferences.getProtocolMode(this)
        binding.toolbar.setNavigationIcon(ru.acestar.vpn.R.drawable.ic_arrow_back_white)
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

        // Автообновление
        binding.switchAutoUpdate.isChecked = VpnSettingsPreferences.isAutoUpdateEnabled(this)
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            VpnSettingsPreferences.setAutoUpdateEnabled(this, isChecked)
        }

        // Юридические ссылки
        binding.tvPrivacyPolicyFooter.setOnClickListener {
            startActivity(LegalDocumentActivity.privacyIntent(this))
        }

        // Управление подпиской
        binding.tvSubscriptionManagement.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
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