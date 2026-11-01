package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ui.components.GlobalBlockingOverlay
import com.example.ui.screens.PaymentVerificationScreen
import com.example.ui.screens.SubscriptionScreen
import com.example.ui.theme.CaptainAutoAcceptTheme

/**
 * Dedicated Activity for Captain Store, Passes, and Subscription Management.
 * Can be launched standalone or from services/notifications when access expires.
 */
class CaptainStoreActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.init(this)
        LicenseManager.init(this)

        setContent {
            CaptainAutoAcceptTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    var showPaymentVerification by remember { mutableStateOf(false) }

                    if (showPaymentVerification) {
                        PaymentVerificationScreen(
                            onNavigateBack = { showPaymentVerification = false },
                            onVerificationSuccess = { finish() }
                        )
                    } else {
                        SubscriptionScreen(
                            onNavigateBack = { finish() },
                            onTriggerGoogleSignIn = {
                                val intent = Intent(this@CaptainStoreActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                startActivity(intent)
                            },
                            onNavigateToPaymentVerification = {
                                showPaymentVerification = true
                            }
                        )
                    }

                    GlobalBlockingOverlay()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LicenseManager.refreshAccessStatus()
    }
}
