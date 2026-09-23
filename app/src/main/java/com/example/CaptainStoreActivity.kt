package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                SubscriptionScreen(
                    onNavigateBack = { finish() },
                    onTriggerGoogleSignIn = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LicenseManager.refreshAccessStatus()
    }
}
