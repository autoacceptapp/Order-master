package com.example

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.theme.CaptainAutoAcceptTheme

/**
 * PaymentActivity - Payment Processing Gateway & Callback Handler
 *
 * Implements strict frontend double-check:
 * Pass status (isPassActive = true) is ONLY updated when the backend (Razorpay / Cashfree / Firebase)
 * returns a confirmed Success signal.
 */
class PaymentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CaptainAutoAcceptTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("OrderMaster Payment Processing Gateway")
                    }
                }
            }
        }
    }

    /**
     * 1. Payment Response Double-Check Karein (Frontend)
     * Pass ka status (isPassActive = true) sirf aur sirf tabhi update karein
     * jab Razorpay / Cashfree / Firebase Backend se Success Signal aaye.
     */
    fun onPaymentSuccess(paymentId: String) {
        // 1. Server/Backend se verify karein
        verifyPaymentWithBackend(paymentId) { isSuccess ->
            if (isSuccess) {
                // Sirf success aane par pass active karein
                PassManager.setPassStatus(this@PaymentActivity, true)
                Toast.makeText(this@PaymentActivity, "Pass Activated!", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                // Payment verify nahi hua
                PassManager.setPassStatus(this@PaymentActivity, false)
                Toast.makeText(this@PaymentActivity, "Payment Failed/Not Verified", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Verifies payment status directly with backend/Firebase Firestore
     */
    fun verifyPaymentWithBackend(paymentId: String, callback: (Boolean) -> Unit) {
        PassManager.verifyPaymentWithBackend(this@PaymentActivity, paymentId, callback)
    }
}
