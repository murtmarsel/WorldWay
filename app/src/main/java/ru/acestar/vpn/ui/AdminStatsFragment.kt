package ru.acestar.vpn.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.admin.UserStats
import ru.acestar.vpn.databinding.DialogStatsBinding
import ru.acestar.vpn.viewmodel.AdminViewModel

class AdminStatsFragment : BottomSheetDialogFragment() {

    private var _binding: DialogStatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AdminViewModel by activityViewModels()

    private var refreshAnimator: ObjectAnimator? = null
    private var autoRefreshJob: Job? = null

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val REFRESH_INTERVAL_MS = 3_000L

        fun newInstance(userId: Int) = AdminStatsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_USER_ID, userId) }
        }
    }

    private val userId: Int get() = arguments?.getInt(ARG_USER_ID) ?: -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRefreshIcon()
        observeStats()
        // Первая загрузка — с индикатором Loading
        viewModel.loadUserStats(userId)
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

    private fun observeStats() {
        viewModel.statsState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AdminViewModel.StatsState.Success -> bindStats(state.stats)
                is AdminViewModel.StatsState.Error -> { /* не мерцаем при ошибке тихого обновления */ }
                else -> {}
            }
        }
    }

    private fun bindStats(stats: UserStats) {
        spinRefreshIcon()

        binding.tvStatsTotalTraffic.text = formatBytes(stats.total_traffic)
        binding.tvStatsUsedTraffic.text = formatBytes(stats.used_traffic)
        val remaining = (stats.total_traffic - stats.used_traffic).coerceAtLeast(0L)
        binding.tvStatsRemainingTraffic.text = formatBytes(remaining)

        val progress = if (stats.total_traffic > 0)
            ((stats.used_traffic.toFloat() / stats.total_traffic) * 100).toInt().coerceIn(0, 100)
        else 0
        binding.progressStatsTraffic.progress = progress
        val tint = when {
            progress >= 90 -> R.color.error
            progress >= 70 -> R.color.connecting
            else -> R.color.primary
        }
        binding.progressStatsTraffic.progressTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), tint)
            )

        binding.tvStatsTotalConnections.text = stats.total_connections.toString()
        binding.tvStatsActiveConnections.text = stats.active_connections.toString()
        binding.tvStatsLastLogin.text = stats.last_login ?: "—"
        binding.tvStatsLoginCount.text = stats.login_count.toString()
        binding.tvStatsLastIp.text = stats.last_ip ?: "—"
        binding.tvStatsLastActive.text = stats.last_active ?: "—"
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                if (isActive) viewModel.silentRefreshStats(userId)
            }
        }
    }

    override fun onDestroyView() {
        autoRefreshJob?.cancel()
        refreshAnimator?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble(); var idx = 0
        while (size >= 1024 && idx < units.size - 1) { size /= 1024; idx++ }
        return String.format("%.2f %s", size, units[idx])
    }
}
