package com.example

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.isDirectInAppMessage
import com.example.ui.components.resolveInAppMessageCategoryLabel
import com.example.ui.components.resolveInAppMessageIcon
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

@RunWith(RobolectricTestRunner::class)
class Step11BDefenseInDepthTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        InAppMessageManager.resetForTesting(context, bypassUiDelay = true)
        LicenseManager.resetStateForTesting(context)
    }

    @After
    fun tearDown() {
        InAppMessageManager.resetForTesting(context, bypassUiDelay = false)
        LicenseManager.resetStateForTesting(context)
    }

    // -------------------------------------------------------------------------
    // 1. InAppMessageManager: Strict targetUid Privacy Filter & Deduplication
    // -------------------------------------------------------------------------

    @Test
    fun globalMessagesAllowAllBlankNullAndExactCurrentUid() {
        val currentUid = "uid_driver_alice_001"

        assertTrue(InAppMessageManager.isGlobalMessageAllowedForUser("ALL", currentUid))
        assertTrue(InAppMessageManager.isGlobalMessageAllowedForUser("all", currentUid))
        assertTrue(InAppMessageManager.isGlobalMessageAllowedForUser(" All ", currentUid))
        assertTrue(InAppMessageManager.isGlobalMessageAllowedForUser(null, currentUid))
        assertTrue(InAppMessageManager.isGlobalMessageAllowedForUser("", currentUid))
        assertTrue(InAppMessageManager.isGlobalMessageAllowedForUser("   ", currentUid))
        assertTrue(InAppMessageManager.isGlobalMessageAllowedForUser(currentUid, currentUid))

        assertTrue(
            InAppMessageManager.processGlobalMessageDocument(
                id = "global_all_1",
                title = "System Update",
                message = "New version live for all drivers.",
                targetUid = "ALL",
                timestamp = 1_000L,
                currentUid = currentUid
            )
        )
        assertTrue(
            InAppMessageManager.processGlobalMessageDocument(
                id = "global_blank_2",
                title = "General Tip",
                message = "Keep battery optimization disabled.",
                targetUid = null,
                timestamp = 2_000L,
                currentUid = currentUid
            )
        )
        assertTrue(
            InAppMessageManager.processGlobalMessageDocument(
                id = "global_self_3",
                title = "Account Notice",
                message = "Your pass has been verified.",
                targetUid = currentUid,
                timestamp = 3_000L,
                currentUid = currentUid
            )
        )

        val active = InAppMessageManager.activeMessage.value
        assertNotNull(active)
        assertEquals("global_all_1", active?.id)

        val queuedIds = InAppMessageManager.getPendingQueueSnapshotForTesting().map { it.id }
        assertEquals(listOf("global_all_1", "global_blank_2", "global_self_3"), queuedIds)
    }

    @Test
    fun globalMessagesSilentlyDropDocumentsTargetedToOtherUsers() {
        val currentUid = "uid_driver_alice_001"

        assertFalse(InAppMessageManager.isGlobalMessageAllowedForUser("other_user_uid", currentUid))
        assertFalse(InAppMessageManager.isGlobalMessageAllowedForUser("uid_driver_bob_999", currentUid))
        assertFalse(InAppMessageManager.isGlobalMessageAllowedForUser("uid_driver_alice_001_extra", currentUid))
        assertFalse(InAppMessageManager.isGlobalMessageAllowedForUser("other_user_uid", null))
        assertFalse(InAppMessageManager.isGlobalMessageAllowedForUser("other_user_uid", ""))

        val acceptedOther = InAppMessageManager.processGlobalMessageDocument(
            id = "legacy_private_msg_1",
            title = "Private Refund Info",
            message = "Secret personal notice for Bob only.",
            targetUid = "other_user_uid",
            timestamp = 1_000L,
            currentUid = currentUid
        )

        assertFalse("Legacy global_messages doc addressed to another user must be silently dropped", acceptedOther)
        assertNull("Dropped message must never become activeMessage", InAppMessageManager.activeMessage.value)
        assertTrue(
            "Dropped message must never enter pendingQueue",
            InAppMessageManager.getPendingQueueSnapshotForTesting().isEmpty()
        )
    }

    @Test
    fun inAppMessagesDeduplicateIdenticalMessageIdAcrossGlobalAndPersonalStreams() {
        val currentUid = "uid_driver_alice_001"

        // Case A: Arrives in global_messages first, then same messageId arrives in users/{uid}/messages
        assertTrue(
            InAppMessageManager.processGlobalMessageDocument(
                id = "shared_notice_42",
                title = "Holiday Bonus",
                message = "Earn 2x referral points today!",
                targetUid = "ALL",
                timestamp = 10_000L,
                currentUid = currentUid
            )
        )
        assertFalse(
            "Duplicate messageId from users/{uid}/messages must be suppressed",
            InAppMessageManager.processUserMessageDocument(
                id = "shared_notice_42",
                title = "Holiday Bonus",
                message = "Earn 2x referral points today!",
                targetUid = currentUid,
                timestamp = 10_000L,
                currentUid = currentUid
            )
        )

        // Case B: Arrives in users/{uid}/messages first, then same messageId arrives in global_messages
        assertTrue(
            InAppMessageManager.processUserMessageDocument(
                id = "direct_notice_99",
                title = "Pass Renewed",
                message = "Weekly pass activated successfully.",
                targetUid = currentUid,
                timestamp = 11_000L,
                currentUid = currentUid
            )
        )
        assertFalse(
            "Duplicate messageId from global_messages must be suppressed",
            InAppMessageManager.processGlobalMessageDocument(
                id = "direct_notice_99",
                title = "Pass Renewed",
                message = "Weekly pass activated successfully.",
                targetUid = currentUid,
                timestamp = 11_000L,
                currentUid = currentUid
            )
        )

        val queued = InAppMessageManager.getPendingQueueSnapshotForTesting()
        assertEquals(listOf("shared_notice_42", "direct_notice_99"), queued.map { it.id })
        assertEquals("shared_notice_42", InAppMessageManager.activeMessage.value?.id)

        // Dismissing shared_notice_42 advances to direct_notice_99 and prevents re-delivery on both streams
        InAppMessageManager.dismissMessage("shared_notice_42")
        assertTrue(InAppMessageManager.isMessageDismissed("shared_notice_42"))
        assertEquals("direct_notice_99", InAppMessageManager.activeMessage.value?.id)

        assertFalse(
            InAppMessageManager.processGlobalMessageDocument(
                id = "shared_notice_42",
                title = "Holiday Bonus",
                message = "Earn 2x referral points today!",
                targetUid = "ALL",
                timestamp = 10_000L,
                currentUid = currentUid
            )
        )
        assertFalse(
            InAppMessageManager.processUserMessageDocument(
                id = "shared_notice_42",
                title = "Holiday Bonus",
                message = "Earn 2x referral points today!",
                targetUid = currentUid,
                timestamp = 10_000L,
                currentUid = currentUid
            )
        )
    }

    @Test
    fun inAppMessageDialogClassifiesBroadcastAllAsCampaignAndPersonalUidAsMarkEmailRead() {
        // Public broadcasts: "ALL" (any case/whitespace), null, or blank -> isDirectMessage == false, Campaign icon
        listOf("ALL", "all", " All ", null, "", "   ").forEach { broadcastTarget ->
            assertFalse(
                "targetUid='$broadcastTarget' must NOT be classified as a direct message",
                isDirectInAppMessage(broadcastTarget)
            )
            assertEquals(
                "targetUid='$broadcastTarget' must render Campaign broadcast icon",
                Icons.Default.Campaign,
                resolveInAppMessageIcon(broadcastTarget)
            )
            assertEquals(
                "targetUid='$broadcastTarget' must render Announcement broadcast label",
                "Announcement",
                resolveInAppMessageCategoryLabel(broadcastTarget)
            )
        }

        // Targeted personal message -> isDirectMessage == true, MarkEmailRead icon
        assertTrue(isDirectInAppMessage("uid_123"))
        assertEquals(Icons.Default.MarkEmailRead, resolveInAppMessageIcon("uid_123"))
        assertEquals("Direct Message", resolveInAppMessageCategoryLabel("uid_123"))
    }

    // -------------------------------------------------------------------------
    // 2. LicenseManager: User-Triggered Action Priority Over Silent UPDATE_PROFILE
    // -------------------------------------------------------------------------

    @Test
    fun silentUpdateProfileLeavesUserActionCooldownUntouchedForImmediateUserActions() {
        val t0 = 2_000_000L

        assertTrue(LicenseManager.isSilentBackgroundAction("UPDATE_PROFILE"))
        assertTrue(LicenseManager.isSilentBackgroundAction("update_profile"))
        assertFalse(LicenseManager.isSilentBackgroundAction("REWARD_AD_WATCHED"))
        assertFalse(LicenseManager.isSilentBackgroundAction("PURCHASE_PASS"))

        // 1. Terminal statuses of silent UPDATE_PROFILE must NEVER anchor or mutate lastActionCompletedAtMs
        listOf("COMPLETED", "FAILED", "REJECTED").forEach { terminalStatus ->
            LicenseManager.evaluateActionQueueSnapshot(
                actionType = "UPDATE_PROFILE",
                status = terminalStatus,
                errorMessage = if (terminalStatus == "COMPLETED") null else "Transient error",
                nowElapsed = t0
            )
            assertEquals(
                "Silent UPDATE_PROFILE ($terminalStatus) must not set lastActionCompletedAtMs",
                0L,
                LicenseManager.getLastActionCompletedAtMsForTesting()
            )
            assertEquals(
                "Silent UPDATE_PROFILE ($terminalStatus) must leave 0ms cooldown",
                0L,
                LicenseManager.computeActionCooldownRemainingMs(t0 + 100L)
            )
        }

        // Direct recordActionTerminalCompletion with UPDATE_PROFILE must also be a no-op
        LicenseManager.recordActionTerminalCompletion(nowElapsed = t0 + 200L, actionType = "UPDATE_PROFILE")
        assertEquals(0L, LicenseManager.getLastActionCompletedAtMsForTesting())

        // 2. Even while background UPDATE_PROFILE is in flight, immediate user actions proceed without delay
        LicenseManager.setBackgroundProfileUpdateInFlightForTesting(true)
        assertFalse(LicenseManager.isActionInFlightForTesting())

        val immediateUserActions = listOf(
            "REWARD_AD_WATCHED",
            "PURCHASE_PASS",
            "ACTIVATE_PASS_PAYMENT",
            "UNLOCK_FEATURE",
            "UNLOCK_MULTIPLE_FEATURES",
            "CLAIM_REFERRAL"
        )
        immediateUserActions.forEach { actionType ->
            assertTrue(LicenseManager.isUserTriggeredAction(actionType))
            val allowed = LicenseManager.checkActionSubmissionAllowed(
                nowElapsed = t0 + 250L,
                actionType = actionType
            )
            assertTrue(
                "Immediate user action $actionType must NOT be blocked by silent UPDATE_PROFILE",
                allowed.isSuccess
            )
        }
        LicenseManager.setBackgroundProfileUpdateInFlightForTesting(false)

        // 3. Silent UPDATE_PROFILE must NOT extend an existing user action's cooldown anchor
        val userCompleteTime = 3_000_000L
        LicenseManager.evaluateActionQueueSnapshot(
            actionType = "REWARD_AD_WATCHED",
            status = "COMPLETED",
            nowElapsed = userCompleteTime
        )
        assertEquals(userCompleteTime, LicenseManager.getLastActionCompletedAtMsForTesting())

        LicenseManager.evaluateActionQueueSnapshot(
            actionType = "UPDATE_PROFILE",
            status = "COMPLETED",
            nowElapsed = userCompleteTime + 5_000L
        )
        assertEquals(
            "Silent UPDATE_PROFILE must never reset/extend existing user cooldown anchor",
            userCompleteTime,
            LicenseManager.getLastActionCompletedAtMsForTesting()
        )
    }

    @Test
    fun silentUpdateProfileDefersWhileUserActionIsInFlightOrWithinCooldownWindow() {
        val userActionTypes = listOf(
            "PURCHASE_PASS",
            "ACTIVATE_PASS_PAYMENT",
            "REWARD_AD_WATCHED",
            "UNLOCK_FEATURE",
            "UNLOCK_MULTIPLE_FEATURES",
            "CLAIM_REFERRAL"
        )

        userActionTypes.forEachIndexed { idx, userAction ->
            LicenseManager.resetStateForTesting(context)
            val tBase = 5_000_000L + (idx * 100_000L)

            // Clean state: silent UPDATE_PROFILE is permitted
            assertTrue(LicenseManager.canDispatchSilentProfileUpdate(tBase))
            assertTrue(LicenseManager.checkActionSubmissionAllowed(tBase, "UPDATE_PROFILE").isSuccess)

            // A. While explicit user action is in-flight -> silent UPDATE_PROFILE must defer
            LicenseManager.setActionInFlightForTesting(true)
            assertFalse(
                "Silent UPDATE_PROFILE must defer while $userAction is in flight",
                LicenseManager.canDispatchSilentProfileUpdate(tBase + 100L)
            )
            val inFlightDeferResult = LicenseManager.checkActionSubmissionAllowed(
                nowElapsed = tBase + 100L,
                actionType = "UPDATE_PROFILE"
            )
            assertTrue(inFlightDeferResult.isFailure)
            assertEquals(
                LicenseManager.SILENT_PROFILE_UPDATE_DEFERRED_MESSAGE,
                inFlightDeferResult.exceptionOrNull()?.message
            )

            // B. User action finishes -> inside 13_500L ms cooldown window, silent UPDATE_PROFILE must still defer
            LicenseManager.setActionInFlightForTesting(false)
            val completionTime = tBase + 1_000L
            LicenseManager.evaluateActionQueueSnapshot(
                actionType = userAction,
                status = "COMPLETED",
                nowElapsed = completionTime
            )
            assertFalse(
                "Silent UPDATE_PROFILE must defer immediately after $userAction completion",
                LicenseManager.canDispatchSilentProfileUpdate(completionTime + 500L)
            )
            assertFalse(
                "Silent UPDATE_PROFILE must defer at +13_499ms after $userAction completion",
                LicenseManager.canDispatchSilentProfileUpdate(completionTime + 13_499L)
            )
            val cooldownDeferResult = LicenseManager.checkActionSubmissionAllowed(
                nowElapsed = completionTime + 10_000L,
                actionType = "UPDATE_PROFILE"
            )
            assertTrue(cooldownDeferResult.isFailure)
            assertEquals(
                LicenseManager.SILENT_PROFILE_UPDATE_DEFERRED_MESSAGE,
                cooldownDeferResult.exceptionOrNull()?.message
            )

            // C. Once full 13_500L ms cooldown elapses -> silent UPDATE_PROFILE may run cleanly
            assertTrue(
                "Silent UPDATE_PROFILE should be allowed once $userAction cooldown expires",
                LicenseManager.canDispatchSilentProfileUpdate(completionTime + 13_500L)
            )
            assertTrue(
                LicenseManager.checkActionSubmissionAllowed(
                    nowElapsed = completionTime + 13_500L,
                    actionType = "UPDATE_PROFILE"
                ).isSuccess
            )
        }
    }
}
