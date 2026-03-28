package ru.acestar.vpn.data.model

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ru.acestar.vpn.api.PaymentApiService
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.model.PaymentData
import ru.acestar.vpn.data.model.PaymentStatusData
import kotlinx.coroutines.launch

sealed class PaymentState {
    object Idle : PaymentState()
    object Loading : PaymentState()
    data class Success(val paymentData: PaymentData) : PaymentState()
    data class Error(val message: String) : PaymentState()
    data class PaymentCompleted(val statusData: PaymentStatusData) : PaymentState()
}

class PaymentViewModel(application: Application) : AndroidViewModel(application) {
    private val apiService = PaymentApiService(SessionManager.getInstance(application))

    private val _paymentResult = MutableLiveData<PaymentResult?>()
    val paymentResult: LiveData<PaymentResult?> = _paymentResult

    data class PaymentResult(
        val success: Boolean,
        val paymentId: String,
        val status: String? = null
    )

    fun setPaymentResult(success: Boolean, paymentId: String, status: String? = null) {
        _paymentResult.value = PaymentResult(success, paymentId, status)
    }

    fun clearPaymentResult() {
        _paymentResult.value = null
    }
    private val _paymentState = MutableLiveData<PaymentState>(PaymentState.Idle)
    val paymentState: LiveData<PaymentState> = _paymentState

    private val _paymentId = MutableLiveData<String?>()
    val paymentId: LiveData<String?> = _paymentId

    fun createPayment(
        amountValue: String,
        returnUrl: String,
        description: String? = null,
        metadata: Map<String, String>? = null
    ) {
        _paymentState.value = PaymentState.Loading

        viewModelScope.launch {
            val result = apiService.createPayment(
                amountValue = amountValue,
                returnUrl = returnUrl,
                description = description,
                metadata = metadata
            )

            result.onSuccess { paymentData ->
                _paymentId.value = paymentData.payment_id
                _paymentState.value = PaymentState.Success(paymentData)
            }.onFailure { exception ->
                _paymentState.value = PaymentState.Error(exception.message ?: "Unknown error")
            }
        }
    }

    fun checkPaymentStatus(paymentId: String) {
        viewModelScope.launch {
            val result = apiService.getPaymentStatus(paymentId)

            result.onSuccess { statusData ->
                if (statusData.status == "succeeded") {
                    _paymentState.value = PaymentState.PaymentCompleted(statusData)
                }
            }.onFailure { exception ->
                // Логируем ошибку, но не меняем состояние
                android.util.Log.e("PaymentViewModel", "Status check failed", exception)
            }
        }
    }

    fun resetState() {
        _paymentState.value = PaymentState.Idle
        _paymentId.value = null
    }

    fun setAuthToken(token: String) {
        apiService.setAuthToken(token)
    }
}