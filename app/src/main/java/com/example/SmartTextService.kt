package com.example

/**
 * Backward-compatible service implementation extending MyAccessibilityService.
 * Ensures existing manifest registrations and tests continue to function seamlessly.
 */
class SmartTextService : MyAccessibilityService() {
    companion object {
        const val DELAY_MS = 1500L
    }
}
