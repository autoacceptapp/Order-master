package com.example

import android.content.Context
import android.util.Log

/**
 * RapidoAccessibilityService is the primary accessibility service for Rapido Captain automation.
 * Inherits all automated ride capture, screen inspection, order threshold calculation,
 * and automated click dispatching from MyAccessibilityService.
 */
class RapidoAccessibilityService : MyAccessibilityService() {

    companion object {
        const val TAG = "RapidoAccessibilityService"

        /**
         * Invokes the system accessibility configuration to activate the Rapido Accessibility Service.
         */
        fun invoke(context: Context) {
            Log.d(TAG, "Invoking RapidoAccessibilityService...")
            PermissionUtils.openAccessibilitySettings(context)
        }
    }
}
