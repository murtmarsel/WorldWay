package ru.acestar.vpn.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.databinding.ActivityConfigSelectionBinding

class ConfigSelectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_CONFIG_ID = "selected_config_id"
    }

    private lateinit var binding: ActivityConfigSelectionBinding
    private val configDao by lazy { (application as VpnApplication).database.vpnConfigDao() }
    private var configs: List<VpnConfigEntity> = emptyList()

    private val configFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveConfigFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityConfigSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUi()
        observeConfigs()
    }

    private fun setupUi() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnAddConfig.setOnClickListener { configFilePicker.launch("*/*") }
    }

    private fun observeConfigs() {
        lifecycleScope.launch {
            configDao.observeAll().collectLatest { list ->
                configs = list.filter { it.configContent.isNotBlank() }
                renderList()
            }
        }
    }

    private fun renderList() {
        if (configs.isEmpty()) {
            binding.lvConfigs.adapter = null
            Toast.makeText(this, R.string.no_saved_configs, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = configs.map { "${it.name} (${it.serverHost})" }
        binding.lvConfigs.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            labels
        )
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
                val name = "Конфиг $host"
                lifecycleScope.launch {
                    configDao.insert(
                        VpnConfigEntity(
                            name = name,
                            serverHost = host,
                            configContent = configText,
                            source = "manual"
                        )
                    )
                    Toast.makeText(this@ConfigSelectionActivity, R.string.config_saved, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
}
