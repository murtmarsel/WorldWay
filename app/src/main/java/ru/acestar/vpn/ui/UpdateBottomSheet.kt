package ru.acestar.vpn.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.UpdateInfo
import ru.acestar.vpn.databinding.DialogUpdateBinding
import ru.acestar.vpn.update.UpdateChecker
import ru.acestar.vpn.update.UpdateManager
import java.util.Calendar

/**
 * Всплывающее окно обновления (показывается когда приложение открыто).
 * Кнопки: Позже (выбор времени) / Обновить (скачать+установить).
 */
class UpdateBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogUpdateBinding? = null
    private val binding get() = _binding!!

    private lateinit var info: UpdateInfo

    companion object {
        private const val ARG_VERSION_CODE = "vc"
        private const val ARG_VERSION_NAME = "vn"
        private const val ARG_APK_URL = "url"
        private const val ARG_APK_SIZE = "size"
        private const val ARG_NOTES = "notes"

        fun newInstance(info: UpdateInfo) = UpdateBottomSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_VERSION_CODE, info.versionCode)
                putString(ARG_VERSION_NAME, info.versionName)
                putString(ARG_APK_URL, info.apkUrl)
                putLong(ARG_APK_SIZE, info.apkSize)
                putString(ARG_NOTES, info.releaseNotes)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        info = UpdateInfo(
            versionCode = args.getInt(ARG_VERSION_CODE),
            versionName = args.getString(ARG_VERSION_NAME) ?: "",
            apkUrl = args.getString(ARG_APK_URL) ?: "",
            apkSize = args.getLong(ARG_APK_SIZE),
            releaseNotes = args.getString(ARG_NOTES) ?: ""
        )

        binding.tvUpdateVersion.text =
            getString(R.string.update_available_text, info.versionName)
        binding.tvUpdateNotes.text =
            info.releaseNotes.ifBlank { getString(R.string.update_no_notes) }

        binding.btnUpdateNow.setOnClickListener { startDownload() }
        binding.btnUpdateLater.setOnClickListener { showScheduleDialog() }
    }

    private fun startDownload() {
        val ctx = requireContext()
        val manager = UpdateManager(ctx)

        // Проверяем разрешение на установку
        if (!manager.canInstallPackages()) {
            startActivity(manager.unknownSourcesSettingsIntent())
            Toast.makeText(ctx, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            return
        }

        binding.btnUpdateNow.isEnabled = false
        binding.btnUpdateLater.isEnabled = false
        binding.layoutUpdateProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            manager.downloadApk(info)
                .onSuccess { file ->
                    manager.installApk(file)
                    dismissAllowingStateLoss()
                }
                .onFailure {
                    binding.layoutUpdateProgress.visibility = View.GONE
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateLater.isEnabled = true
                    Toast.makeText(ctx, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** Диалог выбора даты и времени отложенного обновления */
    private fun showScheduleDialog() {
        val ctx = requireContext()
        val now = Calendar.getInstance()

        DatePickerDialog(ctx, { _, year, month, day ->
            TimePickerDialog(ctx, { _, hour, minute ->
                val scheduled = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                }
                if (scheduled.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(ctx, R.string.update_schedule_past, Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }
                UpdateChecker(ctx).scheduleUpdate(scheduled.timeInMillis, info.versionCode)
                Toast.makeText(ctx, R.string.update_scheduled, Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
            .apply { datePicker.minDate = System.currentTimeMillis() }
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
