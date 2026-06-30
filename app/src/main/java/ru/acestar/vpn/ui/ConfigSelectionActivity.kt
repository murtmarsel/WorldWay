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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.databinding.ActivityConfigSelectionBinding

class ConfigSelectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_CONFIG_ID = "selected_config_id"
        const val EXTRA_ACTIVE_CONFIG_ID   = "active_config_id"
        const val EXTRA_OPEN_ADD           = "open_add"
    }

    // ─── Тип конфига ─────────────────────────────────────────────────────────
    enum class VpnConfigType { OPENVPN, WIREGUARD, UNKNOWN }

    // ─── Поля ─────────────────────────────────────────────────────────────────
    private lateinit var binding: ActivityConfigSelectionBinding

    private val configDao      by lazy { (application as VpnApplication).database.vpnConfigDao() }
    private val sessionManager by lazy { SessionManager.getInstance(this) }
    private val authApiService by lazy { ru.acestar.vpn.api.AuthApiService(sessionManager) }

    private var currentUserId = -1L
    private var configs: List<VpnConfigEntity> = emptyList()
    private var activeConfigId: Long = -1L

    private val configFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { saveConfigFromUri(it) } }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityConfigSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeConfigId = intent.getLongExtra(EXTRA_ACTIVE_CONFIG_ID, -1L)
        setupUi()

        lifecycleScope.launch {
            currentUserId = sessionManager.getUserId()
            if (currentUserId == -1L) {
                finish()
                return@launch
            }
            observeConfigs()

            // Обрабатываем входящий файл из стороннего приложения (ACTION_VIEW / ACTION_SEND)
            when (intent?.action) {
                Intent.ACTION_VIEW -> {
                    intent.data?.let { uri -> saveConfigFromUri(uri) }
                }
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                        ?: intent.data
                    uri?.let { saveConfigFromUri(it) }
                }
                else -> {
                    if (intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) {
                        binding.root.post { configFilePicker.launch("*/*") }
                    }
                }
            }
        }
    }

    // ─── UI ───────────────────────────────────────────────────────────────────

    private fun setupUi() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnAddConfig.setOnClickListener { configFilePicker.launch("*/*") }

        // Свайп вниз — синхронизация конфигов с сервером
        binding.swipeRefreshConfigs.setOnRefreshListener {
            syncConfigsWithServer()
        }
    }

    /**
     * Синхронизация конфигов с сервером:
     * 1. Скачивает полный список серверных конфигов аккаунта
     * 2. Отправляет на сервер локальные конфиги, которых там нет
     * Анимация SwipeRefresh крутится пока идёт процесс.
     */
    private fun syncConfigsWithServer() {
        binding.swipeRefreshConfigs.isRefreshing = true
        lifecycleScope.launch {
            try {
                val user = sessionManager.getCurrentUser()
                if (user == null) {
                    binding.swipeRefreshConfigs.isRefreshing = false
                    return@launch
                }
                val token = sessionManager.getAccessToken()

                // 1. Получаем серверные конфиги (для отображения актуального списка)
                authApiService.getClientFilesList(user.email, token)

                // 2. Отправляем локальные manual-конфиги на сервер
                val snapshot = configDao.getAllByUser(currentUserId)
                snapshot.filter { it.source == "manual" }.forEach { cfg ->
                    // При ошибке отправки — игнорируем, ничего не делаем
                    authApiService.uploadUserConfig(
                        name = cfg.name,
                        type = cfg.type,
                        content = cfg.configContent,
                        serverHost = cfg.serverHost
                    )
                }
                showToast(R.string.configs_synced)
            } catch (e: Exception) {
                // Сеть недоступна — просто прячем индикатор
            } finally {
                binding.swipeRefreshConfigs.isRefreshing = false
            }
        }
    }

    private fun observeConfigs() {
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
        // Долгое нажатие — переименование конфигурации
        binding.lvConfigs.setOnItemLongClickListener { _, _, position, _ ->
            showRenameDialog(configs[position])
            true
        }
    }

    /**
     * Диалог переименования конфигурации.
     * Ограничение: до 10 символов.
     */
    private fun showRenameDialog(config: VpnConfigEntity) {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(config.name.take(10))
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
            setSelection(text?.length ?: 0)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_config_title)
            .setMessage(R.string.rename_config_hint)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty().take(10)
                if (newName.isNotBlank()) {
                    lifecycleScope.launch {
                        configDao.rename(config.id, currentUserId, newName)
                        showToast(R.string.config_renamed)
                    }
                } else {
                    showToast(R.string.rename_config_empty)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ─── СОХРАНЕНИЕ КОНФИГА ───────────────────────────────────────────────────

    private fun saveConfigFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val configText = inputStream.bufferedReader().use { it.readText() }

                // Определяем тип по содержимому + расширению
                val detectedType = detectVpnConfigType(uri, configText)
                if (detectedType == VpnConfigType.UNKNOWN) {
                    showToast("Неизвестный формат конфига")
                    return
                }

                val typeString = when (detectedType) {
                    VpnConfigType.OPENVPN   -> "OpenVPN"
                    VpnConfigType.WIREGUARD -> "WireGuard"
                    else                    -> "Unknown"
                }

                val host      = when (detectedType) {
                    VpnConfigType.OPENVPN   -> extractOpenVpnServerHost(configText)
                    VpnConfigType.WIREGUARD -> extractWireGuardEndpoint(configText)
                    else                    -> ""
                }.ifBlank { "-" }

                val fileName = resolveFileName(uri)
                val name = when (detectedType) {
                    VpnConfigType.OPENVPN -> extractFriendlyName(configText)
                        .ifBlank { fileName.substringBeforeLast(".") }
                        .ifBlank { host }
                    VpnConfigType.WIREGUARD -> fileName.substringBeforeLast(".")
                        .ifBlank { host }
                    else -> fileName
                }

                lifecycleScope.launch {
                    configDao.insert(
                        VpnConfigEntity(
                            userId        = currentUserId,
                            name          = name,
                            serverHost    = host,
                            configContent = configText,
                            source        = "manual",
                            type          = typeString,
                        )
                    )
                    showToast(R.string.config_saved)
                    // Отправляем на сервер — при ошибке ничего не делаем
                    authApiService.uploadUserConfig(
                        name = name,
                        type = typeString,
                        content = configText,
                        serverHost = host
                    )
                }
            }
        } catch (e: Exception) {
            showToast("Ошибка: ${e.message}")
        }
    }

    // ─── ОПРЕДЕЛЕНИЕ ТИПА КОНФИГА ─────────────────────────────────────────────

    /**
     * Определяет тип конфига: сначала по содержимому (надёжно),
     * затем по расширению как fallback.
     */
    private fun detectVpnConfigType(uri: Uri, content: String): VpnConfigType {
        // 1. Анализ содержимого — самый надёжный способ
        val lines = content.lines()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(";") }
            .take(60)

        // Признаки WireGuard
        val wgIndicators = listOf(
            "[interface]", "[peer]",
            "privatekey =", "publickey =",
            "allowedips =", "endpoint =",
            "listenport =", "dns =",
            "presharedkey ="
        )
        // Признаки OpenVPN
        val ovpnIndicators = listOf(
            "client", "dev tun", "dev tap",
            "proto udp", "proto tcp",
            "remote ", "ca ", "<ca>",
            "cert ", "key ", "<cert>", "<key>",
            "pkcs12", "tls-auth", "tls-crypt",
            "auth-user-pass", "cipher "
        )

        var wgScore   = 0
        var ovpnScore = 0

        for (line in lines) {
            when {
                wgIndicators.any   { line.startsWith(it) || line == it } -> wgScore++
                ovpnIndicators.any { line.startsWith(it) || line == it } -> ovpnScore++
            }
        }

        if (wgScore >= 2)   return VpnConfigType.WIREGUARD
        if (ovpnScore >= 2) return VpnConfigType.OPENVPN

        // 2. Fallback по расширению
        val fileName = resolveFileName(uri)
        return when {
            fileName.endsWith(".ovpn", ignoreCase = true) -> VpnConfigType.OPENVPN
            fileName.endsWith(".conf", ignoreCase = true) -> {
                // .conf может быть обоим — пробуем ещё раз с меньшим порогом
                when {
                    wgScore > 0   -> VpnConfigType.WIREGUARD
                    ovpnScore > 0 -> VpnConfigType.OPENVPN
                    else          -> VpnConfigType.UNKNOWN
                }
            }
            else -> VpnConfigType.UNKNOWN
        }
    }

    // ─── ПАРСИНГ КОНФИГОВ ────────────────────────────────────────────────────

    /** Извлекает хост из OpenVPN конфига (строка `remote HOST PORT`). */
    private fun extractOpenVpnServerHost(content: String): String {
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("remote ", ignoreCase = true)) {
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 2) return parts[1]
            }
        }
        return ""
    }

    /**
     * Извлекает хост из WireGuard конфига (строка `Endpoint = HOST:PORT`).
     * Убирает порт, возвращает только хост.
     */
    private fun extractWireGuardEndpoint(content: String): String {
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Endpoint", ignoreCase = true) && trimmed.contains("=")) {
                val value = trimmed.substringAfter("=").trim()
                // IPv6 формат [::1]:port — убираем порт
                return if (value.startsWith("[")) {
                    value.substringBefore("]").removePrefix("[")
                } else {
                    value.substringBeforeLast(":")
                }
            }
        }
        return ""
    }

    /** Читает FRIENDLY_NAME из OpenVPN конфига. */
    private fun extractFriendlyName(content: String): String {
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("setenv FRIENDLY_NAME", ignoreCase = true)) {
                return trimmed.removePrefix("setenv FRIENDLY_NAME")
                    .trim().trim('"')
            }
        }
        return ""
    }

    /**
     * Возвращает метку протокола для отображения в списке.
     * FIX: WireGuard конфиги не имеют строки `proto` — возвращаем "WireGuard".
     */
    private fun getProtocolLabel(item: VpnConfigEntity): String {
        return when (item.type) {
            "WireGuard" -> "WireGuard"
            "OpenVPN"   -> extractOpenVpnProtocol(item.configContent)
            else        -> "?"
        }
    }

    private fun extractOpenVpnProtocol(content: String): String {
        for (line in content.lines()) {
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

    // ─── УТИЛИТЫ ─────────────────────────────────────────────────────────────

    private fun resolveFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx) ?: "config"
            }
        }
        return "config"
    }

    /**
     * Короткая метка хоста для отображения.
     * FIX: IP-адрес (напр. 1.2.3.4) возвращается целиком,
     * домен сокращается до первых двух сегментов.
     */
    private fun shortHostLabel(host: String): String {
        if (host.isBlank() || host == "-") return host
        // IP-адрес — оставляем целиком
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return host
        // IPv6 — оставляем как есть
        if (host.contains(":")) return host
        // Домен — первые два сегмента
        val parts = host.split(".")
        return if (parts.size >= 2) "${parts[0]}.${parts[1]}" else host.take(16)
    }

    private fun showToast(messageRes: Int) {
        if (!VpnSettingsPreferences.isToastNotificationsEnabled(this)) return
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(message: String) {
        if (!VpnSettingsPreferences.isToastNotificationsEnabled(this)) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ─── АДАПТЕР ─────────────────────────────────────────────────────────────

    private inner class ConfigListAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@ConfigSelectionActivity)

        override fun getCount(): Int    = configs.size
        override fun getItem(pos: Int): Any = configs[pos]
        override fun getItemId(pos: Int): Long = configs[pos].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: inflater.inflate(R.layout.item_config, parent, false)
            val item = configs[position]

            val tvTitle    = view.findViewById<TextView>(R.id.tvTitle)
            val tvSubtitle = view.findViewById<TextView>(R.id.tvSubtitle)
            val tvActive   = view.findViewById<TextView>(R.id.tvActive)
            val btnFavorite = view.findViewById<TextView>(R.id.btnFavorite)
            val btnDelete  = view.findViewById<TextView>(R.id.btnDelete)

            // FIX: метка протокола корректна для обоих типов
            val protocolLabel = getProtocolLabel(item)
            tvTitle.text    = "${item.name} [$protocolLabel]"
            tvSubtitle.text = shortHostLabel(item.serverHost)

            val isActive = item.id == activeConfigId
            tvActive.visibility = if (isActive) View.VISIBLE else View.GONE
            tvActive.text = getString(R.string.active_config_label)

            btnFavorite.setTextColor(
                if (item.isFavorite) getColor(R.color.connecting)
                else                 getColor(R.color.disconnected)
            )
            btnFavorite.setOnClickListener {
                lifecycleScope.launch {
                    if (!item.isFavorite &&
                        configDao.getFavoriteCount(currentUserId) >= 10
                    ) {
                        showToast(R.string.favorite_limit_reached)
                        return@launch
                    }
                    configDao.setFavorite(item.id, currentUserId, !item.isFavorite)
                }
            }

            btnDelete.visibility = if (item.source == "manual") View.VISIBLE else View.GONE
            btnDelete.setOnClickListener {
                val message = if (isActive)
                    getString(R.string.delete_active_config_message, item.name)
                else
                    getString(R.string.delete_config_message, item.name)

                AlertDialog.Builder(this@ConfigSelectionActivity)
                    .setTitle(R.string.delete_config_title)
                    .setMessage(message)
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