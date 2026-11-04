package com.example

import com.example.data.repository.AppConfig
import com.example.data.repository.AppConfigRepository
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppConfigTest {

    @Test
    fun testAppConfigDefaultValues() {
        val defaultConfig = AppConfig()
        assertEquals(1, defaultConfig.minSupportedVersionCode)
        assertEquals(1, defaultConfig.latestVersionCode)
        assertEquals("", defaultConfig.dynamicAppDownloadUrl)
    }

    @Test
    fun testAppConfigCustomValues() {
        val config = AppConfig(
            minSupportedVersionCode = 20,
            latestVersionCode = 25,
            dynamicAppDownloadUrl = "https://ordermaster.app/download/v25.apk"
        )
        assertEquals(20, config.minSupportedVersionCode)
        assertEquals(25, config.latestVersionCode)
        assertEquals("https://ordermaster.app/download/v25.apk", config.dynamicAppDownloadUrl)
    }

    @Test
    fun testForceUpdateEvaluationRule() {
        // App version code is 10
        val currentInstalledVersionCode = 10

        // Case 1: Min supported is 5 -> Force update NOT required
        val configSupported = AppConfig(minSupportedVersionCode = 5, latestVersionCode = 12)
        assertFalse(currentInstalledVersionCode < configSupported.minSupportedVersionCode)

        // Case 2: Min supported is 10 -> Force update NOT required (exactly on supported version)
        val configExact = AppConfig(minSupportedVersionCode = 10, latestVersionCode = 12)
        assertFalse(currentInstalledVersionCode < configExact.minSupportedVersionCode)

        // Case 3: Min supported is 15 -> Force update IS required (client version is too old)
        val configDeprecated = AppConfig(minSupportedVersionCode = 15, latestVersionCode = 20)
        assertTrue(currentInstalledVersionCode < configDeprecated.minSupportedVersionCode)
    }

    @Test
    fun testAppConfigRepositoryConstants() {
        assertEquals("admin_settings", AppConfigRepository.COLL_ADMIN_SETTINGS)
        assertEquals("app_config", AppConfigRepository.DOC_APP_CONFIG)
    }

    @Test
    fun testObserveAppConfigWithoutFirebaseDoesNotThrow() = kotlinx.coroutines.test.runTest {
        val flow = AppConfigRepository.observeAppConfig()
        val firstItem = flow.first()
        assertEquals(1, firstItem.minSupportedVersionCode)
        assertEquals(1, firstItem.latestVersionCode)
        assertEquals("", firstItem.dynamicAppDownloadUrl)
    }
}
