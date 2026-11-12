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
                                text = if (isHindi) "ऐप गाइड और फिल्टर ट्रेनिंग" else "App & Filter Setup Guide",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = if (isHindi) "7 आसान स्टेप्स में पूरा सेटअप सीखें" else "Learn full setup in 7 easy steps",
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
                                        text = if (isHindi) "डिटेल समझें (How it Works)" else "How it Works in Detail",
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
                            Text(if (isHindi) "पीछे" else "Previous")
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
                                text = if (isHindi) "अगला स्टेप (Next)" else "Next Step",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        // Last Step: 1-Tap Apply Recommended Settings and Done
                        Button(
                            onClick = {
                                applyRecommendedSettings(context)
                                Toast.makeText(
                                    context,
                                    if (isHindi) "बेस्ट सेटिंग्स लागू हो गईं! अब ऑटो-एक्सेप्ट तैयार है।" else "Recommended settings applied successfully!",
                                    Toast.LENGTH_LONG
                                ).show()
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
                                    text = if (isHindi) "बेस्ट सेटिंग्स सेट करें & शुरू करें" else "Apply Best Settings & Start",
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
fun applyRecommendedSettings(context: Context) {
    AppSettings.setAutoAcceptEnabled(context, true)
    AppSettings.setMinFareEnabled(context, true)
    AppSettings.setMinFare(context, 50.0f)

    AppSettings.setMinPerKmRateEnabled(context, true)
    AppSettings.setMinPerKmRate(context, 13.0f)

    AppSettings.setMaxPickupDistanceEnabled(context, true)
    AppSettings.setMaxPickupDistance(context, 2.5f)

    AppSettings.setMaxDropDistanceEnabled(context, true)
    AppSettings.setMaxDropDistance(context, 15.0f)

    AppSettings.setVoiceAnnouncerEnabled(context, true)
    AppSettings.setFloatingOverlayEnabled(context, true)
    AppSettings.setHomeTutorialCompleted(context, true)
    AppSettings.syncOverlayService(context)
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
                                text = if (isHindi) "नया यूजर गाइड" else "NEW USER GUIDE",
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
                        text = if (isHindi) "ऐप चलाना और फिल्टर्स सेट करना सीखें" else "Learn How to Use & Set Filters",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )

                    Text(
                        text = if (isHindi)
                            "300ms ऑटो-एक्सेप्ट, ₹/KM और बेस्ट कमाई सेटिंग्स की पूरी गाइड"
                        else
                            "Step-by-step tutorial on 300ms auto-accept, ₹/KM rate and best filter settings",
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
private fun getTutorialSteps(context: Context, isHindi: Boolean): List<TutorialGuideStep> {
    return listOf(
        // STEP 1: How Order Master Works
        TutorialGuideStep(
            stepNumber = 1,
            icon = Icons.Default.ElectricBolt,
            badge = if (isHindi) "⚡ 300MS ऑटो-एक्सेप्ट" else "⚡ 300MS AUTO-ACCEPT",
            title = if (isHindi) "ऑर्डर मास्टर कैसे काम करता है?" else "How Order Master Works",
            shortDesc = if (isHindi)
                "Rapido में नया ऑर्डर आते ही 300 मिलीसेकंड में खुद एक्सेप्ट करता है।"
            else
                "Automatically reads incoming Rapido ride offers and accepts in 300ms without touching phone.",
            detailExplainer = if (isHindi)
                "1. आप अपनी Rapido Captain ऐप को सामान्य रूप से चालू रखें।\n" +
                        "2. जैसे ही स्क्रीन पर कोई नई राइड ऑफर आएगी, ऑर्डर मास्टर बैकग्राउंड में उसका किराया (Fare) और दूरी (Distance) चेक करेगा।\n" +
                        "3. अगर राइड आपके सेट किए गए नियमों के अनुसार सही है, तो यह केवल 300ms में ऑटोमैटिक एक्सेप्ट कर लेगा!"
            else
                "1. Keep your Rapido Captain app running as usual.\n" +
                        "2. Whenever a new ride popup appears, Order Master parses fare and distance in background.\n" +
                        "3. If ride matches your filter rules, it taps accept in 300 milliseconds automatically!",
            recommendedSetting = if (isHindi)
                "टिप: स्क्रीन पर ऑर्डर आते ही फोन छूने की जरूरत नहीं, ऐप अपने आप काम करेगा।"
            else
                "Tip: No need to touch your phone; app grabs high-demand rides instantly.",
            speechText = if (isHindi)
                "ऑर्डर मास्टर कैसे काम करता है? जब आप रैपिडो ऐप चला रहे होते हैं, तो स्क्रीन पर नया ऑर्डर आते ही ऐप तीन सौ मिलीसेकंड में खुद एक्सेप्ट कर लेता है।"
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
            badge = if (isHindi) "🔑 जरूरी परमिशन" else "🔑 REQUIRED PERMISSION",
            title = if (isHindi) "Accessibility सर्विस ऑन करें" else "Enable Accessibility Service",
            shortDesc = if (isHindi)
                "बिना इस परमिशन के ऐप स्क्रीन पर राइड्स नहीं पढ़ सकता।"
            else
                "Required for Order Master to detect ride popups and auto-accept.",
            detailExplainer = if (isHindi)
                "स्टेप 1: नीचे दिए गए बटन पर टैप करके Accessibility सेटिंग्स खोलें।\n" +
                        "स्टेप 2: 'Downloaded Apps' या 'इंस्टॉल किए गए ऐप्स' में जाएं।\n" +
                        "स्टेप 3: 'Order Master' पर क्लिक करके स्विच को ON करें और Allow पर टैप करें।"
            else
                "Step 1: Tap the button below to open Accessibility Settings.\n" +
                        "Step 2: Go to 'Downloaded Apps' or 'Installed Services'.\n" +
                        "Step 3: Tap 'Order Master' and toggle the switch to ON.",
            recommendedSetting = if (isHindi)
                "MIUI/Vivo/Oppo यूज़र्स: ऐप को Recents में लॉक करें और AutoStart ऑन रखें।"
            else
                "Xiaomi/Vivo/Oppo users: Lock Order Master in Recents and enable AutoStart.",
            speechText = if (isHindi)
                "एक्सेसिबिलिटी सर्विस चालू करें। फोन सेटिंग्स में जाएं, डाउनलोडेड ऐप्स में ऑर्डर मास्टर को चुनें और स्विच ऑन करें।"
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
            badge = if (isHindi) "💰 न्यूनतम किराया (MIN FARE)" else "💰 MINIMUM FARE FILTER",
            title = if (isHindi) "छोटे और घाटे वाले ऑर्डर्स रोकें" else "Filter Low-Paying Rides",
            shortDesc = if (isHindi)
                "अगर आप ₹50 सेट करेंगे तो ₹30-₹40 के सस्ते ऑर्डर्स खुद छूट जाएंगे।"
            else
                "Ignore orders below this threshold to maximize your trip earnings.",
            detailExplainer = if (isHindi)
                "अक्सर ₹25 या ₹35 के छोटे ऑर्डर्स उठाने पर टाइम और पेट्रोल दोनों खराब होते हैं।\n" +
                        "जब आप 'Minimum Fare Filter' चालू करके ₹50 या ₹60 सेट करते हैं, तो ऐप सिर्फ ₹50 से ऊपर वाले बड़े ऑर्डर्स ही लेगा। छोटे ऑर्डर्स को ऑटोमैटिक छोड़ देगा!"
            else
                "Short ₹25 or ₹35 rides waste valuable peak-hour time and fuel.\n" +
                        "Setting Minimum Fare to ₹50 or ₹60 ensures you only get assigned profitable rides.",
            recommendedSetting = if (isHindi)
                "बेस्ट सेटिंग: ₹50 से ₹70 के बीच रखें।"
            else
                "Recommended Setting: Set between ₹50 and ₹70.",
            speechText = if (isHindi)
                "न्यूनतम किराया फिल्टर। अगर आप पचास रुपये सेट करेंगे, तो कम पैसे वाले छोटे ऑर्डर अपने आप छूट जाएंगे और सिर्फ अच्छी कमाई वाले ऑर्डर आएंगे।"
            else
                "Minimum fare filter. Set minimum fare to fifty rupees or more to avoid low paying rides.",
            accentColor = Color(0xFF10B981),
            mockExample = {
                MockFareComparisonCard(isHindi)
            }
        ),

        // STEP 4: Price Per Kilometer (₹/KM Rate)
        TutorialGuideStep(
            stepNumber = 4,
            icon = Icons.Default.TrendingUp,
            badge = if (isHindi) "📈 रेट प्रति किमी (₹/KM)" else "📈 RATE PER KM (₹/KM)",
            title = if (isHindi) "हर किलोमीटर पर फिक्स मुनाफा कमाएं" else "Guaranteed Profit per Kilometer",
            shortDesc = if (isHindi)
                "लंबी दूरी पर कम किराया देने वाली घाटे की राइड्स से बचें।"
            else
                "Ensures every ride pays enough to cover fuel and driver profit.",
            detailExplainer = if (isHindi)
                "फॉर्मूला: कुल किराया ÷ कुल दूरी = ₹/KM रेट।\n" +
                        "उदाहरण:\n" +
                        "❌ ₹60 की राइड 10 किलोमीटर के लिए = सिर्फ ₹6/KM (पेट्रोल में घाटा!)\n" +
                        "✅ ₹120 की राइड 8 किलोमीटर के लिए = ₹15/KM (बंपर मुनाफा! 🚀)\n" +
                        "जब आप ₹13/KM सेट करते हैं, तो ऐप सिर्फ वही ऑर्डर लेगा जिसमें हर किमी पर ₹13 या उससे ज्यादा कमाई हो।"
            else
                "Formula: Total Fare ÷ Total Distance = Rate per KM.\n" +
                        "Example:\n" +
                        "❌ ₹60 for 10 KM = ₹6/KM (Fuel Loss!)\n" +
                        "✅ ₹120 for 8 KM = ₹15/KM (High Profit!)\n" +
                        "Setting ₹13/KM ensures Order Master only accepts rides that pay well per kilometer.",
            recommendedSetting = if (isHindi)
                "बेस्ट सेटिंग: बाइक के लिए ₹12-₹14/KM और ऑटो के लिए ₹15-₹18/KM रखें।"
            else
                "Recommended Setting: ₹12-₹14/KM for Bikes, ₹15-₹18/KM for Auto Rickshaws.",
            speechText = if (isHindi)
                "रेट प्रति किलोमीटर फिल्टर। सिर्फ कुल किराया मत देखिए। यह फिल्टर सुनिश्चित करता है कि हर किलोमीटर पर आपको कम से कम बारह या पंद्रह रुपये की कमाई हो।"
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
            badge = if (isHindi) "📍 पिकअप और दूरी फिल्टर्स" else "📍 PICKUP & DISTANCE LIMITS",
            title = if (isHindi) "दूर का पिकअप और लंबा सफर रोकें" else "Limit Pickup and Drop Distance",
            shortDesc = if (isHindi)
                "2.5 KM से ज्यादा दूर का पिकअप न लें ताकि पेट्रोल और टाइम बचे।"
            else
                "Avoid wasting fuel on long pickups and keep drop destinations within city limits.",
            detailExplainer = if (isHindi)
                "1. मैक्सिमम पिकअप दूरी: अगर राइडर 4-5 KM दूर है तो वहां तक खाली गाड़ी चलाने में पेट्रोल खर्च होता है। इसे 2.0 से 2.5 KM पर रखें।\n" +
                        "2. अधिकतम ड्रॉप दूरी: अगर आप शहर के बाहर नहीं जाना चाहते, तो इसे 12 से 15 KM पर सेट करें ताकि लंबी और अनचाही यात्राओं से बचा जा सके।"
            else
                "1. Max Pickup Distance: Don't drive 4-5 KM empty just to pick up a passenger. Keep it at 2.0 to 2.5 KM.\n" +
                        "2. Max Drop Distance: If you prefer staying within the city, cap total distance at 12 to 15 KM.",
            recommendedSetting = if (isHindi)
                "बेस्ट सेटिंग: पिकअप 2.0 - 2.5 KM, कुल दूरी 12 - 15 KM।"
            else
                "Recommended: Pickup 2.0 - 2.5 KM, Max Distance 12 - 15 KM.",
            speechText = if (isHindi)
                "पिकअप और दूरी फिल्टर्स। पैसेंजर अगर बहुत दूर है तो वहां तक खाली जाने में नुकसान होता है। पिकअप दूरी को दो या ढाई किलोमीटर पर रखें।"
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
            badge = if (isHindi) "🔊 आवाज़ उद्घोषक (VOICE ANNOUNCER)" else "🔊 VOICE ANNOUNCER",
            title = if (isHindi) "बिना स्क्रीन देखे ऑर्डर की पूरी डिटेल सुनें" else "Eyes-Free Voice Announcements",
            shortDesc = if (isHindi)
                "किराया, पिकअप दूरी व स्थान, और ड्रॉप दूरी व स्थान साफ बोलकर बताता है।"
            else
                "Automatically speaks Fare, Pickup distance/location, and Drop distance/location.",
            detailExplainer = if (isHindi)
                "1. गाड़ी चलाते समय बार-बार मोबाइल स्क्रीन देखना खतरनाक होता है।\n" +
                        "2. नया ऑर्डर आते ही ऐप आपकी चुनी हुई भाषा (हिन्दी, ગુજરાતી, English) में साफ आवाज़ में बोलता है:\n" +
                        "  • किराया: ₹45\n" +
                        "  • पिकअप दूरी: 0.6 km City Centre\n" +
                        "  • ड्रॉप दूरी: 2.4 km Rajkot railway station\n" +
                        "3. इससे आप बिना स्क्रीन देखे तुरंत समझ सकते हैं कि ऑर्डर कितना फायदेमंद है।"
            else
                "1. Looking at your phone while driving is dangerous.\n" +
                        "2. Order Master clearly announces all 5 vital details:\n" +
                        "  • Fare: ₹45\n" +
                        "  • Pickup: 0.6 km City Centre\n" +
                        "  • Drop: 2.4 km Rajkot railway station\n" +
                        "3. Supports Hindi, Gujarati, English and regional Indian languages.",
            recommendedSetting = if (isHindi)
                "टिप: सेटिंग्स में अपनी पसंदीदा भाषा (हिन्दी/गुजराती/English) चुनें और वॉयस उद्घोषक ऑन रखें।"
            else
                "Tip: Keep Voice Announcer enabled with your preferred regional language.",
            speechText = if (isHindi)
                "आवाज़ उद्घोषक फीचर। स्क्रीन पर नया ऑर्डर आते ही ऐप किराया, पिकअप दूरी और ड्रॉप लोकेशन बोलकर सुनाता है, ताकि गाड़ी चलाते समय आपको फोन की स्क्रीन न देखनी पड़े।"
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
            badge = if (isHindi) "🚀 फ्लोटिंग बटन & 1-टैप रेडी" else "🚀 FLOATING WIDGET & READY",
            title = if (isHindi) "फ्लोटिंग बटन, OEM बैकग्राउंड लॉक & 1-क्लिक बेस्ट सेटिंग्स" else "Floating Widget, OEM Lock & 1-Tap Setup",
            shortDesc = if (isHindi)
                "Xiaomi, Vivo, Oppo में ऐप को Recents में लॉक करें और 1-क्लिक बेस्ट सेटिंग्स लागू करें।"
            else
                "Lock in Recents, enable AutoStart, and apply best driver settings in 1-tap.",
            detailExplainer = if (isHindi)
                "1. फ्लोटिंग बबल: Rapido चलाते समय स्क्रीन पर ऊपर तैरता रहता है। एक टैप से ऑटो-एक्सेप्ट चालू या बंद करें।\n" +
                        "2. Xiaomi/Vivo/Oppo/Realme सेटिंग्स: ऐप को रीसेंट्स (Recents) में ताला (Lock) लगाएं और AutoStart ऑन करें ताकि बैकग्राउंड में सर्विस बंद न हो।\n" +
                        "3. 1-क्लिक बेस्ट सेटिंग्स: नीचे दिए गए हरे बटन पर टैप करते ही सबसे अच्छी सेटिंग्स अपने आप लागू हो जाएंगी!"
            else
                "1. Floating Bubble: Hovers above your driver app for instant pause/play.\n" +
                        "2. OEM Battery Setup: Lock Order Master in Recents and enable AutoStart on Xiaomi/Vivo/Oppo.\n" +
                        "3. 1-Click Best Setup: Tap the green button below to automatically apply ideal filters and start earning!",
            recommendedSetting = if (isHindi)
                "सब तैयार है! नीचे 'बेस्ट सेटिंग्स लागू करें' पर टैप करके तुरंत कमाई शुरू करें।"
            else
                "All ready! Tap 'Apply Best Settings' below to get started immediately.",
            speechText = if (isHindi)
                "सब तैयार है। फ्लोटिंग बटन से ऐप को कंट्रोल करें, और अगर आपका फोन शाओमी, विवो या ओप्पो है तो इसे रीसेंट्स में लॉक रखें। नीचे दिए गए बटन से बेस्ट सेटिंग्स लागू करें और कमाई शुरू करें।"
            else
                "You are ready. Use the floating overlay to control auto accept, lock the app in recents, and tap below to apply recommended settings.",
            accentColor = PrimaryEmerald,
            mockExample = {
                MockFloatingWidgetDemoCard(isHindi)
            }
        )
    )
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
                        text = "⭐ 4.8 Rating",
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
                        text = "₹95.00",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black
                        ),
                        color = PrimaryEmerald
                    )
                    Text(
                        text = "5.4 KM • Pickup: 0.8 KM",
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
                            text = if (isHindi) "300ms में ऑटो-एक्सेप्ट" else "300ms Accepted",
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
                text = if (isHindi) "सर्विस चालू करने के 3 आसान कदम:" else "3 Easy Steps to Enable Service:",
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
                    text = if (isHindi) "Settings ➡️ Accessibility (पहुंच योग्यता)" else "Open Settings ➡️ Accessibility",
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
                    text = if (isHindi) "Downloaded Apps ➡️ 'Order Master' चुनें" else "Downloaded Apps ➡️ Tap 'Order Master'",
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
                    text = if (isHindi) "'Use Order Master' को ON करें और Allow दबाएं" else "Turn ON 'Use Order Master' & Allow",
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
                    text = if (isHindi) "अभी Accessibility सेटिंग्स खोलें" else "Open Accessibility Settings Now",
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
                text = if (isHindi) "किराया तुलना (Example):" else "Fare Comparison (Example):",
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
                        Text("₹35.00 (3.2 KM)", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = if (isHindi) "कम किराया (घाटा)" else "Low Fare (Loss)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF4444)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.25f)
                    ) {
                        Text(
                            text = if (isHindi) "❌ छोड़ दिया (Skipped)" else "❌ Skipped",
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
                        Text("₹85.00 (5.0 KM)", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = if (isHindi) "अच्छा किराया (भरपूर कमाई)" else "High Fare (Profitable)",
                            style = MaterialTheme.typography.bodySmall,
                            color = PrimaryEmerald
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PrimaryEmerald
                    ) {
                        Text(
                            text = if (isHindi) "✅ एक्सेप्ट (Accepted)" else "✅ Accepted",
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
                text = if (isHindi) "₹/KM रेट कैलकुलेशन (मुनाफा चेक):" else "₹/KM Calculation Example:",
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
                        Text("₹60 ÷ 10 KM", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("= ₹6.0 / KM", color = Color(0xFFEF4444), fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = if (isHindi) "घाटे का सौदा ⚠️" else "Fuel Loss ⚠️",
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
                        Text("₹120 ÷ 8 KM", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("= ₹15.0 / KM", color = PrimaryEmerald, fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = if (isHindi) "बंपर मुनाफा 🚀" else "High Profit 🚀",
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
                        text = if (isHindi) "मैक्सिमम पिकअप दूरी: 2.5 KM" else "Max Pickup: 2.5 KM",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = if (isHindi) "पैसेंजर तक पहुंचने में पेट्रोल और समय न गंवाएं" else "Save fuel by avoiding long empty pickups",
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
                        text = if (isHindi) "अधिकतम कुल दूरी: 15.0 KM" else "Max Distance: 15.0 KM",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = if (isHindi) "शहर से बाहर अनचाहे लंबे रूट्स पर जाने से बचें" else "Keep trips within convenient city perimeter",
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
    val sampleAnnouncement = if (isHindi) {
        "Kiraya 45 rupaye. Pickup 0.6 kilometer City Centre. Drop 2.4 kilometer Rajkot railway station."
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
                        text = if (isHindi) "5 मुख्य फील्ड्स का वॉइस डेमो" else "5-Field Voice Demo",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF59E0B)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = if (isHindi) "🔊 लाइव टेस्ट" else "🔊 Live Test",
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
                            text = "1. किराया (Fare):",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "₹45 (Auto Boost)",
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
                            text = "2. पिकअप दूरी:",
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
                            text = "3. पिकअप स्थान:",
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
                            text = "4. ड्रॉप दूरी:",
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
                            text = "5. ड्रॉप स्थान:",
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
                    Toast.makeText(context, "🔊 $sampleAnnouncement", Toast.LENGTH_SHORT).show()
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
                    text = if (isHindi) "अभी आवाज़ में सुनकर देखें" else "Listen Audio Announcement",
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
                        text = if (isHindi) "फ्लोटिंग बटन (Screen Overlay)" else "Floating Screen Bubble",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = if (isHindi) "Rapido ऐप के ऊपर हमेशा तैरता रहता है" else "Hovers directly above your Rapido app",
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
                        text = if (isHindi) "🟢 ऑटो-एक्सेप्ट सक्रिय (Active)" else "🟢 Auto-Accept Active",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryEmerald
                    )
                    Text(
                        text = if (isHindi) "1-टैप में पॉज करें" else "1-Tap to Pause",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
