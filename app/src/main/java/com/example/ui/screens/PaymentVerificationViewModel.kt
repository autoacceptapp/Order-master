package com.example.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.PassTier
import com.example.data.PaymentVerificationRepository
import com.example.data.PaymentVerificationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel governing the Payment Verification Screen and Flow.
 */
class PaymentVerificationViewModel(
    private val repository: PaymentVerificationRepository = PaymentVerificationRepository()
) : ViewModel() {

    val uiState: StateFlow<PaymentVerificationState> = repository.verificationState

    private val _utrInput = MutableStateFlow("")
    val utrInput: StateFlow<String> = _utrInput.asStateFlow()

    private val _selectedTier = MutableStateFlow(PassTier.DAILY)
    val selectedTier: StateFlow<PassTier> = _selectedTier.asStateFlow()

    // Derived StateFlow checking strict 12-digit requirement
    val isUtrValid: StateFlow<Boolean> = _utrInput.map { input ->
        input.length == 12 && input.all { it.isDigit() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Handles UTR text changes with strict numeric filtering and 12-character capping.
     */
    fun onUtrChanged(rawInput: String) {
        val sanitized = rawInput.filter { it.isDigit() }.take(12)
        _utrInput.value = sanitized

        // If the user was in an error state and started editing, reset error to Idle
        if (uiState.value is PaymentVerificationState.Error) {
            repository.resetState()
        }
    }

    /**
     * Intelligent clipboard paste:
     * If user copies an entire SMS (e.g. "Paid Rs. 49. UPI Ref: 426719823451"),
     * regex extracts the 12-digit number automatically.
     */
    fun onPasteFromClipboard(clipboardText: String) {
        val twelveDigitMatch = Regex("\\b\\d{12}\\b").find(clipboardText)
        val extracted = twelveDigitMatch?.value ?: clipboardText.filter { it.isDigit() }.take(12)
        if (extracted.isNotEmpty()) {
            _utrInput.value = extracted
            if (uiState.value is PaymentVerificationState.Error) {
                repository.resetState()
            }
        }
    }

    fun onSelectTier(tier: PassTier) {
        _selectedTier.value = tier
    }

    /**
     * Submits the 12-digit UTR for automatic verification against Firestore.
     */
    fun submitVerification(context: Context) {
        val currentUtr = _utrInput.value.trim()
        if (currentUtr.length != 12) {
            return
        }
        repository.verifyPayment(
            context = context.applicationContext,
            rawUtr = currentUtr,
            selectedTier = _selectedTier.value
        )
    }

    /**
     * Cancels real-time listening and resets state back to Idle.
     */
    fun cancelPending() {
        repository.cancelPendingListener()
        repository.resetState()
    }

    fun resetState() {
        repository.resetState()
    }

    override fun onCleared() {
        super.onCleared()
        repository.cancelPendingListener()
    }
}
