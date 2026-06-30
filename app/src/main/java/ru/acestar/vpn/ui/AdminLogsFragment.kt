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
import ru.acestar.vpn.data.model.admin.UserLog
import ru.acestar.vpn.databinding.DialogLogsBinding
import ru.acestar.vpn.ui.adapters.LogsAdapter
import ru.acestar.vpn.viewmodel.AdminViewModel

class AdminLogsFragment : BottomSheetDialogFragment() {

    private var _binding: DialogLogsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AdminViewModel by activityViewModels()
    private val adapter = LogsAdapter()

    private var refreshAnimator: ObjectAnimator? = null
    private var autoRefreshJob: Job? = null

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val REFRESH_INTERVAL_MS = 3_000L

        fun newInstance(userId: Int) = AdminLogsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_USER_ID, userId) }
        }
    }

    private val userId: Int get() = arguments?.getInt(ARG_USER_ID) ?: -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLogs.adapter = adapter

        setupRefreshIcon()
        observeLogs()
        viewModel.loadUserLogs(userId)
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

    private fun observeLogs() {
        viewModel.logsState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AdminViewModel.LogsState.Success -> bindLogs(state.logs)
                else -> {}
            }
        }
    }

    private fun bindLogs(logs: List<UserLog>) {
        spinRefreshIcon()

        if (logs.isEmpty()) {
            binding.tvLogsEmpty.visibility = View.VISIBLE
            binding.tvLogsUnavailable.visibility = View.VISIBLE
            binding.rvLogs.visibility = View.GONE
            binding.tvLogsCount.text = ""
        } else {
            binding.tvLogsEmpty.visibility = View.GONE
            binding.tvLogsUnavailable.visibility = View.GONE
            binding.rvLogs.visibility = View.VISIBLE
            binding.tvLogsCount.text = "${logs.size} записей"
            // DiffUtil — обновляет только новые/изменённые записи
            adapter.submitList(logs)
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                if (isActive) viewModel.silentRefreshLogs(userId)
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
