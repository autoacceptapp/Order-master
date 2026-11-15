package com.example.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.AppSettings
import com.example.FeatureIds
import com.example.LicenseManager
import com.example.PermissionUtils
import com.example.TTSManager
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.OnEmeraldContainer
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.theme.SurfaceStroke
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

/**
 * Step detail model for the interactive tutorial and filter training.
 */
data class TutorialGuideStep(
    val stepNumber: Int,
    val icon: ImageVector,
    val badge: String,
    val title: String,
    val shortDesc: String,
    val detailExplainer: String,
    val recommendedSetting: String,
    val speechText: String,
    val accentColor: Color,
    val mockExample: @Composable () -> Unit
)

/**
 * Interactive App & Filter Training Guide Dialog.
 * Teaches captains step-by-step how Order Master works and how to set up each filter.
 */
@Composable
fun AppTutorialGuideDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var isNarrationMuted by remember { mutableStateOf(false) }
    val userLang = remember { AppSettings.getVoiceLanguage(context).lowercase(Locale.ROOT) }
    val isHindi = userLang == "hi"

    DisposableEffect(Unit) {
        onDispose {
            TTSManager.stop()
        }
    }

    val steps = remember(userLang) { getTutorialSteps(context, isHindi) }

    // Auto-narrate each step as the user navigates or when dialog opens
    LaunchedEffect(selectedTab, isNarrationMuted) {
        if (!isNarrationMuted && steps.isNotEmpty() && selectedTab in steps.indices) {
            kotlinx.coroutines.delay(350L)
            TTSManager.speak(context, steps[selectedTab].speechText, flush = true)
        }
    }

    Dialog(
        onDismissRequest = {
            TTSManager.stop()
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .padding(12.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, SurfaceStroke, RoundedCornerShape(24.dp))
                .testTag("app_tutorial_guide_dialog"),
            color = DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(PrimaryEmerald.copy(alpha = 0.2f))
                                .border(1.dp, PrimaryEmerald.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = PrimaryEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (isHindi) "\u0910\u092A \u0917\u093E\u0907\u0921 \u0914\u0930 \u092B\u093F\u0932\u094D\u091F\u0930 \u091F\u094D\u0930\u0947\u0928\u093F\u0902\u0917" else "App & Filter Setup Guide",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = if (isHindi) "7 \u0906\u0938\u093E\u0928 \u0938\u094D\u091F\u0947\u092A\u094D\u0938 \u092E\u0947\u0902 \u092A\u0942\u0930\u093E \u0938\u0947\u091F\u0905\u092A \u0938\u0940\u0916\u0947\u0902" else "Learn full setup in 7 easy steps",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Narration Audio Mute / Unmute Toggle
                        IconButton(
                            onClick = {
                                isNarrationMuted = !isNarrationMuted
                                if (isNarrationMuted) {
                                    TTSManager.stop()
                                } else if (selectedTab in steps.indices) {
                                    TTSManager.speak(context, steps[selectedTab].speechText, flush = true)
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DarkSurfaceVariant)
                                .testTag("tutorial_mute_btn")
                        ) {
                            Icon(
                                imageVector = if (isNarrationMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                contentDescription = if (isNarrationMuted) "Unmute Narration" else "Mute Narration",
                                tint = if (isNarrationMuted) TextMuted else PrimaryEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Close button
                        IconButton(
                            onClick = {
                                TTSManager.stop()
                                onDismiss()
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DarkSurfaceVariant)
                                .testTag("tutorial_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Guide",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Step Chips Navigation (Horizontal selector)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    steps.forEachIndexed { index, step ->
                        val isSelected = selectedTab == index
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) step.accentColor.copy(alpha = 0.25f) else DarkSurfaceVariant,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) step.accentColor else SurfaceStroke
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedTab = index
                                    TTSManager.speak(context, step.speechText, flush = true)
                                }
                                .testTag("tutorial_step_tab_$index")
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Step ${index + 1}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) step.accentColor else TextMuted
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Active Step Content
                val currentStep = steps[selectedTab]
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        // Header of the active step with TTS voice replay
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = BorderStroke(1.dp, currentStep.accentColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = currentStep.accentColor.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, currentStep.accentColor.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = currentStep.badge,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp
                                            ),
                                            color = currentStep.accentColor,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            TTSManager.speak(context, currentStep.speechText, flush = true)
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(currentStep.accentColor.copy(alpha = 0.15f))
                                            .testTag("tutorial_voice_replay_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VolumeUp,
                                            contentDescription = "Voice Guide",
                                            tint = currentStep.accentColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = currentStep.title,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TextPrimary
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = currentStep.shortDesc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    // Visual Mock / Example Demo Card
                    item {
                        currentStep.mockExample()
                    }

                    // Detailed Explanation & Mechanism
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            border = BorderStroke(1.dp, SurfaceStroke),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = currentStep.accentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (isHindi) "\u0921\u093F\u091F\u0947\u0932 \u0938\u092E\u091D\u0947\u0902 (How it Works)" else "How it Works in Detail",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = currentStep.detailExplainer,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                    color = TextSecondary
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Recommended Tip Banner
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = EmeraldContainer.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = PrimaryEmerald,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = currentStep.recommendedSetting,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = OnEmeraldContainer
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Action Bar: Previous / Next & 1-Tap Apply Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTab > 0) {
                        OutlinedButton(
                            onClick = {
                                selectedTab--
                                TTSManager.speak(context, steps[selectedTab].speechText, flush = true)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(if (isHindi) "\u092A\u0940\u091B\u0947" else "Previous")
                        }
                    }

                    if (selectedTab < steps.size - 1) {
                        Button(
                            onClick = {
                                selectedTab++
                                TTSManager.speak(context, steps[selectedTab].speechText, flush = true)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = currentStep.accentColor,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .weight(if (selectedTab > 0) 1.5f else 1f)
                                .height(48.dp)
                                .testTag("tutorial_next_step_btn")
                        ) {
                            Text(
                                text = if (isHindi) "\u0905\u0917\u0932\u093E \u0938\u094D\u091F\u0947\u092A (Next)" else "Next Step",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        // Last Step: 1-Tap Apply Recommended Settings and Done
                        Button(
                            onClick = {
                                val appliedCount = applyRecommendedSettings(context)
                                val msg = when {
                                    appliedCount == 0 -> if (isHindi) {
                                        "\u092B\u093F\u0932\u094D\u091F\u0930\u094D\u0938 \u0932\u0949\u0915 \u0939\u0948\u0902\u0964 \u092A\u0939\u0932\u0947 Pass \u090F\u0915\u094D\u091F\u093F\u0935\u0947\u091F \u0915\u0930\u0947\u0902 \u092F\u093E Points \u0938\u0947 \u0905\u0928\u0932\u0949\u0915 \u0915\u0930\u0947\u0902\u0964"
                                    } else {
                                        "Filters are locked. Activate a Pass or unlock filters with Points first."
                                    }
                                    isHindi -> "\u0905\u0928\u0932\u0949\u0915 \u0915\u0940 \u0917\u0908 \u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0932\u093E\u0917\u0942 \u0939\u094B \u0917\u0908\u0902!"
                                    else -> "Recommended settings applied to unlocked filters!"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                TTSManager.stop()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryEmerald,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(48.dp)
                                .testTag("tutorial_apply_best_settings_btn")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (isHindi) "\u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0938\u0947\u091F \u0915\u0930\u0947\u0902 & \u0936\u0941\u0930\u0942 \u0915\u0930\u0947\u0902" else "Apply Best Settings & Start",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Applies ideal recommended baseline settings for Rapido captains.
 */
fun applyRecommendedSettings(context: Context): Int {
    var appliedCount = 0

    if (LicenseManager.hasAnyAutoAcceptCapability()) {
        AppSettings.setAutoAcceptEnabled(context, true)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE)) {
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMinFare(context, 50.0f)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.MIN_PER_KM_RATE)) {
        AppSettings.setMinPerKmRateEnabled(context, true)
        AppSettings.setMinPerKmRate(context, 13.0f)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE)) {
        AppSettings.setMaxPickupDistanceEnabled(context, true)
        AppSettings.setMaxPickupDistance(context, 2.5f)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.MAX_DROP_DISTANCE)) {
        AppSettings.setMaxDropDistanceEnabled(context, true)
        AppSettings.setMaxDropDistance(context, 15.0f)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE)) {
        AppSettings.setVoiceAnnouncerEnabled(context, true)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.FLOATING_OVERLAY) && PermissionUtils.canDrawOverlays(context)) {
        AppSettings.setFloatingOverlayEnabled(context, true)
        appliedCount++
    }

    AppSettings.setHomeTutorialCompleted(context, true)
    AppSettings.syncOverlayService(context)
    return appliedCount
}

/**
 * Prominent banner card to place on Home and Settings screens to re-open the tutorial guide anytime.
 */
@Composable
fun AppGuideBannerCard(
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userLang = remember { AppSettings.getVoiceLanguage(context).lowercase(Locale.ROOT) }
    val isHindi = userLang == "hi"

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenGuide() }
            .testTag("app_guide_banner_card")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            PrimaryEmerald.copy(alpha = 0.12f),
                            Color(0xFF38BDF8).copy(alpha = 0.08f)
                        )
                    )
                )
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrimaryEmerald.copy(alpha = 0.2f))
                        .border(1.dp, PrimaryEmerald.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = "Guide",
                        tint = PrimaryEmerald,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = PrimaryEmerald.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (isHindi) "\u0928\u092F\u093E \u092F\u0942\u091C\u0930 \u0917\u093E\u0907\u0921" else "NEW USER GUIDE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = PrimaryEmerald,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (isHindi) "\u0910\u092A \u091A\u0932\u093E\u0928\u093E \u0914\u0930 \u092B\u093F\u0932\u094D\u091F\u0930\u094D\u0938 \u0938\u0947\u091F \u0915\u0930\u0928\u093E \u0938\u0940\u0916\u0947\u0902" else "Learn How to Use & Set Filters",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )

                    Text(
                        text = if (isHindi)
                            "300ms \u0911\u091F\u094B-\u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F, \u20B9/KM \u0914\u0930 \u092C\u0947\u0938\u094D\u091F \u0915\u092E\u093E\u0908 \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0915\u0940 \u092A\u0942\u0930\u0940 \u0917\u093E\u0907\u0921"
                        else
                            "Step-by-step tutorial on 300ms auto-accept, \u20B9/KM rate and best filter settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Open Tutorial",
                        tint = PrimaryEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Returns comprehensive tutorial steps tailored for Indian captains.
 */
@androidx.annotation.VisibleForTesting
internal fun getTutorialSteps(context: Context, isHindi: Boolean): List<TutorialGuideStep> {
    return listOf(
        // STEP 1: How Order Master Works
        TutorialGuideStep(
            stepNumber = 1,
            icon = Icons.Default.ElectricBolt,
            badge = if (isHindi) "\u26A1 300MS \u0911\u091F\u094B-\u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F" else "\u26A1 300MS AUTO-ACCEPT",
            title = if (isHindi) "\u0911\u0930\u094D\u0921\u0930 \u092E\u093E\u0938\u094D\u091F\u0930 \u0915\u0948\u0938\u0947 \u0915\u093E\u092E \u0915\u0930\u0924\u093E \u0939\u0948?" else "How Order Master Works",
            shortDesc = if (isHindi)
                "Rapido \u092E\u0947\u0902 \u0928\u092F\u093E \u0911\u0930\u094D\u0921\u0930 \u0906\u0924\u0947 \u0939\u0940 300 \u092E\u093F\u0932\u0940\u0938\u0947\u0915\u0902\u0921 \u092E\u0947\u0902 \u0916\u0941\u0926 \u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F \u0915\u0930\u0924\u093E \u0939\u0948\u0964"
            else
                "Automatically reads incoming Rapido ride offers and accepts in 300ms without touching phone.",
            detailExplainer = if (isHindi)
                "1. \u0906\u092A \u0905\u092A\u0928\u0940 Rapido Captain \u0910\u092A \u0915\u094B \u0938\u093E\u092E\u093E\u0928\u094D\u092F \u0930\u0942\u092A \u0938\u0947 \u091A\u093E\u0932\u0942 \u0930\u0916\u0947\u0902\u0964\n" +
                        "2. \u091C\u0948\u0938\u0947 \u0939\u0940 \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u092A\u0930 \u0915\u094B\u0908 \u0928\u0908 \u0930\u093E\u0907\u0921 \u0911\u092B\u0930 \u0906\u090F\u0917\u0940, \u0911\u0930\u094D\u0921\u0930 \u092E\u093E\u0938\u094D\u091F\u0930 \u092C\u0948\u0915\u0917\u094D\u0930\u093E\u0909\u0902\u0921 \u092E\u0947\u0902 \u0909\u0938\u0915\u093E \u0915\u093F\u0930\u093E\u092F\u093E (Fare) \u0914\u0930 \u0926\u0942\u0930\u0940 (Distance) \u091A\u0947\u0915 \u0915\u0930\u0947\u0917\u093E\u0964\n" +
                        "3. \u0905\u0917\u0930 \u0930\u093E\u0907\u0921 \u0906\u092A\u0915\u0947 \u0938\u0947\u091F \u0915\u093F\u090F \u0917\u090F \u0928\u093F\u092F\u092E\u094B\u0902 \u0915\u0947 \u0905\u0928\u0941\u0938\u093E\u0930 \u0938\u0939\u0940 \u0939\u0948, \u0924\u094B \u092F\u0939 \u0915\u0947\u0935\u0932 300ms \u092E\u0947\u0902 \u0911\u091F\u094B\u092E\u0948\u091F\u093F\u0915 \u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F \u0915\u0930 \u0932\u0947\u0917\u093E!"
            else
                "1. Keep your Rapido Captain app running as usual.\n" +
                        "2. Whenever a new ride popup appears, Order Master parses fare and distance in background.\n" +
                        "3. If ride matches your filter rules, it taps accept in 300 milliseconds automatically!",
            recommendedSetting = if (isHindi)
                "\u091F\u093F\u092A: \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u092A\u0930 \u0911\u0930\u094D\u0921\u0930 \u0906\u0924\u0947 \u0939\u0940 \u092B\u094B\u0928 \u091B\u0942\u0928\u0947 \u0915\u0940 \u091C\u0930\u0942\u0930\u0924 \u0928\u0939\u0940\u0902, \u0910\u092A \u0905\u092A\u0928\u0947 \u0906\u092A \u0915\u093E\u092E \u0915\u0930\u0947\u0917\u093E\u0964"
            else
                "Tip: No need to touch your phone; app grabs high-demand rides instantly.",
            speechText = if (isHindi)
                "\u0911\u0930\u094D\u0921\u0930 \u092E\u093E\u0938\u094D\u091F\u0930 \u0915\u0948\u0938\u0947 \u0915\u093E\u092E \u0915\u0930\u0924\u093E \u0939\u0948? \u091C\u092C \u0906\u092A \u0930\u0948\u092A\u093F\u0921\u094B \u0910\u092A \u091A\u0932\u093E \u0930\u0939\u0947 \u0939\u094B\u0924\u0947 \u0939\u0948\u0902, \u0924\u094B \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u092A\u0930 \u0928\u092F\u093E \u0911\u0930\u094D\u0921\u0930 \u0906\u0924\u0947 \u0939\u0940 \u0910\u092A \u0924\u0940\u0928 \u0938\u094C \u092E\u093F\u0932\u0940\u0938\u0947\u0915\u0902\u0921 \u092E\u0947\u0902 \u0916\u0941\u0926 \u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F \u0915\u0930 \u0932\u0947\u0924\u093E \u0939\u0948\u0964"
            else
                "How Order Master works. As soon as a ride appears on your driver app, Order Master checks your filters and accepts it in 300 milliseconds.",
            accentColor = PrimaryEmerald,
            mockExample = {
                MockRideOfferDemoCard(isHindi)
            }
        ),

        // STEP 2: Accessibility Permission
        TutorialGuideStep(
            stepNumber = 2,
            icon = Icons.Default.Security,
            badge = if (isHindi) "\uD83D\uDD11 \u091C\u0930\u0942\u0930\u0940 \u092A\u0930\u092E\u093F\u0936\u0928" else "\uD83D\uDD11 REQUIRED PERMISSION",
            title = if (isHindi) "Accessibility \u0938\u0930\u094D\u0935\u093F\u0938 \u0911\u0928 \u0915\u0930\u0947\u0902" else "Enable Accessibility Service",
            shortDesc = if (isHindi)
                "\u092C\u093F\u0928\u093E \u0907\u0938 \u092A\u0930\u092E\u093F\u0936\u0928 \u0915\u0947 \u0910\u092A \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u092A\u0930 \u0930\u093E\u0907\u0921\u094D\u0938 \u0928\u0939\u0940\u0902 \u092A\u0922\u093C \u0938\u0915\u0924\u093E\u0964"
            else
                "Required for Order Master to detect ride popups and auto-accept.",
            detailExplainer = if (isHindi)
                "\u0938\u094D\u091F\u0947\u092A 1: \u0928\u0940\u091A\u0947 \u0926\u093F\u090F \u0917\u090F \u092C\u091F\u0928 \u092A\u0930 \u091F\u0948\u092A \u0915\u0930\u0915\u0947 Accessibility \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0916\u094B\u0932\u0947\u0902\u0964\n" +
                        "\u0938\u094D\u091F\u0947\u092A 2: 'Downloaded Apps' \u092F\u093E '\u0907\u0902\u0938\u094D\u091F\u0949\u0932 \u0915\u093F\u090F \u0917\u090F \u0910\u092A\u094D\u0938' \u092E\u0947\u0902 \u091C\u093E\u090F\u0902\u0964\n" +
                        "\u0938\u094D\u091F\u0947\u092A 3: 'Order Master' \u092A\u0930 \u0915\u094D\u0932\u093F\u0915 \u0915\u0930\u0915\u0947 \u0938\u094D\u0935\u093F\u091A \u0915\u094B ON \u0915\u0930\u0947\u0902 \u0914\u0930 Allow \u092A\u0930 \u091F\u0948\u092A \u0915\u0930\u0947\u0902\u0964"
            else
                "Step 1: Tap the button below to open Accessibility Settings.\n" +
                        "Step 2: Go to 'Downloaded Apps' or 'Installed Services'.\n" +
                        "Step 3: Tap 'Order Master' and toggle the switch to ON.",
            recommendedSetting = if (isHindi)
                "MIUI/Vivo/Oppo \u092F\u0942\u095B\u0930\u094D\u0938: \u0910\u092A \u0915\u094B Recents \u092E\u0947\u0902 \u0932\u0949\u0915 \u0915\u0930\u0947\u0902 \u0914\u0930 AutoStart \u0911\u0928 \u0930\u0916\u0947\u0902\u0964"
            else
                "Xiaomi/Vivo/Oppo users: Lock Order Master in Recents and enable AutoStart.",
            speechText = if (isHindi)
                "\u090F\u0915\u094D\u0938\u0947\u0938\u093F\u092C\u093F\u0932\u093F\u091F\u0940 \u0938\u0930\u094D\u0935\u093F\u0938 \u091A\u093E\u0932\u0942 \u0915\u0930\u0947\u0902\u0964 \u092B\u094B\u0928 \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u092E\u0947\u0902 \u091C\u093E\u090F\u0902, \u0921\u093E\u0909\u0928\u0932\u094B\u0921\u0947\u0921 \u0910\u092A\u094D\u0938 \u092E\u0947\u0902 \u0911\u0930\u094D\u0921\u0930 \u092E\u093E\u0938\u094D\u091F\u0930 \u0915\u094B \u091A\u0941\u0928\u0947\u0902 \u0914\u0930 \u0938\u094D\u0935\u093F\u091A \u0911\u0928 \u0915\u0930\u0947\u0902\u0964"
            else
                "Enable Accessibility Service in phone settings to allow Order Master to read ride popups.",
            accentColor = Color(0xFFF59E0B),
            mockExample = {
                MockPermissionFlowCard(context, isHindi)
            }
        ),

        // STEP 3: Minimum Fare Filter
        TutorialGuideStep(
            stepNumber = 3,
            icon = Icons.Default.CurrencyRupee,
            badge = if (isHindi) "\uD83D\uDCB0 \u0928\u094D\u092F\u0942\u0928\u0924\u092E \u0915\u093F\u0930\u093E\u092F\u093E (MIN FARE)" else "\uD83D\uDCB0 MINIMUM FARE FILTER",
            title = if (isHindi) "\u091B\u094B\u091F\u0947 \u0914\u0930 \u0918\u093E\u091F\u0947 \u0935\u093E\u0932\u0947 \u0911\u0930\u094D\u0921\u0930\u094D\u0938 \u0930\u094B\u0915\u0947\u0902" else "Filter Low-Paying Rides",
            shortDesc = if (isHindi)
                "\u0905\u0917\u0930 \u0906\u092A \u20B950 \u0938\u0947\u091F \u0915\u0930\u0947\u0902\u0917\u0947 \u0924\u094B \u20B930-\u20B940 \u0915\u0947 \u0938\u0938\u094D\u0924\u0947 \u0911\u0930\u094D\u0921\u0930\u094D\u0938 \u0916\u0941\u0926 \u091B\u0942\u091F \u091C\u093E\u090F\u0902\u0917\u0947\u0964"
            else
                "Ignore orders below this threshold to maximize your trip earnings.",
            detailExplainer = if (isHindi)
                "\u0905\u0915\u094D\u0938\u0930 \u20B925 \u092F\u093E \u20B935 \u0915\u0947 \u091B\u094B\u091F\u0947 \u0911\u0930\u094D\u0921\u0930\u094D\u0938 \u0909\u0920\u093E\u0928\u0947 \u092A\u0930 \u091F\u093E\u0907\u092E \u0914\u0930 \u092A\u0947\u091F\u094D\u0930\u094B\u0932 \u0926\u094B\u0928\u094B\u0902 \u0916\u0930\u093E\u092C \u0939\u094B\u0924\u0947 \u0939\u0948\u0902\u0964\n" +
                        "\u091C\u092C \u0906\u092A 'Minimum Fare Filter' \u091A\u093E\u0932\u0942 \u0915\u0930\u0915\u0947 \u20B950 \u092F\u093E \u20B960 \u0938\u0947\u091F \u0915\u0930\u0924\u0947 \u0939\u0948\u0902, \u0924\u094B \u0910\u092A \u0938\u093F\u0930\u094D\u092B \u20B950 \u0938\u0947 \u090A\u092A\u0930 \u0935\u093E\u0932\u0947 \u092C\u0921\u093C\u0947 \u0911\u0930\u094D\u0921\u0930\u094D\u0938 \u0939\u0940 \u0932\u0947\u0917\u093E\u0964 \u091B\u094B\u091F\u0947 \u0911\u0930\u094D\u0921\u0930\u094D\u0938 \u0915\u094B \u0911\u091F\u094B\u092E\u0948\u091F\u093F\u0915 \u091B\u094B\u0921\u093C \u0926\u0947\u0917\u093E!"
            else
                "Short \u20B925 or \u20B935 rides waste valuable peak-hour time and fuel.\n" +
                        "Setting Minimum Fare to \u20B950 or \u20B960 ensures you only get assigned profitable rides.",
            recommendedSetting = if (isHindi)
                "\u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917: \u20B950 \u0938\u0947 \u20B970 \u0915\u0947 \u092C\u0940\u091A \u0930\u0916\u0947\u0902\u0964"
            else
                "Recommended Setting: Set between \u20B950 and \u20B970.",
            speechText = if (isHindi)
                "\u0928\u094D\u092F\u0942\u0928\u0924\u092E \u0915\u093F\u0930\u093E\u092F\u093E \u092B\u093F\u0932\u094D\u091F\u0930\u0964 \u0905\u0917\u0930 \u0906\u092A \u092A\u091A\u093E\u0938 \u0930\u0941\u092A\u092F\u0947 \u0938\u0947\u091F \u0915\u0930\u0947\u0902\u0917\u0947, \u0924\u094B \u0915\u092E \u092A\u0948\u0938\u0947 \u0935\u093E\u0932\u0947 \u091B\u094B\u091F\u0947 \u0911\u0930\u094D\u0921\u0930 \u0905\u092A\u0928\u0947 \u0906\u092A \u091B\u0942\u091F \u091C\u093E\u090F\u0902\u0917\u0947 \u0914\u0930 \u0938\u093F\u0930\u094D\u092B \u0905\u091A\u094D\u091B\u0940 \u0915\u092E\u093E\u0908 \u0935\u093E\u0932\u0947 \u0911\u0930\u094D\u0921\u0930 \u0906\u090F\u0902\u0917\u0947\u0964"
            else
                "Minimum fare filter. Set minimum fare to fifty rupees or more to avoid low paying rides.",
            accentColor = Color(0xFF10B981),
            mockExample = {
                MockFareComparisonCard(isHindi)
            }
        ),

        // STEP 4: Price Per Kilometer (\u20B9/KM Rate)
        TutorialGuideStep(
            stepNumber = 4,
            icon = Icons.Default.TrendingUp,
            badge = if (isHindi) "\uD83D\uDCC8 \u0930\u0947\u091F \u092A\u094D\u0930\u0924\u093F \u0915\u093F\u092E\u0940 (\u20B9/KM)" else "\uD83D\uDCC8 RATE PER KM (\u20B9/KM)",
            title = if (isHindi) "\u0939\u0930 \u0915\u093F\u0932\u094B\u092E\u0940\u091F\u0930 \u092A\u0930 \u092B\u093F\u0915\u094D\u0938 \u092E\u0941\u0928\u093E\u092B\u093E \u0915\u092E\u093E\u090F\u0902" else "Guaranteed Profit per Kilometer",
            shortDesc = if (isHindi)
                "\u0932\u0902\u092C\u0940 \u0926\u0942\u0930\u0940 \u092A\u0930 \u0915\u092E \u0915\u093F\u0930\u093E\u092F\u093E \u0926\u0947\u0928\u0947 \u0935\u093E\u0932\u0940 \u0918\u093E\u091F\u0947 \u0915\u0940 \u0930\u093E\u0907\u0921\u094D\u0938 \u0938\u0947 \u092C\u091A\u0947\u0902\u0964"
            else
                "Ensures every ride pays enough to cover fuel and driver profit.",
            detailExplainer = if (isHindi)
                "\u092B\u0949\u0930\u094D\u092E\u0942\u0932\u093E: \u0915\u0941\u0932 \u0915\u093F\u0930\u093E\u092F\u093E \u00F7 \u0915\u0941\u0932 \u0926\u0942\u0930\u0940 = \u20B9/KM \u0930\u0947\u091F\u0964\n" +
                        "\u0909\u0926\u093E\u0939\u0930\u0923:\n" +
                        "\u274C \u20B960 \u0915\u0940 \u0930\u093E\u0907\u0921 10 \u0915\u093F\u0932\u094B\u092E\u0940\u091F\u0930 \u0915\u0947 \u0932\u093F\u090F = \u0938\u093F\u0930\u094D\u092B \u20B96/KM (\u092A\u0947\u091F\u094D\u0930\u094B\u0932 \u092E\u0947\u0902 \u0918\u093E\u091F\u093E!)\n" +
                        "\u2705 \u20B9120 \u0915\u0940 \u0930\u093E\u0907\u0921 8 \u0915\u093F\u0932\u094B\u092E\u0940\u091F\u0930 \u0915\u0947 \u0932\u093F\u090F = \u20B915/KM (\u092C\u0902\u092A\u0930 \u092E\u0941\u0928\u093E\u092B\u093E! \uD83D\uDE80)\n" +
                        "\u091C\u092C \u0906\u092A \u20B913/KM \u0938\u0947\u091F \u0915\u0930\u0924\u0947 \u0939\u0948\u0902, \u0924\u094B \u0910\u092A \u0938\u093F\u0930\u094D\u092B \u0935\u0939\u0940 \u0911\u0930\u094D\u0921\u0930 \u0932\u0947\u0917\u093E \u091C\u093F\u0938\u092E\u0947\u0902 \u0939\u0930 \u0915\u093F\u092E\u0940 \u092A\u0930 \u20B913 \u092F\u093E \u0909\u0938\u0938\u0947 \u091C\u094D\u092F\u093E\u0926\u093E \u0915\u092E\u093E\u0908 \u0939\u094B\u0964"
            else
                "Formula: Total Fare \u00F7 Total Distance = Rate per KM.\n" +
                        "Example:\n" +
                        "\u274C \u20B960 for 10 KM = \u20B96/KM (Fuel Loss!)\n" +
                        "\u2705 \u20B9120 for 8 KM = \u20B915/KM (High Profit!)\n" +
                        "Setting \u20B913/KM ensures Order Master only accepts rides that pay well per kilometer.",
            recommendedSetting = if (isHindi)
                "\u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917: \u092C\u093E\u0907\u0915 \u0915\u0947 \u0932\u093F\u090F \u20B912-\u20B914/KM \u0914\u0930 \u0911\u091F\u094B \u0915\u0947 \u0932\u093F\u090F \u20B915-\u20B918/KM \u0930\u0916\u0947\u0902\u0964"
            else
                "Recommended Setting: \u20B912-\u20B914/KM for Bikes, \u20B915-\u20B918/KM for Auto Rickshaws.",
            speechText = if (isHindi)
                "\u0930\u0947\u091F \u092A\u094D\u0930\u0924\u093F \u0915\u093F\u0932\u094B\u092E\u0940\u091F\u0930 \u092B\u093F\u0932\u094D\u091F\u0930\u0964 \u0938\u093F\u0930\u094D\u092B \u0915\u0941\u0932 \u0915\u093F\u0930\u093E\u092F\u093E \u092E\u0924 \u0926\u0947\u0916\u093F\u090F\u0964 \u092F\u0939 \u092B\u093F\u0932\u094D\u091F\u0930 \u0938\u0941\u0928\u093F\u0936\u094D\u091A\u093F\u0924 \u0915\u0930\u0924\u093E \u0939\u0948 \u0915\u093F \u0939\u0930 \u0915\u093F\u0932\u094B\u092E\u0940\u091F\u0930 \u092A\u0930 \u0906\u092A\u0915\u094B \u0915\u092E \u0938\u0947 \u0915\u092E \u092C\u093E\u0930\u0939 \u092F\u093E \u092A\u0902\u0926\u094D\u0930\u0939 \u0930\u0941\u092A\u092F\u0947 \u0915\u0940 \u0915\u092E\u093E\u0908 \u0939\u094B\u0964"
            else
                "Rate per kilometer filter ensures every kilometer driven generates solid profit after fuel costs.",
            accentColor = Color(0xFF38BDF8),
            mockExample = {
                MockRatePerKmCard(isHindi)
            }
        ),

        // STEP 5: Max Distance & Pickup Distance
        TutorialGuideStep(
            stepNumber = 5,
            icon = Icons.Default.NearMe,
            badge = if (isHindi) "\uD83D\uDCCD \u092A\u093F\u0915\u0905\u092A \u0914\u0930 \u0926\u0942\u0930\u0940 \u092B\u093F\u0932\u094D\u091F\u0930\u094D\u0938" else "\uD83D\uDCCD PICKUP & DISTANCE LIMITS",
            title = if (isHindi) "\u0926\u0942\u0930 \u0915\u093E \u092A\u093F\u0915\u0905\u092A \u0914\u0930 \u0932\u0902\u092C\u093E \u0938\u092B\u0930 \u0930\u094B\u0915\u0947\u0902" else "Limit Pickup and Drop Distance",
            shortDesc = if (isHindi)
                "2.5 KM \u0938\u0947 \u091C\u094D\u092F\u093E\u0926\u093E \u0926\u0942\u0930 \u0915\u093E \u092A\u093F\u0915\u0905\u092A \u0928 \u0932\u0947\u0902 \u0924\u093E\u0915\u093F \u092A\u0947\u091F\u094D\u0930\u094B\u0932 \u0914\u0930 \u091F\u093E\u0907\u092E \u092C\u091A\u0947\u0964"
            else
                "Avoid wasting fuel on long pickups and keep drop destinations within city limits.",
            detailExplainer = if (isHindi)
                "1. \u092E\u0948\u0915\u094D\u0938\u093F\u092E\u092E \u092A\u093F\u0915\u0905\u092A \u0926\u0942\u0930\u0940: \u0905\u0917\u0930 \u0930\u093E\u0907\u0921\u0930 4-5 KM \u0926\u0942\u0930 \u0939\u0948 \u0924\u094B \u0935\u0939\u093E\u0902 \u0924\u0915 \u0916\u093E\u0932\u0940 \u0917\u093E\u0921\u093C\u0940 \u091A\u0932\u093E\u0928\u0947 \u092E\u0947\u0902 \u092A\u0947\u091F\u094D\u0930\u094B\u0932 \u0916\u0930\u094D\u091A \u0939\u094B\u0924\u093E \u0939\u0948\u0964 \u0907\u0938\u0947 2.0 \u0938\u0947 2.5 KM \u092A\u0930 \u0930\u0916\u0947\u0902\u0964\n" +
                        "2. \u0905\u0927\u093F\u0915\u0924\u092E \u0921\u094D\u0930\u0949\u092A \u0926\u0942\u0930\u0940: \u0905\u0917\u0930 \u0906\u092A \u0936\u0939\u0930 \u0915\u0947 \u092C\u093E\u0939\u0930 \u0928\u0939\u0940\u0902 \u091C\u093E\u0928\u093E \u091A\u093E\u0939\u0924\u0947, \u0924\u094B \u0907\u0938\u0947 12 \u0938\u0947 15 KM \u092A\u0930 \u0938\u0947\u091F \u0915\u0930\u0947\u0902 \u0924\u093E\u0915\u093F \u0932\u0902\u092C\u0940 \u0914\u0930 \u0905\u0928\u091A\u093E\u0939\u0940 \u092F\u093E\u0924\u094D\u0930\u093E\u0913\u0902 \u0938\u0947 \u092C\u091A\u093E \u091C\u093E \u0938\u0915\u0947\u0964"
            else
                "1. Max Pickup Distance: Don't drive 4-5 KM empty just to pick up a passenger. Keep it at 2.0 to 2.5 KM.\n" +
                        "2. Max Drop Distance: If you prefer staying within the city, cap total distance at 12 to 15 KM.",
            recommendedSetting = if (isHindi)
                "\u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917: \u092A\u093F\u0915\u0905\u092A 2.0 - 2.5 KM, \u0915\u0941\u0932 \u0926\u0942\u0930\u0940 12 - 15 KM\u0964"
            else
                "Recommended: Pickup 2.0 - 2.5 KM, Max Distance 12 - 15 KM.",
            speechText = if (isHindi)
                "\u092A\u093F\u0915\u0905\u092A \u0914\u0930 \u0926\u0942\u0930\u0940 \u092B\u093F\u0932\u094D\u091F\u0930\u094D\u0938\u0964 \u092A\u0948\u0938\u0947\u0902\u091C\u0930 \u0905\u0917\u0930 \u092C\u0939\u0941\u0924 \u0926\u0942\u0930 \u0939\u0948 \u0924\u094B \u0935\u0939\u093E\u0902 \u0924\u0915 \u0916\u093E\u0932\u0940 \u091C\u093E\u0928\u0947 \u092E\u0947\u0902 \u0928\u0941\u0915\u0938\u093E\u0928 \u0939\u094B\u0924\u093E \u0939\u0948\u0964 \u092A\u093F\u0915\u0905\u092A \u0926\u0942\u0930\u0940 \u0915\u094B \u0926\u094B \u092F\u093E \u0922\u093E\u0908 \u0915\u093F\u0932\u094B\u092E\u0940\u091F\u0930 \u092A\u0930 \u0930\u0916\u0947\u0902\u0964"
            else
                "Pickup distance filter prevents long empty runs. Set pickup distance to 2.5 KM max.",
            accentColor = Color(0xFFA855F7),
            mockExample = {
                MockDistanceTipsCard(isHindi)
            }
        ),

        // STEP 6: Voice Announcer (Eyes-Free Driving)
        TutorialGuideStep(
            stepNumber = 6,
            icon = Icons.Default.VolumeUp,
            badge = if (isHindi) "\uD83D\uDD0A \u0906\u0935\u093E\u091C\u093C \u0909\u0926\u094D\u0918\u094B\u0937\u0915 (VOICE ANNOUNCER)" else "\uD83D\uDD0A VOICE ANNOUNCER",
            title = if (isHindi) "\u092C\u093F\u0928\u093E \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u0926\u0947\u0916\u0947 \u0911\u0930\u094D\u0921\u0930 \u0915\u0940 \u092A\u0942\u0930\u0940 \u0921\u093F\u091F\u0947\u0932 \u0938\u0941\u0928\u0947\u0902" else "Eyes-Free Voice Announcements",
            shortDesc = if (isHindi)
                "\u0915\u093F\u0930\u093E\u092F\u093E, \u092A\u093F\u0915\u0905\u092A \u0926\u0942\u0930\u0940 \u0935 \u0938\u094D\u0925\u093E\u0928, \u0914\u0930 \u0921\u094D\u0930\u0949\u092A \u0926\u0942\u0930\u0940 \u0935 \u0938\u094D\u0925\u093E\u0928 \u0938\u093E\u092B \u092C\u094B\u0932\u0915\u0930 \u092C\u0924\u093E\u0924\u093E \u0939\u0948\u0964"
            else
                "Automatically speaks Fare, Pickup distance/location, and Drop distance/location.",
            detailExplainer = if (isHindi)
                "1. \u0917\u093E\u0921\u093C\u0940 \u091A\u0932\u093E\u0924\u0947 \u0938\u092E\u092F \u092C\u093E\u0930-\u092C\u093E\u0930 \u092E\u094B\u092C\u093E\u0907\u0932 \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u0926\u0947\u0916\u0928\u093E \u0916\u0924\u0930\u0928\u093E\u0915 \u0939\u094B\u0924\u093E \u0939\u0948\u0964\n" +
                        "2. \u0928\u092F\u093E \u0911\u0930\u094D\u0921\u0930 \u0906\u0924\u0947 \u0939\u0940 \u0910\u092A \u0906\u092A\u0915\u0940 \u091A\u0941\u0928\u0940 \u0939\u0941\u0908 \u092D\u093E\u0937\u093E (\u0939\u093F\u0928\u094D\u0926\u0940, \u0A97\u0AC1\u0A9C\u0AB0\u0ABE\u0AA4\u0AC0, English) \u092E\u0947\u0902 \u0938\u093E\u092B \u0906\u0935\u093E\u091C\u093C \u092E\u0947\u0902 \u092C\u094B\u0932\u0924\u093E \u0939\u0948:\n" +
                        "  \u2022 \u0915\u093F\u0930\u093E\u092F\u093E: \u20B945\n" +
                        "  \u2022 \u092A\u093F\u0915\u0905\u092A \u0926\u0942\u0930\u0940: 0.6 km City Centre\n" +
                        "  \u2022 \u0921\u094D\u0930\u0949\u092A \u0926\u0942\u0930\u0940: 2.4 km Rajkot railway station\n" +
                        "3. \u0907\u0938\u0938\u0947 \u0906\u092A \u092C\u093F\u0928\u093E \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u0926\u0947\u0916\u0947 \u0924\u0941\u0930\u0902\u0924 \u0938\u092E\u091D \u0938\u0915\u0924\u0947 \u0939\u0948\u0902 \u0915\u093F \u0911\u0930\u094D\u0921\u0930 \u0915\u093F\u0924\u0928\u093E \u092B\u093E\u092F\u0926\u0947\u092E\u0902\u0926 \u0939\u0948\u0964"
            else
                "1. Looking at your phone while driving is dangerous.\n" +
                        "2. Order Master clearly announces all 5 vital details:\n" +
                        "  \u2022 Fare: \u20B945\n" +
                        "  \u2022 Pickup: 0.6 km City Centre\n" +
                        "  \u2022 Drop: 2.4 km Rajkot railway station\n" +
                        "3. Supports Hindi, Gujarati, English and regional Indian languages.",
            recommendedSetting = if (isHindi)
                "\u091F\u093F\u092A: \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u092E\u0947\u0902 \u0905\u092A\u0928\u0940 \u092A\u0938\u0902\u0926\u0940\u0926\u093E \u092D\u093E\u0937\u093E (\u0939\u093F\u0928\u094D\u0926\u0940/\u0917\u0941\u091C\u0930\u093E\u0924\u0940/English) \u091A\u0941\u0928\u0947\u0902 \u0914\u0930 \u0935\u0949\u092F\u0938 \u0909\u0926\u094D\u0918\u094B\u0937\u0915 \u0911\u0928 \u0930\u0916\u0947\u0902\u0964"
            else
                "Tip: Keep Voice Announcer enabled with your preferred regional language.",
            speechText = if (isHindi)
                "\u0906\u0935\u093E\u091C\u093C \u0909\u0926\u094D\u0918\u094B\u0937\u0915 \u092B\u0940\u091A\u0930\u0964 \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u092A\u0930 \u0928\u092F\u093E \u0911\u0930\u094D\u0921\u0930 \u0906\u0924\u0947 \u0939\u0940 \u0910\u092A \u0915\u093F\u0930\u093E\u092F\u093E, \u092A\u093F\u0915\u0905\u092A \u0926\u0942\u0930\u0940 \u0914\u0930 \u0921\u094D\u0930\u0949\u092A \u0932\u094B\u0915\u0947\u0936\u0928 \u092C\u094B\u0932\u0915\u0930 \u0938\u0941\u0928\u093E\u0924\u093E \u0939\u0948, \u0924\u093E\u0915\u093F \u0917\u093E\u0921\u093C\u0940 \u091A\u0932\u093E\u0924\u0947 \u0938\u092E\u092F \u0906\u092A\u0915\u094B \u092B\u094B\u0928 \u0915\u0940 \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u0928 \u0926\u0947\u0916\u0928\u0940 \u092A\u0921\u093C\u0947\u0964"
            else
                "Voice announcer feature. Whenever a ride appears, Order Master speaks the fare, pickup distance, and drop destination aloud so you don't need to look at your phone.",
            accentColor = Color(0xFFF59E0B),
            mockExample = {
                MockVoiceAnnouncementDemoCard(isHindi)
            }
        ),

        // STEP 7: Floating Widget, OEM Lock & 1-Tap Ready Setup
        TutorialGuideStep(
            stepNumber = 7,
            icon = Icons.Default.Layers,
            badge = if (isHindi) "\uD83D\uDE80 \u092B\u094D\u0932\u094B\u091F\u093F\u0902\u0917 \u092C\u091F\u0928 & 1-\u091F\u0948\u092A \u0930\u0947\u0921\u0940" else "\uD83D\uDE80 FLOATING WIDGET & READY",
            title = if (isHindi) "\u092B\u094D\u0932\u094B\u091F\u093F\u0902\u0917 \u092C\u091F\u0928, OEM \u092C\u0948\u0915\u0917\u094D\u0930\u093E\u0909\u0902\u0921 \u0932\u0949\u0915 & 1-\u0915\u094D\u0932\u093F\u0915 \u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938" else "Floating Widget, OEM Lock & 1-Tap Setup",
            shortDesc = if (isHindi)
                "Xiaomi, Vivo, Oppo \u092E\u0947\u0902 \u0910\u092A \u0915\u094B Recents \u092E\u0947\u0902 \u0932\u0949\u0915 \u0915\u0930\u0947\u0902 \u0914\u0930 1-\u0915\u094D\u0932\u093F\u0915 \u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0932\u093E\u0917\u0942 \u0915\u0930\u0947\u0902\u0964"
            else
                "Lock in Recents, enable AutoStart, and apply best driver settings in 1-tap.",
            detailExplainer = if (isHindi)
                "1. \u092B\u094D\u0932\u094B\u091F\u093F\u0902\u0917 \u092C\u092C\u0932: Rapido \u091A\u0932\u093E\u0924\u0947 \u0938\u092E\u092F \u0938\u094D\u0915\u094D\u0930\u0940\u0928 \u092A\u0930 \u090A\u092A\u0930 \u0924\u0948\u0930\u0924\u093E \u0930\u0939\u0924\u093E \u0939\u0948\u0964 \u090F\u0915 \u091F\u0948\u092A \u0938\u0947 \u0911\u091F\u094B-\u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F \u091A\u093E\u0932\u0942 \u092F\u093E \u092C\u0902\u0926 \u0915\u0930\u0947\u0902\u0964\n" +
                        "2. Xiaomi/Vivo/Oppo/Realme \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938: \u0910\u092A \u0915\u094B \u0930\u0940\u0938\u0947\u0902\u091F\u094D\u0938 (Recents) \u092E\u0947\u0902 \u0924\u093E\u0932\u093E (Lock) \u0932\u0917\u093E\u090F\u0902 \u0914\u0930 AutoStart \u0911\u0928 \u0915\u0930\u0947\u0902 \u0924\u093E\u0915\u093F \u092C\u0948\u0915\u0917\u094D\u0930\u093E\u0909\u0902\u0921 \u092E\u0947\u0902 \u0938\u0930\u094D\u0935\u093F\u0938 \u092C\u0902\u0926 \u0928 \u0939\u094B\u0964\n" +
                        "3. 1-\u0915\u094D\u0932\u093F\u0915 \u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938: \u0928\u0940\u091A\u0947 \u0926\u093F\u090F \u0917\u090F \u0939\u0930\u0947 \u092C\u091F\u0928 \u092A\u0930 \u091F\u0948\u092A \u0915\u0930\u0924\u0947 \u0939\u0940 \u0938\u092C\u0938\u0947 \u0905\u091A\u094D\u091B\u0940 \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0905\u092A\u0928\u0947 \u0906\u092A \u0932\u093E\u0917\u0942 \u0939\u094B \u091C\u093E\u090F\u0902\u0917\u0940!"
            else
                "1. Floating Bubble: Hovers above your driver app for instant pause/play.\n" +
                        "2. OEM Battery Setup: Lock Order Master in Recents and enable AutoStart on Xiaomi/Vivo/Oppo.\n" +
                        "3. 1-Click Best Setup: Tap the green button below to automatically apply ideal filters and start earning!",
            recommendedSetting = if (isHindi)
                "\u0938\u092C \u0924\u0948\u092F\u093E\u0930 \u0939\u0948! \u0928\u0940\u091A\u0947 '\u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0932\u093E\u0917\u0942 \u0915\u0930\u0947\u0902' \u092A\u0930 \u091F\u0948\u092A \u0915\u0930\u0915\u0947 \u0924\u0941\u0930\u0902\u0924 \u0915\u092E\u093E\u0908 \u0936\u0941\u0930\u0942 \u0915\u0930\u0947\u0902\u0964"
            else
                "All ready! Tap 'Apply Best Settings' below to get started immediately.",
            speechText = if (isHindi)
                "\u0938\u092C \u0924\u0948\u092F\u093E\u0930 \u0939\u0948\u0964 \u092B\u094D\u0932\u094B\u091F\u093F\u0902\u0917 \u092C\u091F\u0928 \u0938\u0947 \u0910\u092A \u0915\u094B \u0915\u0902\u091F\u094D\u0930\u094B\u0932 \u0915\u0930\u0947\u0902, \u0914\u0930 \u0905\u0917\u0930 \u0906\u092A\u0915\u093E \u092B\u094B\u0928 \u0936\u093E\u0913\u092E\u0940, \u0935\u093F\u0935\u094B \u092F\u093E \u0913\u092A\u094D\u092A\u094B \u0939\u0948 \u0924\u094B \u0907\u0938\u0947 \u0930\u0940\u0938\u0947\u0902\u091F\u094D\u0938 \u092E\u0947\u0902 \u0932\u0949\u0915 \u0930\u0916\u0947\u0902\u0964 \u0928\u0940\u091A\u0947 \u0926\u093F\u090F \u0917\u090F \u092C\u091F\u0928 \u0938\u0947 \u092C\u0947\u0938\u094D\u091F \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0932\u093E\u0917\u0942 \u0915\u0930\u0947\u0902 \u0914\u0930 \u0915\u092E\u093E\u0908 \u0936\u0941\u0930\u0942 \u0915\u0930\u0947\u0902\u0964"
            else
                "You are ready. Use the floating overlay to control auto accept, lock the app in recents, and tap below to apply recommended settings.",
            accentColor = PrimaryEmerald,
            mockExample = {
                MockFloatingWidgetDemoCard(isHindi)
            }
        )
    )
}

@androidx.annotation.VisibleForTesting
internal fun getVoiceDemoFieldLabels(isHindi: Boolean): List<String> {
    return if (isHindi) {
        listOf(
            "1. \u0915\u093F\u0930\u093E\u092F\u093E (Fare):",
            "2. \u092A\u093F\u0915\u0905\u092A \u0926\u0942\u0930\u0940:",
            "3. \u092A\u093F\u0915\u0905\u092A \u0938\u094D\u0925\u093E\u0928:",
            "4. \u0921\u094D\u0930\u0949\u092A \u0926\u0942\u0930\u0940:",
            "5. \u0921\u094D\u0930\u0949\u092A \u0938\u094D\u0925\u093E\u0928:"
        )
    } else {
        listOf(
            "1. Fare:",
            "2. Pickup Distance:",
            "3. Pickup Location:",
            "4. Drop Distance:",
            "5. Drop Location:"
        )
    }
}

// =================================================================================================
// VISUAL DEMO CARDS FOR EACH TUTORIAL STEP
// =================================================================================================

@Composable
private fun MockRideOfferDemoCard(isHindi: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.5.dp, PrimaryEmerald.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                    )
                    Text(
                        text = "Rapido Captain Ride Request",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextSecondary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = EmeraldContainer
                ) {
                    Text(
                        text = "\u2B50 4.8 Rating",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = OnEmeraldContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "\u20B995.00",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black
                        ),
                        color = PrimaryEmerald
                    )
                    Text(
                        text = "5.4 KM \u2022 Pickup: 0.8 KM",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PrimaryEmerald,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isHindi) "300ms \u092E\u0947\u0902 \u0911\u091F\u094B-\u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F" else "300ms Accepted",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                color = Color.Black
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MockPermissionFlowCard(context: Context, isHindi: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isHindi) "\u0938\u0930\u094D\u0935\u093F\u0938 \u091A\u093E\u0932\u0942 \u0915\u0930\u0928\u0947 \u0915\u0947 3 \u0906\u0938\u093E\u0928 \u0915\u0926\u092E:" else "3 Easy Steps to Enable Service:",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFFF59E0B)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("1", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Text(
                    text = if (isHindi) "Settings \u27A1\uFE0F Accessibility (\u092A\u0939\u0941\u0902\u091A \u092F\u094B\u0917\u094D\u092F\u0924\u093E)" else "Open Settings \u27A1\uFE0F Accessibility",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("2", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Text(
                    text = if (isHindi) "Downloaded Apps \u27A1\uFE0F 'Order Master' \u091A\u0941\u0928\u0947\u0902" else "Downloaded Apps \u27A1\uFE0F Tap 'Order Master'",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("3", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Text(
                    text = if (isHindi) "'Use Order Master' \u0915\u094B ON \u0915\u0930\u0947\u0902 \u0914\u0930 Allow \u0926\u092C\u093E\u090F\u0902" else "Turn ON 'Use Order Master' & Allow",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    PermissionUtils.invokeRapidoAccessibilityService(context)
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF59E0B),
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isHindi) "\u0905\u092D\u0940 Accessibility \u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938 \u0916\u094B\u0932\u0947\u0902" else "Open Accessibility Settings Now",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MockFareComparisonCard(isHindi: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, SurfaceStroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (isHindi) "\u0915\u093F\u0930\u093E\u092F\u093E \u0924\u0941\u0932\u0928\u093E (Example):" else "Fare Comparison (Example):",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )

            // Bad Order (Skipped)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFEF4444).copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("\u20B935.00 (3.2 KM)", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = if (isHindi) "\u0915\u092E \u0915\u093F\u0930\u093E\u092F\u093E (\u0918\u093E\u091F\u093E)" else "Low Fare (Loss)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF4444)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.25f)
                    ) {
                        Text(
                            text = if (isHindi) "\u274C \u091B\u094B\u0921\u093C \u0926\u093F\u092F\u093E (Skipped)" else "\u274C Skipped",
                            color = Color(0xFFEF4444),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Good Order (Accepted)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PrimaryEmerald.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("\u20B985.00 (5.0 KM)", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = if (isHindi) "\u0905\u091A\u094D\u091B\u093E \u0915\u093F\u0930\u093E\u092F\u093E (\u092D\u0930\u092A\u0942\u0930 \u0915\u092E\u093E\u0908)" else "High Fare (Profitable)",
                            style = MaterialTheme.typography.bodySmall,
                            color = PrimaryEmerald
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PrimaryEmerald
                    ) {
                        Text(
                            text = if (isHindi) "\u2705 \u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F (Accepted)" else "\u2705 Accepted",
                            color = Color.Black,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MockRatePerKmCard(isHindi: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isHindi) "\u20B9/KM \u0930\u0947\u091F \u0915\u0948\u0932\u0915\u0941\u0932\u0947\u0936\u0928 (\u092E\u0941\u0928\u093E\u092B\u093E \u091A\u0947\u0915):" else "\u20B9/KM Calculation Example:",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF38BDF8)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Calculation 1: Bad
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceVariant,
                    border = BorderStroke(1.dp, SurfaceStroke),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("\u20B960 \u00F7 10 KM", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("= \u20B96.0 / KM", color = Color(0xFFEF4444), fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = if (isHindi) "\u0918\u093E\u091F\u0947 \u0915\u093E \u0938\u094C\u0926\u093E \u26A0\uFE0F" else "Fuel Loss \u26A0\uFE0F",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Calculation 2: Good
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PrimaryEmerald.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("\u20B9120 \u00F7 8 KM", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("= \u20B915.0 / KM", color = PrimaryEmerald, fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = if (isHindi) "\u092C\u0902\u092A\u0930 \u092E\u0941\u0928\u093E\u092B\u093E \uD83D\uDE80" else "High Profit \uD83D\uDE80",
                            fontSize = 11.sp,
                            color = OnEmeraldContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MockDistanceTipsCard(isHindi: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFA855F7).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = null,
                        tint = Color(0xFFA855F7),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column {
                    Text(
                        text = if (isHindi) "\u092E\u0948\u0915\u094D\u0938\u093F\u092E\u092E \u092A\u093F\u0915\u0905\u092A \u0926\u0942\u0930\u0940: 2.5 KM" else "Max Pickup: 2.5 KM",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = if (isHindi) "\u092A\u0948\u0938\u0947\u0902\u091C\u0930 \u0924\u0915 \u092A\u0939\u0941\u0902\u091A\u0928\u0947 \u092E\u0947\u0902 \u092A\u0947\u091F\u094D\u0930\u094B\u0932 \u0914\u0930 \u0938\u092E\u092F \u0928 \u0917\u0902\u0935\u093E\u090F\u0902" else "Save fuel by avoiding long empty pickups",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF38BDF8).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column {
                    Text(
                        text = if (isHindi) "\u0905\u0927\u093F\u0915\u0924\u092E \u0915\u0941\u0932 \u0926\u0942\u0930\u0940: 15.0 KM" else "Max Distance: 15.0 KM",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = if (isHindi) "\u0936\u0939\u0930 \u0938\u0947 \u092C\u093E\u0939\u0930 \u0905\u0928\u091A\u093E\u0939\u0947 \u0932\u0902\u092C\u0947 \u0930\u0942\u091F\u094D\u0938 \u092A\u0930 \u091C\u093E\u0928\u0947 \u0938\u0947 \u092C\u091A\u0947\u0902" else "Keep trips within convenient city perimeter",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun MockVoiceAnnouncementDemoCard(isHindi: Boolean) {
    val context = LocalContext.current
    val fieldLabels = remember(isHindi) { getVoiceDemoFieldLabels(isHindi) }
    val sampleAnnouncement = if (isHindi) {
        "\u0915\u093F\u0930\u093E\u092F\u093E 45 \u0930\u0941\u092A\u092F\u0947\u0964 \u092A\u093F\u0915\u0905\u092A 0.6 \u0915\u093F\u0932\u094B\u092E\u0940\u091F\u0930 City Centre\u0964 \u0921\u094D\u0930\u0949\u092A 2.4 \u0915\u093F\u0932\u094B\u092E\u0940\u091F\u0930 Rajkot railway station\u0964"
    } else {
        "Fare 45 rupees. Pickup 0.6 km City Centre. Drop 2.4 km Rajkot railway station."
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: 5 fields preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF59E0B))
                    )
                    Text(
                        text = if (isHindi) "5 \u092E\u0941\u0916\u094D\u092F \u092B\u0940\u0932\u094D\u0921\u094D\u0938 \u0915\u093E \u0935\u0949\u0907\u0938 \u0921\u0947\u092E\u094B" else "5-Field Voice Demo",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF59E0B)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = if (isHindi) "\uD83D\uDD0A \u0932\u093E\u0907\u0935 \u091F\u0947\u0938\u094D\u091F" else "\uD83D\uDD0A Live Test",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF59E0B),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Visual breakdown of the 5 fields
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DarkSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = fieldLabels[0],
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "\u20B945 (Auto Boost)",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Black,
                                color = PrimaryEmerald
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = fieldLabels[1],
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "0.6 km",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = fieldLabels[2],
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "City Centre",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = fieldLabels[3],
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "2.4 km",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = fieldLabels[4],
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "Rajkot railway station",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            // Audio spoken text box
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF59E0B).copy(alpha = 0.1f),
                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "\"$sampleAnnouncement\"",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        ),
                        color = TextPrimary
                    )
                }
            }

            // Listen button
            FilledTonalButton(
                onClick = {
                    TTSManager.speak(context, sampleAnnouncement, flush = true)
                    Toast.makeText(context, "\uD83D\uDD0A $sampleAnnouncement", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFFF59E0B),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isHindi) "\u0905\u092D\u0940 \u0906\u0935\u093E\u091C\u093C \u092E\u0947\u0902 \u0938\u0941\u0928\u0915\u0930 \u0926\u0947\u0916\u0947\u0902" else "Listen Audio Announcement",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MockFloatingWidgetDemoCard(isHindi: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(PrimaryEmerald)
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = if (isHindi) "\u092B\u094D\u0932\u094B\u091F\u093F\u0902\u0917 \u092C\u091F\u0928 (Screen Overlay)" else "Floating Screen Bubble",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = if (isHindi) "Rapido \u0910\u092A \u0915\u0947 \u090A\u092A\u0930 \u0939\u092E\u0947\u0936\u093E \u0924\u0948\u0930\u0924\u093E \u0930\u0939\u0924\u093E \u0939\u0948" else "Hovers directly above your Rapido app",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isHindi) "\uD83D\uDFE2 \u0911\u091F\u094B-\u090F\u0915\u094D\u0938\u0947\u092A\u094D\u091F \u0938\u0915\u094D\u0930\u093F\u092F (Active)" else "\uD83D\uDFE2 Auto-Accept Active",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryEmerald
                    )
                    Text(
                        text = if (isHindi) "1-\u091F\u0948\u092A \u092E\u0947\u0902 \u092A\u0949\u091C \u0915\u0930\u0947\u0902" else "1-Tap to Pause",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
