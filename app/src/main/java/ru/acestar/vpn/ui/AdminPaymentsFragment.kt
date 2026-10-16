package ru.acestar.vpn.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.acestar.vpn.data.model.admin.UserPayment
import ru.acestar.vpn.databinding.DialogPaymentsBinding
import ru.acestar.vpn.ui.adapters.PaymentsAdapter
import ru.acestar.vpn.viewmodel.AdminViewModel

class AdminPaymentsFragment : BottomSheetDialogFragment() {

    private var _binding: DialogPaymentsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AdminViewModel by activityViewModels()
    private val adapter = PaymentsAdapter()

    private var refreshAnimator: ObjectAnimator? = null
    private var autoRefreshJob: Job? = null

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val REFRESH_INTERVAL_MS = 3_000L

        fun newInstance(userId: Int) = AdminPaymentsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_USER_ID, userId) }
        }
    }

    private val userId: Int get() = arguments?.getInt(ARG_USER_ID) ?: -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogPaymentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvPayments.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPayments.adapter = adapter

        setupRefreshIcon()
        observePayments()
        viewModel.loadUserPayments(userId)
        startAutoRefresh()
    }

    private fun setupRefreshIcon() {
        refreshAnimator = ObjectAnimator.ofFloat(binding.ivRefresh, "rotation", 0f, 360f).apply {
            duration = 700
            interpolator = LinearInterpolator()
            repeatCount = 0
        }
    }

    private fun spinRefreshIcon() {
        refreshAnimator?.cancel()
        binding.ivRefresh.rotation = 0f
        refreshAnimator = ObjectAnimator.ofFloat(binding.ivRefresh, "rotation", 0f, 360f).apply {
            duration = 700
            interpolator = LinearInterpolator()
            repeatCount = 0
            start()
        }
    }

    private fun observePayments() {
        viewModel.paymentsState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AdminViewModel.PaymentsState.Success -> bindPayments(state.payments)
                else -> {}
            }
        }
    }

    private fun bindPayments(payments: List<UserPayment>) {
        spinRefreshIcon()

        if (payments.isEmpty()) {
            binding.tvPaymentsEmpty.visibility = View.VISIBLE
            binding.rvPayments.visibility = View.GONE
            binding.tvPaymentsTotal.text = ""
        } else {
            binding.tvPaymentsEmpty.visibility = View.GONE
            binding.rvPayments.visibility = View.VISIBLE
            val total = payments
                .filter { it.status.lowercase() in listOf("succeeded", "success") }
                .sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
            binding.tvPaymentsTotal.text = "Итого: %.0f ₽".format(total)
            // DiffUtil внутри ListAdapter — обновляет только изменившиеся строки, без мерцания
            adapter.submitList(payments)
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                if (isActive) viewModel.silentRefreshPayments(userId)
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
