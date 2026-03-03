package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
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
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finishWithResult() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithResult()
            }
        })

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

        binding.btnOpenSystemVpnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }

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

        val notificationsEnabled = VpnSettingsPreferences.isNotificationsEnabled(this)
        binding.switchNotifications.isChecked = notificationsEnabled

        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            VpnSettingsPreferences.setNotificationsEnabled(this, isChecked)
            if (NotificationManagerCompat.from(this).areNotificationsEnabled() != isChecked) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        }

        binding.tvPrivacyPolicyFooter.setOnClickListener {
            startActivity(LegalDocumentActivity.privacyIntent(this))
        }

        binding.tvSubscriptionManagement.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        binding.btnSaveSettings.setOnClickListener {
            finishWithResult()
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
