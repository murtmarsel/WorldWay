// ui/EmailConfirmationViewModel.kt
package ru.acestar.vpn.data.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class EmailConfirmationViewModel : ViewModel() {

    private val _emailState = MutableLiveData<EmailConfirmationState>(EmailConfirmationState.Idle)
    val emailState: LiveData<EmailConfirmationState> = _emailState

    private val _countdown = MutableLiveData<Int>()
    val countdown: LiveData<Int> = _countdown

    private var generatedCode: String? = null
    private var countdownJob: Job? = null

    // Валидация email
    private fun isValidEmail(email: String): Boolean {
        val emailPattern = Pattern.compile(
            "[a-zA-Z0-9+._%\\-]{1,256}" +
                    "@" +
                    "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
                    "(" +
                    "\\." +
                    "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
                    ")+"
        )
        return emailPattern.matcher(email).matches()
    }

    // Отправка кода подтверждения на email
    fun sendConfirmationCode(email: String) {
        if (!isValidEmail(email)) {
            _emailState.value = EmailConfirmationState.Error("Введите корректный email адрес")
            return
        }

        _emailState.value = EmailConfirmationState.Loading

        viewModelScope.launch {
            try {
                // Генерируем 6-значный код
                generatedCode = 111111.toString()

                // TODO: Здесь отправка реального email через ваш API
                // val response = apiService.sendConfirmationCode(email, generatedCode)

                // Имитация отправки
                delay(1500)

                println("Код подтверждения для $email: $generatedCode")

                _emailState.value = EmailConfirmationState.CodeSent(email)
                startCountdown(60) // 60 секунд на ввод кода

            } catch (e: Exception) {
                _emailState.value = EmailConfirmationState.Error("Ошибка отправки кода: ${e.message}")
            }
        }
    }

    // Проверка введенного кода
    fun verifyCode(email: String, code: String) {
        if (code.length != 6) {
            _emailState.value = EmailConfirmationState.Error("Код должен содержать 6 цифр")
            return
        }

        _emailState.value = EmailConfirmationState.Loading

        viewModelScope.launch {
            try {
                delay(1000) // Имитация проверки

                if (code == generatedCode) {
                    // TODO: Отправить подтверждение на сервер
                    // apiService.verifyEmail(email, code)

                    _emailState.value = EmailConfirmationState.Verified(email)
                    stopCountdown()
                } else {
                    _emailState.value = EmailConfirmationState.Error("Неверный код подтверждения")
                }

            } catch (e: Exception) {
                _emailState.value = EmailConfirmationState.Error("Ошибка проверки кода: ${e.message}")
            }
        }
    }

    // Повторная отправка кода
    fun resendCode(email: String) {
        if ((_countdown.value ?: 0) > 0) {
            _emailState.value = EmailConfirmationState.Error("Подождите ${_countdown.value} секунд перед повторной отправкой")
            return
        }

        sendConfirmationCode(email)
    }

    // Таймер обратного отсчета
    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()

        var remainingSeconds = seconds
        _countdown.value = remainingSeconds

        countdownJob = viewModelScope.launch {
            while (remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
                _countdown.value = remainingSeconds
            }
        }
    }

    private fun stopCountdown() {
        countdownJob?.cancel()
        _countdown.value = 0
    }

    fun resetState() {
        _emailState.value = EmailConfirmationState.Idle
        _countdown.value = 0
        generatedCode = null
        countdownJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}