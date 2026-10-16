package ru.acestar.vpn.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.Tariff
import ru.acestar.vpn.data.model.admin.UserAdminData
import ru.acestar.vpn.databinding.DialogEditUserBinding
import ru.acestar.vpn.viewmodel.AdminViewModel

/**
 * Редактирование пользователя (BottomSheet снизу).
 * Автообновление данных каждые 3 сек + анимация иконки обновления.
 */
class AdminEditUserFragment : BottomSheetDialogFragment() {

    private var _binding: DialogEditUserBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AdminViewModel by activityViewModels()

    private val tariffIds = Tariff.entries.map { it.id }.toTypedArray()
    private val tariffNames = Tariff.entries.map { it.displayName }.toTypedArray()

    private var refreshAnimator: ObjectAnimator? = null
    private var autoRefreshJob: Job? = null
    private var userId: Int = -1
    private var editingActive = false // пользователь меняет поля — не перетираем автообновлением

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val REFRESH_INTERVAL_MS = 3_000L

        fun newInstance(userId: Int) = AdminEditUserFragment().apply {
            arguments = Bundle().apply { putInt(ARG_USER_ID, userId) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogEditUserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userId = requireArguments().getInt(ARG_USER_ID)

        val adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, tariffNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTariff.adapter = adapter

        refreshAnimator = ObjectAnimator.ofFloat(binding.ivRefresh, "rotation", 0f, 360f).apply {
            duration = 700; interpolator = LinearInterpolator()
        }

        // Отмечаем что пользователь редактирует — чтобы автообновление не сбивало
        binding.etEditEmail.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) editingActive = true
        }

        setupButtons()
        observe()

        viewModel.silentRefreshSelectedUser(userId)
        startAutoRefresh()
    }

    private fun setupButtons() {
        binding.btnEditCancel.setOnClickListener { dismissAllowingStateLoss() }

        binding.btnEditSave.setOnClickListener {
            val user = viewModel.selectedUser.value ?: return@setOnClickListener
            val newEmail = binding.etEditEmail.text.toString().trim()
            val newTariff = tariffIds[binding.spinnerTariff.selectedItemPosition]
            viewModel.updateUser(
                userId = user.id,
                email = newEmail.takeIf { it != user.email && it.isNotBlank() },
                tariff = newTariff.takeIf { it != user.tariff },
                emailConfirmed = binding.cbEmailConfirmed.isChecked
                    .takeIf { it != user.email_confirmed },
                isActive = binding.cbIsActive.isChecked.takeIf { it != user.is_active }
            )
            dismissAllowingStateLoss()
        }

        binding.btnAddTraffic.setOnClickListener { showTrafficDelta(true) }
        binding.btnSubTraffic.setOnClickListener { showTrafficDelta(false) }
        binding.btnResetTraffic.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Сбросить трафик")
                .setMessage("Обнулить использованный трафик?")
                .setPositiveButton("Сбросить") { _, _ ->
                    viewModel.updateTraffic(userId, 0L, reset = true)
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun showTrafficDelta(positive: Boolean) {
        val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            hint = "Количество ГБ"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (positive) "Добавить трафик (ГБ)" else "Убавить трафик (ГБ)")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val gb = input.text.toString().toDoubleOrNull()
                if (gb != null && gb > 0) {
                    val bytes = (gb * 1024 * 1024 * 1024).toLong()
                    viewModel.updateTraffic(userId, if (positive) bytes else -bytes)
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun observe() {
        viewModel.selectedUser.observe(viewLifecycleOwner) { user ->
            user?.let { bindUser(it) }
        }
    }

    private fun bindUser(user: UserAdminData) {
        spinIcon()
        binding.tvEditTitle.text = user.email

        // Не перетираем поля если пользователь активно редактирует
        if (!editingActive) {
            binding.etEditEmail.setText(user.email)
            val idx = tariffIds.indexOfFirst { it.equals(user.tariff, ignoreCase = true) }
            if (idx >= 0) binding.spinnerTariff.setSelection(idx)
            binding.cbEmailConfirmed.isChecked = user.email_confirmed
            binding.cbIsActive.isChecked = user.is_active
        }

        // Прогресс-бар трафика обновляется всегда
        val limit = user.traffic_limit
        val used = user.used_traffic
        if (limit > 0L) {
            val progress = ((used.toFloat() / limit) * 100).toInt().coerceIn(0, 100)
            binding.progressEditTraffic.progress = progress
            binding.tvTrafficUsedLabel.text = "Использовано: ${Tariff.formatUsed(used)}"
            binding.tvTrafficLimitLabel.text = "Лимит: ${Tariff.formatTrafficLimit(limit)}"
        } else {
            binding.progressEditTraffic.progress = 0
            binding.tvTrafficUsedLabel.text = "Использовано: ${Tariff.formatUsed(used)}"
            binding.tvTrafficLimitLabel.text = "Лимит: безлимит"
        }
    }

    private fun spinIcon() {
        refreshAnimator?.cancel()
        binding.ivRefresh.rotation = 0f
        refreshAnimator?.start()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                if (isActive) viewModel.silentRefreshSelectedUser(userId)
            }
        }
    }

    override fun onDestroyView() {
        autoRefreshJob?.cancel()
        refreshAnimator?.cancel()
        _binding = null
        super.onDestroyView()
    }
}
