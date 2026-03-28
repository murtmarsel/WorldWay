// ui/AdminActivity.kt
package ru.acestar.vpn.ui

import UsersAdapter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import ru.acestar.vpn.data.model.admin.UserAdminData
import ru.acestar.vpn.data.model.admin.UserConfigFile
import ru.acestar.vpn.data.model.admin.UserLog
import ru.acestar.vpn.data.model.admin.UserPayment
import ru.acestar.vpn.data.model.admin.UserStats
import ru.acestar.vpn.ui.adapters.ConfigsAdapter
import ru.acestar.vpn.databinding.ActivityAdminBinding
import ru.acestar.vpn.databinding.DialogUserDetailBinding
import ru.acestar.vpn.databinding.DialogEditUserBinding
import ru.acestar.vpn.databinding.DialogConfigsBinding
import ru.acestar.vpn.databinding.DialogPaymentsBinding
import ru.acestar.vpn.databinding.DialogLogsBinding
import ru.acestar.vpn.viewmodel.AdminViewModel

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var viewModel: AdminViewModel
    private lateinit var usersAdapter: UsersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
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
        // Адаптер для списка пользователей
        usersAdapter = UsersAdapter { user ->
            onUserClick(user)
        }
        binding.rvAdminUsers.layoutManager = LinearLayoutManager(this)
        binding.rvAdminUsers.adapter = usersAdapter

        // Кнопка входа
        binding.btnAdminLogin.setOnClickListener {
            val password = binding.etAdminPassword.text.toString()
            if (password.isNotEmpty()) {
                viewModel.adminLogin(password)
            } else {
                showError("Введите пароль")
            }
        }

        // Поиск
        binding.etSearchUser.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                usersAdapter.filter(s.toString())
            }
        })

        // Фильтр по тарифу
        val tariffs = arrayOf("Все", "Free", "Month", "Year")
        val tariffAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tariffs)
        tariffAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTariffFilter.adapter = tariffAdapter
        binding.spinnerTariffFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                usersAdapter.filterByTariff(if (position == 0) null else tariffs[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // TabLayout
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

        // Очистка логов
        binding.btnClearLogs.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Очистить логи")
                .setMessage("Вы уверены, что хотите очистить все логи?")
                .setPositiveButton("Очистить") { _, _ ->
                    // TODO: Очистить логи на сервере
                    showSuccess("Логи очищены")
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun setupObservers() {
        // Авторизация
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

        // Список пользователей
        viewModel.usersState.observe(this) { state ->
            when (state) {
                is AdminViewModel.UsersState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
                is AdminViewModel.UsersState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    usersAdapter.submitList(state.users)
                    binding.tvUsersHint.visibility = if (state.users.isEmpty()) View.VISIBLE else View.GONE
                    binding.tvTotalUsers.text = "Всего: ${state.users.size}"
                    binding.tvActiveUsers.text = "Активных: ${state.users.count { it.is_active }}"
                }
                is AdminViewModel.UsersState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    showError(state.message)
                }
                else -> {}
            }
        }

        // Конфигурации
        viewModel.configsState.observe(this) { state ->
            when (state) {
                is AdminViewModel.ConfigsState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
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

        // Статистика
        viewModel.statsState.observe(this) { state ->
            when (state) {
                is AdminViewModel.StatsState.Success -> {
                    showStatsDialog(state.stats)
                }
                is AdminViewModel.StatsState.Error -> {
                    showError(state.message)
                }
                else -> {}
            }
        }

        // Платежи
        viewModel.paymentsState.observe(this) { state ->
            when (state) {
                is AdminViewModel.PaymentsState.Success -> {
                    showPaymentsDialog(state.payments)
                }
                is AdminViewModel.PaymentsState.Error -> {
                    showError(state.message)
                }
                else -> {}
            }
        }

        // Логи
        viewModel.logsState.observe(this) { state ->
            when (state) {
                is AdminViewModel.LogsState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
                is AdminViewModel.LogsState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    showLogsDialog(state.logs)
                }
                is AdminViewModel.LogsState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    showError(state.message)
                }
                else -> {}
            }
        }

        // Операции
        viewModel.operationState.observe(this) { state ->
            when (state) {
                is AdminViewModel.OperationState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
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
            if (userId != null) {
                viewModel.loadUserConfigs(userId)
            } else {
                showError("Введите ID пользователя")
            }
        }
    }

    private fun showLogsPanel() {
        binding.layoutUsers.visibility = View.GONE
        binding.layoutConfigs.visibility = View.GONE
        binding.layoutLogs.visibility = View.VISIBLE
        binding.tvAdminHint.visibility = View.GONE

        // Загружаем логи для выбранного пользователя
//        binding.etLogUserId.setOnClickListener {
//            val userId = binding.etLogUserId.text.toString().toIntOrNull()
//            if (userId != null) {
//                viewModel.loadUserLogs(userId)
//            } else {
//                showError("Введите ID пользователя")
//            }
//        }
    }

    private fun onUserClick(user: UserAdminData) {
        viewModel.selectUser(user)
        showUserDetailDialog(user)
    }

    private fun showUserDetailDialog(user: UserAdminData) {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogUserDetailBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Заполняем данные пользователя
        dialogBinding.tvUserId.text = "ID: ${user.id}"
        dialogBinding.tvUserEmail.text = user.email
        dialogBinding.tvUserTariff.text = "Тариф: ${user.tariff}"
        dialogBinding.tvUserStatus.text = if (user.is_active) "Активен" else "Заблокирован"
        dialogBinding.tvUserStatus.setTextColor(
            if (user.is_active) getColor(android.R.color.holo_green_dark) else getColor(android.R.color.holo_red_dark)
        )
        dialogBinding.tvUserEmailConfirmed.text = if (user.email_confirmed) "✓ Подтвержден" else "✗ Не подтвержден"
        dialogBinding.tvUserCreated.text = "Создан: ${user.create_date}"

        // Кнопки управления
        dialogBinding.btnViewConfigs.setOnClickListener {
            dialog.dismiss()
            viewModel.loadUserConfigs(user.id)
        }

        dialogBinding.btnViewStats.setOnClickListener {
            dialog.dismiss()
            viewModel.loadUserStats(user.id)
        }

        dialogBinding.btnViewPayments.setOnClickListener {
            dialog.dismiss()
            viewModel.loadUserPayments(user.id)
        }

        dialogBinding.btnViewLogs.setOnClickListener {
            dialog.dismiss()
            viewModel.loadUserLogs(user.id)
        }

        dialogBinding.btnEditUser.setOnClickListener {
            dialog.dismiss()
            showEditUserDialog(user)
        }

        dialogBinding.btnResetPassword.setOnClickListener {
            dialog.dismiss()
            showResetPasswordDialog(user)
        }

        dialogBinding.btnManageSubscription.setOnClickListener {
            dialog.dismiss()
            showManageSubscriptionDialog(user)
        }

        dialogBinding.btnDeleteUser.setOnClickListener {
            dialog.dismiss()
            confirmDeleteUser(user)
        }

        dialog.show()
    }

    private fun showEditUserDialog(user: UserAdminData) {
        val dialogBinding = DialogEditUserBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Редактирование пользователя")
            .setView(dialogBinding.root)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        // Заполняем данные
        dialogBinding.etEditEmail.setText(user.email)
        dialogBinding.cbEmailConfirmed.isChecked = user.email_confirmed
        dialogBinding.cbIsActive.isChecked = user.is_active

        val tariffs = arrayOf("free", "premium", "pro", "vip")
        val tariffAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tariffs)
        tariffAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerTariff.adapter = tariffAdapter
        val tariffIndex = tariffs.indexOf(user.tariff)
        if (tariffIndex >= 0) dialogBinding.spinnerTariff.setSelection(tariffIndex)

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newEmail = dialogBinding.etEditEmail.text.toString()
                val newTariff = tariffs[dialogBinding.spinnerTariff.selectedItemPosition]
                val emailConfirmed = dialogBinding.cbEmailConfirmed.isChecked
                val isActive = dialogBinding.cbIsActive.isChecked

                viewModel.updateUser(
                    user.id,
                    newEmail.takeIf { it != user.email },
                    newTariff.takeIf { it != user.tariff },
                    emailConfirmed.takeIf { it != user.email_confirmed },
                    isActive.takeIf { it != user.is_active }
                )
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showResetPasswordDialog(user: UserAdminData) {
        val input = TextInputEditText(this)
        input.hint = "Новый пароль"

        MaterialAlertDialogBuilder(this)
            .setTitle("Сброс пароля для ${user.email}")
            .setView(input)
            .setPositiveButton("Сбросить") { _, _ ->
                val newPassword = input.text.toString()
                if (newPassword.isNotEmpty()) {
                    viewModel.resetPassword(user.id, newPassword)
                } else {
                    showError("Введите новый пароль")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showManageSubscriptionDialog(user: UserAdminData) {
        val options = arrayOf(
            "Приостановить подписку",
            "Возобновить подписку",
            "Отменить подписку",
            "Изменить тариф"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Управление подпиской")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.updateSubscription(user.id, "pause")
                    1 -> viewModel.updateSubscription(user.id, "resume")
                    2 -> viewModel.updateSubscription(user.id, "cancel")
                    3 -> showChangeTariffDialog(user)
                }
            }
            .show()
    }

    private fun showChangeTariffDialog(user: UserAdminData) {
        val tariffs = arrayOf("free", "premium", "pro", "vip")
        var selectedTariff = user.tariff

        MaterialAlertDialogBuilder(this)
            .setTitle("Выберите тариф")
            .setSingleChoiceItems(tariffs, tariffs.indexOf(selectedTariff)) { _, which ->
                selectedTariff = tariffs[which]
            }
            .setPositiveButton("Изменить") { _, _ ->
                viewModel.updateSubscription(user.id, "change", selectedTariff)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteUser(user: UserAdminData) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удаление пользователя")
            .setMessage("Вы уверены, что хотите удалить пользователя ${user.email}? Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                viewModel.deleteUser(user.id)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showConfigsDialog(configs: List<UserConfigFile>) {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogConfigsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val adapter = ConfigsAdapter { config ->
            showConfigEditDialog(config)
        }
        dialogBinding.rvConfigs.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvConfigs.adapter = adapter
        adapter.submitList(configs)

        dialog.show()
    }

    private fun showConfigEditDialog(config: UserConfigFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(config.name)
            .setMessage("Тип: ${config.type}\nРазмер: ${config.size / 1024} KB")
            .setPositiveButton("Редактировать") { _, _ ->
                showEditConfigContentDialog(config)
            }
            .setNegativeButton("Закрыть", null)
            .create()
            .show()
    }

    private fun showEditConfigContentDialog(config: UserConfigFile) {
        val scrollView = android.widget.ScrollView(this)
        val editText = TextInputEditText(this)
        editText.setText(config.content)
        editText.isSingleLine = false
        editText.setLines(20)
        scrollView.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle("Редактирование: ${config.name}")
            .setView(scrollView)
            .setPositiveButton("Сохранить") { _, _ ->
                val newContent = editText.text.toString()
                // TODO: Отправить изменения на сервер
                showSuccess("Конфигурация сохранена")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showStatsDialog(stats: UserStats) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Статистика использования")
            .setMessage("""
                ID пользователя: ${stats.user_id}
                
                Трафик:
                Всего: ${formatBytes(stats.total_traffic)}
                Использовано: ${formatBytes(stats.used_traffic)}
                Осталось: ${formatBytes(stats.total_traffic - stats.used_traffic)}
                
                Подключения:
                Всего: ${stats.total_connections}
                Активных: ${stats.active_connections}
                
                Последняя активность: ${stats.last_active ?: "Нет данных"}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPaymentsDialog(payments: List<UserPayment>) {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogPaymentsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

//        val adapter = PaymentsAdapter()
//        dialogBinding.rvPayments.layoutManager = LinearLayoutManager(this)
//        dialogBinding.rvPayments.adapter = adapter
//        adapter.submitList(payments)

        dialog.show()
    }

    private fun showLogsDialog(logs: List<UserLog>) {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogLogsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

//        val adapter = LogsAdapter()
//        dialogBinding.rvLogs.layoutManager = LinearLayoutManager(this)
//        dialogBinding.rvLogs.adapter = adapter
//        adapter.submitList(logs)

        dialog.show()
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", size, units[unitIndex])
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}