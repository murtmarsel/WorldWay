package ru.acestar.vpn.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.databinding.ActivityConfigSelectionBinding

class ConfigSelectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_CONFIG_ID = "selected_config_id"
        const val EXTRA_ACTIVE_CONFIG_ID = "active_config_id"
        const val EXTRA_OPEN_ADD = "open_add"
    }

    private lateinit var binding: ActivityConfigSelectionBinding
    private val configDao by lazy { (application as VpnApplication).database.vpnConfigDao() }
    private val sessionManager by lazy { SessionManager.getInstance(this) }
    private var currentUserId: Long = -1L
    private var configs: List<VpnConfigEntity> = emptyList()
    private var activeConfigId: Long = -1L

    private val configFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveConfigFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)


        if (currentUserId == -1L) {
            finish()
            return
        }
        lifecycleScope.launch {
            val user = sessionManager.getCurrentUser()
            currentUserId = user?.id ?: -1
        }
        binding = ActivityConfigSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        activeConfigId = intent.getLongExtra(EXTRA_ACTIVE_CONFIG_ID, -1L)
        setupUi()
        observeConfigs()
        if (intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) {
            binding.root.post { configFilePicker.launch("*/*") }
        }
    }

    private fun setupUi() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnAddConfig.setOnClickListener { configFilePicker.launch("*/*") }
    }

    private fun observeConfigs() {
        if (currentUserId == -1L) return
        lifecycleScope.launch {
            configDao.observeAllByUser(currentUserId).collectLatest { list ->
                configs = list.filter { it.configContent.isNotBlank() }
                renderList()
            }
        }
    }

    private fun renderList() {
        if (configs.isEmpty()) {
            binding.lvConfigs.adapter = null
            showToast(R.string.no_saved_configs)
            return
        }
        binding.lvConfigs.adapter = ConfigListAdapter()
        binding.lvConfigs.setOnItemClickListener { _, _, position, _ ->
            val selected = configs[position]
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_SELECTED_CONFIG_ID, selected.id)
            )
            finish()
        }
    }

    private fun saveConfigFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val configText = inputStream.bufferedReader().use { it.readText() }
                val host = extractServerHost(configText).ifBlank { "-" }
                val fileName = resolveFileName(uri)
                val name = extractFriendlyName(configText)
                    .ifBlank { fileName.substringBeforeLast(".") }
                    .ifBlank { host }
                lifecycleScope.launch {
                    configDao.insert(
                        VpnConfigEntity(
                            userId = currentUserId,
                            name = name,
                            serverHost = host,
                            configContent = configText,
                            source = "manual"
                        )
                    )
                    showToast(R.string.config_saved)
                }
            }
        } catch (e: Exception) {
            showToast("Ошибка: ${e.message}")
        }
    }

    private fun resolveFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index) ?: "config"
            }
        }
        return "config"
    }

    private fun extractFriendlyName(configContent: String): String {
        configContent.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("setenv FRIENDLY_NAME")) {
                val value = trimmed.removePrefix("setenv FRIENDLY_NAME").trim()
                return value.trim('"').trim()
            }
        }
        return ""
    }

    private fun extractProtocolLabel(configContent: String): String {
        configContent.lines().forEach { line ->
            val trimmed = line.trim().lowercase()
            if (trimmed.startsWith("proto ")) {
                return when {
                    trimmed.contains("tcp") -> "TCP"
                    trimmed.contains("udp") -> "UDP"
                    else -> "AUTO"
                }
            }
        }
        return "AUTO"
    }

    private fun extractServerHost(configContent: String): String {
        configContent.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("remote ")) {
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 2) return parts[1]
            }
        }
        return ""
    }

    private fun showToast(messageRes: Int) {
        if (!VpnSettingsPreferences.isToastNotificationsEnabled(this)) return
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(message: String) {
        if (!VpnSettingsPreferences.isToastNotificationsEnabled(this)) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class ConfigListAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@ConfigSelectionActivity)

        override fun getCount(): Int = configs.size
        override fun getItem(position: Int): Any = configs[position]
        override fun getItemId(position: Int): Long = configs[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_config, parent, false)
            val item = configs[position]

            val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
            val tvSubtitle = view.findViewById<TextView>(R.id.tvSubtitle)
            val tvActive = view.findViewById<TextView>(R.id.tvActive)
            val btnFavorite = view.findViewById<TextView>(R.id.btnFavorite)
            val btnDelete = view.findViewById<TextView>(R.id.btnDelete)

            val protocol = extractProtocolLabel(item.configContent)
            tvTitle.text = "${item.name} [$protocol]"
            tvSubtitle.text = item.serverHost

            val isActive = item.id == activeConfigId
            tvActive.visibility = if (isActive) View.VISIBLE else View.GONE
            tvActive.text = getString(R.string.active_config_label)

            btnFavorite.setTextColor(
                if (item.isFavorite) getColor(R.color.connecting) else getColor(R.color.disconnected)
            )
            btnFavorite.setOnClickListener {
                lifecycleScope.launch {
                    if (!item.isFavorite && configDao.getFavoriteCount(currentUserId) >= 10) {
                        showToast(R.string.favorite_limit_reached)
                        return@launch
                    }
                    configDao.setFavorite(item.id, currentUserId, !item.isFavorite)
                }
            }

            btnDelete.visibility = if (item.source == "manual") View.VISIBLE else View.GONE
            btnDelete.setOnClickListener {
                val messageRes = if (isActive) {
                    getString(R.string.delete_active_config_message, item.name)
                } else {
                    getString(R.string.delete_config_message, item.name)
                }
                AlertDialog.Builder(this@ConfigSelectionActivity)
                    .setTitle(R.string.delete_config_title)
                    .setMessage(messageRes)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch {
                            configDao.deleteManualById(item.id, currentUserId)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            return view
        }
    }
}
