package ru.acestar.vpn.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.databinding.SheetPasswordRecoveryBinding

class PasswordRecoverySheet : BottomSheetDialogFragment() {
    override fun getTheme(): Int = ru.acestar.vpn.R.style.ThemeOverlay_VPN_RecoverySheet
    companion object {
        fun newInstance(email: String = "", admin: Boolean = false) = PasswordRecoverySheet().apply {
            arguments = bundleOf("email" to email, "admin" to admin)
        }
    }
    private var currentBinding: SheetPasswordRecoveryBinding? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val b = SheetPasswordRecoveryBinding.inflate(inflater, container, false)
        currentBinding = b
        return b.root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = currentBinding ?: return
        val admin = requireArguments().getBoolean("admin")
        b.recoveryEmail.setText(requireArguments().getString("email", ""))
        if (admin) {
            b.recoveryTitle.text = "Доступ администратора"
            b.recoveryDescription.text = "Ссылка для смены пароля админ-панели придёт только на почту поддержки support@shopace.ru. Пароль вашего аккаунта не изменится."
            b.recoveryEmailLayout.visibility = View.GONE
        }
        b.recoveryClose.setOnClickListener { dismiss() }
        b.recoverySend.setOnClickListener {
            val email = b.recoveryEmail.text.toString().trim()
            if (!admin && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                b.recoveryEmailLayout.error = "Введите корректный email"
                return@setOnClickListener
            }
            b.recoveryEmailLayout.error = null
            b.recoverySend.isEnabled = false
            b.recoveryProgress.visibility = View.VISIBLE
            val api = AuthApiService(SessionManager.getInstance(requireContext()))
            viewLifecycleOwner.lifecycleScope.launch {
                val result = api.emailRequest(if (admin) "admin/password/forgot" else "password/forgot", JSONObject().put("email", email))
                val binding = currentBinding ?: return@launch
                binding.recoveryProgress.visibility = View.GONE
                binding.recoveryStatus.visibility = View.VISIBLE
                result.fold(onSuccess = {
                    binding.recoveryEmailLayout.visibility = View.GONE
                    binding.recoveryTitle.text = "Проверьте почту"
                    binding.recoveryDescription.text = if (admin) "Инструкция отправлена на support@shopace.ru." else "Если аккаунт существует, мы отправили инструкцию на $email."
                    binding.recoveryStatus.text = "Ссылка действует 30 минут. Если письма нет, проверьте папку «Спам»."
                    binding.recoverySend.visibility = View.GONE
                    binding.recoveryClose.text = "Понятно"
                }, onFailure = {
                    binding.recoverySend.isEnabled = true
                    binding.recoveryStatus.text = it.message ?: "Не удалось отправить письмо. Попробуйте позже."
                })
            }
        }
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            setShouldRemoveExpandedCorners(false)
            skipCollapsed = true
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }
    override fun onDestroyView() {
        currentBinding = null
        super.onDestroyView()
    }
}
