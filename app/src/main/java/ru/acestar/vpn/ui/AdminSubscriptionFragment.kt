package ru.acestar.vpn.ui

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.Tariff
import ru.acestar.vpn.data.model.admin.UserAdminData
import ru.acestar.vpn.databinding.DialogManageSubscriptionBinding
import ru.acestar.vpn.ui.adapters.PaymentRowAdapter
import ru.acestar.vpn.viewmodel.AdminViewModel

/**
 * Управление подпиской пользователя (BottomSheet, поднимается снизу).
 * Дата истечения, продление, пауза/возобновление/отмена, история оплат.
 * Автообновление данных каждые 3 секунды + анимация иконки.
 */
class AdminSubscriptionFragment : BottomSheetDialogFragment() {

    private var _binding: DialogManageSubscriptionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AdminViewModel by activityViewModels()
    private val paymentsAdapter = PaymentRowAdapter()

    private var refreshAnimator: ObjectAnimator? = null
    private var autoRefreshJob: Job? = null
    private var userId: Int = -1

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val REFRESH_INTERVAL_MS = 3_000L

        fun newInstance(userId: Int) = AdminSubscriptionFragment().apply {
            arguments = Bundle().apply { putInt(ARG_USER_ID, userId) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogManageSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userId = requireArguments().getInt(ARG_USER_ID)

        binding.rvSubPayments.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSubPayments.adapter = paymentsAdapter

        refreshAnimator = ObjectAnimator.ofFloat(binding.ivRefresh, "rotation", 0f, 360f).apply {
            duration = 700; interpolator = LinearInterpolator()
        }

        setupButtons()
        observe()

        // Первая загрузка
        viewModel.silentRefreshSelectedUser(userId)
        viewModel.loadUserPayments(userId)
        startAutoRefresh()
    }

    private fun setupButtons() {
        binding.btnExtend30.setOnClickListener { confirmExtend(30) }
        binding.btnExtend90.setOnClickListener { confirmExtend(90) }
        binding.btnExtend365.setOnClickListener { confirmExtend(365) }
        binding.btnExtendCustom.setOnClickListener { showCustomDaysDialog() }

        binding.btnSubPause.setOnClickListener {
            confirmAction("pause", getString(R.string.sub_pause))
        }
        binding.btnSubResume.setOnClickListener {
            confirmAction("resume", getString(R.string.sub_resume))
        }
        binding.btnSubCancel.setOnClickListener {
            confirmAction("cancel", getString(R.string.sub_cancel))
        }
    }

    private fun confirmExtend(days: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sub_extend_label)
            .setMessage("Продлить подписку на $days дней?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.extendSubscription(userId, days)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomDaysDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            hint = getString(R.string.sub_custom_days_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sub_extend_custom)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val days = input.text.toString().toIntOrNull()
                if (days != null && days > 0) viewModel.extendSubscription(userId, days)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmAction(action: String, label: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(label)
            .setMessage("Подтвердите действие: $label")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.updateSubscription(userId, action)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observe() {
        viewModel.selectedUser.observe(viewLifecycleOwner) { user ->
            user?.let { bindUser(it) }
        }
        viewModel.paymentsState.observe(viewLifecycleOwner) { state ->
            if (state is AdminViewModel.PaymentsState.Success) {
                spinIcon()
                if (state.payments.isEmpty()) {
                    binding.rvSubPayments.visibility = View.GONE
                    binding.tvSubPaymentsEmpty.visibility = View.VISIBLE
                } else {
                    binding.rvSubPayments.visibility = View.VISIBLE
                    binding.tvSubPaymentsEmpty.visibility = View.GONE
                    paymentsAdapter.submitList(state.payments.take(10))
                }
            }
        }
    }

    private fun bindUser(user: UserAdminData) {
        binding.tvSubUserEmail.text = user.email
        binding.tvSubTariff.text = Tariff.fromId(user.tariff).displayName
        binding.tvSubExpiry.text = user.subscription_expiry ?: "—"

        val (statusText, statusColor) = when {
            !user.is_active -> getString(R.string.sub_inactive) to R.color.connecting
            user.subscription_expiry.isNullOrBlank() &&
                user.tariff.equals("Unlimited", true) ->
                    getString(R.string.sub_active) to R.color.connected
            else -> getString(R.string.sub_active) to R.color.connected
        }
        binding.tvSubStatus.text = statusText
        binding.tvSubStatus.setTextColor(ContextCompat.getColor(requireContext(), statusColor))
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
                if (isActive) {
                    viewModel.silentRefreshSelectedUser(userId)
                    viewModel.silentRefreshPayments(userId)
                }
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
