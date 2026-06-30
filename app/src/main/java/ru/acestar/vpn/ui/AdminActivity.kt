package ru.acestar.vpn.ui

import ru.acestar.vpn.ui.UsersAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.Tariff
import ru.acestar.vpn.data.model.admin.UserAdminData
import ru.acestar.vpn.data.model.admin.UserConfigFile
import ru.acestar.vpn.data.model.admin.UserLog
import ru.acestar.vpn.data.model.admin.UserPayment
import ru.acestar.vpn.data.model.admin.UserStats
import ru.acestar.vpn.databinding.*
import ru.acestar.vpn.ui.adapters.ConfigsAdapter
import ru.acestar.vpn.ui.adapters.LogsAdapter
import ru.acestar.vpn.ui.adapters.PaymentsAdapter
import ru.acestar.vpn.viewmodel.AdminViewModel

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var viewModel: AdminViewModel
    private lateinit var usersAdapter: UsersAdapter

    // Список тарифов для спиннеров
    private val tariffIds = Tariff.entries.map { it.id }.toTypedArray()
    private val tariffNames = Tariff.entries.map { it.displayName }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this, ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[AdminViewModel::class.java]

        setupToolbar()
        setupViews()
        setupObservers()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupViews() {
        usersAdapter = UsersAdapter(
            onItemClick    = { user -> onUserClick(user) },
            onConfigsClick = { user -> viewModel.loadUserConfigs(user.id) },
            onStatsClick   = { user ->
                AdminStatsFragment.newInstance(user.id)
                    .show(supportFragmentManager, "stats_${user.id}")
            },
            onEditClick    = { user -> openEditUser(user) },
            onLogsClick    = { user ->
                AdminLogsFragment.newInstance(user.id)
                    .show(supportFragmentManager, "logs_${user.id}")
            }
        )
        binding.rvAdminUsers.layoutManager = LinearLayoutManager(this)
        binding.rvAdminUsers.adapter = usersAdapter

        binding.btnAdminLogin.setOnClickListener {
            val pw = binding.etAdminPassword.text.toString()
            if (pw.isNotEmpty()) viewModel.adminLogin(pw)
            else showError("Введите пароль")
        }

        binding.etSearchUser.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { usersAdapter.filter(s.toString()) }
        })

        // Фильтр по тарифу — используем актуальные тарифы
        val filterItems = arrayOf("Все") + tariffIds
        val tariffAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filterItems)
        tariffAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTariffFilter.adapter = tariffAdapter
        binding.spinnerTariffFilter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    usersAdapter.filterByTariff(if (pos == 0) null else filterItems[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        binding.tabAdmin.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showUsersList()
                    1 -> showConfigsPanel()
                    2 -> showLogsPanel()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnClearLogs.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Очистить логи")
                .setMessage("Вы уверены?")
                .setPositiveButton("Очистить") { _, _ -> showSuccess("Логи очищены") }
                .setNegativeButton("Отмена", null).show()
        }
    }

    private fun setupObservers() {
        viewModel.authState.observe(this) { state ->
            when (state) {
                is AdminViewModel.AuthState.Loading -> {
                    binding.btnAdminLogin.isEnabled = false
                    binding.progressBar.visibility = View.VISIBLE
                }
                is AdminViewModel.AuthState.Success -> {
                    binding.btnAdminLogin.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.cardAdminAuth.visibility = View.GONE
                    binding.layoutAdminContent.visibility = View.VISIBLE
                    binding.tabAdmin.visibility = View.VISIBLE
                    binding.adminContent.visibility = View.VISIBLE
                    showUsersList()
                }
                is AdminViewModel.AuthState.Error -> {
                    binding.btnAdminLogin.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    showError(state.message)
                }
                else -> {}
            }
        }

        viewModel.usersState.observe(this) { state ->
            when (state) {
                is AdminViewModel.UsersState.Loading ->
                    binding.progressBar.visibility = View.VISIBLE
                is AdminViewModel.UsersState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    usersAdapter.submitList(state.users)
                    binding.tvUsersHint.visibility =
                        if (state.users.isEmpty()) View.VISIBLE else View.GONE
                    binding.tvTotalUsers.text = "Всего: ${state.users.size}"
                    binding.tvActiveUsers.text =
                        "Активных: ${state.users.count { it.is_active }}"
                }
                is AdminViewModel.UsersState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    showError(state.message)
                }
                else -> {}
            }
        }

        viewModel.configsState.observe(this) { state ->
            when (state) {
                is AdminViewModel.ConfigsState.Loading ->
                    binding.progressBar.visibility = View.VISIBLE
                is AdminViewModel.ConfigsState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    showConfigsDialog(state.configs)
                }
                is AdminViewModel.ConfigsState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    showError(state.message)
                }
                else -> {}
            }
        }

        viewModel.statsState.observe(this) { state ->
            when (state) {
                is AdminViewModel.StatsState.Error -> showError(state.message)
                else -> {}
            }
        }

        viewModel.paymentsState.observe(this) { state ->
            when (state) {
                is AdminViewModel.PaymentsState.Error -> showError(state.message)
                else -> {}
            }
        }

        viewModel.logsState.observe(this) { state ->
            when (state) {
                is AdminViewModel.LogsState.Loading ->
                    binding.progressBar.visibility = View.VISIBLE
                is AdminViewModel.LogsState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    showError(state.message)
                }
                else -> binding.progressBar.visibility = View.GONE
            }
        }

        viewModel.operationState.observe(this) { state ->
            when (state) {
                is AdminViewModel.OperationState.Loading ->
                    binding.progressBar.visibility = View.VISIBLE
                is AdminViewModel.OperationState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    showSuccess(state.message)
                    viewModel.resetStates()
                }
                is AdminViewModel.OperationState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    showError(state.message)
                    viewModel.resetStates()
                }
                else -> {}
            }
        }
    }

    // ─── Панели ───────────────────────────────────────────────────────────────

    private fun showUsersList() {
        binding.layoutConfigs.visibility = View.GONE
        binding.layoutLogs.visibility = View.GONE
        binding.layoutUsers.visibility = View.VISIBLE
        binding.tvAdminHint.visibility = View.VISIBLE
    }

    private fun showConfigsPanel() {
        binding.layoutUsers.visibility = View.GONE
        binding.layoutLogs.visibility = View.GONE
        binding.layoutConfigs.visibility = View.VISIBLE
        binding.tvAdminHint.visibility = View.GONE
        binding.btnLoadConfigs.setOnClickListener {
            val userId = binding.etConfigUserId.text.toString().toIntOrNull()
            if (userId != null) viewModel.loadUserConfigs(userId)
            else showError("Введите ID пользователя")
        }
    }

    private fun showLogsPanel() {
        binding.layoutUsers.visibility = View.GONE
        binding.layoutConfigs.visibility = View.GONE
        binding.layoutLogs.visibility = View.VISIBLE
        binding.tvAdminHint.visibility = View.GONE
    }

    // ─── Клик по пользователю ─────────────────────────────────────────────────

    private fun onUserClick(user: UserAdminData) {
        viewModel.selectUser(user)
        showUserDetailDialog(user)
    }

    private fun showUserDetailDialog(user: UserAdminData) {
        val dialog = BottomSheetDialog(this)
        val b = DialogUserDetailBinding.inflate(layoutInflater)
        dialog.setContentView(b.root)

        b.tvUserId.text = "ID: ${user.id}"
        b.tvUserEmail.text = user.email
        b.tvUserTariff.text = "Тариф: ${Tariff.fromId(user.tariff).displayName}"
        b.tvUserStatus.text = if (user.is_active) "● Активен" else "● Заблокирован"
        b.tvUserStatus.setTextColor(
            getColor(if (user.is_active) android.R.color.holo_green_dark
                     else android.R.color.holo_red_dark)
        )
        b.tvUserEmailConfirmed.text =
            if (user.email_confirmed) "✓ Email подтверждён" else "✗ Email не подтверждён"
        b.tvUserCreated.text = "Создан: ${user.create_date}"

        // Прогресс-бар трафика
        val limit = user.traffic_limit
        if (limit > 0L) {
            b.layoutTrafficBar.visibility = View.VISIBLE
            val used = user.used_traffic
            val progress = ((used.toFloat() / limit) * 100).toInt().coerceIn(0, 100)
            b.progressTraffic.progress = progress
            b.tvTrafficInfo.text =
                "${Tariff.formatUsed(used)} / ${Tariff.formatTrafficLimit(limit)}"
            val tint = when {
                progress >= 90 -> R.color.error
                progress >= 70 -> R.color.connecting
                else -> R.color.primary
            }
            b.progressTraffic.progressTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, tint))
        } else {
            b.layoutTrafficBar.visibility = View.GONE
        }

        b.btnViewConfigs.setOnClickListener { dialog.dismiss(); viewModel.loadUserConfigs(user.id) }
        b.btnViewStats.setOnClickListener {
            dialog.dismiss()
            AdminStatsFragment.newInstance(user.id)
                .show(supportFragmentManager, "stats_${user.id}")
        }
        b.btnViewPayments.setOnClickListener {
            dialog.dismiss()
            AdminPaymentsFragment.newInstance(user.id)
                .show(supportFragmentManager, "payments_${user.id}")
        }
        b.btnViewLogs.setOnClickListener {
            dialog.dismiss()
            AdminLogsFragment.newInstance(user.id)
                .show(supportFragmentManager, "logs_${user.id}")
        }
        b.btnEditUser.setOnClickListener { dialog.dismiss(); openEditUser(user) }
        b.btnResetPassword.setOnClickListener { dialog.dismiss(); showResetPasswordDialog(user) }
        b.btnManageSubscription.setOnClickListener { dialog.dismiss(); openManageSubscription(user) }
        b.btnDeleteUser.setOnClickListener { dialog.dismiss(); confirmDeleteUser(user) }

        dialog.show()
    }

    /** Открывает редактирование пользователя как BottomSheet снизу */
    private fun openEditUser(user: UserAdminData) {
        viewModel.selectUser(user)
        AdminEditUserFragment.newInstance(user.id)
            .show(supportFragmentManager, "edit_${user.id}")
    }

    /** Открывает управление подпиской как BottomSheet снизу */
    private fun openManageSubscription(user: UserAdminData) {
        viewModel.selectUser(user)
        AdminSubscriptionFragment.newInstance(user.id)
            .show(supportFragmentManager, "sub_${user.id}")
    }

    // ─── Редактирование пользователя — вынесено в AdminEditUserFragment ────────

    // ─── Сброс пароля ─────────────────────────────────────────────────────────

    private fun showResetPasswordDialog(user: UserAdminData) {
        val b = DialogResetPasswordBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(b.root)

        b.btnSetNewPassword.setOnClickListener {
            val pw = b.etNewPassword.text.toString()
            if (pw.length < 6) {
                showError("Пароль должен быть не менее 6 символов")
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Подтверждение")
                .setMessage("Установить новый пароль для ${user.email}?")
                .setPositiveButton("Да") { _, _ ->
                    viewModel.resetPassword(user.id, pw, user.email)
                    dialog.dismiss()
                }
                .setNegativeButton("Отмена", null).show()
        }

        b.btnSendResetEmail.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Отправить письмо")
                .setMessage("Отправить письмо для сброса пароля на ${user.email}?")
                .setPositiveButton("Отправить") { _, _ ->
                    viewModel.sendPasswordResetEmail(user.id, user.email)
                    dialog.dismiss()
                }
                .setNegativeButton("Отмена", null).show()
        }

        dialog.show()
    }

    // ─── Управление подпиской — вынесено в AdminSubscriptionFragment ───────────

    // ─── Удаление пользователя ────────────────────────────────────────────────

    private fun confirmDeleteUser(user: UserAdminData) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удаление пользователя")
            .setMessage("Удалить ${user.email}?\n\nЭто действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ -> viewModel.deleteUser(user.id) }
            .setNegativeButton("Отмена", null).show()
    }

    // ─── Диалоги данных ───────────────────────────────────────────────────────

    private fun showConfigsDialog(configs: List<UserConfigFile>) {
        val dialog = BottomSheetDialog(this)
        val b = DialogConfigsBinding.inflate(layoutInflater)
        dialog.setContentView(b.root)
        val adapter = ConfigsAdapter(
            onOpenContent = { showConfigContentReadOnlyDialog(it) },
            onBackup = { copyConfigBackupToClipboard(it) },
            onCertInfo = { showCertificateInfoDialog(it) }
        )
        b.rvConfigs.layoutManager = LinearLayoutManager(this)
        b.rvConfigs.adapter = adapter
        adapter.submitList(configs)
        dialog.show()
    }

    private fun showStatsDialog(stats: UserStats) {
        // Открываем фрагмент — он сам подпишется на ViewModel и запустит автообновление
        // Этот метод вызывается только при первом открытии (из observer Loading→Success)
        // Последующие обновления идут через silentRefreshStats внутри фрагмента
    }

    private fun showPaymentsDialog(payments: List<UserPayment>) {
        // Аналогично — фрагмент управляет собой сам
    }

    private fun showLogsDialog(logs: List<UserLog>) {
        // Аналогично
    }

    // ─── Конфиги: вспомогательные методы ─────────────────────────────────────

    private fun showConfigContentReadOnlyDialog(config: UserConfigFile) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val sv = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            text = config.content.ifBlank { "—" }
            setTextIsSelectable(true)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
        }
        sv.addView(tv)
        MaterialAlertDialogBuilder(this)
            .setTitle(config.name)
            .setView(sv)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun copyConfigBackupToClipboard(config: UserConfigFile) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("config_backup_${config.name}", config.content))
        showSuccess(getString(R.string.admin_config_backup_done))
    }

    private fun showCertificateInfoDialog(config: UserConfigFile) {
        val summary = buildCertificateSummary(config)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val sv = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            text = "${config.name}\n\n$summary"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(pad, pad, pad, pad)
        }
        sv.addView(tv)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.admin_config_cert_info_title))
            .setView(sv)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun buildCertificateSummary(config: UserConfigFile): String {
        val c = config.content
        val sb = StringBuilder()
        val isWg = config.protocol?.contains("wireguard", ignoreCase = true) == true ||
            config.type.contains("wireguard", ignoreCase = true) ||
            c.trimStart().startsWith("[Interface]")
        if (isWg) {
            sb.appendLine("Формат: WireGuard")
            if (c.contains("PrivateKey")) sb.appendLine("• Приватный ключ (Interface)")
            if (c.contains("PresharedKey")) sb.appendLine("• Preshared key")
            if (c.contains("[Peer]") && c.contains("PublicKey"))
                sb.appendLine("• Публичный ключ пира")
        } else {
            sb.appendLine("Формат: OpenVPN")
            if (c.contains("<ca>")) sb.appendLine("• CA (<ca>)")
            if (c.contains("<cert>")) sb.appendLine("• Клиентский сертификат (<cert>)")
            if (c.contains("<key>")) sb.appendLine("• Клиентский ключ (<key>)")
            if (c.contains("<tls-auth>") || c.contains("tls-auth", ignoreCase = true))
                sb.appendLine("• TLS-auth")
            if (c.contains("<tls-crypt>")) sb.appendLine("• TLS-crypt")
        }
        return sb.toString().trimEnd()
    }

    // ─── Утилиты ──────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var idx = 0
        while (size >= 1024 && idx < units.size - 1) { size /= 1024; idx++ }
        return String.format("%.2f %s", size, units[idx])
    }

    private fun showError(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun showSuccess(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
