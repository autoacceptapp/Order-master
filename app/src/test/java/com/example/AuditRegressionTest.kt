package com.example

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditRegressionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LicenseManager.resetStateForTesting(context)
        AppSettings.init(context)
        AppSettings.resetBusinessStateForTest(context)
        ApkDownloader.cancelDownload(context)
    }

    @After
    fun tearDown() {
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        ApkDownloader.cancelDownload(context)
        FloatingOverlayService.setActiveInstanceForTesting(null)
    }

    @Test
    fun `hasAcceptAction enforces word boundary around Go and rejects substring false positives`() {
        assertFalse(TextAnalysisEngine.hasAcceptAction("Google Maps navigation to drop"))
        assertFalse(TextAnalysisEngine.hasAcceptAction("Going to pickup 1.2 km"))
        assertFalse(TextAnalysisEngine.hasAcceptAction("Cargo delivery ₹120"))

        assertTrue(TextAnalysisEngine.hasAcceptAction("Go"))
        assertTrue(TextAnalysisEngine.hasAcceptAction("Tap Go to start trip"))
        assertTrue(TextAnalysisEngine.hasAcceptAction("Accept Ride ₹85"))
    }

    @Test
    fun `evaluateRideOffer rejects ride when maxDropDistance is enabled and dropDistanceKm is null`() {
        val offerWithoutDrop = ParsedRideOffer(
            rawText = "₹95 Pickup 1.0 km [Accept]",
            totalFare = 95.0,
            pickupDistanceKm = 1.0,
            dropDistanceKm = null,
            hasAcceptButton = true
        )

        val evaluation = TextAnalysisEngine.evaluateRideOffer(
            offer = offerWithoutDrop,
            minFare = 30.0f,
            maxFare = 500.0f,
            maxPickupDistance = 5.0f,
            isAutoAcceptEnabled = true,
            isMinFareEnabled = true,
            isMaxFareEnabled = true,
            isMaxPickupDistanceEnabled = true,
            isMaxDropDistanceEnabled = true,
            maxDropDistance = 8.0f
        )

        assertFalse(evaluation.isAccepted)
        assertTrue(
            "Expected missing drop distance rejection message, got: ${evaluation.decisionReason}",
            evaluation.decisionReason.contains("Drop distance could not be determined")
        )

        val offerWithValidDrop = offerWithoutDrop.copy(dropDistanceKm = 4.5)
        val validEval = TextAnalysisEngine.evaluateRideOffer(
            offer = offerWithValidDrop,
            minFare = 30.0f,
            maxFare = 500.0f,
            maxPickupDistance = 5.0f,
            isAutoAcceptEnabled = true,
            isMinFareEnabled = true,
            isMaxFareEnabled = true,
            isMaxPickupDistanceEnabled = true,
            isMaxDropDistanceEnabled = true,
            maxDropDistance = 8.0f
        )
        assertTrue(validEval.isAccepted)
    }

    @Test
    fun `apk downloader handleDownloadRemoved clears saved tracking and emits Error state`() {
        context.getSharedPreferences(ApkDownloader.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(ApkDownloader.KEY_ACTIVE_DOWNLOAD_ID, 888L)
            .putString(GitHubUpdateManager.KEY_CACHED_APK_FILENAME, "app-release.apk")
            .commit()
        assertTrue(ApkDownloader.isExpectedDownloadId(context, 888L))

        ApkDownloader.handleDownloadRemoved(context)

        assertFalse(ApkDownloader.isExpectedDownloadId(context, 888L))
        val state = ApkDownloader.downloadState.value
        assertTrue("Expected DownloadState.Error after removal, got $state", state is DownloadState.Error)
        assertEquals("Download cancelled or removed.", (state as DownloadState.Error).message)
    }

    @Test
    fun `remove ads pass does not grant pro pass access in LicenseManager or PassManager`() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.REMOVE_ADS,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )

        LicenseManager.refreshAccessStatus(context)

        assertFalse(
            "REMOVE_ADS tier must never activate PassActive Pro state",
            LicenseManager.accessStatus.value is AccessStatus.PassActive
        )
        assertFalse(
            "REMOVE_ADS tier must not grant Pro access",
            LicenseManager.isAccessGranted()
        )
        assertFalse(
            "REMOVE_ADS tier must not mark PassManager active",
            PassManager.isPassActive(context)
        )
        assertTrue(
            "REMOVE_ADS tier must still remove ads",
            LicenseManager.isAdsRemoved()
        )
    }

    @Test
    fun `merged manifest strips privacy sandbox adservices permissions to prevent MEASUREMENT_SERVICE onNullBinding`() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requested = packageInfo.requestedPermissions?.toSet() ?: emptySet()
        assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_ATTRIBUTION"))
        assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_AD_ID"))
        assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_TOPICS"))
    }

    @Test
    fun `tutorial steps and voice demo labels are complete in Hindi and English with intact UTF-8 text`() {
        val hindiSteps = com.example.ui.components.getTutorialSteps(context, isHindi = true)
        val englishSteps = com.example.ui.components.getTutorialSteps(context, isHindi = false)

        assertEquals(7, hindiSteps.size)
        assertEquals(7, englishSteps.size)

        val devanagariRegex = Regex("[\\u0900-\\u097F]+")
        hindiSteps.forEachIndexed { index, step ->
            assertTrue("Hindi step ${index + 1} badge blank", step.badge.isNotBlank())
            assertTrue("Hindi step ${index + 1} title blank", step.title.isNotBlank())
            assertTrue("Hindi step ${index + 1} shortDesc blank", step.shortDesc.isNotBlank())
            assertTrue("Hindi step ${index + 1} detailExplainer blank", step.detailExplainer.isNotBlank())
            assertTrue("Hindi step ${index + 1} recommendedSetting blank", step.recommendedSetting.isNotBlank())
            assertTrue("Hindi step ${index + 1} speechText blank", step.speechText.isNotBlank())
            assertTrue(
                "Hindi step ${index + 1} title missing Devanagari text: ${step.title}",
                devanagariRegex.containsMatchIn(step.title)
            )
            assertFalse("Replacement char found in Hindi step ${index + 1}", step.detailExplainer.contains('\uFFFD'))
            assertFalse("Question-mark placeholder found in Hindi step ${index + 1}", step.detailExplainer.contains("??"))
        }

        assertTrue("Hindi step 3 must contain Rupee symbol", hindiSteps[2].detailExplainer.contains('\u20B9'))
        assertTrue("Hindi step 4 must contain Rupee symbol", hindiSteps[3].detailExplainer.contains('\u20B9'))

        englishSteps.forEachIndexed { index, step ->
            assertTrue("English step ${index + 1} title blank", step.title.isNotBlank())
            assertTrue("English step ${index + 1} detailExplainer blank", step.detailExplainer.isNotBlank())
            assertTrue("English step ${index + 1} speechText blank", step.speechText.isNotBlank())
            assertFalse("Replacement char found in English step ${index + 1}", step.detailExplainer.contains('\uFFFD'))
        }

        val hindiVoiceLabels = com.example.ui.components.getVoiceDemoFieldLabels(isHindi = true)
        val englishVoiceLabels = com.example.ui.components.getVoiceDemoFieldLabels(isHindi = false)
        assertEquals(5, hindiVoiceLabels.size)
        assertEquals(5, englishVoiceLabels.size)
        assertEquals("1. \u0915\u093F\u0930\u093E\u092F\u093E (Fare):", hindiVoiceLabels[0])
        assertEquals("1. Fare:", englishVoiceLabels[0])
        hindiVoiceLabels.forEach { label ->
            assertTrue(label.isNotBlank())
            assertTrue("Hindi voice label missing Devanagari: $label", devanagariRegex.containsMatchIn(label))
        }
    }

    @Test
    fun `fare range presets stay within slider bounds and match clamped settings state`() {
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.MAX_FARE to futureExpiry
            )
        )
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMaxFareEnabled(context, true)

        assertTrue(AppSettings.FARE_RANGE_PRESETS.isNotEmpty())
        AppSettings.FARE_RANGE_PRESETS.forEach { (label, rawMin, rawMax) ->
            assertTrue("Preset label must contain Rupee symbol: $label", label.contains('\u20B9'))
            assertTrue(
                "Preset min $rawMin outside MIN_FARE range",
                rawMin in AppSettings.MIN_FARE_RANGE_START..AppSettings.MIN_FARE_RANGE_END
            )
            assertTrue(
                "Preset max $rawMax outside MAX_FARE range",
                rawMax in AppSettings.MAX_FARE_RANGE_START..AppSettings.MAX_FARE_RANGE_END
            )
            assertTrue("Preset min $rawMin must be <= max $rawMax", rawMin <= rawMax)

            if (rawMin > AppSettings.settingsState.value.maxFare) {
                AppSettings.setMaxFare(context, rawMax)
                AppSettings.setMinFare(context, rawMin)
            } else {
                AppSettings.setMinFare(context, rawMin)
                AppSettings.setMaxFare(context, rawMax)
            }

            val updated = AppSettings.settingsState.value
            assertEquals("Min fare mismatch for preset $label", rawMin, updated.minFare, 0.01f)
            assertEquals("Max fare mismatch for preset $label", rawMax, updated.maxFare, 0.01f)
        }
    }

    @Test
    fun `action queue pass payloads omit client-forged prices or durations`() {
        val purchasePayload = LicenseManager.buildPurchasePassPayload(PassTier.WEEKLY)
        assertEquals(setOf("passTier"), purchasePayload.keys)
        assertEquals(PassTier.WEEKLY.canonicalId, purchasePayload["passTier"])
        assertEquals("weekly", purchasePayload["passTier"])
        assertFalse(purchasePayload.containsKey("pointsCost"))
        assertFalse(purchasePayload.containsKey("durationDays"))
        assertFalse(purchasePayload.containsKey("durationMs"))

        val paymentPayload = LicenseManager.buildActivatePassPaymentPayload(PassTier.MONTHLY, "123456789012")
        assertEquals(setOf("passTier", "utr"), paymentPayload.keys)
        assertEquals(PassTier.MONTHLY.canonicalId, paymentPayload["passTier"])
        assertEquals("monthly", paymentPayload["passTier"])
        assertEquals("123456789012", paymentPayload["utr"])
        assertFalse(paymentPayload.containsKey("amount"))
        assertFalse(paymentPayload.containsKey("durationDays"))
        assertFalse(paymentPayload.containsKey("durationMs"))
        assertFalse(paymentPayload.containsKey("transactionId"))

        val unlockSinglePayload = LicenseManager.buildUnlockFeaturePayload(FeatureIds.MIN_FARE, 2)
        assertEquals(setOf("featureId", "hours"), unlockSinglePayload.keys)
        assertEquals(FeatureIds.MIN_FARE, unlockSinglePayload["featureId"])
        assertEquals(2, unlockSinglePayload["hours"])
        assertFalse(unlockSinglePayload.containsKey("pointsCost"))
        assertFalse(unlockSinglePayload.containsKey("durationMs"))

        val unlockMultiPayload = LicenseManager.buildUnlockMultipleFeaturesPayload(
            listOf(FeatureIds.MIN_FARE, FeatureIds.MAX_FARE),
            3
        )
        assertEquals(setOf("featureIds", "hours"), unlockMultiPayload.keys)
        assertEquals(listOf(FeatureIds.MIN_FARE, FeatureIds.MAX_FARE), unlockMultiPayload["featureIds"])
        assertEquals(3, unlockMultiPayload["hours"])
        assertFalse(unlockMultiPayload.containsKey("pointsCost"))
        assertFalse(unlockMultiPayload.containsKey("durationMs"))
    }

    @Test
    fun `server lastActionTime syncs client rate-limit cooldown and clock drift distinguishes forward drift from rollback`() {
        // 1. Server lastActionTime 4s ago sets cooldown elapsed timestamp accurately
        LicenseManager.resetStateForTesting(context)
        val nowServer = 1_800_000_000_000L
        val nowElapsed = 500_000L
        LicenseManager.updateLastActionCooldownFromServerTimestamp(
            lastActionServerMs = nowServer - 4_000L,
            nowServerMs = nowServer,
            nowElapsed = nowElapsed
        )
        assertEquals(nowElapsed - 4_000L, LicenseManager.getLastActionDispatchElapsedForTesting())

        // Older lastActionTime (> 12.5s ago) after reset leaves cooldown at 0L
        LicenseManager.resetStateForTesting(context)
        LicenseManager.updateLastActionCooldownFromServerTimestamp(
            lastActionServerMs = nowServer - 20_000L,
            nowServerMs = nowServer,
            nowElapsed = nowElapsed
        )
        assertEquals(0L, LicenseManager.getLastActionDispatchElapsedForTesting())

        // 2. Forward drift vs backward rollback classification
        val nowWall = System.currentTimeMillis()
        val currentElapsed = android.os.SystemClock.elapsedRealtime()
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.DAILY,
            passExpiry = nowWall + 86_400_000L,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false
        )
        // Wall clock jumped forward 20 mins relative to monotonic elapsed
        LicenseManager.setLastServerSyncForTesting(
            elapsedMs = currentElapsed,
            wallClockMs = nowWall - (20L * 60L * 1000L)
        )
        assertEquals(
            LicenseManager.ClockTamperStatus.FORWARD_DRIFT,
            LicenseManager.classifyClockTampering(context)
        )
        assertTrue(LicenseManager.isClockTamperingDetected(context))

        // Wall clock wound backward 10 mins triggers immediate HARD_TAMPER
        LicenseManager.setLastServerSyncForTesting(
            elapsedMs = currentElapsed,
            wallClockMs = nowWall + (10L * 60L * 1000L)
        )
        assertEquals(
            LicenseManager.ClockTamperStatus.HARD_TAMPER,
            LicenseManager.classifyClockTampering(context)
        )
    }

    @Test
    fun `pass manager isPassActiveFlow is strictly read-only from LicenseManager accessStatus`() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L

        LicenseManager.resetStateForTesting(context)
        LicenseManager.forceReleaseLoadingState()
        assertFalse(PassManager.isPassActiveFlow.value)
        assertFalse(PassManager::class.java.declaredMethods.any { it.name == "setPassStatus" })
        assertFalse(PassManager.isPassActive(context))

        // Active Trial -> true
        LicenseManager.resetStateForTesting(context)
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = null,
            passExpiry = 0L,
            trialClaimed = System.currentTimeMillis() - 10_000L,
            trialExpiry = futureExpiry,
            trialUsed = true
        )
        LicenseManager.refreshAccessStatus(context)
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.TrialActive)
        assertTrue(PassManager.isPassActiveFlow.value)

        // Expired Trial + expired Pass -> false
        LicenseManager.resetStateForTesting(context)
        LicenseManager.forceReleaseLoadingState()
        assertFalse(PassManager.isPassActiveFlow.value)

        // Remove Ads Pass alone -> false
        LicenseManager.resetStateForTesting(context)
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.REMOVE_ADS,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )
        LicenseManager.refreshAccessStatus(context)
        assertFalse(PassManager.isPassActiveFlow.value)

        // Pro Pass (Weekly) -> true
        LicenseManager.resetStateForTesting(context)
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.WEEKLY,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )
        LicenseManager.refreshAccessStatus(context)
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.PassActive)
        assertTrue(PassManager.isPassActiveFlow.value)
    }

    @Test
    fun `pending referral invite code persists across cold restarts and clears cleanly`() {
        ReferralManager.clearPendingInviteCode(context)
        assertEquals("", ReferralManager.pendingInviteCode.value)

        // Capture invite code from deep link
        ReferralManager.setPendingInviteCode(context, " om78xy90 ")
        assertEquals("OM78XY90", ReferralManager.pendingInviteCode.value)

        // Simulate cold process restart: wipe RAM flow and restore via init()
        ReferralManager.pendingInviteCode.value = ""
        ReferralManager.init(context)
        assertEquals("OM78XY90", ReferralManager.pendingInviteCode.value)

        // Redemption / explicit clear removes both RAM state and SharedPreferences
        ReferralManager.clearPendingInviteCode(context)
        assertEquals("", ReferralManager.pendingInviteCode.value)
        ReferralManager.init(context)
        assertEquals("", ReferralManager.pendingInviteCode.value)
    }

    @Test
    fun `order repository pruneExpiredOrdersIfNeeded removes orders older than 30 days`() = kotlinx.coroutines.test.runTest {
        val db = com.example.data.AppDatabase.getDatabase(context)
        val dao = db.orderDao()
        dao.clearAll()

        val nowMs = 1_800_000_000_000L
        val dayMs = 86_400_000L
        dao.insertOrder(
            com.example.data.OrderEntity(
                timestamp = nowMs - (35L * dayMs),
                fare = 90.0,
                pickupDistanceKm = 1.1,
                dropDistanceKm = 4.0,
                pickupLocation = "Expired Pickup",
                dropLocation = "Expired Drop",
                isAccepted = false,
                decisionReason = "Rejected"
            )
        )
        dao.insertOrder(
            com.example.data.OrderEntity(
                timestamp = nowMs - (5L * dayMs),
                fare = 160.0,
                pickupDistanceKm = 0.7,
                dropDistanceKm = 5.5,
                pickupLocation = "Fresh Pickup",
                dropLocation = "Fresh Drop",
                isAccepted = true,
                decisionReason = "Accepted"
            )
        )

        val repo = com.example.data.OrderRepository(context)
        val deleted = repo.pruneExpiredOrdersIfNeeded(nowMs)
        assertEquals(1, deleted)
        assertEquals(1, repo.getCount())
        // Subsequent call in the same session is throttled
        assertEquals(0, repo.pruneExpiredOrdersIfNeeded(nowMs))

        dao.clearAll()
    }

    @Test
    fun `PassTier fromId accepts canonical and legacy IDs and rejects none blank or unknown without remove_ads conflation`() {
        assertEquals(PassTier.DAILY, PassTier.fromId("daily"))
        assertEquals(PassTier.DAILY, PassTier.fromId(" DAILY_PASS "))
        assertEquals(PassTier.WEEKLY, PassTier.fromId("weekly"))
        assertEquals(PassTier.WEEKLY, PassTier.fromId("weekly_pass"))
        assertEquals(PassTier.MONTHLY, PassTier.fromId("monthly"))
        assertEquals(PassTier.MONTHLY, PassTier.fromId("monthly_pass"))
        assertEquals(PassTier.REMOVE_ADS, PassTier.fromId("remove_ads"))
        assertEquals(PassTier.REMOVE_ADS, PassTier.fromId("remove_ads_pass"))

        listOf(null, "", "   ", "none", "NONE", "null", "free", "unknown", "vip").forEach { nonPass ->
            assertEquals("Expected null PassTier for '$nonPass'", null, PassTier.fromId(nonPass))
            assertEquals("Expected null canonicalId for '$nonPass'", null, PassTier.canonicalPassTierOrNull(nonPass))
        }
        assertFalse(LicenseManager.isRemoveAdsActive())
    }

    @Test
    fun `non-pass activePassType with stale future passExpiryTimestamp never grants paid access`() {
        LicenseManager.forceReleaseLoadingState()
        val futureExpiry = System.currentTimeMillis() + 86_400_000L

        listOf("none", "", "   ", null, "unknown_tier").forEach { rawPassType ->
            assertFalse(
                "PassTier.isPaidPassActive must be false for '$rawPassType'",
                PassTier.isPaidPassActive(rawPassType, futureExpiry, System.currentTimeMillis())
            )

            AppSettings.applyFirestoreSnapshotForTest(
                context,
                mapOf(
                    "activePassType" to rawPassType,
                    "passExpiryTimestamp" to futureExpiry,
                    "isAutoAcceptEnabled" to true
                )
            )
            LicenseManager.setLicensingForTesting(
                passTier = PassTier.fromId(rawPassType),
                passExpiry = futureExpiry
            )

            assertFalse("AppSettings.isPassActive must be false for '$rawPassType'", AppSettings.isPassActive(context))
            assertFalse("AppSettings.isPassActiveFlow must be false for '$rawPassType'", AppSettings.isPassActiveFlow.value)
            assertEquals("AppSettings.getPassExpiryTimestamp must be 0L for '$rawPassType'", 0L, AppSettings.getPassExpiryTimestamp(context))
            assertEquals(
                "settingsState.passExpiryTimestamp must zero out stale expiry for '$rawPassType'",
                0L,
                AppSettings.settingsState.value.passExpiryTimestamp
            )
            assertFalse("LicenseManager.isPassActive must be false for '$rawPassType'", LicenseManager.isPassActive)
            assertFalse("LicenseManager.isAccessGranted must be false for '$rawPassType'", LicenseManager.isAccessGranted())
            assertFalse("LicenseManager.isRemoveAdsActive must not conflate '$rawPassType'", LicenseManager.isRemoveAdsActive())
            assertFalse("PassManager.isPassActive must be false for '$rawPassType'", PassManager.isPassActive(context))
        }
    }

    @Test
    fun `firebaseUid is populated on INIT_PROFILE and preserved in UPDATE_PROFILE without write loops`() {
        val initPayload = LicenseManager.buildInitProfilePayload(
            firebaseUid = "uid_abc_123",
            userEmail = "captain@example.com",
            hardwareId = "hw_xyz",
            legacyCustomId = "OM-USER-OLD"
        )
        assertEquals("uid_abc_123", initPayload["firebaseUid"])
        assertEquals("captain@example.com", initPayload["userEmail"])
        assertEquals("hw_xyz", initPayload["linkedHardwareId"])
        assertEquals("OM-USER-OLD", initPayload["legacyCustomUserId"])

        // Canonical document already in sync -> zero UPDATE_PROFILE writes (no write loop)
        val noUpdates = LicenseManager.computeNeededProfileUpdates(
            snapshotFirebaseUid = "uid_abc_123",
            snapshotHardwareId = "hw_xyz",
            snapshotEmail = "captain@example.com",
            expectedFirebaseUid = "uid_abc_123",
            currentHardwareId = "hw_xyz",
            authEmail = "captain@example.com"
        )
        assertTrue("Already-canonical doc must trigger zero updates", noUpdates.isEmpty())

        // Document missing firebaseUid -> repaired once while preserving UID
        val missingUidRepair = LicenseManager.computeNeededProfileUpdates(
            snapshotFirebaseUid = null,
            snapshotHardwareId = "hw_xyz",
            snapshotEmail = "captain@example.com",
            expectedFirebaseUid = "uid_abc_123",
            currentHardwareId = "hw_xyz",
            authEmail = "captain@example.com"
        )
        assertEquals(mapOf("firebaseUid" to "uid_abc_123"), missingUidRepair)
        assertTrue(LicenseManager.shouldDispatchProfileUpdate("uid_abc_123", missingUidRepair))
        assertFalse("Duplicate hash suppresses repeated dispatch", LicenseManager.shouldDispatchProfileUpdate("uid_abc_123", missingUidRepair))

        // Canonical lookup wins when present; fallback firebaseUid doc used when canonical missing
        assertEquals(
            "uid_abc_123",
            LicenseManager.selectResolvedUserDocumentPath("uid_abc_123", canonicalExists = true, fallbackDocIds = listOf("legacy_1"))
        )
        assertEquals(
            "legacy_1",
            LicenseManager.selectResolvedUserDocumentPath("uid_abc_123", canonicalExists = false, fallbackDocIds = listOf("legacy_1"))
        )
    }

    @Test
    fun `PURCHASE_PASS sends canonical passTier values and action queue handles REJECTED and FAILED immediately`() {
        val expectedCanonical = mapOf(
            PassTier.DAILY to "daily",
            PassTier.WEEKLY to "weekly",
            PassTier.MONTHLY to "monthly",
            PassTier.REMOVE_ADS to "remove_ads"
        )
        PassTier.entries.forEach { tier ->
            val payload = LicenseManager.buildPurchasePassPayload(tier)
            val wireTier = payload["passTier"] as? String
            assertEquals(expectedCanonical[tier], wireTier)
            assertTrue(wireTier in PassTier.VALID_CANONICAL_PASS_TIERS)
            assertFalse(wireTier.isNullOrBlank())
            assertFalse(wireTier.equals("none", ignoreCase = true))
        }

        assertEquals(
            LicenseManager.ActionQueueOutcome.Completed,
            LicenseManager.evaluateActionQueueSnapshot("PURCHASE_PASS", status = "COMPLETED")
        )
        assertEquals(
            LicenseManager.ActionQueueOutcome.Pending,
            LicenseManager.evaluateActionQueueSnapshot("PURCHASE_PASS", status = "PENDING")
        )

        val rejectedInvalidTier = LicenseManager.evaluateActionQueueSnapshot(
            actionType = "PURCHASE_PASS",
            status = "REJECTED",
            error = "INVALID_PASS_TIER"
        )
        assertTrue(rejectedInvalidTier is LicenseManager.ActionQueueOutcome.Failed)
        val errMsg = (rejectedInvalidTier as LicenseManager.ActionQueueOutcome.Failed).errorMessage
        assertTrue(
            "Expected friendly invalid pass plan message, got: $errMsg",
            errMsg.contains("Daily, Weekly, Monthly, or Remove Ads")
        )

        val failedWithReason = LicenseManager.evaluateActionQueueSnapshot(
            actionType = "PURCHASE_PASS",
            status = "FAILED",
            reason = "Insufficient points balance"
        )
        assertTrue(failedWithReason is LicenseManager.ActionQueueOutcome.Failed)
        assertEquals(
            "Insufficient points balance",
            (failedWithReason as LicenseManager.ActionQueueOutcome.Failed).errorMessage
        )
    }

    @Test
    fun `accessibility traversal depth and timeout limits are updated to prevent early aborts`() {
        assertEquals(120L, RapidoAccessibilityService.MAX_TRAVERSAL_TIMEOUT_MS)
        assertEquals(25, RapidoAccessibilityService.MAX_TRAVERSAL_DEPTH)

        // Verify countAcceptNodesInSubtree runs safely without timing out on empty root
        val root = android.view.accessibility.AccessibilityNodeInfo.obtain()
        val count = RideOfferParser.countAcceptNodesInSubtree(root, maxCount = 2, screenWidthPx = 1080)
        assertEquals(0, count)
    }

    @Test
    fun `floating overlay service setTemporarilyUntouchable updates flag safely and bounds detection works`() {
        // Without active instance, toggling untouchable is safe no-op
        FloatingOverlayService.setActiveInstanceForTesting(null)
        assertFalse(FloatingOverlayService.isTemporarilyUntouchable())
        FloatingOverlayService.setTemporarilyUntouchable(true)
        assertTrue(FloatingOverlayService.isTemporarilyUntouchable())
        assertNull(FloatingOverlayService.getCurrentOverlayBoundsOnScreen())
        FloatingOverlayService.setTemporarilyUntouchable(false)
        assertFalse(FloatingOverlayService.isTemporarilyUntouchable())

        // With mock/test instance
        val service = FloatingOverlayService()
        val root = FrameLayout(context)
        val params = WindowManager.LayoutParams(
            150,
            150,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = 200
            y = 300
        }
        service.overlayRoot = root
        service.windowLayoutParams = params
        service.isViewAttached = true
        FloatingOverlayService.setActiveInstanceForTesting(service)

        val bounds = FloatingOverlayService.getCurrentOverlayBoundsOnScreen()
        assertNotNull(bounds)
        assertEquals(200, bounds!!.left)
        assertEquals(300, bounds.top)

        // Toggle untouchable
        FloatingOverlayService.setTemporarilyUntouchable(true)
        assertTrue(FloatingOverlayService.isTemporarilyUntouchable())
        assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)

        // Toggle back to touchable
        FloatingOverlayService.setTemporarilyUntouchable(false)
        assertFalse(FloatingOverlayService.isTemporarilyUntouchable())
        assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0)

        FloatingOverlayService.setActiveInstanceForTesting(null)
    }

    @Test
    fun `processedOfferIds does not record offer when auto-click is disabled or access is inactive`() {
        val service = RapidoAccessibilityService()
        RapidoAccessibilityService.clearProcessedOffersForTesting(service)

        val offer = ParsedRideOffer(
            cardId = "test_offer_voice_101",
            rawText = "₹120 Pickup 1.0 km Drop 5.0 km [Accept]",
            totalFare = 120.0,
            pickupDistanceKm = 1.0,
            dropDistanceKm = 5.0,
            hasAcceptButton = true
        )

        // Case A: Auto-Click disabled (Voice-Only mode) -> offer must NOT be added to processedOfferIds
        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setAutoClickEnabled(context, false)
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.DAILY,
            passExpiry = System.currentTimeMillis() + 86_400_000L,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )
        LicenseManager.refreshAccessStatus(context)
        assertTrue(LicenseManager.isAccessGranted())

        service.processOrderQueue(listOf(offer), emptyList(), isProMode = true)
        assertFalse(
            "Offer must NOT be permanently added to processedOfferIds in Voice-Only mode",
            RapidoAccessibilityService.isOfferProcessedForTesting(service, offer.id)
        )

        // Case B: Auto-Click enabled with active access -> offer IS added to processedOfferIds
        AppSettings.setAutoClickEnabled(context, true)
        service.processOrderQueue(listOf(offer), emptyList(), isProMode = true)
        assertTrue(
            "Offer must be added to processedOfferIds when Auto-Click is enabled and access is active",
            RapidoAccessibilityService.isOfferProcessedForTesting(service, offer.id)
        )

        // Clean up
        RapidoAccessibilityService.clearProcessedOffersForTesting(service)
    }

    @Test
    fun `executeCardAcceptClick removes offer from processedOfferIds when click fails or stalls`() = runBlocking {
        val service = RapidoAccessibilityService()
        RapidoAccessibilityService.clearProcessedOffersForTesting(service)

        val offer = ParsedRideOffer(
            cardId = "test_failed_offer_202",
            rawText = "₹90 Pickup 0.5 km Drop 3.0 km [Accept]",
            totalFare = 90.0,
            pickupDistanceKm = 0.5,
            dropDistanceKm = 3.0,
            hasAcceptButton = true,
            bounds = Rect(100, 200, 500, 600)
        )
        val evaluation = RideEvaluation(
            offer = offer,
            isAutoAcceptEnabled = true,
            passesFare = true,
            passesPickupDistance = true,
            isAccepted = true,
            decisionReason = "Valid test offer"
        )

        // Pre-seed offer in processedOfferIds as if queued
        RapidoAccessibilityService.addProcessedOfferForTesting(service, offer.id)
        assertTrue(RapidoAccessibilityService.isOfferProcessedForTesting(service, offer.id))

        // When auto-click is disabled or blocked during delay, executeCardAcceptClick removes offer for retry
        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setAutoClickEnabled(context, false) // Disabled during delay

        service.executeCardAcceptClick(offer, evaluation, retainedAcceptNode = null)

        assertFalse(
            "Offer must be removed from processedOfferIds on failed/skipped click to allow retry",
            RapidoAccessibilityService.isOfferProcessedForTesting(service, offer.id)
        )
    }

    @Test
    fun `test ACTIVE_TRIP_INDICATORS_REGEX rejects offer card text and matches genuine active trip UI`() {
        val offerCardPhrases = listOf(
            "Pickup 1.2 km",
            "Pickup: MG Road Metro Station",
            "Drop: Koramangala 4th Block",
            "Drop-off location: Whitefield",
            "Emergency SOS contact",
            "Google Maps navigation to drop",
            "Navigate",
            "Arrived",
            "₹50 0.8 km Pickup Indiranagar 3.2 km Drop Koramangala Accept"
        )
        for (phrase in offerCardPhrases) {
            assertFalse(
                "Offer card phrase must NOT match active trip regex: $phrase",
                RapidoAccessibilityService.ACTIVE_TRIP_INDICATORS_REGEX.containsMatchIn(phrase)
            )
        }

        val activeTripPhrases = listOf(
            "Enter OTP",
            "Ask customer for OTP: 4821",
            "Start ride after customer boards",
            "Start trip",
            "Call customer directly",
            "Call rider",
            "Contact rider",
            "Navigate to pickup point",
            "Arrived at pickup",
            "Arrived at location",
            "Arrived at passenger location",
            "Drop-off at Rajkot Station",
            "Pick up customer"
        )
        for (phrase in activeTripPhrases) {
            assertTrue(
                "Active trip phrase must match active trip regex: $phrase",
                RapidoAccessibilityService.ACTIVE_TRIP_INDICATORS_REGEX.containsMatchIn(phrase)
            )
        }
    }

    @Test
    fun `findClickableParentWithinCard strictly rejects oversized outer card container`() {
        val service = RapidoAccessibilityService()
        val cardBounds = Rect(100, 200, 500, 800) // height = 600, width = 400

        @Suppress("DEPRECATION")
        val outerCard = AccessibilityNodeInfo.obtain()
        outerCard.isClickable = true
        outerCard.isEnabled = true
        outerCard.setBoundsInScreen(Rect(100, 200, 500, 800)) // height = 600 (> 0.45 * 600 and > 280px)

        @Suppress("DEPRECATION")
        val innerLabel = AccessibilityNodeInfo.obtain()
        innerLabel.isClickable = false
        innerLabel.isEnabled = true
        innerLabel.setBoundsInScreen(Rect(350, 720, 480, 780)) // height = 60

        org.robolectric.Shadows.shadowOf(outerCard).addChild(innerLabel)

        // The outerCard is clickable, but oversized (height = 600px).
        // findClickableParentWithinCard must reject outerCard and return null!
        val result = service.findClickableParentWithinCard(innerLabel, cardBounds)
        assertNull("findClickableParentWithinCard must NOT return oversized outer card container", result)

        // Case 2: Intermediate Button container within card is clickable and <= 280px / <= 0.45 * card height
        @Suppress("DEPRECATION")
        val buttonContainer = AccessibilityNodeInfo.obtain()
        buttonContainer.isClickable = true
        buttonContainer.isEnabled = true
        buttonContainer.setBoundsInScreen(Rect(340, 710, 490, 790)) // height = 80px <= 270px and <= 280px

        @Suppress("DEPRECATION")
        val buttonLabel = AccessibilityNodeInfo.obtain()
        buttonLabel.isClickable = false
        buttonLabel.isEnabled = true
        buttonLabel.setBoundsInScreen(Rect(350, 720, 480, 780))

        org.robolectric.Shadows.shadowOf(outerCard).addChild(buttonContainer)
        org.robolectric.Shadows.shadowOf(buttonContainer).addChild(buttonLabel)

        val buttonResult = service.findClickableParentWithinCard(buttonLabel, cardBounds)
        assertNotNull("findClickableParentWithinCard must return proper button container", buttonResult)
        assertEquals(buttonContainer, buttonResult)
    }

    @Test
    fun `findClickableParentOrSelf rejects container with height greater than 280px`() {
        val service = RapidoAccessibilityService()

        @Suppress("DEPRECATION")
        val giantContainer = AccessibilityNodeInfo.obtain()
        giantContainer.isClickable = true
        giantContainer.isEnabled = true
        giantContainer.setBoundsInScreen(Rect(0, 0, 1080, 600)) // height = 600 > 280

        @Suppress("DEPRECATION")
        val child = AccessibilityNodeInfo.obtain()
        child.isClickable = false
        child.isEnabled = true
        child.setBoundsInScreen(Rect(100, 100, 200, 150))

        org.robolectric.Shadows.shadowOf(giantContainer).addChild(child)

        val result = service.findClickableParentOrSelf(child)
        assertNull("findClickableParentOrSelf must not climb to container > 280px", result)
    }

    @Test
    fun `hasActiveTripIndicators accurately distinguishes active trip screens from ride offer screens`() {
        val service = RapidoAccessibilityService()

        // Screen 1: Ride offer card on screen
        @Suppress("DEPRECATION")
        val offerRoot = AccessibilityNodeInfo.obtain()
        @Suppress("DEPRECATION")
        val pickup = AccessibilityNodeInfo.obtain().apply { text = "Pickup 1.5 km away at Indiranagar" }
        @Suppress("DEPRECATION")
        val drop = AccessibilityNodeInfo.obtain().apply { text = "Drop 4.2 km at Koramangala" }
        org.robolectric.Shadows.shadowOf(offerRoot).addChild(pickup)
        org.robolectric.Shadows.shadowOf(offerRoot).addChild(drop)

        assertFalse(
            "Offer screen must NOT trigger hasActiveTripIndicators",
            service.hasActiveTripIndicators(listOf(offerRoot))
        )

        // Screen 2: Active trip screen with OTP
        @Suppress("DEPRECATION")
        val tripRoot = AccessibilityNodeInfo.obtain()
        @Suppress("DEPRECATION")
        val otpNode = AccessibilityNodeInfo.obtain().apply { text = "Ask rider to enter OTP 5821" }
        @Suppress("DEPRECATION")
        val navigateNode = AccessibilityNodeInfo.obtain().apply { text = "Navigate to pickup" }
        org.robolectric.Shadows.shadowOf(tripRoot).addChild(otpNode)
        org.robolectric.Shadows.shadowOf(tripRoot).addChild(navigateNode)

        assertTrue(
            "Trip screen with OTP and Navigate to must trigger hasActiveTripIndicators",
            service.hasActiveTripIndicators(listOf(tripRoot))
        )
    }
}

